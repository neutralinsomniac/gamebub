package psx

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import chisel3.util.Decoupled
import lib.mem.MemoryInterface
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import scala.util.Random

object PsxMemorySpec {
  /**
   * The memory with its three related clocks made from the test's clock:
   * twelve base cycles per clk1x cycle, clk2x every six, clk3x every four,
   * all rising together at phase 0 (`phase` is the base cycle within the
   * clk1x cycle).
   */
  class Harness(config: SdramEngine.Config) extends Module {
    val io = IO(new Bundle {
      val phase = Output(UInt(4.W))
      val ram = new PsxMemory.MainRam
      val ddram = new PsxMemory.Ddram
      val host = new MemoryInterface(addressWidth = 26, dataWidth = 32)
      val pins = new SdramEngine.Pins(2)
      val ready = Output(Bool())
      val copy = Flipped(Decoupled(new PsxMemory.LineCopy))
    })
    // The clocks run during reset (the memory's resets are synchronous).
    val (phase, clk3x, clk2x, clk1x) = withReset(false.B) {
      val phase = RegInit(0.U(4.W))
      val next = Mux(phase === 11.U, 0.U, phase + 1.U)
      phase := next
      def mod(x: UInt, m: Int): UInt = VecInit((0 until 12).map(i => (i % m).U(4.W)))(x)
      (phase, RegNext(mod(next, 4) < 2.U, false.B), RegNext(mod(next, 6) < 3.U, false.B), RegNext(next < 6.U, false.B))
    }
    io.phase := phase
    val memory = withClockAndReset(clk3x.asClock, reset) { Module(new PsxMemory(config)) }
    memory.io.clk1x := clk1x.asClock
    memory.io.reset1x := reset.asBool
    memory.io.clk2x := clk2x.asClock
    memory.io.reset2x := reset.asBool
    memory.io.ram <> io.ram
    memory.io.ddram <> io.ddram
    memory.io.host <> io.host
    memory.io.pins <> io.pins
    memory.io.display.request.valid := false.B
    memory.io.display.request.bits := DontCare
    memory.io.copy <> io.copy
    io.ready := memory.io.ready
  }
}

/**
 * Drives [[PsxMemory]] the way the PSX core and the host do (requests in
 * their clock domains, the MiSTer `sdram.sv` response protocol) against
 * the SDRAM chip model, and checks the data and the protocol: done pulses
 * of one clk1x cycle, the instruction cache fill, DMA words, the DMA write
 * FIFO, DDR3 bursts across rows, host accesses to both chips.
 */
class PsxMemorySpec extends AnyFreeSpec {
  import PsxMemorySpec._

  val config = SdramEngine.Config(clockHz = 101.5625e6, tInit = 2000, sourceBits = PsxMemory.SourceBits, ioPrimitives = false)

  /** The test bench: base-cycle stepping, with hooks per clock domain. */
  class Bench(dut: Harness) {
    val model = new SdramChipsModel(config, 2)
    var base = 0L
    var cycle3x = 0L
    val shadow0 = mutable.Map[Long, Long]() // chip 0, 32-bit words by byte address
    val shadow1 = mutable.Map[Long, Long]() // chip 1

    // clk1x domain: the core's main RAM side
    case class RamRequest(adr: Long, rnw: Boolean, be: Int, data: Long, dma: Boolean, cnt: Int, cache: Boolean)
    val ramQueue = mutable.Queue[RamRequest]()
    var ramWaiting: Option[RamRequest] = None
    var dmaOutstanding = mutable.Queue[RamRequest]()
    var dmaNextAllowed = true
    val ramResults = mutable.ArrayBuffer[(RamRequest, Long)]() // reads: data
    val dmaWords = mutable.ArrayBuffer[Long]()
    val cacheWrites = mutable.ArrayBuffer[(Int, Int, Long, Long)]() // (addr, slot, data, cycle3x)
    var doneHigh = 0
    var lastDoneCycle = -10L
    var cycle1x = 0L

