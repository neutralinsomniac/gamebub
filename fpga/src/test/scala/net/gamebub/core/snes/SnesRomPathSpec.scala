package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import lib.mem.cache.{LineBuffer, LineReadCache}
import lib.mem.{PipelineMemoryArbiter, PipelineMemoryBurstCdc}
import lib.mem.sdram.{BurstSdramController, Signals}
import lib.util.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

object SnesRomPathSpec {
  /**
   * The SNES ROM read path as built in `HandheldSnes`:
   * LineBuffer -> LineReadCache -> arbiter -> burst CDC -> SDRAM controller,
   * with the core-side blocks on a /4 clock like the real system.
   */
  class Wrapper extends Module {
    val io = IO(new Bundle {
      val request = Input(Bool())
      val address = Input(UInt(25.W))
      val hit = Output(Bool())
      val dataRead = Output(UInt(32.W))
      val pending = Output(Bool())
      val respValid = Output(Bool())
      val miss = Output(Bool())
      val sdram = new Signals(addressWidth = 13, dataWidth = 16, bankWidth = 2)
      val phase = Output(UInt(3.W))
      val debug = Output(Vec(16, UInt(32.W)))
    })
    // The module clock is a 2x "base" clock; the SDRAM (fast) and core (slow)
    // clocks are both derived from one counter on it, so that their common
    // rising edges happen in the same simulation delta and every flop of
    // both domains captures pre-edge values, as on the hardware's PLL
    // outputs. (A slow clock derived from a fast-domain register fires a
    // delta late and the slow domain sees post-edge fast values.)
    // Free-running (not reset): the derived clocks must tick while reset is
    // asserted for the derived domains to see it.
    val base = withReset(false.B)(RegInit(0.U(3.W)))
    base := base + 1.U
    io.phase := base
    val fastClock = base(0).asClock // rises on even -> odd
    val slowClock = (base >= 1.U && base <= 4.U).asClock // rises on 0 -> 1, with the fast clock

    val sdramConfig = BurstSdramController.Config(
      clockFrequency = 85_909_000, accessLength = 2, timeRsc = 23, timeWr = 23, enableBurst = true)
    val (sdram, cdc) = withClockAndReset(fastClock, reset) {
      val sdram = Module(new BurstSdramController(sdramConfig))
      io.sdram <> sdram.io.signals
      val cdc = Module(new PipelineMemoryBurstCdc(addressWidth = 25, dataWidth = 32, addressBurstIncrement = 4, enablePrefetch = true))
      cdc.io.slowClock := slowClock
      cdc.io.target <> sdram.io.mem
      (sdram, cdc)
    }

