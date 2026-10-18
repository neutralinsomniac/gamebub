package psx

import chisel3._
import chisel3.util._

object SdramEngine {
  case class Config(
    /** Controller (and SDRAM) clock frequency (Hz). */
    clockHz: Double,
    /** Number of chips on the bus (one chip select each). */
    chips: Int = 2,
    /**
     * Cycles from the cycle in which a READ is decided (`cmd` set) to the
     * cycle in which its first beat comes out of the input register: one to
     * the output register, the CAS latency, and the round trip through the
     * pins (the capture edge, see the constraints).
     */
    captureLatency: Int = 4,
    /** Bits of the transaction source tag (returned with read data). */
    sourceBits: Int = 3,
    /**
     * Make the data pins' output and tristate registers FDRE primitives (see
     * the pin registers); off in simulation, which has no models for them.
     */
    ioPrimitives: Boolean = true,
    // W9825G6KH-6 timing (ns)
    tRcd: Double = 18,
    tRp: Double = 18,
    tRas: Double = 42,
    tRrd: Double = 12,
    tWr: Double = 15,
    tRfc: Double = 60,
    tMrd: Int = 2,
    /** Refresh interval: 8192 rows per 64 ms, with some slack. */
    tRefi: Double = 7600,
    /** Power-up delay. */
    tInit: Double = 200_000,
  ) {
    val casLatency = 2
    def cycles(ns: Double): Int = math.ceil(ns * clockHz / 1e9 - 1e-6).toInt.max(1)
    val rcd = cycles(tRcd)
    val rp = cycles(tRp)
    val ras = cycles(tRas)
    val rrd = cycles(tRrd)
    val wr = cycles(tWr).max(2)
    val rfc = cycles(tRfc)
    val refi = cycles(tRefi)
    val init = cycles(tInit)
    /**
     * Earliest WRITE after a READ (cycles between the decisions): the
     * READ's two beats are on the bus in the third and fourth cycle after
     * it reaches the chip, which then needs a few nanoseconds to let go.
     */
    val readToWrite = casLatency + 3
    /**
     * Earliest PRECHARGE of the bank after a WRITE: the last beat reaches
     * the chip one cycle after the command, then the write recovery time.
     */
    val writeToPrecharge = 1 + wr
    /** Mode register: burst length 2, sequential, CAS latency 2, burst writes. */
    val mode = 0x021
  }

  /**
   * A run of 32-bit words (two-beat bursts) within one row. The column is
   * the 16-bit column of the first beat and must be even.
   */
  class Transaction(sourceBits: Int) extends Bundle {
    val chip = UInt(1.W)
    val bank = UInt(2.W)
    val row = UInt(13.W)
    val column = UInt(9.W)
    /** Number of words - 1. */
    val words = UInt(4.W)
    val write = Bool()
    val source = UInt(sourceBits.W)
  }

  class ReadWord(sourceBits: Int) extends Bundle {
    val data = UInt(32.W)
    val source = UInt(sourceBits.W)
    /** Index of the word within its transaction. */
    val index = UInt(4.W)
    /** The last word of its transaction. */
    val last = Bool()
  }

  /** The SDRAM bus, every output from a register meant for the pin's I/O block. */
  class Pins(chips: Int) extends Bundle {
    val csN = Output(UInt(chips.W))
    val rasN = Output(Bool())
    val casN = Output(Bool())
    val weN = Output(Bool())
    val bank = Output(UInt(2.W))
    val address = Output(UInt(13.W))
    val dqm = Output(UInt(2.W))
    val dqOut = Output(UInt(16.W))
    val dqOe = Output(UInt(16.W))
    val dqIn = Input(UInt(16.W))
  }

  object State extends ChiselEnum {
    val initWait, initPrecharge, initRefresh, initMode, idle, precharge, activate, access, refreshPrecharge, refresh = Value
  }
}

