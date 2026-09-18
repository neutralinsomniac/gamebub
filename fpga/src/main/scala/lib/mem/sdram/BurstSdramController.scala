package lib.mem.sdram

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, PipelineMemoryInterface}
import lib.mem.sdram.BurstSdramController.{Address, Command, State}

object BurstSdramController {
  case class Config(
    /** Real clock frequency (Hz) */
    clockFrequency: Int,

    /** Physical address width */
    addressWidth: Int = 13,
    /** Physical data width (word size) */
    dataWidth: Int = 16,
    /** Bank address width */
    bankWidth: Int = 2,
    /** Row address width */
    rowWidth: Int = 13,
    /** Column address width */
    columnWidth: Int = 9,

    /** How many physical words go into a logical word. */
    accessLength: Int = 2,
    /** CAS latency (2 or 3) */
    casLatency: Int = 2,

    /** Chip initialization pause time (ns) */
    timeInit: Int = 200_000,
    /** Mode register set cycle time (ns) */
    timeRsc: Int = 15,
    /** Active to Active command delay time (ns) */
    timeRc: Int = 60,
    /** Active to Read/Write delay time (ns) */
    timeRcd: Int = 18,
    /** Precharge to Active delay time (ns) */
    timeRp: Int = 18,
    /** Write recovery time (ns) */
    timeWr: Int = 15,
    /** Refresh period (for all rows) (ns) */
    timeRef: Int = 64_000_000,

    /** Initialization refresh cycle count. */
    initRefreshCount: Int = 8,

    /**
     * Whether to optimize for linear burst accesses.
     *
     * Keeps reads open in a clock suspended state, which increases latency of
     * random accesses.
     */
    enableBurst: Boolean = true,

    /**
     * Extra clock cycles between a read command and its first data word
     * reaching `regData`, on top of the CAS latency, should the round trip
     * through the pins (the forwarded clock's phase, the SDRAM's access time
     * and the input path) take more than a cycle. The SDRAM itself is still
     * programmed with `casLatency`; the clock suspend that ends a burst is
     * timed from the command, so it is not affected. With CAS latency m the
     * chip drives a beat from the clock edge m - 1 after the READ so that it
     * is valid at edge m (JEDEC), which the plain count already allows for.
     */
    readLatencyExtra: Int = 0,
    /**
     * Capture the read data on the falling edge of the clock, half a cycle
     * before the rising edge that moves it into `regData`. Together with a
     * forwarded clock phase this puts the capture point in the middle of the
     * data valid window instead of at its edge.
     */
    readCaptureFalling: Boolean = false,
  ) {
    /** The physical data bus width (in bytes). */
    val dataWidthBytes: Int = dataWidth / 8

    /** The logical address width. */
    val logicalAddressWidth: Int = new Address(this).getWidth

    /** The logical data width, considering word size length. */
    val logicalDataWidth: Int = dataWidth * accessLength

    /** Clock cycle time in nanoseconds */
    val clockPeriod: Double = 1000000000 / clockFrequency

    /** Cycles to wait during initial initialization. */
    val initDuration = (timeInit / clockPeriod).ceil.toInt

    /** Cycles to wait during mode set. */
    val modeDuration = (timeRsc / clockPeriod).ceil.toInt

    /** Cycles to wait during auto-refresh. */
    val refreshDuration = (timeRc / clockPeriod).ceil.toInt

    /** Cycles to wait during precharge.  */
    val prechargeDuration = (timeRp / clockPeriod).ceil.toInt

    /** Cycles to wait during row activation. */
    val activeDuration = (timeRcd / clockPeriod).ceil.toInt

    /** Cycles to wait before precharge during write. */
    val writePrechargeTime = accessLength + ((timeWr / clockPeriod).ceil.toInt - 1)

    /** Cycles to wait during a write. */
    val writeDuration = writePrechargeTime + prechargeDuration

    /** Cycles to wait during a read. */
    val readDuration = casLatency + accessLength + readLatencyExtra

    /**
     * Cycles to wait before clock suspend during read.
     * 1 cycle because regCke is only set next cycle,
     * another cycle because CKE takes effect one cycle after that.
     * Timed from the read command (like the SDRAM's burst), so the extra
     * read latency plays no part.
     */
    val readClockSuspendTime = casLatency + accessLength - 2

    /** Number of clock cycles between auto-refresh commands. */
    val refreshInterval = ((timeRef / (1 << rowWidth)) / clockPeriod).floor.toInt

    /** Width of the command duration counter (overestimate) */
    val waitCounterWidth = log2Ceil(timeInit) + 1

    /** Value of the mode register. */
    def mode: UInt = Cat(
      // Reserved (3 bits)
      0.U(3.W),
      // Write mode. 0: Burst read and burst write, 1: Single write
      0.U(1.W),
      // Reserved (2 bits)
      0.U(2.W),
      // CAS latency
      casLatency.U(3.W),
      // Burst type. 0: Sequential, 1: Interleaved
      0.U(1.W),
      // Burst length (words) -- 0b111: full page
      "b111".U(3.W),
    )
    assert(mode.getWidth <= addressWidth)
  }