    // clk3x domain: the DMA write FIFO
    val dmafifo = mutable.Queue[(Long, Long)]()
    /** The FIFO's read strobe in the previous clk3x cycle (the head was taken at its end). */
    var dmafifoReadLast = false

    // clk2x domain: the DDR3 port
    case class DdrRequest(addr: Long, burst: Int, write: Boolean, data: Long, be: Int)
    val ddrQueue = mutable.Queue[DdrRequest]()
    var ddrActive: Option[DdrRequest] = None
    /** `busy` in the previous clk2x cycle (a request held then was taken unless busy). */
    var ddrBusyLast = true
    var ddrReadExpected = mutable.Queue[Long]()
    var ddrReadPending = false

    // clk3x domain: line copies
    case class Copy(fromLine: Int, fromWord: Int, words: Int, to: Long)
    val copyQueue = mutable.Queue[Copy]()
    var copyActive: Option[Copy] = None
    var copyReadyLast = false
    var copyRunning = false

    // host (clk1x)
    case class HostRequest(address: Long, write: Boolean, data: Long)
    val hostQueue = mutable.Queue[HostRequest]()
    var hostActive: Option[HostRequest] = None
    val hostReads = mutable.ArrayBuffer[(HostRequest, Long)]()

    def chipAddress(ddrByte: Long): Long = if (((ddrByte >> 24) & 0xF) == 0xE) 0x1000000L | (ddrByte & 0xFFFFFF) else ddrByte & 0xFFFFFF

    def step(): Unit = {
      val phase = dut.io.phase.peek().litValue.toInt
      // `phase` is the base cycle within the clk1x cycle that just started;
      // the derived clocks rose at the edge that started phase 0 / 4 / 8 (3x),
      // 0 / 6 (2x), 0 (1x).
      if (phase % 4 == 0) on3x()
      if (phase % 6 == 0) on2x()
      if (phase == 0) on1x()
      dut.clock.step()
      base += 1
    }

    def on3x(): Unit = {
      // Line copies: the one held in the previous cycle was taken if ready
      // was high; it is done when ready is high again.
      copyActive.foreach { c =>
        if (copyReadyLast) {
          copyActive = None
          copyRunning = true
          for (j <- 0 until c.words) {
            val from = c.fromLine.toLong * 2048 + ((c.fromWord + j) & 511) * 4
            shadow1(c.to + 4 * j) = shadow1.getOrElse(from, 0L)
          }
        }
      }
      if (copyActive.isEmpty && copyRunning && copyReadyLast) copyRunning = false
      if (copyActive.isEmpty && !copyRunning && copyQueue.nonEmpty) copyActive = Some(copyQueue.dequeue())
      copyActive match {
        case Some(c) =>
          dut.io.copy.valid.poke(true.B)
          dut.io.copy.bits.fromLine.poke(c.fromLine.U)
          dut.io.copy.bits.fromWord.poke(c.fromWord.U)
          dut.io.copy.bits.words.poke(c.words.U)
          dut.io.copy.bits.to.poke(c.to.U)
        case None =>
          dut.io.copy.valid.poke(false.B)
      }
      copyReadyLast = dut.io.copy.ready.peek().litToBoolean
      // DMA write FIFO (fall-through): the head goes when the read strobe was high.
      if (dmafifoReadLast) {
        assert(dmafifo.nonEmpty)
        dmafifo.dequeue()
      }
      dmafifo.headOption match {
        case Some((a, d)) =>
          dut.io.ram.dmafifoEmpty.poke(false.B)
          dut.io.ram.dmafifoAdr.poke(a.U)
          dut.io.ram.dmafifoData.poke(d.U)
        case None =>
          dut.io.ram.dmafifoEmpty.poke(true.B)
      }
      val dqIn = model.cycle(cycle3x,
        dut.io.pins.csN.peek().litValue.toInt,
        dut.io.pins.rasN.peek().litValue.toInt,
        dut.io.pins.casN.peek().litValue.toInt,
        dut.io.pins.weN.peek().litValue.toInt,
        dut.io.pins.bank.peek().litValue.toInt,
        dut.io.pins.address.peek().litValue.toInt,
        dut.io.pins.dqm.peek().litValue.toInt,
        dut.io.pins.dqOut.peek().litValue.toInt,
        dut.io.pins.dqOe.peek().litValue.toInt)
      dut.io.pins.dqIn.poke(dqIn.U)
      // Instruction cache writes
      val wr = dut.io.ram.cacheWr.peek().litValue.toInt
      if (wr != 0) {
        assert(Integer.bitCount(wr) == 1)
        cacheWrites += ((dut.io.ram.cacheAddr.peek().litValue.toInt, Integer.numberOfTrailingZeros(wr),
          dut.io.ram.cacheData.peek().litValue.toLong, cycle3x))
      }
      dmafifoReadLast = dut.io.ram.dmafifoRead.peek().litToBoolean
      cycle3x += 1
    }