/**
 * Command engine for SDR SDRAM chips sharing one bus (the Game Bub rev 4:
 * two W9825G6KH, separate chip selects), running at the SDRAM clock.
 *
 * It executes one transaction at a time: a run of 32-bit words, each a
 * two-beat burst (burst length 2), within one row of one bank. Rows are
 * left open (one per bank, eight banks with two chips); a transaction to an
 * open row starts with its first READ / WRITE, one to another row of the
 * bank precharges it first. Bursts go out every other cycle, so a run moves
 * one beat per cycle. Both chips are refreshed together (all banks
 * precharged, then one AUTO REFRESH with both chip selects), every
 * `tRefi`; a pending refresh goes before the next transaction.
 *
 * Write data: while a write transaction runs, the engine takes word
 * `writeIndex` in the cycles that `writeTake` is high; `writeData` and
 * `writeMask` (byte enables) must show that word of the transaction from
 * `writeSource` combinationally.
 *
 * Read data comes out on `read`, one word per two cycles at best, in
 * order, tagged with its transaction's source and the word's index.
 *
 * The bus outputs come straight from registers meant to be packed into the
 * I/O blocks (`IOB` attribute), the data direction one register per pin; the
 * read data goes into an I/O block register first.
 */
class SdramEngine(config: SdramEngine.Config) extends Module {
  import SdramEngine._
  import config._

  val io = IO(new Bundle {
    val request = Flipped(Decoupled(new Transaction(sourceBits)))
    val writeTake = Output(Bool())
    val writeIndex = Output(UInt(4.W))
    val writeSource = Output(UInt(sourceBits.W))
    val writeData = Input(UInt(32.W))
    val writeMask = Input(UInt(4.W))
    val read = Valid(new ReadWord(sourceBits))
    /** Initialization done. */
    val ready = Output(Bool())
    /** Statistics: a transaction found its row open / had to precharge another row first. */
    val rowHit = Output(Bool())
    val rowConflict = Output(Bool())
    val pins = new Pins(chips)
  })

  val banks = chips * 4

  //////////////////////////////////
  // Next command (decided this cycle, on the pins next cycle)
  //////////////////////////////////
  val CmdNop = "b111".U(3.W)
  val CmdActive = "b011".U(3.W)
  val CmdRead = "b101".U(3.W)
  val CmdWrite = "b100".U(3.W)
  val CmdPrecharge = "b010".U(3.W)
  val CmdRefresh = "b001".U(3.W)
  val CmdMode = "b000".U(3.W)

  val cmd = WireDefault(CmdNop)
  val cmdChips = WireDefault(0.U(chips.W)) // selected chips (active high)
  val cmdBank = WireDefault(0.U(2.W))
  val cmdAddress = WireDefault(0.U(13.W))
  val dqm = WireDefault(0.U(2.W))
  val dqOut = WireDefault(0.U(16.W))
  val dqOe = WireDefault(false.B)

  private def pinReg[T <: Data](next: T, init: T): T = {
    val r = RegNext(next, init)
    chisel3.util.addAttribute(r, "IOB = \"TRUE\"")
    r
  }
  io.pins.csN := pinReg(~cmdChips, Fill(chips, 1.U(1.W)))
  io.pins.rasN := pinReg(cmd(2), true.B)
  io.pins.casN := pinReg(cmd(1), true.B)
  io.pins.weN := pinReg(cmd(0), true.B)
  io.pins.bank := pinReg(cmdBank, 0.U(2.W))
  io.pins.address := pinReg(cmdAddress, 0.U(13.W))
  io.pins.dqm := pinReg(dqm, 0.U(2.W))
  // The data pins: an output register and a tristate register (high =
  // released, so that no inverter sits between it and the pin: the two
  // inversions here and in the top level cancel out) per pin, the pair in
  // the pin's I/O block. The two must have the same controls there, so they
  // are primitives without reset (synthesis folds logic into an inferred
  // register's reset); the tristate registers are kept apart (DONT_TOUCH;
  // synthesis would merge the sixteen equal registers into one). They start
  // released at configuration and take the engine's (idle) state with the
  // first clock edge.
  if (ioPrimitives) {
    val outs = (0 until 16).map(i => xilinx.FDRE(clock, dqOut(i), init = false))
    // (DONT_TOUCH on these is in core_psx.xdc: firtool takes attributes on registers only.)
    val tristates = (0 until 16).map(_ => xilinx.FDRE(clock, !dqOe, init = true))
    outs.foreach(_.suggestName("dqOutPin"))
    tristates.foreach(_.suggestName("dqTristatePin"))
    io.pins.dqOut := VecInit(outs.map(_.io.Q)).asUInt
    io.pins.dqOe := ~VecInit(tristates.map(_.io.Q)).asUInt
  } else {
    io.pins.dqOut := pinReg(dqOut, 0.U(16.W))
    io.pins.dqOe := VecInit((0 until 16).map(_ => pinReg(dqOe, false.B))).asUInt
  }
  val dqIn = pinReg(io.pins.dqIn, 0.U(16.W))

