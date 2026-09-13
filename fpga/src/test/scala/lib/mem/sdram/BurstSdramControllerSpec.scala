package lib.mem.sdram

import chisel3._
import chisel3.util._
import lib.log.Log
import lib.mem.{PipelineMemoryBurstCdc, PipelineMemoryInterface}
import lib.mem.sdram.BurstSdramControllerSpec.{ChainWrapper, SdramModel, Wrapper}
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite

object BurstSdramControllerSpec {
  /**
   * A cycle-level model of an SDR SDRAM chip: CAS latency from the mode
   * register, full-page sequential bursts, clock suspend, single-row
   * activation per bank.
   *
   * The chip runs on the inverted controller clock: it samples the
   * controller's registered outputs half a cycle after they change, as the
   * chip on the board does with a forwarded clock. With CAS latency m it
   * drives a beat from its edge m - 1 after the READ (to be valid at edge
   * m, JEDEC), i.e. from a falling edge here; `dataOnRising` re-registers
   * the data on the rising edge, standing for the chip's access time and
   * the input path: a beat is then on the bus from one rising edge to the
   * next and the controller captures it on the falling edge in between
   * (`readCaptureFalling`). That is the SNES interface as it behaves on the
   * board: the beats change within the first quarter cycle after the
   * controller's rising edge. Without `dataOnRising` a beat is on the bus
   * from a falling edge to the next, and the controller's rising edge in
   * between samples it: the GBA / Game Boy timing.
   */
  class SdramModel(dataOnRising: Boolean) extends Module {
    val io = IO(new Bundle {
      val signals = Flipped(new Signals(addressWidth = 13, dataWidth = 16, bankWidth = 2))
    })

    private val modelClock = (!clock.asBool).asClock
    private val dq = Wire(UInt(16.W))

    withClockAndReset(modelClock, reset) {
      /** {bank, row[4:0], column}: enough rows for the tests. */
      val mem = Mem(1 << 16, UInt(16.W))
      def index(bank: UInt, row: UInt, column: UInt): UInt = Cat(bank, row(4, 0), column)

      // CKE is sampled at every edge; a low sample suspends the next one.
      val regCke = RegInit(true.B)
      regCke := io.signals.cke
      val enabled = regCke

      val regCasLatency = RegInit(2.U(3.W))
      val openRow = Reg(Vec(4, UInt(13.W)))
      val burstBank = Reg(UInt(2.W))
      val burstColumn = Reg(UInt(9.W))
      val readActive = RegInit(false.B)
      val writeActive = RegInit(false.B)
      // The CAS pipeline: a beat read at an edge is driven from the edge
      // casLatency - 1 later (valid at edge casLatency).
      val stage = Reg(Vec(3, UInt(16.W)))
      val regDq = Reg(UInt(16.W))

      val command = Cat(io.signals.cs, io.signals.ras, io.signals.cas, io.signals.we)
      val isMode = command === "b0000".U
      val isPrecharge = command === "b0010".U
      val isActive = command === "b0011".U
      val isWrite = command === "b0100".U
      val isRead = command === "b0101".U
      val isBurstStop = command === "b0110".U

      when (enabled) {
        // Pipeline advance (also while nothing is being read: the stages then carry stale data).
        stage(1) := stage(0)
        stage(2) := stage(1)
        regDq := Mux(regCasLatency === 3.U, stage(1), stage(0))
        when (readActive) {
          stage(0) := mem.read(index(burstBank, openRow(burstBank), burstColumn))
          burstColumn := burstColumn + 1.U
        }
        when (writeActive && io.signals.dqm === 0.U) {
          mem.write(index(burstBank, openRow(burstBank), burstColumn), io.signals.dataOut)
          burstColumn := burstColumn + 1.U
        }

        when (isMode) {
          regCasLatency := io.signals.address(6, 4)
          assert(io.signals.address(2, 0) === "b111".U, "the model only knows full-page bursts")
        }
        when (isActive) {
          openRow(io.signals.bank) := io.signals.address
        }
        when (isPrecharge || isBurstStop) {
          readActive := false.B
          writeActive := false.B
        }
        when (isRead) {
          assert(io.signals.address(10) === 0.U, "the model does not know auto-precharge")
          readActive := true.B
          writeActive := false.B
          burstBank := io.signals.bank
          burstColumn := io.signals.address(8, 0) + 1.U
          stage(0) := mem.read(index(io.signals.bank, openRow(io.signals.bank), io.signals.address(8, 0)))
        }
        when (isWrite) {
          assert(io.signals.address(10) === 0.U, "the model does not know auto-precharge")
          assert(io.signals.dataDir, "write data must be driven with the command")
          readActive := false.B
          writeActive := true.B
          burstBank := io.signals.bank
          burstColumn := io.signals.address(8, 0) + 1.U
          when (io.signals.dqm === 0.U) {
            mem.write(index(io.signals.bank, openRow(io.signals.bank), io.signals.address(8, 0)), io.signals.dataOut)
          }
        }
      }
      dq := regDq
    }