    def on1x(): Unit = {
      // The core reacts to a response in the next cycle (its registers).
      val canIssue = ramWaiting.isEmpty && dmaNextAllowed
      val hostCanIssue = hostActive.isEmpty
      // Responses (registered outputs of the clk1x domain, this cycle)
      val done = dut.io.ram.done.peek().litToBoolean
      if (done) {
        assert(cycle1x != lastDoneCycle + 1, s"clk1x cycle $cycle1x: done longer than one cycle")
        lastDoneCycle = cycle1x
        val r = ramWaiting.get
        if (r.rnw) ramResults += ((r, dut.io.ram.dataRead.peek().litValue.toLong))
        ramWaiting = None
      }
      if (dut.io.ram.dmaReqProcessed.peek().litToBoolean) dmaNextAllowed = true
      if (dut.io.ram.dmaWr.peek().litToBoolean) dmaWords += dut.io.ram.dmaData.peek().litValue.toLong
      // Host
      if (dut.io.host.done.peek().litToBoolean) {
        val h = hostActive.get
        if (!h.write) hostReads += ((h, dut.io.host.dataRead.peek().litValue.toLong))
        hostActive = None
      }
      // New requests (as registers updated at this edge)
      dut.io.ram.ena.poke(false.B)
      val ready = dut.io.ready.peek().litToBoolean
      if (ready && canIssue && ramQueue.nonEmpty) {
        val r = ramQueue.dequeue()
        dut.io.ram.ena.poke(true.B)
        dut.io.ram.adr.poke(r.adr.U)
        dut.io.ram.rnw.poke(r.rnw.B)
        dut.io.ram.be.poke(r.be.U)
        dut.io.ram.dataWrite.poke(r.data.U)
        dut.io.ram.dma.poke(r.dma.B)
        dut.io.ram.cntDma.poke(r.cnt.U)
        dut.io.ram.cache.poke(r.cache.B)
        if (r.dma) {
          // A DMA read completes with its words; the next may go once accepted.
          dmaNextAllowed = false
          dmaOutstanding.enqueue(r)
        } else {
          ramWaiting = Some(r)
        }
      }
      if (ready && hostCanIssue && hostQueue.nonEmpty) {
        hostActive = Some(hostQueue.dequeue())
      }
      hostActive match {
        case Some(h) =>
          dut.io.host.enable.poke(true.B)
          dut.io.host.address.poke(h.address.U)
          dut.io.host.write.poke(h.write.B)
          dut.io.host.dataWrite.poke(h.data.U)
          dut.io.host.writeStrobe.poke(15.U)
        case None =>
          dut.io.host.enable.poke(false.B)
      }
      cycle1x += 1
    }

