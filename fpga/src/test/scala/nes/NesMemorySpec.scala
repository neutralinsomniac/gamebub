package nes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import psx.{SdramChipsModel, SdramEngine}

import scala.collection.mutable
import scala.util.Random

/**
 * Verifies NesMemory (host 32-bit port, ch0/ch1/ch2 8-bit channels with
 * coherent 32-bit read caches, and 64-bit savestate port) against the
 * cycle-accurate SdramChipsModel at 4:1 clk4x:clk1x ratio.
 */
class NesMemorySpec extends AnyFreeSpec {
  val Clock4xHz = 85.909091e6
  val config = SdramEngine.Config(
    clockHz = Clock4xHz,
    chips = 1,
    captureLatency = 4, // SdramChipsModel delivers beat 0 at n+2 (captureLatency 4)
    sourceBits = NesMemory.SourceBits,
    tInit = 2000,
    ioPrimitives = false,
  )

  /** Harness generating phase-aligned 4:1 clk4x and clk1x from a single 2x base clock. */
  class Harness extends Module {
    val io = IO(new Bundle {
      val hostEn = Input(Bool())
      val hostWr = Input(Bool())
      val hostAddr = Input(UInt(25.W))
      val hostWdata = Input(UInt(32.W))
      val hostRdata = Output(UInt(32.W))
      val hostDone = Output(Bool())

      val ch0Addr = Input(UInt(25.W))
      val ch0Rd = Input(Bool())
      val ch0Wr = Input(Bool())
      val ch0Din = Input(UInt(8.W))
      val ch0Dout = Output(UInt(8.W))
      val ch0Busy = Output(Bool())

      val ch1Addr = Input(UInt(25.W))
      val ch1Rd = Input(Bool())
      val ch1Wr = Input(Bool())
      val ch1Din = Input(UInt(8.W))
      val ch1Dout = Output(UInt(8.W))
      val ch1Busy = Output(Bool())

      val ssEna = Input(Bool())
      val ssRnw = Input(Bool())
      val ssAdr = Input(UInt(26.W))
      val ssDin = Input(UInt(64.W))
      val ssBe = Input(UInt(8.W))
      val ssDout = Output(UInt(64.W))
      val ssDone = Output(Bool())

      val ssLatchIn = Input(UInt(16.W))
      val ssLatchLoad = Input(Bool())
      val ssLatchOut = Output(UInt(16.W))

      val pins = new SdramEngine.Pins(1)
      val ready = Output(Bool())
      val clk1xEdge = Output(Bool())
      val clk4xEdge = Output(Bool())
    })

    // Free-running 3-bit divider on base clock (2x clk4x):
    // bit 0 = clk4x, bit 2 = clk1x (exact 4:1 ratio, shared delta on rising edges)
    val div = withReset(false.B) {
      val c = RegInit(0.U(3.W))
      c := c + 1.U
      c
    }
    val clk4x = div(0).asClock
    val clk1x = div(2).asClock
    io.clk4xEdge := div(0) === 0.U
    io.clk1xEdge := div === 0.U

    val mem = withClockAndReset(clk4x, reset) { Module(new NesMemory(config)) }
    mem.io.clk1x := clk1x
    mem.io.reset1x := reset.asBool
    mem.io.cacheInvalidate := false.B

    mem.io.host.enable := io.hostEn
    mem.io.host.write := io.hostWr
    mem.io.host.address := io.hostAddr
    mem.io.host.dataWrite := io.hostWdata
    mem.io.host.writeStrobe := "b1111".U
    io.hostRdata := mem.io.host.dataRead
    io.hostDone := mem.io.host.done

    mem.io.streamer.enable := false.B
    mem.io.streamer.write := false.B
    mem.io.streamer.address := 0.U
    mem.io.streamer.dataWrite := 0.U
    mem.io.streamer.writeStrobe := "b1111".U

    mem.io.ch0.addr := io.ch0Addr
    mem.io.ch0.rd := io.ch0Rd
    mem.io.ch0.wr := io.ch0Wr
    mem.io.ch0.din := io.ch0Din
    io.ch0Dout := mem.io.ch0.dout
    io.ch0Busy := mem.io.ch0.busy

    mem.io.ch1.addr := io.ch1Addr
    mem.io.ch1.rd := io.ch1Rd
    mem.io.ch1.wr := io.ch1Wr
    mem.io.ch1.din := io.ch1Din
    io.ch1Dout := mem.io.ch1.dout
    io.ch1Busy := mem.io.ch1.busy

