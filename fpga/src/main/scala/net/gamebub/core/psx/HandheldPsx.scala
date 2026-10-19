package net.gamebub.core.psx

import chisel3._
import chisel3.util._
import lib.mem.{MemoryInterface, MemoryMap, RegisterMap}
import lib.mem.sram.AsyncSramController
import net.gamebub.framework.Core
import net.gamebub.framework.interface._
import psx.{PsxCore, PsxDisc, PsxMemory, PsxScanout, SdramEngine}
import xilinx.{MMCM, ODDRWrapper}

object HandheldPsx {
  /**
   * Clocks: one MMCM, 50 MHz / 2 * 40.625 = 1015.625 MHz VCO, divided by
   * 30 / 15 / 10 for the core's clk1x / clk2x / clk3x (33.854 / 67.708 /
   * 101.5625 MHz; the console's 33.8688 MHz is 432 ppm faster, which no
   * single MMCM setting gets closer to with exact 1:2:3 ratios). The three
   * are phase-aligned, as the core requires (it moves signals between them
   * without synchronizers). clk3x is also the SDRAM clock; the SDRAM chips
   * get it inverted (through an ODDR) and a little later, from its own MMCM
   * output (`SdramClockPhaseNs`): commands and write data, launched on
   * clk3x's rising edge, are sampled a little over half a cycle later, and
   * the read data's window straddles the clk3x edge that captures it (the
   * budget is in core_psx.xdc).
   */
  val ClockInHz = 50_000_000
  val MmcmDivide = 2
  val MmcmMultiply = 40.625
  val MmcmVcoHz = ClockInHz.toDouble / MmcmDivide * MmcmMultiply
  val Clk3xDivider = 10
  val Clk2xDivider = 15
  val Clk1xDivider = 30
  val Clock1xHz = MmcmVcoHz / Clk1xDivider
  val Clock3xHz = MmcmVcoHz / Clk3xDivider
  /**
   * How much later than clk3x the SDRAM chips' clock is (before the
   * inversion). In steps of 1/8 VCO period (4.5 degrees of clk3x); Vivado
   * corrects other values with a critical warning.
   */
  val SdramClockPhaseNs = 1.6
  val SdramClockPhaseDegrees = {
    val step = 360.0 / (8 * Clk3xDivider)
    (360.0 * SdramClockPhaseNs * Clock3xHz / 1e9 / step).round * step
  }
  /** Host SPI clock, above the framework's 160 MHz minimum: 169.27 MHz. */
  val SpiDivider = 6
  require(MmcmVcoHz / SpiDivider > 160e6)

  /**
   * The core's NTSC frame in its synchronous video timing (the only one
   * used here): 263 lines of 2172 clk1x cycles. PAL frames are longer; the
   * display follows with its vertical front porch.
   */
  val FramePeriod = 263.0 * 2172 / Clock1xHz


  object CommandState extends ChiselEnum {
    val idle, busy, error, done = Value
  }

  /** Host memory map (32-bit words). */
  object HostMap {
    /**
     * SDRAM: chip 0 at 0x3000_0000 (main RAM at 0; the BIOS slots, 512 KiB
     * each, US / Japan / Europe, from 8 MiB), chip 1 at 0x3200_0000.
     */
    val Sdram = 0x3000_0000L
    val Bios = Sdram + 0x80_0000
    /** The disc's table of contents (see [[psx.PsxDisc]]). */
    val DiscToc = 0x6000_0000L
    /** The disc sector buffer. */
    val DiscSector = 0x7000_0000L
  }

  /** File IDs (files.json): the disc, and the BIOS for each region (slots 0-2, the core's region codes). */
  object FileId {
    val Disc = 0
    val BiosUs = 1
    val BiosJapan = 2
    val BiosEurope = 3
  }
}