    def on2x(): Unit = {
      if (dut.io.ddram.doutReady.peek().litToBoolean) {
        assert(ddrReadExpected.nonEmpty, "unexpected DDR3 word")
        val e = ddrReadExpected.dequeue()
        val got = dut.io.ddram.dout.peek().litValue.toLong
        assert(got == e, f"DDR3 read $got%016x, expected $e%016x")
        if (ddrReadExpected.isEmpty) ddrReadPending = false
      }
      // The request held in the previous cycle was taken if busy was low.
      ddrActive.foreach { _ => if (!ddrBusyLast) ddrActive = None }
      if (ddrActive.isEmpty && !ddrReadPending && ddrQueue.nonEmpty && dut.io.ready.peek().litToBoolean) {
        val r = ddrQueue.dequeue()
        ddrActive = Some(r)
        if (!r.write) {
          ddrReadPending = true
          for (i <- 0 until r.burst) {
            val byte = chipAddress((r.addr + i) * 8)
            ddrReadExpected.enqueue((shadow1.getOrElse(byte + 4, 0L) << 32) | shadow1.getOrElse(byte, 0L))
          }
        } else {
          val byte = chipAddress(r.addr * 8)
          for ((off, shift) <- Seq((0, 0), (4, 32))) {
            val old = shadow1.getOrElse(byte + off, 0L)
            val m = (r.be >> (shift / 8)) & 0xF
            var v = old
            for (b <- 0 until 4 if ((m >> b) & 1) != 0) {
              v = (v & ~(0xFFL << (8 * b))) | (((r.data >> (shift + 8 * b)) & 0xFF) << (8 * b))
            }
            shadow1(byte + off) = v
          }
        }
      }
      ddrActive match {
        case Some(r) =>
          dut.io.ddram.rd.poke((!r.write).B)
          dut.io.ddram.we.poke(r.write.B)
          dut.io.ddram.addr.poke((0x3L << 25 | r.addr).U)
          dut.io.ddram.burstCnt.poke(r.burst.U)
          dut.io.ddram.din.poke(r.data.U)
          dut.io.ddram.be.poke(r.be.U)
        case None =>
          dut.io.ddram.rd.poke(false.B)
          dut.io.ddram.we.poke(false.B)
      }
      ddrBusyLast = dut.io.ddram.busy.peek().litToBoolean
    }

    def busy: Boolean = copyQueue.nonEmpty || copyActive.nonEmpty || copyRunning || ramQueue.nonEmpty || ramWaiting.nonEmpty || dmaOutstanding.size > dmaWordsComplete ||
      dmafifo.nonEmpty || ddrQueue.nonEmpty || ddrActive.nonEmpty || ddrReadPending || hostQueue.nonEmpty || hostActive.nonEmpty
    def dmaWordsComplete: Int = {
      // DMA requests whose words have all arrived
      var words = dmaWords.size
      var n = 0
      for (r <- dmaOutstanding if words >= r.cnt + 1) { words -= r.cnt + 1; n += 1 }
      n
    }

    def runUntilIdle(limit: Long = 2_000_000): Unit = {
      var quiet = 0
      while (quiet < 400 && base < limit) {
        step()
        quiet = if (busy || !dut.io.ready.peek().litToBoolean) 0 else quiet + 1
      }
      assert(base < limit, "timeout")
      assert(model.errors.isEmpty, model.errors.mkString("\n"))
    }

    // Shadow updates for chip 0 writes (CPU, DMA FIFO, host).
    def write0(address: Long, data: Long, be: Int): Unit = {
      val old = shadow0.getOrElse(address, 0L)
      var v = old
      for (b <- 0 until 4 if ((be >> b) & 1) != 0) v = (v & ~(0xFFL << (8 * b))) | (((data >> (8 * b)) & 0xFF) << (8 * b))
      shadow0(address) = v
    }
  }