    withClockAndReset (slowClock, reset) {
      val arbiter = Module(new PipelineMemoryArbiter(addressWidth = 25, dataWidth = 32, n = 2))
      arbiter.io.initiator(0).enable := false.B
      arbiter.io.initiator(0).address := DontCare
      arbiter.io.initiator(0).isWrite := DontCare
      arbiter.io.initiator(0).writeStrobe := DontCare
      arbiter.io.initiator(0).dataWrite := DontCare
      cdc.io.initiator <> arbiter.io.target
      val cache = Module(new LineReadCache(addressWidth = 25, dataWidth = 32, numLines = 1024, wordsPerLine = 4, ways = 2, prefetchDistance = 2))
      arbiter.io.initiator(1) <> cache.io.out
      val buffer = Module(new LineBuffer(addressWidth = 25, numLines = 16, wordsPerLine = 4))
      cache.io.in <> buffer.io.out
      buffer.io.outLine := cache.io.line
      buffer.io.earlyValid := cache.io.fillWordValid
      cache.io.eagerIssue := true.B
      buffer.io.earlyAddress := cache.io.fillWordAddress
      buffer.io.earlyData := cache.io.fillWordData
      buffer.io.request := io.request
      buffer.io.address := io.address
      io.hit := buffer.io.hit
      io.dataRead := buffer.io.dataRead
      io.pending := buffer.io.pending
      io.respValid := buffer.io.respValid
      io.miss := cache.io.miss
      import chisel3.util.experimental.BoringUtils.bore
      io.debug := DontCare
      io.debug(0) := bore(cache.state).asUInt
      io.debug(1) := Cat(bore(cache.fillActive), bore(cache.fillIsPrefetch), bore(cache.fillIssued), bore(cache.fillWord), bore(cache.fillReceived))
      io.debug(2) := bore(cache.fillAddress)
      io.debug(3) := Cat(bore(buffer.outBusy), bore(buffer.outIsDemand), bore(buffer.pending), bore(buffer.demandIssued), bore(buffer.pullWanted))
      io.debug(4) := bore(buffer.outAddress)
      io.debug(5) := bore(buffer.missAddress)
      io.debug(6) := Cat(bore(arbiter.regBusy), bore(arbiter.regNextPending))
      io.debug(7) := Cat(bore(cdc.regBusy), bore(cdc.regBusyPrefetch), bore(cdc.regReadBurst), bore(cdc.regSkidComplete))
      io.debug(8) := bore(cdc.regBusyAddress)
      io.debug(9) := Cat(bore(sdram.regRequestPending), bore(sdram.regClockEnable))
      io.debug(10) := Cat(cache.io.out.enable, cache.io.out.ready, cache.io.in.enable, cache.io.in.ready, buffer.io.out.ready, bore(cache.regPending))
      io.debug(11) := bore(cache.regAddress)
      io.debug(12) := bore(cdc.slowDebug)
      io.debug(13) := bore(cdc.slowDebugBusyAddress)
      io.debug(14) := bore(cdc.slowDebugResponseAddress)
      io.debug(15) := Cat(arbiter.io.target.enable, arbiter.io.target.ready, cdc.io.target.enable, cdc.io.target.ready)
    }
  }

  /** A behavioural SDRAM (CAS latency 2, full-page bursts, clock suspend). */
  class SdramModel(mem: Int => Int) {
    var ckePrev = true
    val openRow = Array.fill(4)(-1)
    var burstBank = 0
    var burstCol = 0
    var burstDelay = 0
    var burstActive = false
    var out = 0xFFFF
    var reads = 0

    /** Called after each clock edge with the signals sampled at that edge. */
    def edge(cke: Boolean, cs: Boolean, ras: Boolean, cas: Boolean, we: Boolean, bank: Int, address: Int): Unit = {
      if (ckePrev) {
        if (!cs) {
          val code = (if (ras) 4 else 0) | (if (cas) 2 else 0) | (if (we) 1 else 0)
          code match {
            case 3 => openRow(bank) = address & 0x1FFF // active
            case 5 => // read
              require(openRow(bank) >= 0, "read on a closed bank")
              burstBank = bank; burstCol = address & 0x1FF; burstDelay = 1; burstActive = true; reads += 1
            case 2 => burstActive = false; if ((address & 0x400) != 0) (0 until 4).foreach(openRow(_) = -1) else openRow(bank) = -1 // precharge
            case 4 | 6 => burstActive = false
            case _ =>
          }
        }
        if (burstActive) {
          if (burstDelay > 0) burstDelay -= 1
          else {
            out = mem((burstBank << 22) | (openRow(burstBank) << 9) | burstCol)
            burstCol = (burstCol + 1) & 0x1FF
          }
        }
      }
      ckePrev = cke
    }
  }
}

class SnesRomPathSpec extends AnyFreeSpec {
  import SnesRomPathSpec._

  /** Physical 16-bit word `index` (address bits 24:1). */
  def mem16(index: Int): Int = ((index * 2654435761L + 0x9E37) & 0xFFFF).toInt
  /** The 32-bit word the SDRAM controller delivers for aligned byte address `a`. */
  def expected(a: Int): BigInt = (BigInt(mem16(a >> 1)) << 16) | mem16((a >> 1) + 1)