/**
 * The MiSTer PSX core on the Game Bub (rev 4: needs both SDRAM chips).
 *
 * Memory: main RAM, BIOS, VRAM, memory cards and save states in the SDRAM
 * ([[psx.PsxMemory]]), SPU RAM in the SRAM. Video: the core's own video
 * output is not used (it would read VRAM a second time); the display is
 * scanned out of VRAM here ([[psx.PsxScanout]]).
 */
class HandheldPsx extends Module with Core {
  import HandheldPsx._

  val displayDivider = (MmcmVcoHz / ClocksV0.getClockDisplayHz(FramePeriod)._1).floor.toInt

  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      clockSystemHz = Clock1xHz.toInt,
      clockDisplayHz = (MmcmVcoHz / displayDivider).toInt,
      clockSpiHz = (MmcmVcoHz / SpiDivider).toInt,
    )
    val video = new VideoPullV0(framePeriod = FramePeriod, latency = PsxScanout.Latency)
    val audio = new AudioV0()
    val host = new HostV0()
    val input = new InputV0()
    val vibrate = new VibrateV0()
    val sram = new SramV0()
    val sdram = new SdramV0(chips = 2, dataDirPerBit = true)
  })

  //////////////////////////////////
  // Clocks
  //////////////////////////////////
  val mmcm = Module(new MMCM(
    clockInHz = ClockInHz,
    divide = MmcmDivide,
    multiply = MmcmMultiply,
    clockOutConfig = Seq(
      MMCM.ClockOut(Clk3xDivider),
      MMCM.ClockOut(Clk2xDivider),
      MMCM.ClockOut(Clk1xDivider),
      MMCM.ClockOut(displayDivider),
      MMCM.ClockOut(SpiDivider),
      MMCM.ClockOut(Clk3xDivider, phase = SdramClockPhaseDegrees), // SDRAM chips
    )
  ))
  mmcm.io.clockIn := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B
  val clk3x = mmcm.io.clockOuts(0)
  val clk2x = mmcm.io.clockOuts(1)
  io.clocks.clockOutSystem := mmcm.io.clockOuts(2)
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(3)
  io.clocks.clockOutSpi := mmcm.io.clockOuts(4)
  io.clocks.locked := mmcm.io.locked
  // This module's own clock is the system clock, i.e. clk1x.
  val clk1x = clock
  val reset2x = withClock(clk2x) { RegNext(RegNext(reset.asBool, true.B), true.B) }
  val reset3x = withClock(clk3x) { RegNext(RegNext(reset.asBool, true.B), true.B) }

  //////////////////////////////////
  // Framework state (driven by the command interface below)
  //////////////////////////////////
  /** The host has finished loading files (SetupComplete). */
  val regCoreSetup = RegInit(false.B)
  /** The core is halted, i.e. held in reset: before CoreRun and after CoreHalt. */
  val regCoreReset = RegInit(true.B)
  /** The core has the user's focus; it is paused otherwise (the pause menu takes it). */
  val regCoreFocus = RegInit(false.B)
  /** A one-cycle reset requested through register 0x2000. */
  val regCoreResetOnce = RegInit(false.B)
  regCoreResetOnce := false.B

  /** Files loaded: the disc's table of contents, the BIOS slots. */
  val discLoaded = RegInit(false.B)
  val biosLoaded = RegInit(VecInit(Seq.fill(3)(false.B)))
  val coreRunPulse = WireDefault(false.B)
  val disc = Module(new PsxDisc(sectorHostAddress = HostMap.DiscSector))

  //////////////////////////////////
  // Host registers
  //////////////////////////////////
  // N.B. the last field is bit 0 of the register.
  val configReg = RegInit(0x103.U(9.W).asTypeOf(new Bundle {
    /** Bit 8: PAL video timing if the region is Europe (bit 2 is ignored). */
    val videoAuto = Bool()
    /** Bit 7: fixed vertical blanking (upstream option). */
    val fixedVBlank = Bool()
    /** Bit 6: the controller is a DualShock (analog sticks and rumble). */
    val dualShock = Bool()
    /** Bit 5: the controller is an analog controller. */
    val analog = Bool()
    /** Bit 4: skip the BIOS intro when booting a disc. */
    val fastboot = Bool()
    /** Bit 3: no dithering. */
    val ditherOff = Bool()
    /** Bit 2: PAL video timing. */
    val pal = Bool()
    /** Bits 1-0: console region (0 US, 1 Japan, 2 Europe, 3 the disc's). */
    val region = UInt(2.W)
  }))
  val statusWire = Wire(UInt(8.W))
  val discTocInterface = Wire(new MemoryInterface(addressWidth = 11, dataWidth = 32))
  val discSectorInterface = Wire(new MemoryInterface(addressWidth = 12, dataWidth = 32))
  // Statistics (the firmware logs them when the game is paused, clearing
  // each by writing it; 0x1000 / 0x1004 are the stall / run cycles every
  // core has, here the clk1x cycles with a main RAM access outstanding and
  // those with the core running).
  val statMemoryWait = RegInit(0.U(32.W))
  val statRun = RegInit(0.U(32.W))
  val statRowHits = Wire(UInt(32.W))
  val statRowConflicts = Wire(UInt(32.W))
  /** Clear pulses, by counter (0x1000 + 4 * index). */
  val statClear = WireDefault(VecInit(Seq.fill(7)(false.B)))
  def statEntry(index: Int, value: UInt) = (0x1000 + 4 * index) -> RegisterMap.Entry(32,
    RegisterMap.ReadFn(_ => value),
    RegisterMap.WriteFn((write, _) => { statClear(index) := write }))

  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val sdramHost = Wire(new MemoryInterface(addressWidth = 26, dataWidth = 32))
  val commandInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val memoryMap = MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      0x0.U(4.W) -> registerInterface,
      0x3.U(4.W) -> sdramHost,
      0x6.U(4.W) -> discTocInterface,
      0x7.U(4.W) -> discSectorInterface,
      0xF0.U(8.W) -> commandInterface,
    ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U

  registerInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0000 -> RegisterMap.Entry.rw(configReg),
      0x0100 -> RegisterMap.Entry.r(statusWire),
      statEntry(0, statMemoryWait),
      statEntry(1, statRun),
      statEntry(2, statRowHits),
      statEntry(3, statRowConflicts),
      statEntry(4, disc.io.sectorReads),
      statEntry(5, disc.io.readErrors),
      statEntry(6, disc.io.worstWait),
      // The disc's region (bit 2: recognized; bits 1-0: 0 US, 1 Japan, 2 Europe)
      0x1100 -> RegisterMap.Entry.r(Cat(disc.io.region.valid, disc.io.region.bits)),
      0x2000 -> RegisterMap.Entry(32,
        RegisterMap.ReadFn(_ => 0.U),
        RegisterMap.WriteFn((write, data) => { when (write && data(0)) { regCoreResetOnce := true.B } })),
    ),
  )

  //////////////////////////////////
  // Memory
  //////////////////////////////////
  val memory = withClockAndReset(clk3x, reset3x) {
    Module(new PsxMemory(SdramEngine.Config(clockHz = Clock3xHz, sourceBits = PsxMemory.SourceBits)))
  }
  memory.io.clk1x := clk1x
  memory.io.reset1x := reset.asBool
  memory.io.clk2x := clk2x
  memory.io.reset2x := reset2x
  memory.io.host <> sdramHost
  val memoryReady = RegNext(RegNext(memory.io.ready, false.B), false.B)

  // SDRAM pins. The chips' clock is clk3x inverted and delayed (see the
  // companion object; core_psx.xdc names this ODDR).
  val sdramClockOut = withClockAndReset(mmcm.io.clockOuts(5), false.B) { Module(new ODDRWrapper(initial = false)) }
  sdramClockOut.io.D1 := false.B
  sdramClockOut.io.D2 := true.B
  io.sdram.clock := sdramClockOut.io.Q.asClock
  io.sdram.cke := true.B
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

  // Row statistics: clk3x pulses, counted there and read here.
  withClockAndReset(clk3x, reset3x) {
    def counter(event: Bool, clear: Bool): UInt = {
      val count = RegInit(0.U(32.W))
      when (RegNext(clear, false.B)) {
        count := 0.U
      } .elsewhen (event) {
        count := count + 1.U
      }
      count
    }
    statRowHits := RegNext(counter(memory.io.rowHit, statClear(2)), 0.U)
    statRowConflicts := RegNext(counter(memory.io.rowConflict, statClear(3)), 0.U)
  }

  //////////////////////////////////
  // Core
  //////////////////////////////////
  val core = Module(new PsxCore)
  val hostReset = reset.asBool || regCoreReset || regCoreResetOnce || !memoryReady || disc.io.holdCore
  core.io.clk1x := clk1x
  core.io.clk2x := clk2x
  core.io.clk3x := clk3x
  val coreReset = RegNext(hostReset, true.B)
  core.io.reset := coreReset
  core.io.pause := !regCoreFocus

  core.io.fastboot := configReg.fastboot
  core.io.ditherOff := configReg.ditherOff
  core.io.REPRODUCIBLEGPUTIMING := false.B
  core.io.LIMITREADSPEED := false.B
  core.io.fixedVBlank := configReg.fixedVBlank
  core.io.SPUIRQTrigger := false.B
  core.io.REPRODUCIBLESPUDMA := false.B
  // Region: the setting, or the disc's (US if unknown); PAL with Europe
  // unless set. The BIOS is the region's if it was loaded, else the first
  // one that was (a BIOS from another region shows its own menu, but does
  // not boot the disc).
  val region = Mux(configReg.region === 3.U,
    Mux(disc.io.region.valid, disc.io.region.bits, PsxDisc.Region.Us.U), configReg.region)
  val regionReg = RegNext(region, 0.U)
  val biosSlot = Mux(biosLoaded(regionReg), regionReg, PriorityEncoder(biosLoaded.asUInt))
  core.io.biosregion := RegNext(biosSlot, 0.U)

  // Main RAM / BIOS
  memory.io.ram.ena := core.io.ram_ena
  memory.io.ram.adr := core.io.ram_Adr
  memory.io.ram.rnw := core.io.ram_rnw
  memory.io.ram.be := core.io.ram_be
  memory.io.ram.dataWrite := core.io.ram_dataWrite
  memory.io.ram.dma := core.io.ram_dma
  memory.io.ram.cntDma := core.io.ram_cntDMA
  memory.io.ram.cache := core.io.ram_cache
  core.io.ram_done := memory.io.ram.done
  core.io.ram_dataRead32 := memory.io.ram.dataRead
  core.io.dma_reqprocessed := memory.io.ram.dmaReqProcessed
  core.io.dma_wr := memory.io.ram.dmaWr
  core.io.dma_data := memory.io.ram.dmaData
  core.io.cache_wr := memory.io.ram.cacheWr
  core.io.cache_data := memory.io.ram.cacheData
  core.io.cache_addr := memory.io.ram.cacheAddr
  memory.io.ram.dmafifoAdr := core.io.ram_dmafifo_adr
  memory.io.ram.dmafifoData := core.io.ram_dmafifo_data
  memory.io.ram.dmafifoEmpty := core.io.ram_dmafifo_empty
  core.io.ram_dmafifo_read := memory.io.ram.dmafifoRead

  // VRAM etc.
  memory.io.ddram.burstCnt := core.io.DDRAM_BURSTCNT
  memory.io.ddram.addr := core.io.DDRAM_ADDR
  memory.io.ddram.rd := core.io.DDRAM_RD
  memory.io.ddram.din := core.io.DDRAM_DIN
  memory.io.ddram.be := core.io.DDRAM_BE
  memory.io.ddram.we := core.io.DDRAM_WE
  core.io.DDRAM_BUSY := memory.io.ddram.busy
  core.io.DDRAM_DOUT := memory.io.ddram.dout
  core.io.DDRAM_DOUT_READY := memory.io.ddram.doutReady

  // CD: no disc yet.
  core.io.region := regionReg
  core.io.hasCD := discLoaded
  core.io.LIDopen := false.B
  disc.io.toc <> discTocInterface
  disc.io.sector <> discSectorInterface
  io.host.commandCore <> disc.io.commandCore
  disc.io.discLoaded := discLoaded
  disc.io.start := coreRunPulse
  disc.io.coreReset := coreReset
  disc.io.hpsReq := core.io.cd_hps_req
  disc.io.hpsLba := core.io.cd_hps_lba
  core.io.cd_hps_ack := disc.io.hpsAck
  core.io.cd_hps_write := disc.io.hpsWrite
  core.io.cd_hps_data := disc.io.hpsData
  core.io.trackinfo_data := disc.io.trackinfoData
  core.io.trackinfo_addr := disc.io.trackinfoAddr
  core.io.trackinfo_write := disc.io.trackinfoWrite
  disc.io.clearSectorReads := statClear(4)
  disc.io.clearReadErrors := statClear(5)
  disc.io.clearWorstWait := statClear(6)

  // Main RAM waits (from the request to its completion) and run cycles.
  locally {
    val waiting = RegInit(false.B)
    when (core.io.ram_ena) {
      waiting := true.B
    } .elsewhen (core.io.ram_done || core.io.dma_wr) {
      waiting := false.B
    }
    when (statClear(0)) {
      statMemoryWait := 0.U
    } .elsewhen (waiting) {
      statMemoryWait := statMemoryWait + 1.U
    }
    when (statClear(1)) {
      statRun := 0.U
    } .elsewhen (!coreReset && !core.io.isPaused) {
      statRun := statRun + 1.U
    }
  }

  // Memory cards: none yet.
  core.io.memcard1_load := false.B
  core.io.memcard2_load := false.B
  core.io.memcard_save := false.B
  core.io.memcard1_mounted := false.B
  core.io.memcard1_available := false.B
  core.io.memcard1_ack := false.B
  core.io.memcard1_write := false.B
  core.io.memcard1_addr := 0.U
  core.io.memcard1_dataIn := 0.U
  core.io.memcard2_mounted := false.B
  core.io.memcard2_available := false.B
  core.io.memcard2_ack := false.B
  core.io.memcard2_write := false.B
  core.io.memcard2_addr := 0.U
  core.io.memcard2_dataIn := 0.U

  // Save states: not yet.
  core.io.save_state := false.B
  core.io.load_state := false.B
  core.io.savestate_number := 0.U

  //////////////////////////////////
  // SPU RAM (SRAM, 16-bit accesses)
  //////////////////////////////////
  val sramController = Module(new AsyncSramController(addressWidth = 18, dataWidth = 16, registeredOutputs = true))
  io.sram.ceN := false.B
  io.sram.weN := sramController.io.signals.weN
  io.sram.oeN := sramController.io.signals.oeN
  io.sram.writeMaskN := sramController.io.signals.writeMaskN
  io.sram.address := sramController.io.signals.address
  sramController.io.signals.dataIn := io.sram.dataIn
  io.sram.dataOut := sramController.io.signals.dataOut
  io.sram.dataDir := sramController.io.signals.dataDir
  locally {
    // The SPU's requests pulse for one cycle and wait for `done`; it only
    // ever uses the low halfword. The controller takes a request in the
    // cycle it pulses (the SPU's budget per sample is tight) and sees it
    // held from the registers until `done`.
    val pending = RegInit(false.B)
    val address = Reg(UInt(18.W))
    val write = Reg(Bool())
    val data = Reg(UInt(16.W))
    val mask = Reg(UInt(2.W))
    val ena = core.io.spuram_ena
    when (ena) {
      pending := true.B
      address := core.io.spuram_Adr(18, 1)
      write := !core.io.spuram_rnw
      data := core.io.spuram_dataWrite(15, 0)
      mask := core.io.spuram_be(1, 0)
    }
    val mem = sramController.io.mem
    mem.enable := ena || pending
    mem.address := Mux(ena, core.io.spuram_Adr(18, 1), address)
    mem.write := Mux(ena, !core.io.spuram_rnw, write)
    mem.dataWrite := Mux(ena, core.io.spuram_dataWrite(15, 0), data)
    mem.writeStrobe := Mux(ena, core.io.spuram_be(1, 0), mask)
    when (mem.done) {
      pending := false.B
    }
    core.io.spuram_done := mem.done
    core.io.spuram_dataRead := Cat(0.U(16.W), mem.dataRead)
  }

  //////////////////////////////////
  // Input
  //////////////////////////////////
  // Select is a modifier for L2 / R2: Select + L is L2, Select + R is R2;
  // Select alone is Select.
  locally {
    import PsxCore.Button
    val b = io.input.buttons
    val shift = b.select
    val buttons = Wire(Vec(16, Bool()))
    buttons := DontCare
    buttons(Button.Cross) := b.b
    buttons(Button.Circle) := b.a
    buttons(Button.Square) := b.y
    buttons(Button.Triangle) := b.x
    buttons(Button.L1) := b.l && !shift
    buttons(Button.R1) := b.r && !shift
    buttons(Button.L2) := b.l && shift
    buttons(Button.R2) := b.r && shift
    buttons(Button.Select) := shift && !b.l && !b.r
    buttons(Button.Start) := b.start
    buttons(Button.Up) := b.up
    buttons(Button.Down) := b.down
    buttons(Button.Left) := b.left
    buttons(Button.Right) := b.right
    buttons(Button.L3) := false.B
    buttons(Button.R3) := false.B
    core.io.pad_buttons := RegNext(buttons.asUInt, 0.U)
  }
  core.io.PadPortAnalog := configReg.analog
  core.io.PadPortDS := configReg.dualShock
  // Sticks centered (two's complement in the core).
  core.io.pad_lx := 0.U
  core.io.pad_ly := 0.U
  core.io.pad_rx := 0.U
  core.io.pad_ry := 0.U
  io.vibrate.mode := Mux(RegNext(core.io.pad_rumble =/= 0.U, false.B), VibrateV0.Mode.On, VibrateV0.Mode.Off)

  //////////////////////////////////
  // Audio
  //////////////////////////////////
  // 44.1 kHz samples, held between updates; the framework samples them at
  // the DAC's rate.
  io.audio.left := core.io.sound_out_left.asSInt
  io.audio.right := core.io.sound_out_right.asSInt

  //////////////////////////////////
  // Video
  //////////////////////////////////
  io.video.vblank := core.io.vblank
  core.io.isPal := RegNext(Mux(configReg.videoAuto, regionReg === PsxDisc.Region.Europe.U, configReg.pal), false.B)
  core.io.pal60 := false.B
  val scanout = withClockAndReset(io.video.clock, io.video.reset) { Module(new PsxScanout) }
  scanout.io.x := io.video.x
  scanout.io.y := io.video.y
  scanout.io.screenWidth := io.video.screenWidth
  scanout.io.columnMajor := io.video.columnMajor
  scanout.io.frameStart := io.video.frameStart
  io.video.data := scanout.io.data
  scanout.io.clk1x := clk1x
  scanout.io.params.offsetX := core.io.DisplayOffsetX
  scanout.io.params.offsetY := core.io.DisplayOffsetY
  scanout.io.params.areaWidth := core.io.DisplayWidth
  locally {
    // The TV picture: 240 (NTSC) / 288 (PAL) lines from line 16 / 19 of the
    // field (as the core counts for its DisplayOffsetY, which already skips
    // any display range above that), of which the vertical display range
    // shows VRAM. Twice the lines in 480-line modes, where the scanout
    // buffer takes 512 at most.
    val pal = core.io.video_isPal
    val doubled = core.io.DisplayHeight > 256.U
    val base = Mux(pal, 19.U(10.W), 16.U(10.W))
    val window = Mux(pal, 288.U(10.W), 240.U(10.W))
    val start = core.io.vDisplayRange(9, 0)
    val end = core.io.vDisplayRange(19, 10)
    val first = Mux(start > base, start, base)
    val last = Mux(end < base + window, end, base + window)
    val lines = Mux(last > first, last - first, 0.U)
    val rows = Mux(doubled, (lines << 1).min(512.U), lines)
    scanout.io.params.areaHeight := RegNext(Mux(doubled, window << 1, window))
    scanout.io.params.topRows := RegNext(Mux(doubled, (first - base) << 1, first - base))
    scanout.io.params.rows := RegNext(rows)
    scanout.io.params.disabled := RegNext(core.io.displayDisable || rows === 0.U)
  }
  scanout.io.params.depth24 := core.io.colorDepth24
  scanout.io.clk3x := clk3x
  scanout.io.reset3x := reset3x
  memory.io.display.request <> scanout.io.fetch
  scanout.io.word := memory.io.display.word

  //////////////////////////////////
  // Host commands
  //////////////////////////////////
  // The host writes a command and up to three arguments to the four words
  // and reads a result from word 0.
  val commandHostState = RegInit(CommandState.idle)
  val regCommandHost = Reg(Vec(4, UInt(32.W)))
  commandInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries =
      regCommandHost.zipWithIndex.map { case (reg, i) => (0x0000 + (4 * i) -> RegisterMap.Entry.rw(reg)) } ++
      // Core -> host request words (the disc's sector reads)
      disc.io.commandWords.zipWithIndex.map { case (word, i) => (0x1000 + (4 * i) -> RegisterMap.Entry.r(word)) }
  )
  val setupReady = regCoreSetup && memoryReady
  statusWire := Cat(0.U(5.W), regCoreReset, regCoreSetup, memoryReady)
  io.host.commandHost.busy := commandHostState === CommandState.busy
  io.host.commandHost.done := commandHostState === CommandState.done
  io.host.commandHost.error := commandHostState === CommandState.error
  when (io.host.commandHost.request) {
    when (commandHostState === CommandState.idle) {
      val command = regCommandHost(0)(15, 0)
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
        // No BIOS: nothing to run.
        when (biosLoaded.asUInt.orR) {
          regCoreSetup := true.B
        } .otherwise {
          commandHostState := CommandState.error
        }
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
        coreRunPulse := true.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      } .elsewhen (command === HostV0.CommandFileWriteStart.U) {
        // Files go straight to their memory windows; the SDRAM must be up.
        when (!memoryReady) {
          commandHostState := CommandState.busy
        }
      } .elsewhen (command === HostV0.CommandFileWriteEnd.U) {
        val fileId = regCommandHost(1)(15, 0)
        val loaded = regCommandHost(2) =/= 0.U
        when (fileId === FileId.Disc.U) {
          discLoaded := loaded
        }
        for (slot <- 0 until 3) {
          when (fileId === (FileId.BiosUs + slot).U) {
            biosLoaded(slot) := loaded
          }
        }
      } .elsewhen (command === HostV0.CommandFileReadStart.U) {
      } .elsewhen (command === HostV0.CommandFileReadEnd.U) {
      } .otherwise {
        commandHostState := CommandState.error
      }
    } .elsewhen (commandHostState === CommandState.busy) {
      when (memoryReady) {
        commandHostState := CommandState.done
      }
    }
  } .otherwise {
    commandHostState := CommandState.idle
  }
}