  /// SDRAM commands, treated as (CS, RAS, CAS, WE).
  private object Command extends ChiselEnum {
    val mode = Value
    val refresh = Value
    val precharge = Value
    val active = Value
    val write = Value
    val read = Value
    val burstStop = Value
    val nop = Value
    val deselect = Value
  }

  /// States of the controller.
  private object State extends ChiselEnum {
    val init = Value
    val mode = Value
    val idle = Value
    val active = Value
    val write = Value
    val read = Value
    /// End of a burst, waiting for something to happen.
    val readSuspend = Value
    /// Burst ended, precharge
    val precharge = Value
    val refreshSuspend = Value
    val refresh = Value
    // TODO: support self-refresh mode?
  }

  private class Address(config: Config) extends Bundle {
    val bank = UInt(config.bankWidth.W)
    val row = UInt(config.rowWidth.W)
    val column = UInt(config.columnWidth.W)
    val _word = UInt(log2Ceil(config.dataWidthBytes).W)
  }
}

/// SDRAM controller that tries to keep bursts active
class BurstSdramController(config: BurstSdramController.Config) extends Module {
  val io = IO(new Bundle {
    /** Signals to the SDRAM chip. */
    val signals = new Signals(
      addressWidth = config.addressWidth,
      dataWidth = config.dataWidth,
      bankWidth = config.bankWidth
    )

    /** Standard memory interface to consumers. */
    val mem = new PipelineMemoryInterface(
      addressWidth = config.logicalAddressWidth,
      dataWidth = config.logicalDataWidth
    )

  })

  private val nextState = Wire(State())
  private val nextCommand = Wire(Command())

  private val regState = RegNext(nextState, State.init)
  private val regCommand = RegNext(nextCommand, Command.nop)
  /** Address of the current access. */
  private val regAccessAddress = Reg(new Address(config))
  /** Whether the current access is a write. */
  val regAccessWrite = Reg(Bool())
  /** Data for the access. */
  val regData = Reg(Vec(config.accessLength, UInt(config.dataWidth.W)))
  /** Clock enable (takes effect next cycle) */
  val regClockEnable = RegInit(true.B)
  /** DQM */
  val regDqm = RegInit("b11".U(2.W))

  val regDelayCounter = RegInit(0.U(config.waitCounterWidth.W))
  when (nextState =/= regState) {
    regDelayCounter := 0.U
  } .otherwise {
    regDelayCounter := regDelayCounter + 1.U
  }

  // Refresh deficit handling
  // Number of refreshes we need to do.
  val regRefreshDeficit = Reg(UInt(13.W))
  val (_, refreshOverflow) = Counter(0 until config.refreshInterval)
  when (refreshOverflow && nextCommand =/= Command.refresh) {
    regRefreshDeficit := regRefreshDeficit + 1.U
  }
  when (!refreshOverflow && nextCommand === Command.refresh) {
    regRefreshDeficit := regRefreshDeficit - 1.U
  }
  val doRefresh = regRefreshDeficit > 0.U
  val doRefreshUrgent = regRefreshDeficit >= 4096.U

  // Request intake handling
  val canAcceptRequest = WireDefault(false.B)
  val regRequestPending = RegInit(false.B)
  val doRequest = WireDefault(regRequestPending)
  when (io.mem.ready && io.mem.enable) {
    regAccessAddress := io.mem.address.asTypeOf(regAccessAddress)
    regAccessWrite := io.mem.isWrite
    regRequestPending := true.B
    doRequest := true.B
  }
  when (nextState === State.active) {
    regRequestPending := false.B
  }

