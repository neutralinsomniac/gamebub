package snes

import chisel3._

/**
 * Which cartridge coprocessors to synthesize into the core. Every enabled
 * chip costs logic and block RAM on the XC7A100T; the firmware refuses ROMs
 * that need a disabled one (keep `Snes::check_supported` in sync).
 */
case class SnesCoreConfig(
  /** DSP-1/1B/2/3/4, ST010/ST011 (needs ROM images, see fpga/verilog/snes/tools/gen_roms.py) */
  dsp: Boolean = true,
  /** Capcom CX4 (Mega Man X2/X3) */
  cx4: Boolean = true,
  /** S-DD1 (Star Ocean, Street Fighter Alpha 2) */
  sdd1: Boolean = true,
  /** Super FX / GSU */
  gsu: Boolean = true,
  /** SA-1 */
  sa1: Boolean = true,
  /** SPC7110 */
  spc7110: Boolean = false,
  /** Satellaview BS-X */
  bsx: Boolean = false,
  /** Sufami Turbo */
  sufami: Boolean = false,
  /** MSU-1 (needs streaming storage; not supported on Game Bub) */
  msu: Boolean = false,
  /** Save states (needs DDR-backed storage; not supported on Game Bub) */
  savestates: Boolean = false,
)

/**
 * The vendored MiSTer SNES core (fpga/verilog/snes/rtl/main.v).
 *
 * Port names match `main.v` exactly. Everything is clocked by MCLK/ACLK
 * (nominally 21.477 MHz); the surrounding glue in
 * `net.gamebub.core.snes.HandheldSnes` handles memory, video and input.
 */