    private val regDqRising = RegNext(dq)
    io.signals.dataIn := (if (dataOnRising) regDqRising else dq)
  }

  /**
   * The SNES's configuration (its 2x clock, 2-clock mode set and write
   * recovery, a short initialization). A def: the config elaborates
   * hardware types, so it has to be built inside a module.
   */
  def snesConfig(readLatencyExtra: Int, readCaptureFalling: Boolean) = BurstSdramController.Config(
    clockFrequency = 42_954_545,
    accessLength = 2,
    timeInit = 1_000,
    timeRsc = 47,
    timeWr = 47,
    enableBurst = true,
    readLatencyExtra = readLatencyExtra,
    readCaptureFalling = readCaptureFalling,
  )

  /** The controller against the model. */
  class Wrapper(readLatencyExtra: Int, readCaptureFalling: Boolean, dataOnRising: Boolean) extends Module {
    val config = snesConfig(readLatencyExtra, readCaptureFalling)
    val io = IO(new Bundle {
      val mem = new PipelineMemoryInterface(config.logicalAddressWidth, config.logicalDataWidth)
    })
    val controller = Module(new BurstSdramController(config))
    val model = Module(new SdramModel(dataOnRising))
    model.io.signals <> controller.io.signals
    io.mem <> controller.io.mem
  }

  /**
   * The SNES's SDRAM chain: a system-clock initiator, the burst CDC into
   * the 2x SDRAM clock, the controller and the model. Both clocks are
   * derived from one counter on the base clock, so that their common edges
   * fall in the same simulation delta (a slow clock made from a fast-domain
   * register fires a delta late, and the slow domain then sees post-edge
   * fast values: the CDC misbehaves in ways the hardware never does).
   * `phase` is high in the base step before a system clock edge.
   */
  class ChainWrapper extends Module {
    val config = snesConfig(readLatencyExtra = 0, readCaptureFalling = true)
    val io = IO(new Bundle {
      val mem = new PipelineMemoryInterface(config.logicalAddressWidth, config.logicalDataWidth)
      val phase = Output(Bool())
    })
    // Free-running (not held by the reset, so that the derived clocks tick
    // while the reset is applied).
    val base = withReset(false.B) { RegInit(0.U(2.W)) }
    base := base + 1.U
    // Fast: rises at base 0 -> 1 and 2 -> 3; slow: rises at 0 -> 1 (with the fast one).
    val fastClock = base(0).asClock
    val slowClock = (base(1) ^ base(0)).asClock
    io.phase := base === 0.U
    withClock(fastClock) {
      val cdc = Module(new PipelineMemoryBurstCdc(
        addressWidth = config.logicalAddressWidth,
        dataWidth = config.logicalDataWidth,
        addressBurstIncrement = 4,
        enablePrefetch = true,
      ))
      cdc.io.slowClock := slowClock
      val controller = Module(new BurstSdramController(config))
      val model = Module(new SdramModel(dataOnRising = true))
      model.io.signals <> controller.io.signals
      cdc.io.target <> controller.io.mem
      io.mem <> cdc.io.initiator
    }
  }
}

