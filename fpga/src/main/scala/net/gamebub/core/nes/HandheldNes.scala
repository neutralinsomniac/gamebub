package net.gamebub.core.nes

import chisel3._
import chisel3.util._
import lib.audio.AudioFilterChain
import lib.mem.{MemoryInterface, MemoryMap, RegisterMap}
import lib.video.Crt
import nes.{NesCore, NesMemory, NesRomLoader}
import net.gamebub.framework.Core
import net.gamebub.framework.interface._
import psx.SdramEngine
import xilinx.{BUFG, BUFGCE, MMCM, ODDRWrapper, PLL, XpmCdcHandshake}

object HandheldNes {
  /**
   * NES master clock: exact 21.477272 MHz (945/44 MHz), matching NTSC colorburst x6.
   *
   * Uses the same MMCM + PLL tree as HandheldSnes:
   *  - MMCM: 50 MHz / 1 * 23.625 = 1181.25 MHz VCO
   *      OUT0: /11 = 107.38636 MHz -> BUFG -> PLL input
   *      OUT1: /displayDivider -> Display clock
   *      OUT2: /7 = 168.75 MHz -> Host SPI clock (> 160 MHz)
   *  - PLL: 107.38636 MHz / 1 * 12 = 1288.63636 MHz VCO
   *      OUT0: /60 = 21.477272 MHz (clk1x: NES master clock & system clock)
   *      OUT1: /15 = 85.909091 MHz (clk4x: SDRAM engine clock)
   *      OUT2: /15, +120 deg (3.880 ns) -> non-inverted ODDR -> sdram_clk pin
   */
  val ClockInHz = 50_000_000
  val MmcmDivide = 1
  val MmcmMultiply = 23.625
  val MmcmVcoHz = ClockInHz.toDouble / MmcmDivide * MmcmMultiply
  val PllInputDivider = 11
  val PllMultiply = 12
  val PllVcoHz = MmcmVcoHz / PllInputDivider * PllMultiply
  val SysDivider = 60
  val SdramDivider = 15
  val ClockSystemHz = (PllVcoHz / SysDivider).toInt
  val ClockSdramHz = PllVcoHz / SdramDivider
  /**
   * Phase shift of sdram_clk ahead of clk4x (264 degrees = 8.536 ns at 85.909 MHz,
   * exact multiple of the PLL's 3.0 deg = 1/8 VCO step). See core_nes.xdc.
   */
  val SdramClockPhaseDegrees = 264.0
  val SpiDivider = (MmcmVcoHz / 160_000_000).floor.toInt
  val ClockSpiHz = (MmcmVcoHz / SpiDivider).toInt
  require(ClockSpiHz > 160_000_000)

  /** NTSC master clock rate and PAL master clock rate (paced via BUFGCE). */
  val MasterClockNtscHz = 21477272
  val MasterClockPalHz = 21281370

  /** NTSC NES frame: 262 lines x 341 dots x 4 master clocks = 357,368 clocks (60.0988 Hz). */
  val MasterClocksPerFrame = 262 * 341 * 4
  val FramePeriod = MasterClocksPerFrame.toDouble / ClockSystemHz

  /** Color depth per channel in the framework's double-buffered 256x240 framebuffer (full RGB888). */
  val FramebufferColorDepth = 8

  object CommandState extends ChiselEnum {
    val idle, busy, error, done = Value
  }

  /** File IDs (fpga/cores/nes/files.json). */
  object FileId {
    val Rom = 0
    val FdsBios = 1
    val Save = 2
    val State1 = 3
  }

  /** Save states: 4 slots x 2 MiB stride in SDRAM (0x180_0000..0x1FF_FFFF), 0x144000 bytes per file. */
  object SaveState {
    val Slots = 4
    val FileSize = 0x14_4000 // 1,327,104 bytes = STATESIZE (331,776) * 4
    val RequestSave = 1
    val RequestLoad = 2
    val ResultDone = 1
    val ResultEmpty = 2
    val ResultUnavailable = 3
    val ResultFailed = 4
    /** Max clk1x cycles to wait for savestate_busy to rise (~100 ms; worst case ~1 frame to pause). */
    val StartTimeout = 2_147_727

    object Step extends ChiselEnum {
      val idle, request, starting, busy = Value
    }
  }
}

/**
 * MiSTer NES core on the Game Bub.
 *
 * Runs at the exact 21.477272 MHz NTSC master clock (paced to 21.281370 MHz for PAL),
 * backed by a single 32 MiB SDRAM chip at 85.909 MHz (`NesMemory` / `SdramEngine`).
 * Supports iNES 1.0, NES 2.0, FDS (with optional `fds_bios.bin`), NSF playback,
 * expansion audio (VRC6, VRC7 OPLL, MMC5, Sunsoft 5B, Namco 163, FDS), battery/EEPROM/FDS
 * saves, 4-slot MiSTer-compatible save states, 4-player dock multiplayer (Four Score),
 * and the Game Bub CRT/aspect scaler (`NesVideoFilter`).
 */