class SnesCore(config: SnesCoreConfig) extends ExtModule(Map(
  "USE_DLH" -> (if (config.dsp) 1 else 0),
  "USE_CX4" -> (if (config.cx4) 1 else 0),
  "USE_SDD1" -> (if (config.sdd1) 1 else 0),
  "USE_GSU" -> (if (config.gsu) 1 else 0),
  "USE_SA1" -> (if (config.sa1) 1 else 0),
  "USE_DSPn" -> (if (config.dsp) 1 else 0),
  "USE_SPC7110" -> (if (config.spc7110) 1 else 0),
  "USE_BSX" -> (if (config.bsx) 1 else 0),
  "USE_SUFAMI" -> (if (config.sufami) 1 else 0),
  "USE_MSU" -> (if (config.msu) 1 else 0),
  "USE_SS" -> (if (config.savestates) 1 else 0),
)) {
  override def desiredName: String = "main"

  val io = FlatIO(new Bundle {
    val RESET_N = Input(Bool())
    val MCLK = Input(Clock())
    val ACLK = Input(Clock())

    val ROM_TYPE = Input(UInt(8.W))
    val ROM_MASK = Input(UInt(24.W))
    val RAM_MASK = Input(UInt(24.W))
    val RAM_SIZE = Input(UInt(4.W))

    val SYSCLKR_CE = Output(Bool())
    val SYSCLKF_CE = Output(Bool())
    val REFRESH = Output(Bool())

    val ROM_ADDR = Output(UInt(24.W))
    val ROM_D = Output(UInt(16.W))
    val ROM_Q = Input(UInt(16.W))
    val ROM_CE_N = Output(Bool())
    val ROM_OE_N = Output(Bool())
    val ROM_WE_N = Output(Bool())
    val ROM_WORD = Output(Bool())

    val BSRAM_ADDR = Output(UInt(20.W))
    val BSRAM_D = Output(UInt(8.W))
    val BSRAM_Q = Input(UInt(8.W))
    val BSRAM_CE_N = Output(Bool())
    val BSRAM_OE_N = Output(Bool())
    val BSRAM_WE_N = Output(Bool())

    val WRAM_ADDR = Output(UInt(17.W))
    val WRAM_D = Output(UInt(8.W))
    val WRAM_Q = Input(UInt(8.W))
    val WRAM_CE_N = Output(Bool())
    val WRAM_OE_N = Output(Bool())
    val WRAM_WE_N = Output(Bool())

    val VRAM1_ADDR = Output(UInt(16.W))
    val VRAM1_DI = Input(UInt(8.W))
    val VRAM1_DO = Output(UInt(8.W))
    val VRAM1_WE_N = Output(Bool())
    val VRAM2_ADDR = Output(UInt(16.W))
    val VRAM2_DI = Input(UInt(8.W))
    val VRAM2_DO = Output(UInt(8.W))
    val VRAM2_WE_N = Output(Bool())
    val VRAM_OE_N = Output(Bool())

    val ARAM_ADDR = Output(UInt(16.W))
    val ARAM_D = Output(UInt(8.W))
    val ARAM_Q = Input(UInt(8.W))
    val ARAM_CE_N = Output(Bool())
    val ARAM_OE_N = Output(Bool())
    val ARAM_WE_N = Output(Bool())

    val GSU_ACTIVE = Output(Bool())
    /** Game Bub addition: the GSU samples ROM_Q / BSRAM_Q this cycle (see GSU.vhd). */
    val GSU_ROM_SAMPLE = Output(Bool())
    val GSU_RAM_SAMPLE = Output(Bool())
    /** Game Bub addition: the GSU owns the RAM bus, so the S-CPU cannot be reading BSRAM_Q (see GSU.vhd). */
    val GSU_RAM_OWNED = Output(Bool())
    /** Game Bub addition: the SA-1 side (65C816 / DMA / VBP) samples ROM_Q this cycle (see SA1.vhd). */
    val SA1_ROM_SAMPLE = Output(Bool())
    /** Game Bub addition: the SA-1 side samples BSRAM_Q this cycle / owns the BW-RAM bus (see SA1.vhd). */
    val SA1_BWRAM_SAMPLE = Output(Bool())
    val SA1_BWRAM_OWNED = Output(Bool())
    val GSU_TURBO = Input(Bool())
    val GSU_FASTROM = Input(Bool())
    val SUFAMI_SWAP = Input(Bool())
    val CC_DIP = Input(UInt(8.W))

    val BLEND = Input(Bool())
    val PAL = Input(Bool())
    val HIGH_RES = Output(Bool())
    val V224_MODE = Output(Bool())
    val FIELD = Output(Bool())
    val INTERLACE = Output(Bool())
    val DOTCLK = Output(Bool())
    val R = Output(UInt(8.W))
    val G = Output(UInt(8.W))
    val B = Output(UInt(8.W))
    val HBLANKn = Output(Bool())
    val VBLANKn = Output(Bool())
    val HSYNC = Output(Bool())
    val VSYNC = Output(Bool())
    val HVCNT_ATZERO = Output(Bool())

    val JOY1_DI = Input(UInt(2.W))
    val JOY2_DI = Input(UInt(2.W))
    val JOY_STRB = Output(Bool())
    val JOY1_CLK = Output(Bool())
    val JOY2_CLK = Output(Bool())
    val JOY1_P6 = Output(Bool())
    val JOY2_P6 = Output(Bool())
    val JOY2_P6_in = Input(Bool())
    val SNI_JOY = Output(UInt(64.W))

    val EXT_RTC = Input(UInt(65.W))

    val GG_EN = Input(Bool())
    val GG_CODE = Input(UInt(129.W))
    val GG_RESET = Input(Bool())
    val GG_AVAILABLE = Output(Bool())

    val SPC_MODE = Input(Bool())

    val IO_ADDR = Input(UInt(17.W))
    val IO_DAT = Input(UInt(16.W))
    val IO_WR = Input(Bool())

    val DBG_BG_EN = Input(UInt(5.W))
    val DBG_CPU_EN = Input(Bool())

    val TURBO = Input(Bool())
    val TURBO_ALLOW = Output(Bool())

    val DSP_FREQ = Input(Bool())

    val MSU_TRACK_NUM = Output(UInt(16.W))
    val MSU_TRACK_REQUEST = Output(Bool())
    val MSU_TRACK_MOUNTING = Input(Bool())
    val MSU_TRACK_MISSING = Input(Bool())
    val MSU_VOLUME = Output(UInt(8.W))
    val MSU_AUDIO_STOP = Input(Bool())
    val MSU_AUDIO_REPEAT = Output(Bool())
    val MSU_AUDIO_RESUME = Output(Bool())
    val MSU_AUDIO_PLAYING = Output(Bool())
    val MSU_AUDIO_SECTOR = Input(UInt(22.W))
    val MSU_RESUME_SECTOR = Output(UInt(22.W))
    val MSU_AUDIO_LOOP_INDEX = Input(UInt(32.W))
    val MSU_RESUME_LOOP_INDEX = Output(UInt(32.W))
    val MSU_DATA_ADDR = Output(UInt(32.W))
    val MSU_DATA = Input(UInt(8.W))
    val MSU_DATA_ACK = Input(Bool())
    val MSU_DATA_SEEK = Output(Bool())
    val MSU_DATA_REQ = Output(Bool())
    val MSU_ENABLE = Input(Bool())

    val SS_SAVE = Input(Bool())
    val SS_TOSD = Input(Bool())
    val SS_LOAD = Input(Bool())
    val SS_SLOT = Input(UInt(2.W))
    val SS_AVAIL = Output(Bool())
    /** The save-state program is running (a Game Bub addition to main.v). */
    val SS_BUSY = Output(Bool())

    val SS_DDR_DI = Input(UInt(64.W))
    val SS_DDR_ACK = Input(Bool())
    val SS_DDR_DO = Output(UInt(64.W))
    val SS_DDR_ADDR = Output(UInt(19.W))
    val SS_DDR_WE = Output(Bool())
    val SS_DDR_BE = Output(UInt(8.W))
    val SS_DDR_REQ = Output(Bool())

    val AUDIO_L = Output(UInt(16.W))
    val AUDIO_R = Output(UInt(16.W))
  })

  /** Tie off every input that Game Bub doesn't use. */
  def tieOffUnused(): Unit = {
    io.GSU_TURBO := false.B
    io.GSU_FASTROM := false.B
    io.SUFAMI_SWAP := false.B
    io.CC_DIP := 0.U
    io.JOY2_DI := "b11".U
    io.JOY2_P6_in := true.B
    io.EXT_RTC := 0.U
    io.GG_EN := false.B
    io.GG_CODE := 0.U
    io.GG_RESET := false.B
    io.SPC_MODE := false.B
    io.IO_ADDR := 0.U
    io.IO_DAT := 0.U
    io.IO_WR := false.B
    io.DBG_BG_EN := "b11111".U
    io.DBG_CPU_EN := true.B
    io.TURBO := false.B
    io.DSP_FREQ := false.B
    io.MSU_TRACK_MOUNTING := false.B
    io.MSU_TRACK_MISSING := false.B
    io.MSU_AUDIO_STOP := false.B
    io.MSU_AUDIO_SECTOR := 0.U
    io.MSU_AUDIO_LOOP_INDEX := 0.U
    io.MSU_DATA := 0.U
    io.MSU_DATA_ACK := false.B
    io.MSU_ENABLE := false.B
    io.SS_SAVE := false.B
    io.SS_TOSD := false.B
    io.SS_LOAD := false.B
    io.SS_SLOT := 0.U
    io.SS_DDR_DI := 0.U
    io.SS_DDR_ACK := false.B
  }
}