  //////////////////////////////////
  // Bank and bus timing
  //////////////////////////////////
  val bankOpen = RegInit(VecInit(Seq.fill(banks)(false.B)))
  val bankRow = Reg(Vec(banks, UInt(13.W)))
  /** Cycles until the bank may be precharged (tRAS, write recovery). */
  val bankPrechargeWait = RegInit(VecInit(Seq.fill(banks)(0.U(4.W))))
  for (b <- 0 until banks) {
    when (bankPrechargeWait(b) =/= 0.U) {
      bankPrechargeWait(b) := bankPrechargeWait(b) - 1.U
    }
  }
  /** Cycles until the chip may get another ACTIVE (tRRD). */
  val chipActiveWait = RegInit(VecInit(Seq.fill(chips)(0.U(2.W))))
  for (c <- 0 until chips) {
    when (chipActiveWait(c) =/= 0.U) {
      chipActiveWait(c) := chipActiveWait(c) - 1.U
    }
  }
  /** Cycles until the next READ / WRITE (the previous burst's second beat). */
  val burstWait = RegInit(0.U(1.W))
  when (burstWait =/= 0.U) { burstWait := 0.U }
  /** Cycles until a WRITE may follow the last READ. */
  val writeWait = RegInit(0.U(3.W))
  when (writeWait =/= 0.U) { writeWait := writeWait - 1.U }
  /** Sequencer delay (tRP, tRCD, tRFC, tMRD, initialization). */
  val delay = RegInit(init.U(log2Ceil(init + 1).W))
  when (delay =/= 0.U) { delay := delay - 1.U }

  val refreshCounter = RegInit(0.U(log2Ceil(refi + 1).W))
  val refreshDue = RegInit(false.B)
  when (refreshCounter === (refi - 1).U) {
    refreshCounter := 0.U
    refreshDue := true.B
  } .otherwise {
    refreshCounter := refreshCounter + 1.U
  }

  //////////////////////////////////
  // Sequencer
  //////////////////////////////////
  val state = RegInit(State.initWait)
  val initRefreshes = RegInit(0.U(4.W))
  val txn = Reg(new Transaction(sourceBits))
  val txnIndex = Reg(UInt(4.W))
  val txnColumn = Reg(UInt(9.W))
  val txnBank = Cat(txn.chip, txn.bank)
  /** The second beat of a WRITE goes out in the cycle after it. */
  val writeSecond = RegInit(false.B)
  val writeSecondData = Reg(UInt(16.W))
  val writeSecondMask = Reg(UInt(2.W))

  io.ready := RegNext(state =/= State.initWait && state =/= State.initPrecharge &&
    state =/= State.initRefresh && state =/= State.initMode, false.B)
  io.request.ready := false.B
  io.writeTake := false.B
  io.writeIndex := txnIndex
  io.writeSource := txn.source
  io.rowHit := false.B
  io.rowConflict := false.B

  val allChips = Fill(chips, 1.U(1.W))
  val anyBankOpen = bankOpen.asUInt.orR
  val allPrechargeable = bankPrechargeWait.map(_ === 0.U).reduce(_ && _)

  when (writeSecond) {
    writeSecond := false.B
    dqOut := writeSecondData
    dqm := writeSecondMask
    dqOe := true.B
  }