  def withBench(body: Bench => Unit): Unit = {
    simulate(new Harness(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(30)
      dut.reset.poke(false.B)
      val bench = new Bench(dut)
      body(bench)
    }
  }

  "host accesses reach both chips" in {
    withBench { b =>
      val rng = new Random(2)
      val writes = (0 until 64).map(_ => (((rng.nextInt(1 << 23) & ~3) | (rng.nextInt(2).toLong << 25)), rng.nextLong() & 0xFFFFFFFFL))
      for ((a, d) <- writes) b.hostQueue.enqueue(b.HostRequest(a, write = true, d))
      for ((a, _) <- writes) b.hostQueue.enqueue(b.HostRequest(a, write = false, 0))
      b.runUntilIdle()
      val last = writes.groupBy(_._1).map { case (a, ws) => a -> ws.last._2 }
      assert(b.hostReads.size == writes.size)
      for ((h, d) <- b.hostReads) assert(d == last(h.address), f"host read ${h.address}%x: $d%08x, expected ${last(h.address)}%08x")
      // Chip 1 got what was written there
      for ((a, d) <- last if ((a >> 25) & 1) == 1) {
        val col = (a & 0x3FF) >> 1
        val lo = b.model.memory.getOrElse((1, ((a >> 10) & 3).toInt, ((a >> 12) & 0x1FFF).toInt, col.toInt), 0)
        assert(lo == (d & 0xFFFF))
      }
    }
  }

  "CPU reads, writes, cache fills and DMA" in {
    withBench { b =>
      val rng = new Random(3)
      // Fill a region of main RAM through the host first.
      val base = 0x1000L
      for (i <- 0 until 256) {
        val d = rng.nextLong() & 0xFFFFFFFFL
        b.hostQueue.enqueue(b.HostRequest(base + 4 * i, write = true, d))
        b.write0(base + 4 * i, d, 15)
      }
      b.runUntilIdle()
      // CPU writes with byte enables, then reads of every kind.
      for (i <- 0 until 40) {
        val a = base + 4 * rng.nextInt(256)
        val d = rng.nextLong() & 0xFFFFFFFFL
        val be = 1 + rng.nextInt(15)
        b.ramQueue.enqueue(b.RamRequest(a, rnw = false, be, d, dma = false, 0, cache = false))
        b.write0(a, d, be)
      }
      val reads = (0 until 60).map { i =>
        val a = base + 4 * rng.nextInt(240)
        i % 3 match {
          case 0 => b.RamRequest(a, rnw = true, 15, 0, dma = false, 0, cache = false)
          case 1 => b.RamRequest(a, rnw = true, 15, 0, dma = false, 0, cache = true)
          case _ => b.RamRequest(a, rnw = true, 15, 0, dma = true, rng.nextInt(4), cache = false)
        }
      }
      reads.foreach(b.ramQueue.enqueue)
      b.runUntilIdle()
      // Plain and cache reads return the requested word
      val nonDma = reads.filter(!_.dma)
      assert(b.ramResults.map(_._1) == nonDma)
      for ((r, d) <- b.ramResults) assert(d == b.shadow0(r.adr), f"read ${r.adr}%x: $d%08x")
      // Cache fills: the words from the requested one to the end of the line, in order
      val fills = nonDma.filter(_.cache).flatMap { r =>
        val start = ((r.adr >> 2) & 3).toInt
        (start until 4).map(slot => (((r.adr >> 4) & 0xFF).toInt, slot, b.shadow0((r.adr & ~0xFL) + 4 * slot)))
      }
      assert(b.cacheWrites.map(c => (c._1, c._2, c._3)) == fills)
      // DMA words
      val dmaExpected = reads.filter(_.dma).flatMap(r => (0 to r.cnt).map(i => b.shadow0(r.adr + 4 * i)))
      assert(b.dmaWords == dmaExpected)
    }
  }

  "DMA reads back to back, a whole block (the MDEC's are 32 words)" in {
    withBench { b =>
      val rng = new Random(6)
      val base = 0x2000L
      for (i <- 0 until 128) {
        val d = rng.nextLong() & 0xFFFFFFFFL
        b.hostQueue.enqueue(b.HostRequest(base + 4 * i, write = true, d))
        b.write0(base + 4 * i, d, 15)
      }
      b.runUntilIdle()
      // Four words each, the next asked for as soon as one is accepted.
      val reads = (0 until 32).map(i => b.RamRequest(base + 16 * i, rnw = true, 15, 0, dma = true, 3, cache = false))
      reads.foreach(b.ramQueue.enqueue)
      b.runUntilIdle()
      assert(b.dmaWords == (0 until 128).map(i => b.shadow0(base + 4 * i)))
    }
  }

  "line copies within chip 1 (wrapping at the line's end, across rows)" in {
    withBench { b =>
      val rng = new Random(7)
      // Two VRAM lines through the host.
      for (line <- Seq(5, 6); w <- 0 until 512) {
        val a = line * 2048L + 4 * w
        val d = rng.nextLong() & 0xFFFFFFFFL
        b.hostQueue.enqueue(b.HostRequest((1L << 25) | a, write = true, d))
        b.shadow1(a) = d
      }
      b.runUntilIdle(limit = 4_000_000)
      val copies = Seq(
        b.Copy(5, 0, 481, 0x800000L),          // a 640-pixel 24-bit line
        b.Copy(6, 300, 300, 0x900000L + 2048), // wraps at the line's end
        b.Copy(5, 7, 13, 0x800000L + 4096),
      )
      copies.foreach(b.copyQueue.enqueue)
      // CPU traffic meanwhile
      for (i <- 0 until 40) b.ramQueue.enqueue(b.RamRequest(4L * rng.nextInt(1000), rnw = true, 15, 0, dma = false, 0, cache = i % 2 == 0))
      b.runUntilIdle(limit = 4_000_000)
      for (c <- copies; j <- 0 until c.words) b.hostQueue.enqueue(b.HostRequest((1L << 25) | (c.to + 4 * j), write = false, 0))
      b.runUntilIdle(limit = 8_000_000)
      for ((h, d) <- b.hostReads) {
        val e = b.shadow1.getOrElse(h.address & 0x1FFFFFFL, 0L)
        assert(d == e, f"copy at ${h.address & 0x1FFFFFFL}%x: $d%08x, expected $e%08x")
      }
      assert(b.hostReads.size == copies.map(_.words).sum)
    }
  }

  "DMA write FIFO drains before CPU reads" in {
    withBench { b =>
      val rng = new Random(4)
      val writes = (0 until 50).map(i => ((0x8000L + 4 * rng.nextInt(64)), rng.nextLong() & 0xFFFFFFFFL))
      for ((a, d) <- writes) { b.dmafifo.enqueue((a, d)); b.write0(a, d, 15) }
      val reads = writes.map(_._1).distinct.map(a => b.RamRequest(a, rnw = true, 15, 0, dma = false, 0, cache = false))
      reads.foreach(b.ramQueue.enqueue)
      b.runUntilIdle()
      for ((r, d) <- b.ramResults) assert(d == b.shadow0(r.adr), f"read ${r.adr}%x: $d%08x")
      assert(b.ramResults.size == reads.size)
    }
  }

  "DDR3 port: writes and bursts across rows, with CPU traffic" in {
    withBench { b =>
      val rng = new Random(5)
      // VRAM lines (2 KiB = 256 words of 64 bits); save state area too.
      for (i <- 0 until 300) {
        val addr = if (i % 10 == 0) (0xE000000L >> 3) + rng.nextInt(512) else rng.nextInt(1 << 17).toLong
        b.ddrQueue.enqueue(b.DdrRequest(addr, 1, write = true, rng.nextLong() & Long.MaxValue, if (rng.nextInt(3) == 0) rng.nextInt(256) else 0xFF))
      }
      for (i <- 0 until 30) {
        val addr = rng.nextInt(1 << 17).toLong
        b.ddrQueue.enqueue(b.DdrRequest(addr, 1 + rng.nextInt(128), write = false, 0, 0))
      }
      for (i <- 0 until 100) b.ramQueue.enqueue(b.RamRequest(4L * rng.nextInt(1000), rnw = true, 15, 0, dma = false, 0, cache = i % 2 == 0))
      b.runUntilIdle(limit = 6_000_000)
      assert(b.ddrReadExpected.isEmpty)
    }
  }
}
