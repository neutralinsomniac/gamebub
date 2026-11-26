// Game Bub wrapper around the vendored MiSTer NES core.
//
// Instantiates NES, video (palette + timing), GameLoader (iNES/NES 2.0/FDS/NSF
// header parser), the 2 KiB EEPROM BRAM (Mapper 16/159), the 4 KiB Loopy NSF
// player ROM, the SS_Ext savestate register (for ch0/ch1 SDRAM read latches),
// and the 4-player joypad shift registers.

import regs_savestates::*;

module nes_gamebub (
	input         clk,
	input         reset_nes,
	input         cold_reset,
	input         pausecore,
	output        corepaused,

	// Settings
	input   [1:0] sys_type,          // 0=NTSC, 1=PAL, 2=Dendy, 3=Vs.
	input         ppu_rst_behavior,  // 0=Famicom, 1=NES
	input   [2:0] palette_sel,       // 0=Kitrinx, 1=Smooth, 2=Wavebeam, 3=Sony CXA, 4=PC-10, 5=Tao
	input   [1:0] mask_edges,        // 0=Off, 1=Left, 2=Both, 3=Auto
	input         ex_sprites,
	input         int_audio,
	input         ext_audio,
	input         joy_swap,
	input         multitap,
	input         fds_auto_eject,
	input         fds_fast,
	input         dejitter_disable,

	// Controllers: {Right, Left, Down, Up, Start, Select, B, A} (matches MiSTer nes_joy_*)
	input   [7:0] joy_a,
	input   [7:0] joy_b,
	input   [7:0] joy_c,
	input   [7:0] joy_d,
	input         fds_btn,
	input         mic_btn,

	// GameLoader interface (driven by Chisel ROM streamer while core is in reset)
	input         loader_reset,
	input         loader_downloading,
	input   [3:0] loader_filetype,   // [0]=bios, [1]=nes, [2]=fds, [3]=nsf
	input   [7:0] loader_indata,
	input         loader_indata_clk,
	input   [1:0] loader_clearval,
	output [24:0] loader_mem_addr,
	output  [7:0] loader_mem_data,
	output        loader_mem_write,
	output        loader_busy,
	output        loader_done,
	output        loader_error,
	output [63:0] mapper_flags_out,
	output  [1:0] diskside_out,
	input   [1:0] max_diskside,

	// Memory ports (SDRAM)
	output [24:0] cpumem_addr,
	output        cpumem_read,
	output        cpumem_write,
	output  [7:0] cpumem_dout,
	input   [7:0] cpumem_din,
	output [21:0] ppumem_addr,
	output        ppumem_read,
	output        ppumem_write,
	output  [7:0] ppumem_dout,
	input   [7:0] ppumem_din,
	output        ppu_ce_out,
	output        cart_ce_out,
	output        cpu_ce_out,

	// EEPROM BRAM host access (2 KiB, for Mapper 16/159)
	input         eeprom_clk,
	input  [10:0] eeprom_addr,
	input   [7:0] eeprom_din,
	input         eeprom_wren,
	output  [7:0] eeprom_dout,
	output        bram_override_out,
	output        save_written_out,
	output        mapper_has_flashsaves_out,

	// Video (256x240 RGB888)
	output        ce_pix,
	output        hblank,
	output        vblank,
	output        hsync,
	output        vsync,
	output  [7:0] video_r,
	output  [7:0] video_g,
	output  [7:0] video_b,
	output        hold_reset_out,

	// Audio (unsigned 16-bit mono)
	output [15:0] audio_sample,

	// Save states
	output        mapper_has_savestate,
	input         save_state,
	input         load_state,
	input   [1:0] savestate_number,
	output        sleep_savestate,
	output        state_loaded,
	output        savestate_busy,

	// Savestate SDRAM RAM copy port (ch2)
	output [24:0] ss_sdram_addr,
	output        ss_sdram_rd,
	output        ss_sdram_wr,
	output  [7:0] ss_sdram_wdata,
	input   [7:0] ss_sdram_rdata,

	// Savestate SDRAM read-latch state (SS_Ext[15:0])
	output [15:0] sdram_ss_in,
	output        sdram_ss_load,
	input  [15:0] sdram_ss_out,

	// Savestate 64-bit DDR/SDRAM storage port
	output [63:0] save_out_din,
	input  [63:0] save_out_dout,
	output [25:0] save_out_adr,
	output        save_out_rnw,
	output        save_out_ena,
	output  [7:0] save_out_be,
	input         save_out_done
);