  val initPause = WireDefault(false.B)
  val modeDone = regDelayCounter === (config.modeDuration - 1).U
  val refreshDone = regDelayCounter === (config.refreshDuration - 1).U
  val activeDone = regDelayCounter === (config.activeDuration - 1).U
  val writeDone = regDelayCounter === (config.writeDuration - 1).U
  val readDone = regDelayCounter === (config.readDuration - 1).U

  /** The read data as captured: on the falling edge when configured (see the config). */
  /**
   * The read data as captured: on the falling edge when configured (see the
   * config). `dataIn_reg` in the netlist; a constraints file can place it in
   * the IOB (its clock inversion is available there) for a fixed pin path.
   */
  private val dataIn = if (config.readCaptureFalling) {
    withClock((!clock.asBool).asClock) { RegNext(io.signals.dataIn) }
  } else {
    io.signals.dataIn
  }
  when (regState === State.read || regState === State.write) {
    regData := dataIn +: regData.init
  }

  nextState := regState
  nextCommand := Command.nop

  switch (regState) {
    is (State.init) {
      canAcceptRequest := true.B
      // 1. Initial pause (200 microseconds). Hold DKM and CKE high.
      // 2. Precharge all banks (keep A10 high)
      // 3. Set mode register
      // 4. 8 auto-refresh cycles (before or after mode register)
      val counterStartPrecharge = config.initDuration - 1
      val counterStartMode = counterStartPrecharge + config.prechargeDuration
      initPause := regDelayCounter <= counterStartPrecharge.U
      when (regDelayCounter === counterStartPrecharge.U) {
        // Precharge all banks (keep A10 high).
        nextCommand := Command.precharge
      } .elsewhen (regDelayCounter === counterStartMode.U) {
        nextState := State.mode
        nextCommand := Command.mode
        regRefreshDeficit := config.initRefreshCount.U
      }
    }
    is (State.mode) {
      canAcceptRequest := true.B

      when (modeDone) {
        nextState := State.idle
      }
    }
    is (State.idle) {
      canAcceptRequest := true.B

      // Do refreshes if the deficit is high, or there is no request pending.
      // Unless the refresh is urgent, wait until we've been in the idle state for 8 cycles.
      // Empirically chosen value: after this amount of time, we're likely going to be in idle
      // for a while. 
      when (doRefreshUrgent || (doRefresh && !doRequest && regDelayCounter >= 8.U)) {
        nextState := State.refresh
        nextCommand := Command.refresh
      } .elsewhen (doRequest) {
        nextState := State.active
        nextCommand := Command.active

      }
    }
    is (State.active) {
      // TODO ensure we wait tRC between actives and refreshes
      when (activeDone) {
        regDqm := 0.U // Prepare for read and write.
        when (regAccessWrite) {
          nextState := State.write
          nextCommand := Command.write
          regData := io.mem.dataWrite.asTypeOf(regData)
        } .otherwise {
          nextState := State.read
          nextCommand := Command.read
        }
      }
    }
    is (State.write) {
      when (regDelayCounter === (config.writePrechargeTime - 1).U) {
        nextCommand := Command.precharge
      }

      // After writing wordSize words, raise DQM to avoid overwriting the next
      // data during the precharge.
      when (regDelayCounter === (config.accessLength - 1).U) {
        regDqm := "b11".U(2.W)
      }

      when (writeDone) {
        nextState := State.idle
        // TODO: see about avoiding extra clock cycle before going to refresh or active
      }
    }
    is (State.read) {
      // Whether this access represents the last word of the column.
      val isLastWord = ((regAccessAddress.column + config.accessLength.U) === 0.U) || (!config.enableBurst.B)

      // Precharge after the last word of the column
      when (isLastWord && regDelayCounter === config.accessLength.U) {
        nextCommand := Command.precharge
        // TODO: make sure tRP has passed from precharge to next activate.
        // Should be fine because we wait CAS *plus* another cycle because we go to idle first.
      }

      // Prepare to enter read suspend state: disable clock
      when (!isLastWord && regDelayCounter === config.readClockSuspendTime.U) {
        regClockEnable := false.B
      }

      when (readDone) {
        when (isLastWord) {
          // This was the last word of the column, and we already precharged.
          nextState := State.idle
        } .otherwise {
          // Keep the bank active, but suspend the burst.
          // Clock was already suspended.
          nextState := State.readSuspend
          // Prepare access address for the next sequential read.
          regAccessAddress.column := regAccessAddress.column + config.accessLength.U
        }
      }
    }
    is (State.readSuspend) {
      // Guarantees "ready" is high for at least one cycle to give back the read data.
      canAcceptRequest := true.B

      when (doRequest) {
        val nextAddress = io.mem.address.asTypeOf(regAccessAddress)
        when (!io.mem.isWrite && regAccessAddress === nextAddress) {
          // This is a read request for the next word, continue doing the read.
          // Note that the next word is already on the bus.
          regRequestPending := false.B // Don't keep the request pending
          nextState := State.read
          regClockEnable := true.B
          // CAS already done, so start after that. DQ already has the next word.
          // Actually start one later, because CKE latency
          regDelayCounter := (config.casLatency - 1).U
        } .elsewhen (!io.mem.isWrite && (regAccessAddress.bank === nextAddress.bank) && (regAccessAddress.row === nextAddress.row)) {
          // Read request for same column.
          regRequestPending := false.B // Don't keep the request pending
          regClockEnable := true.B
          // Go to active, one cycle before next Read command (for clock enable)
          // TODO: have a general "do a command in one cycle after clock enable"
          // because this only works when activeDuration is > 1
          nextState := State.active
          regDelayCounter := (config.activeDuration - 1).U
        } .otherwise {
          // This ends the burst, precharge.
          // First: for next cycle, raise clock enable
          regClockEnable := true.B
          // Then, the cycle after that, do precharge
          nextState := State.precharge
        }
      } .elsewhen (regDelayCounter > 32.U || regRefreshDeficit > 4096.U) {
        // After having spent too long without a request (or if we really need to refresh),
        // precharge the row and move to idle (allowing us to pay down the refresh deficit).
        regClockEnable := true.B
        nextState := State.precharge
      }
    }
    is (State.precharge) {
      canAcceptRequest := true.B

      // Precharge command not sent previously because clockEnable was 0, so
      // send it now.
      when (regDelayCounter === 0.U) {
        nextCommand := Command.precharge
      }
      when (regDelayCounter === (config.prechargeDuration - 1).U) {
        // Have to wait the right amount of time for row to be precharged before activate.
        // We don't send precharge command until cycle 0 of this state, so add a cycle...
        // ... but next state is idle, so there's one more cycle before activate (and it cancels out).
        nextState := State.idle
      }
    }
    is (State.refreshSuspend) {
      canAcceptRequest := true.B

      nextState := State.refresh
      nextCommand := Command.refresh
    }
    is (State.refresh) {
      canAcceptRequest := true.B

      when (refreshDone) {
        when (doRequest) {
          nextState := State.active
          nextCommand := Command.active
        } .elsewhen (doRefresh) {
          nextCommand := Command.refresh
          regDelayCounter := 0.U
        } .otherwise {
          nextState := State.idle
        }
      }
    }
  }