class BurstSdramControllerSpec extends AnyFunSuite {
  private class Harness(dut: Wrapper) {
    def step(n: Int = 1): Unit = dut.clock.step(n)

    def reset(): Unit = {
      dut.io.mem.enable.poke(false.B)
      dut.reset.poke(true.B)
      step()
      dut.reset.poke(false.B)
    }

    private def waitReady(what: String): Unit = {
      var n = 0
      while (!dut.io.mem.ready.peek().litToBoolean) {
        step()
        n += 1
        assert(n < 20_000, s"timeout waiting for ready ($what)")
      }
    }

    /** Issue a request (a read or a write) and wait for it to complete; returns the read data. */
    def access(address: BigInt, isWrite: Boolean, data: BigInt = 0): BigInt = {
      waitReady(s"before request 0x${address.toString(16)}")
      dut.io.mem.enable.poke(true.B)
      dut.io.mem.address.poke(address.U)
      dut.io.mem.isWrite.poke(isWrite.B)
      dut.io.mem.dataWrite.poke(data.U)
      step()
      dut.io.mem.enable.poke(false.B)
      // The request is pending: ready falls until the access has completed
      // (readSuspend / idle), where the read data is on `dataRead`.
      assert(!dut.io.mem.ready.peek().litToBoolean, "ready should fall after a request")
      waitReady(s"completion of 0x${address.toString(16)}")
      dut.io.mem.dataRead.peek().litValue
    }
    def write(address: BigInt, data: BigInt): Unit = access(address, isWrite = true, data)
    def read(address: BigInt): BigInt = access(address, isWrite = false)
  }

  private def go(readLatencyExtra: Int, readCaptureFalling: Boolean, dataOnRising: Boolean)(body: Harness => Unit): Unit = {
    simulate(new Wrapper(readLatencyExtra, readCaptureFalling, dataOnRising)) { dut =>
      val h = new Harness(dut)
      h.reset()
      body(h)
    }
  }

  /** Byte addresses: bit 0 word, bits 9:1 column, bits 22:10 row, bits 24:23 bank. */
  private val sameRow = Seq(BigInt(0x10), BigInt(0x14), BigInt(0x18), BigInt(0x1C))
  private val lastOfPage = BigInt(510 << 1)
  private val nextRow = BigInt(1 << 10)
  private val otherBank = BigInt(1 << 23) + 0x40
  private def pattern(address: BigInt): BigInt = (address * 0x9E3779B1L + 0x12345678L) & 0xFFFFFFFFL

  private def exercise(h: Harness): Unit = {
    val all = sameRow ++ Seq(lastOfPage, nextRow, otherBank)
    for (a <- all) {
      h.write(a, pattern(a))
    }
    // Single reads, in an order that takes every path out of readSuspend:
    // the next word (burst continuation), another column of the same row,
    // another row, another bank, and the last word of a page followed by
    // the first of the next.
    val order = Seq(sameRow(0), sameRow(1), sameRow(2), sameRow(3), sameRow(0), sameRow(2),
      nextRow, otherBank, sameRow(1), lastOfPage, nextRow, sameRow(3))
    for (a <- order) {
      val got = h.read(a)
      assert(got == pattern(a), f"read 0x${a}%x: got 0x${got}%08x, expected 0x${pattern(a)}%08x")
    }
    // A write that ends a burst, then the data it replaced.
    h.read(sameRow(0))
    h.write(sameRow(1), 0xCAFEBABEL)
    assert(h.read(sameRow(1)) == 0xCAFEBABEL)
    assert(h.read(sameRow(2)) == pattern(sameRow(2)))
    assert(h.read(sameRow(0)) == pattern(sameRow(0)))
  }

  test("rising-edge capture (the GBA / Game Boy timing)") {
    // On the board the chip's access time and the input path put the beat
    // just past the rising edge: `dataOnRising`.
    go(readLatencyExtra = 0, readCaptureFalling = false, dataOnRising = true) { h => exercise(h) }
  }

  test("SNES timing: data captured on the falling edge") {
    go(readLatencyExtra = 0, readCaptureFalling = true, dataOnRising = true) { h => exercise(h) }
  }