class HandheldNes extends Module with Core {
  import HandheldNes._

  val displayDivider = (MmcmVcoHz / ClocksV0.getClockDisplayHz(FramePeriod)._1).floor.toInt

  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      clockSystemHz = ClockSystemHz,
      clockDisplayHz = (MmcmVcoHz / displayDivider).toInt,
      clockSpiHz = ClockSpiHz,
    )
    val video = new VideoV0(
      videoWidth = NesVideoFilter.Width,
      videoHeight = NesVideoFilter.Height,
      colorDepthR = FramebufferColorDepth,
      colorDepthG = FramebufferColorDepth,
      colorDepthB = FramebufferColorDepth,
      framePeriod = FramePeriod,
    )
    val videoFilter = new VideoScalerV0(
      colorInDepthR = FramebufferColorDepth,
      colorInDepthG = FramebufferColorDepth,
      colorInDepthB = FramebufferColorDepth,
      latency = NesVideoFilter.Latency,
    )
    val audio = new AudioV0()
    val host = new HostV0()
    val input = new InputExtendedV0(numSlots = 4, hasRumble = false)
    val sdram = new SdramV0(chips = 1, dataDirPerBit = true)
  })

  //////////////////////////////////
  // Clocks
  //////////////////////////////////
  val mmcm = Module(new MMCM(
    clockInHz = ClockInHz,
    divide = MmcmDivide,
    multiply = MmcmMultiply,
    clockOutConfig = Seq(
      MMCM.ClockOut(PllInputDivider),
      MMCM.ClockOut(displayDivider),
      MMCM.ClockOut(SpiDivider),
    )
  ))
  mmcm.io.clockIn := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B

  val pll = Module(new PLL(
    clockInHz = (MmcmVcoHz / PllInputDivider).round.toInt,
    divide = 1,
    multiply = PllMultiply,
    clockOutConfig = Seq(
      PLL.ClockOut(SysDivider),
      PLL.ClockOut(SdramDivider),
      PLL.ClockOut(SdramDivider, phase = SdramClockPhaseDegrees),
    )
  ))
  pll.io.clockIn := BUFG(mmcm.io.clockOuts(0))
  pll.io.powerDown := false.B
  pll.io.reset := !mmcm.io.locked

  io.clocks.clockOutSystem := pll.io.clockOuts(0)
  val clk4x = pll.io.clockOuts(1)
  val clkSdramPin = pll.io.clockOuts(2)
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(1)
  io.clocks.clockOutSpi := mmcm.io.clockOuts(2)
  io.clocks.locked := mmcm.io.locked && pll.io.locked

  val clk1x = clock
  val reset4x = withClock(clk4x) { RegNext(RegNext(reset.asBool, true.B), true.B) }

  //////////////////////////////////
  // Framework state
  //////////////////////////////////
  val regCoreSetup = RegInit(false.B)
  val regCoreReset = RegInit(true.B)
  val regCoreFocus = RegInit(false.B)
  val regCoreResetOnce = RegInit(false.B)
  regCoreResetOnce := false.B

  val romFileSize = RegInit(0.U(25.W))
  val biosLoaded = RegInit(false.B)
  val saveLoaded = RegInit(false.B)
  val saveDirty = RegInit(false.B)

  //////////////////////////////////
  // Host registers
  //////////////////////////////////
  // N.B. Chisel Bundle.asUInt puts the LAST declared field at bit 0!
  // Default 0x00C0: maskEdges = 3 (Auto), everything else 0.
  val configReg = RegInit(0x00C0.U(14.W).asTypeOf(new Bundle {
    /** Bit 13: FDS disk speed: 0 = Fast, 1 = Original. */
    val fdsOriginalSpeed = Bool()
    /** Bit 12: FDS disk swap: 0 = Auto, 1 = L1 button. */
    val fdsManualSwap = Bool()
    /** Bit 11: Four Score 4-player multitap. */
    val multitap = Bool()
    /** Bit 10: Swap controller ports 1 and 2. */
    val joySwap = Bool()
    /** Bit 9: Button layout: 0 = A/B (X/Y turbo), 1 = B/Y (A/X turbo). */
    val buttonLayoutBY = Bool()
    /** Bit 8: Extra sprites per scanline (reduces flicker). */
    val extraSprites = Bool()
    /** Bits 7:6: Mask left/right edges (0=Off, 1=Left, 2=Both, 3=Auto). */
    val maskEdges = UInt(2.W)
    /** Bits 5:3: Palette (0=Kitrinx, 1=Smooth, 2=Wavebeam, 3=Sony CXA, 4=PC-10, 5=Tao). */
    val palette = UInt(3.W)
    /** Bits 2:0: Region (0=Auto, 1=NTSC, 2=PAL, 3=Dendy, 4=Vs. System). */
    val region = UInt(3.W)
  }))

  val regSkipUnchanged = RegInit(false.B)
  /** CRT settings (0x0028): 25% magenta/green mask, 75% scanlines, bilinear. */
  val crtReg = RegInit(0x36.U(6.W).asTypeOf(new Crt.Settings))
  /** Video scaling settings (0x002C): Crop 224p, 4:3. */
  val videoReg = RegInit(0.U(3.W).asTypeOf(new NesVideoFilter.Settings))

  // Save-state registers (0x0014, 0x0018, 0x001C)
  val stateSlot = RegInit(0.U(2.W))
  val stateRequest = RegInit(0.U(2.W))
  val stateStatus = Wire(UInt(10.W))
  val stateResult = RegInit(0.U(3.W))
  val stateWriteLevel = WireDefault(false.B)
  val stateWriteData = WireDefault(0.U(2.W))
  val stateActive = Wire(Bool())

  // Statistics
  val statRegStalls = RegInit(0.U(32.W))
  val statRegCycles = RegInit(0.U(32.W))
  val statRowHits = Wire(UInt(32.W))
  val statRowConflicts = Wire(UInt(32.W))
  val statClear = WireDefault(VecInit(Seq.fill(4)(false.B)))
  val mapperInfoWire = Wire(UInt(32.W))

  def statEntry(index: Int, value: UInt) = (0x1000 + 4 * index) -> RegisterMap.Entry(32,
    RegisterMap.ReadFn(_ => value),
    RegisterMap.WriteFn((write, _) => { statClear(index) := write }))

  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val sdramHost = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val commandInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val memoryMap = MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      0x0.U(4.W) -> registerInterface,
      0x3.U(4.W) -> sdramHost,
      0xF0.U(8.W) -> commandInterface,
    ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U

  registerInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0000 -> RegisterMap.Entry.rw(configReg),
      0x0014 -> RegisterMap.Entry(32,
        RegisterMap.ReadFn(_ => stateStatus),
        RegisterMap.WriteFn((write, data) => {
          stateWriteLevel := write
          stateWriteData := data(1, 0)
        })),
      0x0018 -> RegisterMap.Entry.rw(stateSlot),
      0x001C -> RegisterMap.Entry.r(stateResult),
      0x0024 -> RegisterMap.Entry.rw(regSkipUnchanged),
      0x0028 -> RegisterMap.Entry.rw(crtReg),
      0x002C -> RegisterMap.Entry.rw(videoReg),
      statEntry(0, statRegStalls),
      statEntry(1, statRegCycles),
      statEntry(2, statRowHits),
      statEntry(3, statRowConflicts),
      0x1010 -> RegisterMap.Entry.r(mapperInfoWire),
      0x2000 -> RegisterMap.Entry(32,
        RegisterMap.ReadFn(_ => 0.U),
        RegisterMap.WriteFn((write, data) => { when (write && data(0)) { regCoreResetOnce := true.B } })),
    )
  )

  //////////////////////////////////
  // Memory (single-chip 32 MiB SDRAM at 85.909 MHz)
  //////////////////////////////////
  val memory = withClockAndReset(clk4x, reset4x) {
    Module(new NesMemory(SdramEngine.Config(
      clockHz = ClockSdramHz,
      chips = 1,
      captureLatency = 4,
      sourceBits = NesMemory.SourceBits,
    )))
  }
  memory.io.clk1x := clk1x
  memory.io.reset1x := reset.asBool
  memory.io.host <> sdramHost
  val memoryReady = RegNext(RegNext(memory.io.ready, false.B), false.B)

  // Non-inverted ODDR on +264 deg shifted clk4x (see core_nes.xdc).
  val sdramClockOut = withClockAndReset(clkSdramPin, false.B) { Module(new ODDRWrapper(initial = false)) }
  sdramClockOut.io.D1 := true.B
  sdramClockOut.io.D2 := false.B
  io.sdram.clock := sdramClockOut.io.Q.asClock
  io.sdram.cke := 1.U(1.W)
  io.sdram.cs := memory.io.pins.csN
  io.sdram.ras := memory.io.pins.rasN
  io.sdram.cas := memory.io.pins.casN
  io.sdram.we := memory.io.pins.weN
  io.sdram.dqm := memory.io.pins.dqm
  io.sdram.bank := memory.io.pins.bank
  io.sdram.address := memory.io.pins.address
  io.sdram.dataOut := memory.io.pins.dqOut
  io.sdram.dataDir := memory.io.pins.dqOe
  memory.io.pins.dqIn := io.sdram.dataIn

  withClockAndReset(clk4x, reset4x) {
    def counter(event: Bool, clear: Bool): UInt = {
      val c = RegInit(0.U(32.W))
      when (RegNext(clear, false.B)) {
        c := 0.U
      } .elsewhen (event) {
        c := c + 1.U
      }
      c
    }
    statRowHits := RegNext(counter(RegNext(memory.io.rowHit, false.B), statClear(2)), 0.U)
    statRowConflicts := RegNext(counter(RegNext(memory.io.rowConflict, false.B), statClear(3)), 0.U)
  }

  //////////////////////////////////
  // ROM / BIOS / EEPROM Loader
  //////////////////////////////////
  val romLoader = Module(new NesRomLoader)
  romLoader.io.start := false.B
  romLoader.io.op := NesRomLoader.Op.none
  romLoader.io.romSize := romFileSize
  romLoader.io.biosLoaded := biosLoaded
  memory.io.streamer <> romLoader.io.mem

  //////////////////////////////////
  // NES Core (gated clock for PAL pacing & rare SDRAM stall protection)
  //////////////////////////////////
  val coreRun = Wire(Bool())
  val nesClock = BUFGCE(clk1x, coreRun)
  val tick = RegNext(coreRun, false.B)

  val core = Module(new NesCore)
  core.clk := nesClock

  val mapperFlags = core.mapper_flags_out
  // Auto region detection (matches NES.sv lines 188-197):
  // mapper_flags[44:43] == 1 => Vs. System (3)
  // mapper_flags[37:36] == 1 => PAL (1), == 3 => Dendy (2), else NTSC (0)
  val autoSysType = Mux(mapperFlags(44, 43) === 1.U, 3.U(2.W),
    Mux(mapperFlags(37, 36) === 1.U, 1.U(2.W),
      Mux(mapperFlags(37, 36) === 3.U, 2.U(2.W), 0.U(2.W))))
  val effSysType = Mux(configReg.region === 0.U || configReg.region > 4.U,
    autoSysType, (configReg.region - 1.U)(1, 0))
  val oldSysType = RegNext(effSysType, 0.U)
  val sysTypeChanged = oldSysType =/= effSysType
  val regionPal = effSysType === 1.U

  val hostReset = reset.asBool || regCoreReset || regCoreResetOnce || !memoryReady || romLoader.io.busy || sysTypeChanged
  core.reset_nes := hostReset
  core.cold_reset := romLoader.io.loaderDownloading || romLoader.io.loaderReset
  // NES has its own cycle-exact pausecore mechanism (freezes CPU/APU/PPU at a
  // vblank + instruction boundary, which is the exact state savestates.vhd needs).
  core.pausecore := !regCoreFocus && !stateActive

  core.sys_type := effSysType
  core.ppu_rst_behavior := false.B // Famicom reset behavior (most compatible)
  core.palette_sel := configReg.palette
  core.mask_edges := configReg.maskEdges
  core.ex_sprites := configReg.extraSprites
  core.int_audio := true.B
  core.ext_audio := true.B
  core.joy_swap := configReg.joySwap
  core.multitap := configReg.multitap
  core.fds_auto_eject := !configReg.fdsManualSwap
  core.fds_fast := !configReg.fdsOriginalSpeed
  core.dejitter_disable := false.B

  // GameLoader & EEPROM wiring
  core.loader_reset := romLoader.io.loaderReset
  core.loader_downloading := romLoader.io.loaderDownloading
  core.loader_filetype := romLoader.io.loaderFiletype
  core.loader_indata := romLoader.io.loaderIndata
  core.loader_indata_clk := romLoader.io.loaderIndataClk
  core.loader_clearval := 0.U // RAM already cleared to 0x00 by NesRomLoader
  core.max_diskside := romLoader.io.maxDiskside
  romLoader.io.loaderMemAddr := core.loader_mem_addr
  romLoader.io.loaderMemWrite := core.loader_mem_write
  romLoader.io.loaderDone := core.loader_done
  romLoader.io.loaderError := core.loader_error

  core.eeprom_clk := clk1x
  core.eeprom_addr := romLoader.io.eepromAddr
  core.eeprom_din := romLoader.io.eepromDin
  core.eeprom_wren := romLoader.io.eepromWren
  romLoader.io.eepromDout := core.eeprom_dout

  // SDRAM channel muxing: ch0 serves GameLoader while loading, PPU otherwise.
  val loaderOwnsCh0 = romLoader.io.loaderDownloading || core.loader_busy
  memory.io.ch0.addr := Mux(loaderOwnsCh0, core.loader_mem_addr, Cat(0.U(3.W), core.ppumem_addr))
  memory.io.ch0.rd := !loaderOwnsCh0 && core.ppumem_read && !hostReset
  memory.io.ch0.wr := Mux(loaderOwnsCh0, core.loader_mem_write, core.ppumem_write && !hostReset)
  memory.io.ch0.din := Mux(loaderOwnsCh0, core.loader_mem_data, core.ppumem_dout)
  core.ppumem_din := memory.io.ch0.dout
  romLoader.io.ch0Busy := memory.io.ch0Pending

  memory.io.ch1.addr := core.cpumem_addr
  memory.io.ch1.rd := core.cpumem_read && !hostReset
  memory.io.ch1.wr := core.cpumem_write && !hostReset
  memory.io.ch1.din := core.cpumem_dout
  core.cpumem_din := memory.io.ch1.dout

  memory.io.ch2.addr := core.ss_sdram_addr
  memory.io.ch2.rd := core.ss_sdram_rd
  memory.io.ch2.wr := core.ss_sdram_wr
  memory.io.ch2.din := core.ss_sdram_wdata
  core.ss_sdram_rdata := memory.io.ch2.dout

  memory.io.ssIn := core.sdram_ss_in
  memory.io.ssLoad := core.sdram_ss_load
  core.sdram_ss_out := memory.io.ssOut

  memory.io.saveState.din := core.save_out_din
  core.save_out_dout := memory.io.saveState.dout
  memory.io.saveState.adr := core.save_out_adr
  memory.io.saveState.rnw := core.save_out_rnw
  memory.io.saveState.ena := core.save_out_ena
  memory.io.saveState.be := core.save_out_be
  core.save_out_done := memory.io.saveState.done

  memory.io.cacheInvalidate := hostReset || romLoader.io.busy

  // Stall protection: at 85.9 MHz, normal SDRAM accesses complete in 1.25-2.25
  // clk1x cycles (well inside the 4-cycle PPU / 6-cycle CPU window). On the
  // rare cycle where a refresh or host SPI read delays a response until a
  // sample edge (ppu_ce, cart_ce, cpu_ce), gate nesClock cleanly.
  val stallPpu = memory.io.ch0.busy && core.ppu_ce_out
  val stallCpu = memory.io.ch1.busy && (core.cart_ce_out || core.cpu_ce_out)
  val stallSs = memory.io.ch2.busy && core.savestate_busy
  val stall = !hostReset && !romLoader.io.busy && (stallPpu || stallCpu || stallSs)

  // PAL master-clock pacing (exact 21.281370 MHz average via phase accumulator).
  val palPace = RegInit(0.U(25.W))
  val palSkip = WireDefault(false.B)
  val coreWant = !stall
  when (!regionPal || hostReset || romLoader.io.busy || stateActive) {
    palPace := 0.U
  } .elsewhen (coreWant) {
    val sum = palPace + (MasterClockNtscHz - MasterClockPalHz).U
    when (sum >= MasterClockNtscHz.U) {
      palPace := sum - MasterClockNtscHz.U
      palSkip := true.B
    } .otherwise {
      palPace := sum
    }
  }
  coreRun := coreWant && !palSkip

  // Statistics
  when (statClear(0)) {
    statRegStalls := 0.U
  } .elsewhen (regCoreFocus && !hostReset && stall) {
    statRegStalls := statRegStalls + 1.U
  }
  when (statClear(1)) {
    statRegCycles := 0.U
  } .elsewhen (regCoreFocus && !hostReset && coreRun) {
    statRegCycles := statRegCycles + 1.U
  }

  mapperInfoWire := Cat(
    core.mapper_has_savestate,
    core.bram_override_out,
    romLoader.io.isFds,
    romLoader.io.isNsf,
    effSysType,
    mapperFlags(25, 0),
  )

  // Track battery/FDS/EEPROM writes for dirty-save detection
  when (tick && !hostReset && !core.corepaused && core.save_written_out) {
    saveDirty := true.B
  }

  //////////////////////////////////
  // Save states (4 slots, MiSTer savestates.vhd)
  //////////////////////////////////
  val stateHeld = RegInit(VecInit(Seq.fill(SaveState.Slots)(false.B)))
  val stateSaved = RegInit(VecInit(Seq.fill(SaveState.Slots)(false.B)))
  val stateWrite = stateWriteLevel && !RegNext(stateWriteLevel, false.B)
  when (stateWrite) {
    stateRequest := stateWriteData
  }
  locally {
    import SaveState.Step
    val step = RegInit(Step.idle)
    val slot = RegInit(0.U(2.W))
    val saving = Reg(Bool())
    val sawLoaded = RegInit(false.B)
    val timer = Reg(UInt(log2Ceil(SaveState.StartTimeout + 1).W))

    core.save_state := step === Step.request && saving
    core.load_state := step === Step.request && !saving
    core.savestate_number := slot
    stateActive := step =/= Step.idle
    stateStatus := Cat(core.savestate_busy, stateActive, stateSaved.asUInt, stateHeld.asUInt)

    def finish(result: Int): Unit = {
      when (stateRequest === 0.U) { stateResult := result.U }
    }

    when (core.state_loaded && tick) {
      sawLoaded := true.B
    }

    switch (step) {
      is (Step.idle) {
        when (stateRequest =/= 0.U && !core.savestate_busy && !hostReset) {
          val save = stateRequest === SaveState.RequestSave.U
          val load = stateRequest === SaveState.RequestLoad.U
          stateRequest := 0.U
          when (!core.mapper_has_savestate) {
            stateResult := SaveState.ResultUnavailable.U
          } .elsewhen (save || (load && stateHeld(stateSlot))) {
            step := Step.request
            slot := stateSlot
            saving := save
            sawLoaded := false.B
            when (save) {
              stateHeld(stateSlot) := false.B
              stateSaved(stateSlot) := false.B
            }
          } .elsewhen (load) {
            stateResult := SaveState.ResultEmpty.U
          }
        }
      }
      is (Step.request) {
        // Hold save_state / load_state until the core clocks once so
        // statemanager's rising-edge detector sees it even under PAL pacing.
        when (coreRun) {
          step := Step.starting
          timer := 0.U
        }
      }
      is (Step.starting) {
        timer := timer + 1.U
        when (core.savestate_busy) {
          step := Step.busy
        } .elsewhen (timer === SaveState.StartTimeout.U) {
          step := Step.idle
          finish(SaveState.ResultFailed)
        }
      }
      is (Step.busy) {
        when (!core.savestate_busy) {
          step := Step.idle
          when (saving) {
            stateHeld(slot) := true.B
            stateSaved(slot) := true.B
            finish(SaveState.ResultDone)
          } .otherwise {
            // If savestates.vhd rejected the header, state_loaded never pulsed.
            when (sawLoaded) {
              finish(SaveState.ResultDone)
            } .otherwise {
              finish(SaveState.ResultEmpty)
            }
          }
        }
      }
    }
    when (stateWrite && stateWriteData =/= 0.U) {
      stateResult := 0.U
    }
    when (hostReset) {
      step := Step.idle
      stateRequest := 0.U
    }
  }

  //////////////////////////////////
  // Controllers (4 players + turbo + FDS swap / Mic)
  //////////////////////////////////
  // ~15 Hz turbo clock (toggle every ~715,909 cycles -> ~30 Hz toggle = 15 Hz press)
  val turboCounter = RegInit(0.U(20.W))
  turboCounter := turboCounter + 1.U
  val turboPulse = turboCounter(19)

  def mapPad(slot: InputExtendedV0.Slot): UInt = {
    val b = slot.buttons
    val a = slot.analog
    val upRaw = b.up || a.leftY <= (-64).S
    val downRaw = b.down || a.leftY >= 64.S
    val leftRaw = b.left || a.leftX <= (-64).S
    val rightRaw = b.right || a.leftX >= 64.S
    // Opposing D-pad direction filter
    val up = upRaw && !downRaw
    val down = downRaw && !upRaw
    val left = leftRaw && !rightRaw
    val right = rightRaw && !leftRaw

    // Layout 0 (A/B): A=A, B=B, X=Turbo A, Y=Turbo B
    // Layout 1 (B/Y): B=A, Y=B, A=Turbo A, X=Turbo B
    val btnA = Mux(configReg.buttonLayoutBY,
      b.b || (b.a && turboPulse),
      b.a || (b.x && turboPulse))
    val btnB = Mux(configReg.buttonLayoutBY,
      b.y || (b.x && turboPulse),
      b.b || (b.y && turboPulse))

    // MiSTer NES joypad bit order: {Right, Left, Down, Up, Start, Select, B, A}
    Mux(slot.active, Cat(right, left, down, up, b.start, b.select, btnB, btnA), 0.U(8.W))
  }

  val pads = io.input.slots.map(s => RegNext(mapPad(s), 0.U(8.W)))
  core.joy_a := pads(0)
  core.joy_b := pads(1)
  core.joy_c := pads(2)
  core.joy_d := pads(3)
  core.fds_btn := RegNext(io.input.slots(0).buttons.l1 || io.input.slots(1).buttons.l1, false.B)
  core.mic_btn := RegNext(io.input.slots(0).buttons.r1 || io.input.slots(1).buttons.r1, false.B)

  //////////////////////////////////
  // Video output (256 x 240 -> VideoV0 framebuffer)
  //////////////////////////////////
  // video.sv emits 256 active pixels per line x 240 lines per frame on ce_pix
  // while !hblank && !vblank. Track pixel/line bounds defensively so a reset
  // or mode switch can never overflow the framework framebuffer.
  val pixTick = core.ce_pix && tick
  val hblankPrev = RegEnable(core.hblank, true.B, pixTick)
  val vblankPrev = RegEnable(core.vblank, true.B, pixTick)
  val hblankRise = pixTick && core.hblank && !hblankPrev
  val lineX = RegInit(0.U(9.W))
  val lineY = RegInit(0.U(9.W))

  when (core.vblank) {
    lineX := 0.U
    lineY := 0.U
  } .elsewhen (hblankRise) {
    lineX := 0.U
    when (lineY < NesVideoFilter.Height.U) {
      lineY := lineY + 1.U
    }
  } .elsewhen (pixTick && !core.hblank) {
    when (lineX < NesVideoFilter.Width.U) {
      lineX := lineX + 1.U
    }
  }

  val shift = 8 - FramebufferColorDepth
  io.video.dataEnable := pixTick && !core.hblank && !core.vblank &&
    lineX < NesVideoFilter.Width.U && lineY < NesVideoFilter.Height.U
  io.video.data.r := core.video_r(7, shift)
  io.video.data.g := core.video_g(7, shift)
  io.video.data.b := core.video_b(7, shift)
  io.video.hblank := core.hblank
  io.video.vblank := core.vblank

  //////////////////////////////////
  // Audio (unsigned 16-bit mono -> signed 16-bit -> AudioFilterChain 48 kHz)
  //////////////////////////////////
  val audioSigned = Mux(hostReset || core.corepaused || !regCoreFocus,
    0.S(16.W),
    (core.audio_sample ^ 0x8000.U(16.W)).asSInt)
  val audioFilter = Module(new AudioFilterChain(
    inputRateHz = ClockSystemHz,
    cicR = (ClockSystemHz.toFloat / (4 * 48000)).ceil.toInt,
    dataBits = 16,
    outputRateHz = 48000,
  ))
  audioFilter.io.in(0) := audioSigned
  audioFilter.io.in(1) := audioSigned
  io.audio.left := audioFilter.io.out(0)
  io.audio.right := audioFilter.io.out(1)

  //////////////////////////////////
  // Host commands (0xF0xx_xxxx)
  //////////////////////////////////
  val commandHostState = RegInit(CommandState.idle)
  val regCommandHost = Reg(Vec(4, UInt(32.W)))
  commandInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = regCommandHost.zipWithIndex.map { case (reg, i) => (0x0000 + (4 * i) -> RegisterMap.Entry.rw(reg)) }
  )

  val pendingBiosCopy = RegInit(false.B)
  val pendingEepromLoad = RegInit(false.B)
  val setupReady = regCoreSetup && memoryReady && !romLoader.io.busy && !pendingBiosCopy && !pendingEepromLoad

  // Save file size (matching NES.sv lines 1264-1268):
  // - Vs. System / EEPROM: 2 KiB
  // - FDS: rounded up to 512 bytes (up to 256 KiB)
  // - NES 2.0 prg_nvram != 0: 64 << prg_nvram (capped at 256 KiB)
  // - iNES battery bit (mapper_flags[25]): 32 KiB
  val vsMode = effSysType === 3.U
  val prgNvram = mapperFlags(34, 31)
  val hasBattery = mapperFlags(25)
  val hasAnySave = hasBattery || vsMode || romLoader.io.isFds || core.bram_override_out
  val fdsSaveSize = ((romFileSize + 511.U) & (~511.U(25.W))).min(0x4_0000.U)
  val nvramSaveSize = (64.U(25.W) << prgNvram)(24, 0).min(0x4_0000.U)
  val saveFileSize = Mux(vsMode || core.bram_override_out, 2048.U(25.W),
    Mux(romLoader.io.isFds, fdsSaveSize,
      Mux(prgNvram =/= 0.U, nvramSaveSize,
        Mux(hasBattery, 32768.U(25.W), 0.U(25.W)))))

  // Hold FILE_READ_START while a save state is finishing (same pattern as HandheldPsx).
  val readWait = RegInit(0.U(26.W))
  val holdRead = regCommandHost(0)(15, 0) === HostV0.CommandFileReadStart.U &&
    (stateRequest =/= 0.U || stateActive) && !readWait.andR
  when (!io.host.commandHost.request) {
    readWait := 0.U
  } .elsewhen (commandHostState === CommandState.idle && holdRead) {
    readWait := readWait + 1.U
  }

  // Deferred post-ROM setup steps (run once loadRom completes, before setupReady rises):
  // - copyFdsBios if fds_bios.bin arrived after/during an FDS ROM load
  // - loadEeprom if a Mapper 16/159 save was loaded
  val waitingEepromSave = RegInit(false.B)
  when (!romLoader.io.busy && !waitingEepromSave) {
    when (pendingBiosCopy) {
      pendingBiosCopy := false.B
      when (romLoader.io.isFds) {
        romLoader.io.start := true.B
        romLoader.io.op := NesRomLoader.Op.copyFdsBios
      }
    } .elsewhen (pendingEepromLoad) {
      pendingEepromLoad := false.B
      when (core.bram_override_out) {
        romLoader.io.start := true.B
        romLoader.io.op := NesRomLoader.Op.loadEeprom
      }
    }
  }

  val pendingSaveReadSize = RegInit(0.U(32.W))

  io.host.commandHost.busy := commandHostState === CommandState.busy ||
    (io.host.commandHost.request && commandHostState === CommandState.idle && holdRead)
  io.host.commandHost.done := commandHostState === CommandState.done
  io.host.commandHost.error := commandHostState === CommandState.error

  when (io.host.commandHost.request) {
    when (commandHostState === CommandState.idle && !holdRead) {
      val command = regCommandHost(0)(15, 0)
      val fileId = regCommandHost(1)(15, 0)
      val fileSize = regCommandHost(2)
      for (reg <- regCommandHost) {
        reg := 0.U
      }
      commandHostState := CommandState.done

      when (command === HostV0.CommandGetStatus.U) {
        when (setupReady) {
          regCommandHost(0) := Mux(regCoreReset, HostV0.StatusCoreHalt.U, HostV0.StatusCoreRun.U)
        } .otherwise {
          regCommandHost(0) := HostV0.StatusSetup.U
        }
      } .elsewhen (command === HostV0.CommandSetupComplete.U) {
        // Return immediately (NOTIFY_TIMEOUT = 10 ms); firmware polls GET_STATUS
        // for STATUS_CORE_HALT (SETUP_TIMEOUT = 100 ms) while any remaining
        // ROM/BIOS/EEPROM loader work finishes.
        when (romLoader.io.error) {
          commandHostState := CommandState.error
        } .otherwise {
          regCoreSetup := true.B
          when (saveLoaded) {
            pendingEepromLoad := true.B
          }
        }
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      } .elsewhen (command === HostV0.CommandFileWriteStart.U) {
        // FILE_WRITE_START has SETUP_TIMEOUT (100 ms). Wait for SDRAM init, and
        // before writing Save (0x3C0000) wait for any in-progress ROM/FDS load so
        // an FDS disk image finishes writing 0x3C0000 before .sav overlays it.
        when (!memoryReady || (fileId === FileId.Save.U && romLoader.io.busy)) {
          commandHostState := CommandState.busy
        }
        when (fileId === FileId.Rom.U) {
          saveDirty := false.B
          saveLoaded := false.B
          pendingBiosCopy := false.B
          pendingEepromLoad := false.B
        }
        for (slot <- 0 until SaveState.Slots) {
          when (fileId === (FileId.State1 + slot).U) {
            stateHeld(slot) := false.B
          }
        }
      } .elsewhen (command === HostV0.CommandFileWriteEnd.U) {
        // Return immediately (NOTIFY_TIMEOUT = 10 ms); ROM/BIOS streaming runs
        // asynchronously in the background while firmware verifies/loads other files.
        val loaded = fileSize =/= 0.U
        when (fileId === FileId.Rom.U) {
          romFileSize := fileSize(24, 0)
          romLoader.io.start := true.B
          romLoader.io.op := NesRomLoader.Op.loadRom
          romLoader.io.romSize := fileSize(24, 0)
        } .elsewhen (fileId === FileId.FdsBios.U) {
          biosLoaded := loaded
          when (loaded) {
            pendingBiosCopy := true.B
          }
        } .elsewhen (fileId === FileId.Save.U) {
          saveLoaded := loaded
          saveDirty := false.B
        }
        for (slot <- 0 until SaveState.Slots) {
          when (fileId === (FileId.State1 + slot).U) {
            stateHeld(slot) := fileSize === SaveState.FileSize.U
            stateSaved(slot) := false.B
          }
        }
      } .elsewhen (command === HostV0.CommandFileReadStart.U) {
        when (fileId === FileId.Save.U) {
          val shouldSave = hasAnySave && (saveDirty || !regSkipUnchanged)
          when (shouldSave && saveFileSize =/= 0.U) {
            saveDirty := false.B
            when (core.bram_override_out) {
              romLoader.io.start := true.B
              romLoader.io.op := NesRomLoader.Op.saveEeprom
              pendingSaveReadSize := saveFileSize
              waitingEepromSave := true.B
              commandHostState := CommandState.busy
            } .otherwise {
              regCommandHost(0) := saveFileSize
            }
          }
        }
        for (slot <- 0 until SaveState.Slots) {
          when (fileId === (FileId.State1 + slot).U && (stateSaved(slot) || !regSkipUnchanged && stateHeld(slot))) {
            regCommandHost(0) := SaveState.FileSize.U
            stateSaved(slot) := false.B
          }
        }
      } .elsewhen (command === HostV0.CommandFileReadEnd.U) {
        // Nothing to do.
      } .otherwise {
        commandHostState := CommandState.error
      }
    } .elsewhen (commandHostState === CommandState.busy) {
      when (memoryReady && !romLoader.io.busy) {
        when (waitingEepromSave) {
          regCommandHost(0) := pendingSaveReadSize
          waitingEepromSave := false.B
        }
        commandHostState := CommandState.done
      }
    }
  } .otherwise {
    commandHostState := CommandState.idle
  }
  io.host.commandCore.request := false.B

  //////////////////////////////////
  // Video filter (scaling + CRT effect in display clock domain)
  //////////////////////////////////
  val filterSettings = Wire(new Bundle {
    val crt = new Crt.Settings
    val video = new NesVideoFilter.Settings
  })
  filterSettings.crt := crtReg
  filterSettings.video := videoReg
  withClockAndReset(io.videoFilter.clock, io.videoFilter.reset) {
    val filter = Module(new NesVideoFilter(colorDepth = FramebufferColorDepth))
    val settings = XpmCdcHandshake.continuous(clk1x, filterSettings)
    filter.io.crt := settings.crt
    filter.io.settings := settings.video
    filter.io.x := io.videoFilter.x
    filter.io.y := io.videoFilter.y
    filter.io.screenWidth := io.videoFilter.screenWidth
    filter.io.screenHeight := io.videoFilter.screenHeight
    filter.io.columnMajor := io.videoFilter.columnMajor
    filter.io.hdmi := io.videoFilter.hdmi
    io.videoFilter.frameX := filter.io.frameX
    io.videoFilter.frameY := filter.io.frameY
    filter.io.dataIn := io.videoFilter.dataIn
    io.videoFilter.dataOut := filter.io.dataOut
  }
}