    mem.io.ch2.addr := 0.U
    mem.io.ch2.rd := false.B
    mem.io.ch2.wr := false.B
    mem.io.ch2.din := 0.U

    mem.io.saveState.ena := io.ssEna
    mem.io.saveState.rnw := io.ssRnw
    mem.io.saveState.adr := io.ssAdr
    mem.io.saveState.din := io.ssDin
    mem.io.saveState.be := io.ssBe
    io.ssDout := mem.io.saveState.dout
    io.ssDone := mem.io.saveState.done

    mem.io.ssIn := io.ssLatchIn
    mem.io.ssLoad := io.ssLatchLoad
    io.ssLatchOut := mem.io.ssOut

    io.pins <> mem.io.pins
    io.ready := mem.io.ready
  }

  "host, byte channels with cache coherence, and 64-bit savestate port round-trip cleanly" in {
    val model = new SdramChipsModel(config, 1)
    simulate(new Harness) { dut =>
      var clk4xCycle = 0L
      var inReset = true

      def stepBase(): Unit = {
        if (!inReset && dut.io.clk4xEdge.peek().litToBoolean) {
          val dqIn = model.cycle(clk4xCycle,
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
          clk4xCycle += 1
        }
        dut.clock.step()
      }

      def step1x(): Unit = {
        do { stepBase() } while (!dut.io.clk1xEdge.peek().litToBoolean)
      }

      dut.reset.poke(true.B)
      for (_ <- 0 until 4) step1x()
      dut.reset.poke(false.B)
      inReset = false

      var timeout = 0
      while (!dut.io.ready.peek().litToBoolean && timeout < 5000) {
        step1x()
        timeout += 1
      }
      assert(dut.io.ready.peek().litToBoolean, "SDRAM did not initialize")

      // 1. Host 32-bit writes & reads
      val shadow = mutable.Map[Int, Int]()
      def hostWrite(addr: Int, data: Long): Unit = {
        dut.io.hostEn.poke(true.B)
        dut.io.hostWr.poke(true.B)
        dut.io.hostAddr.poke(addr.U)
        dut.io.hostWdata.poke(data.U)
        var t = 0
        while (!dut.io.hostDone.peek().litToBoolean && t < 100) { step1x(); t += 1 }
        assert(dut.io.hostDone.peek().litToBoolean, f"hostWrite timeout at 0x$addr%x")
        dut.io.hostEn.poke(false.B)
        step1x()
        for (b <- 0 until 4) shadow(addr + b) = ((data >> (8 * b)) & 0xFF).toInt
      }
      def hostRead(addr: Int): Long = {
        dut.io.hostEn.poke(true.B)
        dut.io.hostWr.poke(false.B)
        dut.io.hostAddr.poke(addr.U)
        var t = 0
        while (!dut.io.hostDone.peek().litToBoolean && t < 100) { step1x(); t += 1 }
        assert(dut.io.hostDone.peek().litToBoolean, f"hostRead timeout at 0x$addr%x")
        val v = dut.io.hostRdata.peek().litValue.toLong
        dut.io.hostEn.poke(false.B)
        step1x()
        v
      }

      hostWrite(0x000000, 0xDEADBEEFL)
      hostWrite(0x000004, 0x12345678L)
      hostWrite(0x200000, 0xCAFEBABEL)
      assert(hostRead(0x000000) == 0xDEADBEEFL)
      assert(hostRead(0x000004) == 0x12345678L)
      assert(hostRead(0x200000) == 0xCAFEBABEL)

      // 2. Ch0 (PPU) sequential byte reads (miss then 3 cache hits)
      def ch0Read(addr: Int): Int = {
        dut.io.ch0Addr.poke(addr.U)
        dut.io.ch0Rd.poke(true.B)
        step1x()
        var t = 0
        while (dut.io.ch0Busy.peek().litToBoolean && t < 50) { step1x(); t += 1 }
        assert(!dut.io.ch0Busy.peek().litToBoolean)
        val v = dut.io.ch0Dout.peek().litValue.toInt
        dut.io.ch0Rd.poke(false.B)
        step1x()
        v
      }
      def ch1Write(addr: Int, data: Int): Unit = {
        dut.io.ch1Addr.poke(addr.U)
        dut.io.ch1Din.poke(data.U)
        dut.io.ch1Wr.poke(true.B)
        step1x()
        var t = 0
        while (dut.io.ch1Busy.peek().litToBoolean && t < 50) { step1x(); t += 1 }
        assert(!dut.io.ch1Busy.peek().litToBoolean)
        dut.io.ch1Wr.poke(false.B)
        step1x()
        shadow(addr) = data & 0xFF
      }
      def ch1Read(addr: Int): Int = {
        dut.io.ch1Addr.poke(addr.U)
        dut.io.ch1Rd.poke(true.B)
        step1x()
        var t = 0
        while (dut.io.ch1Busy.peek().litToBoolean && t < 50) { step1x(); t += 1 }
        assert(!dut.io.ch1Busy.peek().litToBoolean)
        val v = dut.io.ch1Dout.peek().litValue.toInt
        dut.io.ch1Rd.poke(false.B)
        step1x()
        v
      }

      assert(ch0Read(0x200000) == 0xBE)
      assert(ch0Read(0x200001) == 0xBA)
      assert(ch0Read(0x200002) == 0xFE)
      assert(ch0Read(0x200003) == 0xCA)

      // 3. Cross-channel cache coherence: ch1 writes to 0x200001, ch0 must see new byte!
      ch1Write(0x200001, 0x42)
      assert(ch0Read(0x200001) == 0x42, "ch0 cache did not invalidate on ch1 write!")
      assert(ch1Read(0x000000) == 0xEF)
      assert(ch1Read(0x000003) == 0xDE)

      // 4. Savestate 64-bit write & read in slot 2 (DWORD addr 0x0380_0000 + 2 * 0x80000 = 0x0390_0000 -> SDRAM 0x1C0_0000)
      def ssWrite(dwordAdr: Int, data: BigInt, be: Int = 0xFF): Unit = {
        dut.io.ssAdr.poke(dwordAdr.U)
        dut.io.ssDin.poke(data.U)
        dut.io.ssBe.poke(be.U)
        dut.io.ssRnw.poke(false.B)
        dut.io.ssEna.poke(true.B)
        step1x()
        dut.io.ssEna.poke(false.B)
        var t = 0
        while (!dut.io.ssDone.peek().litToBoolean && t < 100) { step1x(); t += 1 }
        assert(dut.io.ssDone.peek().litToBoolean, "ssWrite timeout")
        step1x()
      }
      def ssRead(dwordAdr: Int): BigInt = {
        dut.io.ssAdr.poke(dwordAdr.U)
        dut.io.ssRnw.poke(true.B)
        dut.io.ssEna.poke(true.B)
        step1x()
        dut.io.ssEna.poke(false.B)
        var t = 0
        while (!dut.io.ssDone.peek().litToBoolean && t < 100) { step1x(); t += 1 }
        assert(dut.io.ssDone.peek().litToBoolean, "ssRead timeout")
        val v = dut.io.ssDout.peek().litValue
        step1x()
        v
      }

      val ssPattern = (BigInt(0x00051000L) << 32) | BigInt(0x00000007L)
      ssWrite(0x03900000, ssPattern)
      assert(ssRead(0x03900000) == ssPattern)
      // Verify host sees the exact same 64-bit word at SDRAM 0x1C0_0000
      assert(hostRead(0x1C00000) == 0x00000007L)
      assert(hostRead(0x1C00004) == 0x00051000L)

      // 5. Savestate ch0/ch1 latch save/restore
      dut.io.ssLatchIn.poke(0xA55A.U)
      dut.io.ssLatchLoad.poke(true.B)
      step1x()
      dut.io.ssLatchLoad.poke(false.B)
      step1x()
      assert(dut.io.ssLatchOut.peek().litValue == 0xA55A)
      assert(dut.io.ch0Dout.peek().litValue == 0x5A)
      assert(dut.io.ch1Dout.peek().litValue == 0xA5)

      // 6. Random byte traffic across ch0/ch1
      val rng = new Random(42)
      for (_ <- 0 until 200) {
        val addr = 0x3C0000 + rng.nextInt(64)
        if (rng.nextBoolean()) {
          val d = rng.nextInt(256)
          ch1Write(addr, d)
        } else if (shadow.contains(addr)) {
          val got = if (rng.nextBoolean()) ch0Read(addr) else ch1Read(addr)
          assert(got == shadow(addr), f"mismatch at 0x$addr%x: got 0x$got%x, expected 0x${shadow(addr)}%x")
        }
      }
      assert(model.errors.isEmpty, model.errors.mkString("\n"))
    }
  }
}
