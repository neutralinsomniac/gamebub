package psx

import chisel3._

/**
 * The vendored MiSTer PSX core behind the Game Bub wrapper
 * (fpga/verilog/psx/gamebub/psx_gamebub.vhd), which fixes the options the
 * port does not offer and leaves these ports. Port names match the wrapper
 * exactly; the comments there and in the upstream `psx_mister.vhd` describe
 * them.
 *
 * Clocks: `clk1x` 33.8688 MHz (the console's CPU clock), `clk2x` and `clk3x`
 * exactly twice and three times it, from the same MMCM (the core moves data
 * between them without synchronizers). The main RAM interface (`ram_*`) and
 * the CD, memory card and pad ports are in the `clk1x` domain, except the
 * instruction cache fill (`cache_*`) and the DMA write FIFO
 * (`ram_dmafifo_*`), which are in the `clk3x` domain, the SDRAM controller's;
 * the DDR3 port (`DDRAM_*`) and the SPU RAM port (`spuram_*`) are in the
 * `clk2x` domain.
 */
class PsxCore extends ExtModule {
  override def desiredName: String = "psx_gamebub"

  val io = FlatIO(new Bundle {
    val clk1x = Input(Clock())
    val clk2x = Input(Clock())
    val clk3x = Input(Clock())
    val reset = Input(Bool())
    val pause = Input(Bool())
    val isPaused = Output(Bool())

    // Settings
    val REPRODUCIBLEGPUTIMING = Input(Bool())
    val LIMITREADSPEED = Input(Bool())
    val fixedVBlank = Input(Bool())
    val SPUIRQTrigger = Input(Bool())
    val REPRODUCIBLESPUDMA = Input(Bool())
    val biosregion = Input(UInt(2.W))

    // Main RAM / BIOS
    val ram_refresh = Output(Bool())
    val ram_dataWrite = Output(UInt(32.W))
    val ram_dataRead32 = Input(UInt(32.W))
    val ram_Adr = Output(UInt(25.W))
    val ram_cntDMA = Output(UInt(2.W))
    val ram_be = Output(UInt(4.W))
    val ram_rnw = Output(Bool())
    val ram_ena = Output(Bool())
    val ram_dma = Output(Bool())
    val ram_cache = Output(Bool())
    val ram_done = Input(Bool())
    val ram_dmafifo_adr = Output(UInt(23.W))
    val ram_dmafifo_data = Output(UInt(32.W))
    val ram_dmafifo_empty = Output(Bool())
    val ram_dmafifo_read = Input(Bool())
    val cache_wr = Input(UInt(4.W))
    val cache_data = Input(UInt(32.W))
    val cache_addr = Input(UInt(8.W))
    val dma_wr = Input(Bool())
    val dma_reqprocessed = Input(Bool())
    val dma_data = Input(UInt(32.W))

    // VRAM, memory cards, save states (the MiSTer's DDR3 port)
    val DDRAM_BUSY = Input(Bool())
    val DDRAM_BURSTCNT = Output(UInt(8.W))
    val DDRAM_ADDR = Output(UInt(29.W))
    val DDRAM_DOUT = Input(UInt(64.W))
    val DDRAM_DOUT_READY = Input(Bool())
    val DDRAM_RD = Output(Bool())
    val DDRAM_DIN = Output(UInt(64.W))
    val DDRAM_BE = Output(UInt(8.W))
    val DDRAM_WE = Output(Bool())

    // CD
    val region = Input(UInt(2.W))
    val region_out = Output(UInt(2.W))
    val hasCD = Input(Bool())
    val LIDopen = Input(Bool())
    val trackinfo_data = Input(UInt(32.W))
    val trackinfo_addr = Input(UInt(9.W))
    val trackinfo_write = Input(Bool())
    val resetFromCD = Output(Bool())
    val cd_hps_req = Output(Bool())
    val cd_hps_lba = Output(UInt(32.W))
    val cd_hps_ack = Input(Bool())
    val cd_hps_write = Input(Bool())
    val cd_hps_data = Input(UInt(16.W))

    // SPU RAM
    val spuram_dataWrite = Output(UInt(32.W))
    val spuram_Adr = Output(UInt(19.W))
    val spuram_be = Output(UInt(4.W))
    val spuram_rnw = Output(Bool())
    val spuram_ena = Output(Bool())
    val spuram_dataRead = Input(UInt(32.W))
    val spuram_done = Input(Bool())

    // Memory cards
    val memcard_changed = Output(Bool())
    val saving_memcard = Output(Bool())
    val memcard1_load = Input(Bool())
    val memcard2_load = Input(Bool())
    val memcard_save = Input(Bool())
    val memcard1_mounted = Input(Bool())
    val memcard1_available = Input(Bool())
    val memcard1_rd = Output(Bool())
    val memcard1_wr = Output(Bool())
    val memcard1_lba = Output(UInt(7.W))
    val memcard1_ack = Input(Bool())
    val memcard1_write = Input(Bool())
    val memcard1_addr = Input(UInt(9.W))
    val memcard1_dataIn = Input(UInt(16.W))
    val memcard1_dataOut = Output(UInt(16.W))
    val memcard2_mounted = Input(Bool())
    val memcard2_available = Input(Bool())
    val memcard2_rd = Output(Bool())
    val memcard2_wr = Output(Bool())
    val memcard2_lba = Output(UInt(7.W))
    val memcard2_ack = Input(Bool())
    val memcard2_write = Input(Bool())
    val memcard2_addr = Input(UInt(9.W))
    val memcard2_dataIn = Input(UInt(16.W))
    val memcard2_dataOut = Output(UInt(16.W))

    // Video
    val isPal = Input(Bool())
    val pal60 = Input(Bool())
    val hsync = Output(Bool())
    val vsync = Output(Bool())
    val hblank = Output(Bool())
    val vblank = Output(Bool())
    val DisplayWidth = Output(UInt(11.W))
    val DisplayHeight = Output(UInt(10.W))
    val DisplayOffsetX = Output(UInt(10.W))
    val DisplayOffsetY = Output(UInt(9.W))
    val video_interlace = Output(Bool())
    val video_isPal = Output(Bool())
    /** GPUSTAT: display disabled / 24-bit color (a Game Bub change to gpu.vhd). */
    val displayDisable = Output(Bool())
    val colorDepth24 = Output(Bool())
    /** GP1(07h): the vertical display range (first line 9-0, end 19-10; a Game Bub change to gpu.vhd). */
    val vDisplayRange = Output(UInt(20.W))
    /** GP1(06h): the horizontal display range (start 11-0, end 23-12). */
    val hDisplayRange = Output(UInt(24.W))
    val video_hResMode = Output(UInt(3.W))

    // Controllers on ports 1 (pad_*) and 2 (pad2_*, if enabled), both of the
    // same type (see [[PsxCore.Button]]; sticks two's complement); padMode:
    // the DualShocks' analog mode (bit 0 port 1, bit 1 port 2), toggled by a
    // one-cycle pulse on ToggleDS (their Analog buttons)
    val PadPortAnalog = Input(Bool())
    val PadPortDS = Input(Bool())
    val pad_buttons = Input(UInt(16.W))
    val pad_lx = Input(UInt(8.W))
    val pad_ly = Input(UInt(8.W))
    val pad_rx = Input(UInt(8.W))
    val pad_ry = Input(UInt(8.W))
    val pad_rumble = Output(UInt(16.W))
    val PadPortEnable2 = Input(Bool())
    val pad2_buttons = Input(UInt(16.W))
    val pad2_lx = Input(UInt(8.W))
    val pad2_ly = Input(UInt(8.W))
    val pad2_rx = Input(UInt(8.W))
    val pad2_ry = Input(UInt(8.W))
    val padMode = Output(UInt(2.W))
    val ToggleDS = Input(UInt(2.W))

    // Sound
    val sound_out_left = Output(UInt(16.W))
    val sound_out_right = Output(UInt(16.W))
    // Pulses with each new sample (a Game Bub addition).
    val sound_out_valid = Output(Bool())

    // Save states: a rising edge of save_state / load_state saves / loads
    // slot savestate_number (which must hold until savestate_busy falls).
    // validSStates: the slots holding a state (by their header, read after
    // each reset, and those saved since, or marked by a validSStates_set
    // pulse, a Game Bub addition). savestate_busy (a Game Bub addition to
    // psx_top): a state is being saved or loaded, or the memory cleared
    // after a reset.
    val save_state = Input(Bool())
    val load_state = Input(Bool())
    val savestate_number = Input(UInt(2.W))
    val state_loaded = Output(Bool())
    val validSStates = Output(UInt(4.W))
    val validSStates_set = Input(UInt(4.W))
    val savestate_busy = Output(Bool())

    // Debug (a Game Bub addition to psx_top): every error code seen since
    // reset (bit n: code n, as MiSTer shows them), the CPU's fetch address
    val debug_errors = Output(UInt(16.W))
    val debug_pc = Output(UInt(32.W))
    /** MDECSTAT; the DMA controller's state; CPU / DMA flags (bit layouts in psx_top.vhd / dma.vhd). */
    val debug_mdec = Output(UInt(32.W))
    val debug_dma = Output(UInt(32.W))
    val debug_flags = Output(UInt(16.W))
  })
}

object PsxCore {
  /** Bit positions in `pad_buttons` (active high). */
  object Button {
    val Cross = 0
    val Circle = 1
    val Square = 2
    val Triangle = 3
    val L1 = 4
    val R1 = 5
    val L2 = 6
    val R2 = 7
    val Select = 8
    val Start = 9
    val Up = 10
    val Down = 11
    val Left = 12
    val Right = 13
    val L3 = 14
    val R3 = 15
  }
}