  class Harness(val dut: Wrapper) {
    val model = new SdramModel(mem16)
    var fastCycles = 0L
    /** One base step; the SDRAM model sees the fast edges (base even -> odd). */
    def baseStep(): Unit = {
      val fastEdge = (dut.io.phase.peek().litValue & 1) == 0
      if (fastEdge) {
        // The command on the bus during this fast cycle is registered at its
        // edge, as is the data the model drives during it.
        val (cke, cs, ras, cas, we, bank, address) = (
          dut.io.sdram.cke.peek().litToBoolean, dut.io.sdram.cs.peek().litToBoolean,
          dut.io.sdram.ras.peek().litToBoolean, dut.io.sdram.cas.peek().litToBoolean,
          dut.io.sdram.we.peek().litToBoolean, dut.io.sdram.bank.peek().litValue.toInt,
          dut.io.sdram.address.peek().litValue.toInt)
        dut.io.sdram.dataIn.poke(model.out.U)
        dut.clock.step()
        fastCycles += 1
        model.edge(cke, cs, ras, cas, we, bank, address)
      } else {
        dut.clock.step()
      }
    }
    /** From just after a slow edge (base = 1): run to just before the next one (base = 0). */
    def toEndOfSlowCycle(): Unit = { for (_ <- 0 until 7) baseStep(); assert(dut.io.phase.peek().litValue == 0) }
    /** Complete the slow cycle (the edge). */
    def slowEdge(): Unit = { baseStep(); assert(dut.io.phase.peek().litValue == 1) }
    def idle(slowCycles: Int): Unit = {
      if (slowCycles > 0) note(s"idle $slowCycles")
      dut.io.request.poke(false.B)
      for (i <- 0 until slowCycles) { toEndOfSlowCycle(); dump(s"  idle $i"); slowEdge() }
    }
    var misses = 0
    val history = scala.collection.mutable.ArrayBuffer[String]()
    def note(s: String): Unit = { history += s; if (history.size > 12) history.remove(0) }
    /** Read like the glue: a one-cycle request, then wait for the answer. Returns (data, wait cycles). */
    val ring = scala.collection.mutable.ArrayBuffer[String]()
    def dump(tag: String): Unit = {
      val d = (0 until 16).map(i => dut.io.debug(i).peek().litValue.toString(16))
      ring += (s"$tag: cache=${d(0)} fill=${d(1)} fillAddr=${d(2)} buf=${d(3)} outAddr=${d(4)} miss=${d(5)} arb=${d(6)} cdcFast=${d(7)} cdcAddr=${d(8)} sdram=${d(9)} enrdy=${d(10)} regAddr=${d(11)} cdcSlow(busy,w,resp,empty,full)=${d(12)} busyAddr=${d(13)} respAddr=${d(14)} arb/cdc en,rdy=${d(15)} hit=${dut.io.hit.peek().litValue} resp=${dut.io.respValid.peek().litValue}")
      if (ring.size > 400) ring.remove(0)
    }
    def read(address: Int, maxWait: Int = 200): (BigInt, Int) = {
      dut.io.request.poke(true.B)
      dut.io.address.poke(address.U)
      toEndOfSlowCycle()
      dump(s"req 0x${address.toHexString}")
      if (dut.io.miss.peek().litValue == 1) misses += 1
      if (dut.io.hit.peek().litValue == 1) {
        val d = dut.io.dataRead.peek().litValue
        slowEdge()
        dut.io.request.poke(false.B)
        note(s"0x${address.toHexString} hit")
        (d, 0)
      } else {
        slowEdge()
        dut.io.request.poke(false.B)
        var waited = 1
        while (true) {
          toEndOfSlowCycle()
          dump(s"  wait $waited")
          if (dut.io.miss.peek().litValue == 1) misses += 1
          if (dut.io.respValid.peek().litValue == 1) {
            val d = dut.io.dataRead.peek().litValue
            slowEdge()
            note(s"0x${address.toHexString} miss $waited")
            return (d, waited)
          }
          if (waited >= maxWait - 16) {
            val d = (0 until 12).map(i => dut.io.debug(i).peek().litValue.toString(16))
            println(s"wait $waited: cacheState=${d(0)} fill(act,pf,iss,word,recv)=${d(1)} fillAddr=${d(2)} buf(busy,dem,pend,demIss,pull)=${d(3)} outAddr=${d(4)} miss=${d(5)} arb(busy,next)=${d(6)} cdc(busy,pf,burst,skid)=${d(7)} cdcAddr=${d(8)} sdram(req,cke)=${d(9)} en/rdy(out.en,out.rdy,in.en,in.rdy,buf.rdy,regPending)=${d(10)} regAddr=${d(11)}")
          }
          slowEdge()
          waited += 1
          if (waited >= maxWait) { println(s"TRACE for hang at 0x${address.toHexString}:"); ring.slice(185, 240).foreach(l => println("T " + l)); println("T ... (skipping steady state)"); }
          assert(waited < maxWait, s"read at 0x${address.toHexString} never completed; history: ${history.mkString(", ")}")
        }
        throw new IllegalStateException
      }
    }
  }