  test("a cycle of extra read latency is a cycle late here") {
    go(readLatencyExtra = 1, readCaptureFalling = true, dataOnRising = true) { h =>
      h.write(sameRow(0), pattern(sameRow(0)))
      h.write(sameRow(1), pattern(sameRow(1)))
      assert(h.read(sameRow(0)) != pattern(sameRow(0)))
    }
  }

  private class ChainHarness(dut: ChainWrapper) {
    private def step(): Unit = dut.clock.step()
    /** Advance to just after a system clock edge. */
    def stepSlow(): Unit = {
      while (!dut.io.phase.peek().litToBoolean) step()
      step()
    }
    def reset(): Unit = {
      dut.io.mem.enable.poke(false.B)
      // The clock counter is held by the reset: step the base clock (the
      // derived clocks do not tick, so the registers take their reset at
      // the first edges after it, before anything is requested).
      dut.reset.poke(true.B)
      for (_ <- 0 until 8) step()
      dut.reset.poke(false.B)
      for (_ <- 0 until 4) stepSlow()
    }
    private def waitReady(what: String): Unit = {
      var n = 0
      while (!dut.io.mem.ready.peek().litToBoolean) {
        stepSlow()
        n += 1
        assert(n < 2_000, s"timeout waiting for ready ($what)")
      }
    }
    def access(address: BigInt, isWrite: Boolean, data: BigInt = 0): BigInt = {
      waitReady(s"before request 0x${address.toString(16)}")
      dut.io.mem.enable.poke(true.B)
      dut.io.mem.address.poke(address.U)
      dut.io.mem.isWrite.poke(isWrite.B)
      dut.io.mem.dataWrite.poke(data.U)
      stepSlow()
      dut.io.mem.enable.poke(false.B)
      if (isWrite) {
        // The CDC takes the write data at the end of the first cycle after
        // the request in which it is ready (its request fifo has room).
        waitReady(s"push of 0x${address.toString(16)}")
        stepSlow()
      }
      waitReady(s"completion of 0x${address.toString(16)}")
      dut.io.mem.dataRead.peek().litValue
    }
    def write(address: BigInt, data: BigInt): Unit = access(address, isWrite = true, data)
    def read(address: BigInt): BigInt = access(address, isWrite = false)
  }

  test("through the burst CDC at the 2x clock") {
    Log.setDefaultLevel(Log.Level.Critical)
    simulate(new ChainWrapper) { dut =>
      val h = new ChainHarness(dut)
      h.reset()
      val line = (0 until 16).map(i => BigInt(0x800 + 4 * i))
      val all = line ++ Seq(lastOfPage, nextRow, otherBank) ++ sameRow
      for (a <- all) {
        h.write(a, pattern(a))
      }
      // Sequential (prefetched), then scattered, then a mix with writes.
      val order = line ++ Seq(lastOfPage, nextRow, sameRow(2), otherBank, line(5), line(6), line(7),
        sameRow(0), sameRow(1), line(15), line(0), nextRow, otherBank)
      for (a <- order) {
        val got = h.read(a)
        assert(got == pattern(a), f"read 0x${a}%x: got 0x${got}%08x, expected 0x${pattern(a)}%08x")
      }
      h.read(line(2))
      h.write(line(3), 0xCAFEBABEL)
      assert(h.read(line(3)) == 0xCAFEBABEL)
      assert(h.read(line(4)) == pattern(line(4)))
      h.write(line(4), 0x0BADF00DL)
      h.read(line(9))
      assert(h.read(line(4)) == 0x0BADF00DL)
      assert(h.read(line(3)) == 0xCAFEBABEL)
      assert(h.read(line(5)) == pattern(line(5)))
    }
  }

  test("the model tells the timings apart") {
    // Rising-edge capture against a beat that changes on the falling edge:
    // the sample lands in the next beat.
    go(readLatencyExtra = 0, readCaptureFalling = false, dataOnRising = false) { h =>
      h.write(sameRow(0), pattern(sameRow(0)))
      h.write(sameRow(1), pattern(sameRow(1)))
      assert(h.read(sameRow(0)) != pattern(sameRow(0)))
    }
  }
}
