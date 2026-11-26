package nes

import chisel3._

/**
 * Chisel black-box binding for `nes_gamebub` (`fpga/verilog/nes/gamebub/nes_gamebub.sv`),
 * which wraps the vendored MiSTer NES core, video/palette pipeline, GameLoader,
 * 2 KiB EEPROM BRAM, 4 KiB NSF player ROM, savestate external register, and
 * 4-player joypad serializers.
 */
class NesCore extends ExtModule {
  override def desiredName: String = "nes_gamebub"

  val clk = IO(Input(Clock()))
  val reset_nes = IO(Input(Bool()))
  val cold_reset = IO(Input(Bool()))
  val pausecore = IO(Input(Bool()))
  val corepaused = IO(Output(Bool()))

  // Settings
  val sys_type = IO(Input(UInt(2.W)))
  val ppu_rst_behavior = IO(Input(Bool()))
  val palette_sel = IO(Input(UInt(3.W)))
  val mask_edges = IO(Input(UInt(2.W)))
  val ex_sprites = IO(Input(Bool()))
  val int_audio = IO(Input(Bool()))
  val ext_audio = IO(Input(Bool()))
  val joy_swap = IO(Input(Bool()))
  val multitap = IO(Input(Bool()))
  val fds_auto_eject = IO(Input(Bool()))
  val fds_fast = IO(Input(Bool()))
  val dejitter_disable = IO(Input(Bool()))

  // Controllers: {Right, Left, Down, Up, Start, Select, B, A}
  val joy_a = IO(Input(UInt(8.W)))
  val joy_b = IO(Input(UInt(8.W)))
  val joy_c = IO(Input(UInt(8.W)))
  val joy_d = IO(Input(UInt(8.W)))
  val fds_btn = IO(Input(Bool()))
  val mic_btn = IO(Input(Bool()))

  // GameLoader interface
  val loader_reset = IO(Input(Bool()))
  val loader_downloading = IO(Input(Bool()))
  val loader_filetype = IO(Input(UInt(4.W)))
  val loader_indata = IO(Input(UInt(8.W)))
  val loader_indata_clk = IO(Input(Bool()))
  val loader_clearval = IO(Input(UInt(2.W)))
  val loader_mem_addr = IO(Output(UInt(25.W)))
  val loader_mem_data = IO(Output(UInt(8.W)))
  val loader_mem_write = IO(Output(Bool()))
  val loader_busy = IO(Output(Bool()))
  val loader_done = IO(Output(Bool()))
  val loader_error = IO(Output(Bool()))
  val mapper_flags_out = IO(Output(UInt(64.W)))
  val diskside_out = IO(Output(UInt(2.W)))
  val max_diskside = IO(Input(UInt(2.W)))

  // Memory ports (SDRAM)
  val cpumem_addr = IO(Output(UInt(25.W)))
  val cpumem_read = IO(Output(Bool()))
  val cpumem_write = IO(Output(Bool()))
  val cpumem_dout = IO(Output(UInt(8.W)))
  val cpumem_din = IO(Input(UInt(8.W)))
  val ppumem_addr = IO(Output(UInt(22.W)))
  val ppumem_read = IO(Output(Bool()))
  val ppumem_write = IO(Output(Bool()))
  val ppumem_dout = IO(Output(UInt(8.W)))
  val ppumem_din = IO(Input(UInt(8.W)))
  val ppu_ce_out = IO(Output(Bool()))
  val cart_ce_out = IO(Output(Bool()))
  val cpu_ce_out = IO(Output(Bool()))

  // EEPROM BRAM host access (2 KiB, Mapper 16/159)
  val eeprom_clk = IO(Input(Clock()))
  val eeprom_addr = IO(Input(UInt(11.W)))
  val eeprom_din = IO(Input(UInt(8.W)))
  val eeprom_wren = IO(Input(Bool()))
  val eeprom_dout = IO(Output(UInt(8.W)))
  val bram_override_out = IO(Output(Bool()))
  val save_written_out = IO(Output(Bool()))
  val mapper_has_flashsaves_out = IO(Output(Bool()))

  // Video (256x240 RGB888)
  val ce_pix = IO(Output(Bool()))
  val hblank = IO(Output(Bool()))
  val vblank = IO(Output(Bool()))
  val hsync = IO(Output(Bool()))
  val vsync = IO(Output(Bool()))
  val video_r = IO(Output(UInt(8.W)))
  val video_g = IO(Output(UInt(8.W)))
  val video_b = IO(Output(UInt(8.W)))
  val hold_reset_out = IO(Output(Bool()))

  // Audio (unsigned 16-bit mono)
  val audio_sample = IO(Output(UInt(16.W)))

  // Save states
  val mapper_has_savestate = IO(Output(Bool()))
  val save_state = IO(Input(Bool()))
  val load_state = IO(Input(Bool()))
  val savestate_number = IO(Input(UInt(2.W)))
  val sleep_savestate = IO(Output(Bool()))
  val state_loaded = IO(Output(Bool()))
  val savestate_busy = IO(Output(Bool()))

  // Savestate SDRAM RAM copy port (ch2)
  val ss_sdram_addr = IO(Output(UInt(25.W)))
  val ss_sdram_rd = IO(Output(Bool()))
  val ss_sdram_wr = IO(Output(Bool()))
  val ss_sdram_wdata = IO(Output(UInt(8.W)))
  val ss_sdram_rdata = IO(Input(UInt(8.W)))

  // Savestate SDRAM read-latch state (SS_Ext[15:0])
  val sdram_ss_in = IO(Output(UInt(16.W)))
  val sdram_ss_load = IO(Output(Bool()))
  val sdram_ss_out = IO(Input(UInt(16.W)))

  // Savestate 64-bit DDR/SDRAM storage port
  val save_out_din = IO(Output(UInt(64.W)))
  val save_out_dout = IO(Input(UInt(64.W)))
  val save_out_adr = IO(Output(UInt(26.W)))
  val save_out_rnw = IO(Output(Bool()))
  val save_out_ena = IO(Output(Bool()))
  val save_out_be = IO(Output(UInt(8.W)))
  val save_out_done = IO(Input(Bool()))
}