  def withPath(body: Harness => Unit): Unit = {
    simulate(new Wrapper) { dut =>
      val h = new Harness(dut)
      dut.io.request.poke(false.B)
      dut.io.address.poke(0.U)
      dut.reset.poke(true.B)
      for (_ <- 0 until 24) h.baseStep()
      dut.reset.poke(false.B)
      // Align to the slow clock: base 1 is right after the slow edge.
      while (dut.io.phase.peek().litValue != 1) h.baseStep()
      // SDRAM initialisation (200 us) and cache invalidation.
      h.idle(5000)
      body(h)
    }
  }

  "isolated reads return the SDRAM contents" in withPath { h =>
    val rnd = new Random(3)
    for (i <- 0 until 60) {
      val a = (rnd.nextInt(1 << 20) & ~3) | (if (i < 4) i * 4 else 0)
      val (d, waited) = h.read(a)
      assert(d == expected(a), s"0x${a.toHexString}: got 0x${d.toString(16)} expected 0x${expected(a).toString(16)} (waited $waited)")
      h.idle(rnd.nextInt(6))
    }
    assert(h.misses > 0)
  }

  "every word of a line and its neighbours read back correctly in any order" in withPath { h =>
    val rnd = new Random(5)
    for (line <- 0 until 40) {
      val base = 0x40000 + line * 0x400
      val order = rnd.shuffle((0 until 12).toList) // three consecutive lines, any word first
      for (w <- order) {
        val a = base + w * 4
        val (d, _) = h.read(a)
        assert(d == expected(a), s"line $line word $w (0x${a.toHexString}): got 0x${d.toString(16)} expected 0x${expected(a).toString(16)}")
        if (rnd.nextInt(3) == 0) h.idle(rnd.nextInt(4))
      }
    }
  }

  "a CPU-like stream (sequential runs, jumps, a hot loop) never returns wrong data" in withPath { h =>
    val rnd = new Random(11)
    var a = 0x8000
    val hot = 0x12340
    var seq = 0
    for (i <- 0 until 3000) {
      val (d, _) = h.read(a)
      assert(d == expected(a), s"step $i 0x${a.toHexString}: got 0x${d.toString(16)} expected 0x${expected(a).toString(16)}")
      rnd.nextInt(10) match {
        case 0 | 1 => a = hot + rnd.nextInt(24) * 4 // a loop and its data
        case 2 => a = rnd.nextInt(1 << 22) & ~3 // a far jump
        case 3 => a = (a - rnd.nextInt(8) * 4) & ~3 // a short backward branch
        case _ => a = (a + 4) & ((1 << 25) - 1)
      }
      if (rnd.nextInt(4) == 0) h.idle(rnd.nextInt(8))
    }
    println(s"CPU-like stream: ${h.misses} misses in 3000 reads, ${h.model.reads} SDRAM reads")
  }