  io.signals.cke := regClockEnable
  io.signals.cs := regCommand.asUInt(3)
  io.signals.ras := regCommand.asUInt(2)
  io.signals.cas := regCommand.asUInt(1)
  io.signals.we := regCommand.asUInt(0)
  io.signals.dqm := regDqm // TODO: support byte write strobe
  io.signals.bank := Mux(regState === State.mode, 0.U, regAccessAddress.bank)
  io.signals.address := MuxLookup(regState.asUInt, 0.U)(Seq(
    State.init.asUInt -> "b10000000000".U, // Keep A10 high to precharge all banks.
    State.mode.asUInt -> config.mode,
    State.active.asUInt -> regAccessAddress.row,
    State.read.asUInt -> regAccessAddress.column, // No auto-precharge
    State.write.asUInt -> regAccessAddress.column, // No auto-precharge
    State.precharge.asUInt -> "b10000000000".U, // Precharge all banks
  ))
  // Every PRECHARGE is a precharge-all (A10 high). The command register
  // lags the state by a cycle, so the precharge that ends a suspended burst
  // is on the pins in the idle state, where the address mux above is 0: it
  // was a single-bank precharge of `regAccessAddress.bank`, which the
  // request that ended the burst had already pointed at the *next* bank.
  // The bank just read stayed open, and its next ACTIVE (from idle, after
  // a suspend timeout or a refresh) was ignored by the chip: the read then
  // returned the stale row. The precharges during a read or write (last
  // word of a page, write recovery) address the bursting bank either way.
  when (regCommand === Command.precharge) {
    io.signals.address := "b10000000000".U
  }
  io.signals.dataOut := regData.last
  io.signals.dataDir := regState === State.write

  io.mem.dataRead := regData.asUInt
  io.mem.ready := canAcceptRequest && !regRequestPending
}