wire [1:0] nes_div;
wire [5:0] color;
wire [2:0] emphasis;
wire [8:0] cycle;
wire [8:0] scanline;
wire       nes_hblank, nes_hsync, nes_vsync, nes_vblank;
wire       hold_reset;
assign hold_reset_out = hold_reset;

// LFSR for optional random RAM clear
reg [31:0] lfsr = 32'haaaaaaaa;
wire lfsr_fb = lfsr[31]^lfsr[29]^lfsr[28]^lfsr[27]^lfsr[23]^lfsr[20]^lfsr[19]^lfsr[17]^lfsr[15]^lfsr[14]^lfsr[12]^lfsr[11]^lfsr[9]^lfsr[4]^lfsr[3]^lfsr[2];
always @(posedge clk) lfsr <= {lfsr[30:0], lfsr_fb};
wire [7:0] cleardata = (loader_clearval == 2'd3) ? lfsr[7:0] : (loader_clearval == 2'd2 ? 8'hFF : 8'h00);

// NSF player ROM (4 KiB)
wire [7:0] nsf_data;
nsf_rom nsfplayrom (
	.clock   (clk),
	.address (loader_mem_addr[11:0]),
	.q       (nsf_data)
);

wire [63:0] loader_flags;
reg  [63:0] mapper_flags = 64'd0;
wire  [9:0] prg_mask_short, chr_mask_short;
wire        rom_loaded_w;
wire        is_nsf = (loader_flags[7:0] == 8'h1F);
wire  [7:0] loader_input = (loader_busy && !loader_downloading) ? (is_nsf ? nsf_data : 8'h00) : loader_indata;

always @(posedge clk) begin
	if (loader_done) mapper_flags <= loader_flags;
end
assign mapper_flags_out = mapper_flags;

GameLoader loader (
	.clk         (clk),
	.reset       (loader_reset),
	.downloading (loader_downloading),
	.filetype    ({4'b0000, loader_filetype}),
	.is_bios     (1'b1), // FDS BIOS is copied directly by the Game Bub host loader
	.indata      (loader_input),
	.indata_clk  (loader_indata_clk),
	.clearval    (|loader_clearval),
	.cleardata   (cleardata),
	.mem_addr    (loader_mem_addr),
	.mem_data    (loader_mem_data),
	.mem_write   (loader_mem_write),
	.mapper_flags(loader_flags),
	.prg_mask    (prg_mask_short),
	.chr_mask    (chr_mask_short),
	.busy        (loader_busy),
	.done        (loader_done),
	.error       (loader_error),
	.rom_loaded  (rom_loaded_w)
);

// Joypads & Famicom microphone
wire [2:0] joypad_out;
wire [1:0] joypad_clock;
reg [23:0] joypad_bits, joypad_bits2;
reg  [1:0] last_joypad_clock;

reg [7:0] mic_cnt;
always @(posedge clk) mic_cnt <= (mic_cnt == 8'd250) ? 8'd0 : mic_cnt + 1'b1;
wire mic = (mic_cnt < 8'd215) && mic_btn;

wire vs_mode = (sys_type == 2'd3);
wire [6:0] default_expansion = mapper_flags[59:53];
wire vs_player_left = vs_mode && (default_expansion == 7'h05);
wire eff_joy_swap = joy_swap ^ vs_player_left;

wire [4:0] joypad1_data = {2'b00, mic, 1'b0, joypad_bits[0]};
wire [4:0] joypad2_data = {4'b0000, joypad_bits2[0]};

wire eff_reset = reset_nes || hold_reset;

always @(posedge clk) begin
	if (eff_reset) begin
		joypad_bits       <= 24'd0;
		joypad_bits2      <= 24'd0;
		last_joypad_clock <= 2'd0;
	end else begin
		if (joypad_out[0]) begin
			joypad_bits  <= {multitap ? {8'h08, joy_c} : 16'hFFFF, eff_joy_swap ? joy_b : joy_a};
			joypad_bits2 <= {multitap ? {8'h04, joy_d} : 16'hFFFF, eff_joy_swap ? joy_a : joy_b};
		end
		if (!joypad_clock[0] && last_joypad_clock[0]) begin
			joypad_bits <= {1'b0, joypad_bits[23:1]};
		end
		if (!joypad_clock[1] && last_joypad_clock[1]) begin
			joypad_bits2 <= {1'b0, joypad_bits2[23:1]};
		end
		last_joypad_clock <= joypad_clock;
	end
end

// Video pause clock divider (keeps video counters ticking while core is paused)
reg [1:0] videopause_ce = 2'd0;
always @(posedge clk) begin
	if (!corepaused)
		videopause_ce <= nes_div + 2'd1;
	else
		videopause_ce <= videopause_ce + 2'd1;
end

// EEPROM BRAM (Mapper 16/159)
wire [17:0] bram_addr;
wire  [7:0] bram_din;
wire  [7:0] bram_dout;
wire        bram_write;
wire        bram_override;
assign bram_override_out = bram_override;

dpram #(.widthad_a(11), .width_a(8)) eeprom (
	.clock_a   (clk),
	.address_a (bram_addr[10:0]),
	.data_a    (bram_dout),
	.wren_a    (bram_write),
	.q_a       (bram_din),

	.clock_b   (eeprom_clk),
	.address_b (eeprom_addr),
	.data_b    (eeprom_din),
	.wren_b    (eeprom_wren),
	.q_b       (eeprom_dout)
);

// Savestate external register for SDRAM ch0/ch1 latches
wire [63:0] SaveStateBus_Din;
wire  [9:0] SaveStateBus_Adr;
wire        SaveStateBus_wren;
wire        SaveStateBus_rst;
wire [63:0] SaveStateBus_Dout;
wire [63:0] SS_Ext;
wire [63:0] SS_Ext_BACK = {48'd0, sdram_ss_out};
assign sdram_ss_in = SS_Ext[15:0];

eReg_SavestateV #(SSREG_INDEX_EXT, SSREG_DEFAULT_EXT) iREG_SAVESTATE_Ext (
	.clk      (clk),
	.BUS_Din  (SaveStateBus_Din),
	.BUS_Adr  (SaveStateBus_Adr),
	.BUS_wren (SaveStateBus_wren),
	.BUS_rst  (SaveStateBus_rst),
	.BUS_Dout (SaveStateBus_Dout),
	.Din      (SS_Ext_BACK),
	.Dout     (SS_Ext)
);

NES nes (
	.clk                   (clk),
	.reset_nes             (eff_reset),
	.ppu_rst_behavior      (ppu_rst_behavior),
	.cold_reset            (cold_reset),
	.pausecore             (pausecore | sleep_savestate),
	.corepaused            (corepaused),
	.debug_dots            (1'b0),
	.sys_type              (sys_type),
	.vs_dip_switches       (8'd0),
	.nes_div               (nes_div),
	.mapper_flags          (loader_downloading ? 64'd0 : mapper_flags),
	.gg                    (1'b1), // 1 = cheats disabled in MiSTer NES
	.gg_code               (129'd0),
	.gg_reset              (cold_reset),
	.gg_avail              (),

	// Audio
	.sample                (audio_sample),
	.audio_channels        (5'b11111),
	.int_audio             (int_audio),
	.ext_audio             (ext_audio),
	.apu_ce                (),

	// Video
	.ex_sprites            (ex_sprites),
	.color                 (color),
	.hsync                 (nes_hsync),
	.hblank                (nes_hblank),
	.vsync                 (nes_vsync),
	.vblank                (nes_vblank),
	.composite_a           (),
	.composite_b           (),
	.emphasis              (emphasis),
	.cycle                 (cycle),
	.scanline              (scanline),
	.mask                  (mask_edges),
	.dejitter_timing       (dejitter_disable),

	// Controllers & FDS
	.joypad_out            (joypad_out),
	.joypad_clock          (joypad_clock),
	.joypad1_data          (joypad1_data),
	.joypad2_data          (joypad2_data),
	.diskside              (diskside_out),
	.fds_busy              (1'b0),
	.fds_eject             (fds_btn),
	.fds_auto_eject        (fds_auto_eject),
	.max_diskside          (max_diskside),
	.fds_fast              (fds_fast),

	// Memory
	.cpumem_addr           (cpumem_addr),
	.cpumem_read           (cpumem_read),
	.cpumem_write          (cpumem_write),
	.cpumem_dout           (cpumem_dout),
	.cpumem_din            (cpumem_din),
	.ppumem_addr           (ppumem_addr),
	.ppumem_read           (ppumem_read),
	.ppumem_write          (ppumem_write),
	.ppumem_dout           (ppumem_dout),
	.ppumem_din            (ppumem_din),
	.refresh               (),

	.prg_mask              ({11'd0, prg_mask_short}),
	.chr_mask              ({10'd0, chr_mask_short}),

	.bram_addr             (bram_addr),
	.bram_din              (bram_din),
	.bram_dout             (bram_dout),
	.bram_write            (bram_write),
	.bram_override         (bram_override),
	.save_written          (save_written_out),
	.mapper_has_flashsaves (mapper_has_flashsaves_out),

	// Savestates
	.mapper_has_savestate  (mapper_has_savestate),
	.increaseSSHeaderCount (1'b1),
	.save_state            (save_state),
	.load_state            (load_state),
	.savestate_number      (savestate_number),
	.sleep_savestate       (sleep_savestate),
	.state_loaded          (state_loaded),

	.Savestate_SDRAMAddr     (ss_sdram_addr),
	.Savestate_SDRAMRdEn     (ss_sdram_rd),
	.Savestate_SDRAMWrEn     (ss_sdram_wr),
	.Savestate_SDRAMWriteData(ss_sdram_wdata),
	.Savestate_SDRAMReadData (ss_sdram_rdata),

	.SaveStateExt_Din      (SaveStateBus_Din),
	.SaveStateExt_Adr      (SaveStateBus_Adr),
	.SaveStateExt_wren     (SaveStateBus_wren),
	.SaveStateExt_rst      (SaveStateBus_rst),
	.SaveStateExt_Dout     (SaveStateBus_Dout),
	.SaveStateExt_load     (sdram_ss_load),

	.SAVE_out_Din          (save_out_din),
	.SAVE_out_Dout         (save_out_dout),
	.SAVE_out_Adr          (save_out_adr),
	.SAVE_out_rnw          (save_out_rnw),
	.SAVE_out_ena          (save_out_ena),
	.SAVE_out_be           (save_out_be),
	.SAVE_out_done         (save_out_done),

	// Game Bub glue exports
	.ppu_ce_out            (ppu_ce_out),
	.cart_ce_out           (cart_ce_out),
	.cpu_ce_out            (cpu_ce_out),
	.savestate_busy_out    (savestate_busy)
);

wire pal_video = (sys_type == 2'd1) || (sys_type == 2'd2);
wire [1:0] nes_ce_video = corepaused ? videopause_ce : nes_div;

video video_inst (
	.clk              (clk),
	.reset            (reset_nes),
	.cnt              (nes_ce_video),
	.color            (color),
	.count_h          (cycle),
	.count_v          (scanline),
	.hide_overscan    (2'b01), // Always emit full 256x240; Game Bub scaler crops to 224 when selected
	.palette          ({1'b0, palette_sel}),
	.emphasis         (emphasis),
	.reticle          (2'b00),
	.sys_type         (sys_type),
	.vs_ppu_type      (mapper_flags[48:45]),
	.pal_video        (pal_video),
	.nes_hblank       (nes_hblank),
	.nes_hsync        (nes_hsync),
	.nes_vsync        (nes_vsync),
	.nes_vblank       (nes_vblank),
	.load_color       (1'b0),
	.load_color_data  (24'd0),
	.load_color_index (6'd0),
	.hold_reset       (hold_reset),
	.ce_pix           (ce_pix),
	.HSync            (hsync),
	.VSync            (vsync),
	.HBlank           (hblank),
	.VBlank           (vblank),
	.R                (video_r),
	.G                (video_g),
	.B                (video_b)
);

endmodule


/////////////////////////////////////////////////////////////////////////
// GameLoader (from upstream NES.sv)
// Reads iNES / NES 2.0 / FDS / NSF headers and streams PRG/CHR/Extra/FDS
// data into the SDRAM address space.
/////////////////////////////////////////////////////////////////////////
module GameLoader (
	input         clk,
	input         reset,
	input         downloading,
	input   [7:0] filetype,
	input         is_bios,
	input   [7:0] indata,
	input         indata_clk,
	input         clearval,
	input   [7:0] cleardata,
	output reg [24:0] mem_addr,
	output  [7:0] mem_data,
	output        mem_write,
	output [63:0] mapper_flags,
	output reg [9:0] prg_mask,
	output reg [9:0] chr_mask,
	output reg    busy,
	output reg    done,
	output reg    error,
	output reg    rom_loaded
);

initial begin
	rom_loaded <= 0;
end

reg  [3:0] ctr;
reg  [7:0] ines[0:15];
reg [24:0] bytes_left;

wire [7:0] prgrom = ines[4];
wire [7:0] chrrom = ines[5];
wire [3:0] chrram = ines[11][3:0];
wire has_chr_ram = ~is_nes20 ? (chrrom == 0) : |chrram;

assign mem_data = (state == S_CLEARRAM || (~copybios && state == S_COPYBIOS)) ? (clearval && ~(state == S_COPYBIOS) ? (mapper == 8'd232 ? 8'h00 : cleardata) : 8'h00) : indata;
assign mem_write = (((bytes_left != 0) && (state == S_LOADPRG || state == S_LOADCHR || state == S_LOADEXTRA)
	|| (downloading && (state == S_LOADHEADER || state == S_LOADFDS || state == S_LOADNSFH || state == S_LOADNSFD))) && indata_clk)
	|| ((bytes_left != 0) && ((state == S_CLEARRAM) || (state == S_COPYBIOS) || (state == S_COPYPLAY)) && clearclk == 4'h2);

wire is_nes20 = (ines[7][3:2] == 2'b10);
wire is_nes20_prg = (is_nes20 && (ines[9][3:0] == 4'hF));
wire is_nes20_chr = (is_nes20 && (ines[9][7:4] == 4'hF));

reg [21:0] prg_size2, chr_size2, chr_ram_size;

function [9:0] mask;
	input [10:0] size;
	integer i;
	begin
		for (i=0;i<10;i=i+1) mask[i] = ( size > (11'd1 << i) );
	end
endfunction

always @(posedge clk) begin
	prg_size2 <= is_nes20_prg ? ({19'b0, ines[4][1:0], 1'b1} << ines[4][7:2]) : {prgrom, 14'b0};
	prg_mask <= mask(prg_size2[21:11]);

	chr_size2 <= is_nes20_chr ? ({19'b0, ines[5][1:0], 1'b1} << ines[5][7:2]) : {1'b0, chrrom, 13'b0};
	chr_ram_size <= is_nes20 ? (22'd64 << chrram) : 22'h2000;
	chr_mask <= mask(|chr_size2 ? chr_size2[21:11] : chr_ram_size[21:11]);
end

wire [2:0] prg_size = prgrom <= 1  ? 3'd0 :
	prgrom <= 2  ? 3'd1 :
	prgrom <= 4  ? 3'd2 :
	prgrom <= 8  ? 3'd3 :
	prgrom <= 16 ? 3'd4 :
	prgrom <= 32 ? 3'd5 :
	prgrom <= 64 ? 3'd6 : 3'd7;

wire [2:0] chr_size = chrrom <= 1  ? 3'd0 :
	chrrom <= 2  ? 3'd1 :
	chrrom <= 4  ? 3'd2 :
	chrrom <= 8  ? 3'd3 :
	chrrom <= 16 ? 3'd4 :
	chrrom <= 32 ? 3'd5 :
	chrrom <= 64 ? 3'd6 : 3'd7;

wire is_dirty = !is_nes20 && ((ines[9][7:1] != 0)
	|| (ines[10] != 0)
	|| (ines[11] != 0)
	|| (ines[12] != 0)
	|| (ines[13] != 0)
	|| (ines[14] != 0)
	|| (ines[15] != 0));

wire [7:0] mapper = {is_dirty ? 4'b0000 : ines[7][7:4], ines[6][7:4]};
wire [7:0] ines2mapper = {is_nes20 ? ines[8] : 8'h00};
wire [3:0] prgram = {is_nes20 ? ines[10][3:0] : 4'h0};
wire [3:0] prg_nvram = (is_nes20 ? ines[10][7:4] : 4'h0);
wire       piano = is_nes20 && (ines[15][5:0] == 6'h19);
wire       mapper99 = ({ines2mapper[1:0], mapper} == 10'd99);
wire [3:0] vs_hardware_type = (is_nes20 && (ines[7][1:0] == 2'd1)) ? ines[13][7:4] : 4'd0;
wire       mapper99_dual = mapper99 && (vs_hardware_type == 4'd5);
wire       mapper99_raid = mapper99 && (vs_hardware_type == 4'd6);
wire       has_saves = ines[6][1];

assign mapper_flags[63:60] = 4'd0;
assign mapper_flags[59:53] = is_nes20 ? ines[15][6:0] : 7'd0;
assign mapper_flags[52:49] = vs_hardware_type;
assign mapper_flags[48:45] = (is_nes20 && (ines[7][1:0] == 2'd1)) ? ines[13][3:0] : 4'd0;
assign mapper_flags[44:43] = is_nes20 ? ines[7][1:0] : 2'd0;
assign mapper_flags[42:41] = mapper99 ? chr_size2[14:13] : 2'd0;
assign mapper_flags[40:38] = mapper99_dual ? prg_size2[16:14] :
	mapper99_raid ? 3'd4 :
	mapper99 ? prg_size2[15:13] : 3'd0;
assign mapper_flags[37:36] = is_nes20 ? ines[12][1:0] : 2'b00;
assign mapper_flags[35]    = is_nes20;
assign mapper_flags[34:31] = prg_nvram;
assign mapper_flags[30]    = piano;
assign mapper_flags[29:26] = prgram;
assign mapper_flags[25]    = has_saves;
assign mapper_flags[24:17] = ines2mapper;
assign mapper_flags[16]    = ines[6][3];
assign mapper_flags[15]    = has_chr_ram;
assign mapper_flags[14]    = ines[6][0];
assign mapper_flags[13:11] = chr_size;
assign mapper_flags[10:8]  = prg_size;
assign mapper_flags[7:0]   = mapper;

reg [3:0] clearclk;
reg copybios;
reg cleardone;

typedef enum bit [3:0] { S_LOADHEADER, S_LOADPRG, S_LOADCHR, S_LOADEXTRA, S_LOADFDS, S_ERROR, S_CLEARRAM, S_COPYBIOS, S_LOADNSFH, S_LOADNSFD, S_COPYPLAY, S_DONE } mystate;
mystate state;

wire type_bios = filetype[0];
wire type_nes = filetype[1];
wire type_fds = filetype[2];
wire type_nsf = filetype[3];

always @(posedge clk) begin
	if (downloading && (type_fds || type_nes || type_nsf))
		rom_loaded <= 1;

	if (reset) begin
		state <= S_LOADHEADER;
		busy <= 0;
		done <= 0;
		ctr <= 0;
		mem_addr <= type_fds ? 25'b0_0011_1100_0000_0000_0001_0000 :
			type_nsf ? 25'b0_0000_0000_0000_0001_0000_0000
			: 25'b0_0000_0000_0000_0000_0000_0000;
		copybios <= 0;
		cleardone <= 0;
	end else begin
		case(state)
		S_LOADHEADER:
			if (indata_clk) begin
				error <= 0;
				ctr <= ctr + 1'd1;
				mem_addr <= mem_addr + 1'd1;
				ines[ctr] <= indata;
				bytes_left <= prg_size2;
				if (ctr == 4'b1111) begin
					busy <= 1;
					if ((ines[0] == 8'h4E) && (ines[1] == 8'h45) && (ines[2] == 8'h53) && (ines[3] == 8'h1A) && !ines[6][2]) begin
						mem_addr <= 0;
						state <= S_LOADPRG;
					end else if ((ines[0] == 8'h46) && (ines[1] == 8'h44) && (ines[2] == 8'h53) && (ines[3] == 8'h1A)) begin
						mem_addr <= 25'b0_0011_1100_0000_0000_0001_0000;
						state <= S_LOADFDS;
						bytes_left <= 25'b1;
					end else if (type_bios) begin
						state <= S_LOADFDS;
						mem_addr <= 25'b0_0000_0000_0000_0000_0001_0000;
						bytes_left <= 25'b1;
					end else if (type_fds) begin
						state <= S_LOADFDS;
						mem_addr <= 25'b0_0011_1100_0000_0000_0010_0000;
						bytes_left <= 25'b1;
					end else if (type_nsf) begin
						state <= S_LOADNSFH;
						bytes_left <= 25'h70;
					end else begin
						state <= S_ERROR;
					end
				end
			end
		S_LOADPRG, S_LOADCHR: begin
			if (downloading && bytes_left != 0) begin
				if (indata_clk) begin
					bytes_left <= bytes_left - 1'd1;
					mem_addr <= mem_addr + 1'd1;
				end
			end else if (state == S_LOADPRG) begin
				state <= S_LOADCHR;
				mem_addr <= 25'b0_0010_0000_0000_0000_0000_0000;
				bytes_left <= chr_size2;
			end else if (state == S_LOADCHR) begin
				state <= S_LOADEXTRA;
				mem_addr <= 25'b1_0000_0000_0000_0000_0000_0000;
				bytes_left <= ({ines2mapper[1:0],mapper} == 10'd413) ? 25'b0_1000_0000_0000_0000_0000_0000 : 25'd0;
			end
		end
		S_LOADEXTRA: begin
			if (downloading && bytes_left != 0) begin
				if (indata_clk) begin
					bytes_left <= bytes_left - 1'd1;
					mem_addr <= mem_addr + 1'd1;
				end
			end else if (mapper == 8'd232) begin
				mem_addr <= 25'b0_0011_1000_0000_0111_1111_1110;
				bytes_left <= 25'h2;
				state <= S_CLEARRAM;
				clearclk <= 4'h0;
				cleardone <= 1;
			end else if (clearval) begin
				mem_addr <= 25'b0_0011_0000_0000_0000_0000_0000;
				bytes_left <= 25'hF_FFFF;
				state <= S_CLEARRAM;
				clearclk <= 4'h0;
				cleardone <= 1;
			end else begin
				done <= 1;
				busy <= 0;
			end
		end
		S_ERROR: begin
			done <= 1;
			error <= 1;
			busy <= 0;
		end
		S_LOADFDS: begin
			if (downloading) begin
				if (indata_clk) begin
					mem_addr <= mem_addr + 1'd1;
				end
			end else begin
				mem_addr <= 25'b0_0011_1000_0000_0001_0000_0010;
				bytes_left <= 25'h2;
				ines[4] <= 8'h80;
				ines[5] <= 8'h00;
				ines[6] <= 8'h40;
				ines[7] <= 8'h10;
				ines[8] <= 8'h00;
				ines[9] <= 8'h00;
				ines[10] <= 8'h00;
				ines[11] <= 8'h00;
				ines[12] <= 8'h00;
				ines[13] <= 8'h00;
				ines[14] <= 8'h00;
				ines[15] <= 8'h00;
				state <= S_CLEARRAM;
				clearclk <= 4'h0;
				copybios <= ~is_bios;
				cleardone <= 1; // BIOS copied externally from SDRAM 0x03B0000
			end
		end
		S_CLEARRAM: begin
			clearclk <= clearclk + 4'h1;
			if (bytes_left != 25'h0) begin
				if (clearclk == 4'hF) begin
					bytes_left <= bytes_left - 1'd1;
					mem_addr <= mem_addr + 1'd1;
				end
			end else if (!cleardone) begin
				mem_addr <= 25'b0_0000_0000_0000_0000_0000_0000;
				bytes_left <= 25'h2000;
				state <= S_COPYBIOS;
				clearclk <= 4'h0;
			end else begin
				state <= S_DONE;
			end
		end
		S_COPYBIOS: begin
			clearclk <= clearclk + 4'h1;
			if (bytes_left != 25'h0) begin
				if (clearclk == 4'hF) begin
					bytes_left <= bytes_left - 1'd1;
					mem_addr <= mem_addr + 1'd1;
				end
			end else begin
				state <= S_DONE;
			end
		end
		S_LOADNSFH: begin
			if (bytes_left != 0) begin
				if (indata_clk) begin
					bytes_left <= bytes_left - 1'd1;
					mem_addr <= mem_addr + 1'd1;
				end
			end else begin
				state <= S_LOADNSFD;
				mem_addr <= {13'b0_0001_0000_0000,ines[9][3:0],ines[8]};
				bytes_left <= 25'b1;
			end
		end
		S_LOADNSFD: begin
			if (downloading) begin
				if (indata_clk) begin
					mem_addr <= mem_addr + 1'd1;
				end
			end else begin
				mem_addr <= 25'b0_0000_0000_0000_0001_1000_0000;
				bytes_left <= 25'h0E80;
				ines[4] <= 8'h80;
				ines[5] <= 8'h00;
				ines[6] <= 8'hF0;
				ines[7] <= 8'h18;
				ines[8] <= 8'hF0;
				ines[9] <= 8'h00;
				ines[10] <= 8'h00;
				ines[11] <= 8'h07;
				ines[12] <= 8'h00;
				ines[13] <= 8'h00;
				ines[14] <= 8'h00;
				ines[15] <= 8'h19;
				state <= S_COPYPLAY;
				clearclk <= 4'h0;
			end
		end
		S_COPYPLAY: begin
			clearclk <= clearclk + 4'h1;
			if (bytes_left != 25'h0) begin
				if (clearclk == 4'hF) begin
					bytes_left <= bytes_left - 1'd1;
					mem_addr <= mem_addr + 1'd1;
				end
			end else begin
				state <= S_DONE;
			end
		end
		S_DONE: begin
			done <= 1;
			busy <= 0;
		end
		endcase
	end
end
endmodule