  "an S-CPU under latency hiding, with the glue's data register and stall rule, sees correct data" in withPath { h =>
    // Model of the glue in HandheldSnes: romData / romPending, the core
    // latching ROM_Q at its SYSCLKF_CE and being stalled there while a read
    // is outstanding; the request is a one-cycle pulse that may repeat
    // (OE toggling, or the other 16-bit half of the same word) before the
    // latch.
    val rnd = new Random(23)
    val dut = h.dut
    var romPending = false
    var romData = BigInt(0)
    /** One slow cycle with the glue model; returns (romDataNow, romOutstanding). */
    def cycle(request: Option[Int]): (BigInt, Boolean) = {
      dut.io.request.poke(request.isDefined.B)
      request.foreach(a => dut.io.address.poke(a.U))
      h.toEndOfSlowCycle()
      val hit = dut.io.hit.peek().litValue == 1
      val resp = dut.io.respValid.peek().litValue == 1
      val dataRead = dut.io.dataRead.peek().litValue
      val now = if (hit || resp) dataRead else romData
      val outstanding = romPending && !resp
      h.dump(s"${request.map(a => s"req 0x${a.toHexString}").getOrElse("   -")} data=0x${dataRead.toString(16)} pend=$romPending")
      romData = now
      romPending = if (request.isDefined && !hit) true else if (hit || resp) false else romPending
      h.slowEdge()
      (now, outstanding)
    }
    var a = 0x8000
    val hot = 0x12340
    for (i <- 0 until 3000) {
      // Request, then the rest of the CPU cycle, then the latch.
      var (now, outstanding) = cycle(Some(a))
      val latchAfter = 2 + rnd.nextInt(5)
      var repeats = if (rnd.nextInt(5) == 0) 1 + rnd.nextInt(2) else 0
      for (c <- 0 until latchAfter) {
        val r = if (repeats > 0 && rnd.nextInt(2) == 0) { repeats -= 1; Some(a) } else None
        val t = cycle(r); now = t._1; outstanding = t._2
      }
      var stalled = 0
      while (outstanding) {
        // The core is stalled (no new activity) until the read completes.
        val t = cycle(None); now = t._1; outstanding = t._2
        stalled += 1
        assert(stalled < 200, s"step $i: latch at 0x${a.toHexString} never released")
      }
      if (now != expected(a)) { println(s"TRACE for step $i:"); h.ring.takeRight(30).foreach(l => println("T " + l)) }
      assert(now == expected(a), s"step $i 0x${a.toHexString}: latched 0x${now.toString(16)} expected 0x${expected(a).toString(16)}")
      rnd.nextInt(10) match {
        case 0 | 1 => a = hot + rnd.nextInt(24) * 4
        case 2 => a = rnd.nextInt(1 << 22) & ~3
        case 3 => a = (a - rnd.nextInt(8) * 4) & ~3
        case _ => a = (a + 4) & ((1 << 25) - 1)
      }
      if (rnd.nextInt(3) == 0) for (_ <- 0 until rnd.nextInt(4)) cycle(None)
    }
  }

  "two aliasing streams (code and a DMA source) read back correctly" in withPath { h =>
    val rnd = new Random(17)
    val setStride = 1024 * 16 // lines x line bytes: same set
    var code = 0x100000
    var dma = 0x100000 + setStride * 3 + 0x40
    for (i <- 0 until 1500) {
      val a = if (i % 3 == 0) { dma += 4; dma - 4 } else { code += 4; code - 4 }
      val (d, _) = h.read(a)
      assert(d == expected(a), s"step $i 0x${a.toHexString}: got 0x${d.toString(16)} expected 0x${expected(a).toString(16)}")
      if (rnd.nextInt(3) == 0) h.idle(rnd.nextInt(3))
    }
  }
}