  switch (state) {
    is (State.initWait) {
      when (delay === 0.U) {
        state := State.initPrecharge
      }
    }
    is (State.initPrecharge) {
      cmd := CmdPrecharge
      cmdChips := allChips
      cmdAddress := (1 << 10).U // all banks
      delay := (rp - 1).U
      initRefreshes := 0.U
      state := State.initRefresh
    }
    is (State.initRefresh) {
      when (delay === 0.U) {
        cmd := CmdRefresh
        cmdChips := allChips
        delay := (rfc - 1).U
        initRefreshes := initRefreshes + 1.U
        when (initRefreshes === 7.U) {
          state := State.initMode
        }
      }
    }
    is (State.initMode) {
      when (delay === 0.U) {
        cmd := CmdMode
        cmdChips := allChips
        cmdAddress := mode.U
        delay := (tMrd - 1).U
        refreshCounter := 0.U
        refreshDue := false.B
        state := State.idle
      }
    }

    is (State.idle) {
      when (delay === 0.U) {
        when (refreshDue) {
          state := State.refreshPrecharge
        } .otherwise {
          io.request.ready := true.B
          when (io.request.valid) {
            val r = io.request.bits
            val b = Cat(r.chip, r.bank)
            txn := r
            txnIndex := 0.U
            txnColumn := r.column
            when (bankOpen(b) && bankRow(b) === r.row) {
              state := State.access
              io.rowHit := true.B
            } .elsewhen (bankOpen(b)) {
              state := State.precharge
              io.rowConflict := true.B
            } .otherwise {
              state := State.activate
            }
          }
        }
      }
    }

    is (State.precharge) {
      when (bankPrechargeWait(txnBank) === 0.U) {
        cmd := CmdPrecharge
        cmdChips := UIntToOH(txn.chip, chips)
        cmdBank := txn.bank
        bankOpen(txnBank) := false.B
        delay := (rp - 1).U
        state := State.activate
      }
    }

    is (State.activate) {
      when (delay === 0.U && chipActiveWait(txn.chip) === 0.U) {
        cmd := CmdActive
        cmdChips := UIntToOH(txn.chip, chips)
        cmdBank := txn.bank
        cmdAddress := txn.row
        bankOpen(txnBank) := true.B
        bankRow(txnBank) := txn.row
        bankPrechargeWait(txnBank) := ras.U
        chipActiveWait(txn.chip) := (rrd - 1).U
        delay := (rcd - 1).U
        state := State.access
      }
    }

    is (State.access) {
      when (delay === 0.U && burstWait === 0.U && (!txn.write || writeWait === 0.U)) {
        cmd := Mux(txn.write, CmdWrite, CmdRead)
        cmdChips := UIntToOH(txn.chip, chips)
        cmdBank := txn.bank
        cmdAddress := txnColumn // A10 low: no auto precharge
        burstWait := 1.U
        when (txn.write) {
          io.writeTake := true.B
          dqOut := io.writeData(15, 0)
          dqm := ~io.writeMask(1, 0)
          dqOe := true.B
          writeSecond := true.B
          writeSecondData := io.writeData(31, 16)
          writeSecondMask := ~io.writeMask(3, 2)
          bankPrechargeWait(txnBank) := bankPrechargeWait(txnBank).max(writeToPrecharge.U)
        } .otherwise {
          writeWait := (readToWrite - 1).U
          bankPrechargeWait(txnBank) := bankPrechargeWait(txnBank).max(2.U)
        }
        txnIndex := txnIndex + 1.U
        txnColumn := txnColumn + 2.U
        when (txnIndex === txn.words) {
          state := State.idle
        }
      }
    }

    is (State.refreshPrecharge) {
      when (!anyBankOpen) {
        state := State.refresh
      } .elsewhen (allPrechargeable) {
        cmd := CmdPrecharge
        cmdChips := allChips
        cmdAddress := (1 << 10).U
        bankOpen.foreach(_ := false.B)
        delay := (rp - 1).U
        state := State.refresh
      }
    }
    is (State.refresh) {
      when (delay === 0.U) {
        cmd := CmdRefresh
        cmdChips := allChips
        refreshDue := false.B
        delay := (rfc - 1).U
        state := State.idle
      }
    }
  }

  //////////////////////////////////
  // Read data
  //////////////////////////////////
  // The tag of each READ travels down a pipe to the cycle its first beat
  // leaves the input register; the word is complete with the second beat.
  class Tag extends Bundle {
    val source = UInt(sourceBits.W)
    val index = UInt(4.W)
    val last = Bool()
  }
  val issuedTag = Wire(Valid(new Tag))
  issuedTag.valid := cmd === CmdRead && state === State.access
  issuedTag.bits.source := txn.source
  issuedTag.bits.index := txnIndex
  issuedTag.bits.last := txnIndex === txn.words
  val firstBeat = ShiftRegister(issuedTag, captureLatency, 0.U.asTypeOf(issuedTag), true.B)
  val firstBeatData = RegNext(dqIn)
  val secondBeat = RegNext(firstBeat, 0.U.asTypeOf(firstBeat))
  io.read.valid := secondBeat.valid
  io.read.bits.data := Cat(dqIn, firstBeatData)
  io.read.bits.source := secondBeat.bits.source
  io.read.bits.index := secondBeat.bits.index
  io.read.bits.last := secondBeat.bits.last
}
