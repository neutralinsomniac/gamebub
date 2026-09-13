module main (
	input             RESET_N,

	input             MCLK,
	input             ACLK,

	input       [7:0] ROM_TYPE,
	input      [23:0] ROM_MASK,
	input      [23:0] RAM_MASK,
	input      [ 3:0] RAM_SIZE,
	
	output            SYSCLKR_CE,
	output            SYSCLKF_CE,
	output            REFRESH,

	output reg [23:0] ROM_ADDR,
	output reg [15:0] ROM_D,
	input      [15:0] ROM_Q,
	output reg        ROM_CE_N,
	output reg        ROM_OE_N,
	output reg        ROM_WE_N,
	output reg        ROM_WORD,

	output reg [19:0] BSRAM_ADDR,
	output reg  [7:0] BSRAM_D,
	input       [7:0] BSRAM_Q,
	output reg        BSRAM_CE_N,
	output reg        BSRAM_OE_N,
	output reg        BSRAM_WE_N,

	output     [16:0] WRAM_ADDR,
	output      [7:0] WRAM_D,
	input       [7:0] WRAM_Q,
	output            WRAM_CE_N,
	output            WRAM_OE_N,
	output            WRAM_WE_N,

	output     [15:0] VRAM1_ADDR,
	input       [7:0] VRAM1_DI,
	output      [7:0] VRAM1_DO,
	output            VRAM1_WE_N,
	output     [15:0] VRAM2_ADDR,
	input       [7:0] VRAM2_DI,
	output      [7:0] VRAM2_DO,
	output            VRAM2_WE_N,
	output            VRAM_OE_N,

	output reg [15:0] ARAM_ADDR,
	output reg  [7:0] ARAM_D,
	input       [7:0] ARAM_Q,
	output reg        ARAM_CE_N,
	output reg        ARAM_OE_N,
	output reg        ARAM_WE_N,

	output            GSU_ACTIVE,
	output            GSU_ROM_SAMPLE,
	output            GSU_RAM_SAMPLE,
	output            GSU_RAM_OWNED,
	output            SA1_ROM_SAMPLE,
	output            SA1_BWRAM_SAMPLE,
	output            SA1_BWRAM_OWNED,
	input             GSU_TURBO,
	input             GSU_FASTROM,
	input             SUFAMI_SWAP,
	input       [7:0] CC_DIP,

	input             BLEND,
	input             PAL,
	output            HIGH_RES,
	output            V224_MODE,
	output            FIELD,
	output            INTERLACE,
	output            DOTCLK,
	output      [7:0] R,
	output      [7:0] G,
	output      [7:0] B,
	output            HBLANKn,
	output            VBLANKn,
	output            HSYNC,
	output            VSYNC,
	output            HVCNT_ATZERO,

	input       [1:0] JOY1_DI,
	input       [1:0] JOY2_DI,
	output            JOY_STRB,
	output            JOY1_CLK,
	output            JOY2_CLK,
	output            JOY1_P6,
	output            JOY2_P6,
	input             JOY2_P6_in,
	output     [63:0] SNI_JOY, // Game Bub: was "output reg" (driven by instance; invalid in Verilog-2001)

	input      [64:0] EXT_RTC,

	input             GG_EN,
	input     [128:0] GG_CODE,
	input             GG_RESET,
	output            GG_AVAILABLE,

	input             SPC_MODE,

	input      [16:0] IO_ADDR,
	input      [15:0] IO_DAT,
	input             IO_WR,

	input       [4:0] DBG_BG_EN,
	input             DBG_CPU_EN,

	input             TURBO,
	output            TURBO_ALLOW,
	
	input             DSP_FREQ,

	output     [15:0] MSU_TRACK_NUM,
	output            MSU_TRACK_REQUEST,
	input             MSU_TRACK_MOUNTING,
	input             MSU_TRACK_MISSING,
	output      [7:0] MSU_VOLUME,
	input             MSU_AUDIO_STOP,
	output            MSU_AUDIO_REPEAT,
	output            MSU_AUDIO_RESUME,
	output            MSU_AUDIO_PLAYING,
	input      [21:0] MSU_AUDIO_SECTOR,
	output     [21:0] MSU_RESUME_SECTOR,
	input      [31:0] MSU_AUDIO_LOOP_INDEX,
	output     [31:0] MSU_RESUME_LOOP_INDEX,
	output     [31:0] MSU_DATA_ADDR,
	input       [7:0] MSU_DATA,
	input             MSU_DATA_ACK,
	output            MSU_DATA_SEEK,
	output            MSU_DATA_REQ,
	input             MSU_ENABLE,

	input             SS_SAVE,
	input             SS_TOSD,
	input             SS_LOAD,
	input       [1:0] SS_SLOT,
	output            SS_AVAIL,
	output            SS_BUSY,

	input      [63:0] SS_DDR_DI,
	input             SS_DDR_ACK,
	output     [63:0] SS_DDR_DO,
	output     [21:3] SS_DDR_ADDR,
	output            SS_DDR_WE,
	output      [7:0] SS_DDR_BE,
	output            SS_DDR_REQ,

	output     [15:0] AUDIO_L,
	output     [15:0] AUDIO_R
);

parameter USE_DLH = 1'b1;
parameter USE_CX4 = 1'b1;
parameter USE_SDD1 = 1'b1;
parameter USE_GSU = 1'b1;
parameter USE_SA1 = 1'b1;
parameter USE_DSPn = 1'b1;
parameter USE_SPC7110 = 1'b1;
parameter USE_BSX = 1'b1;
parameter USE_SUFAMI = 1'b1;
parameter USE_MSU = 1'b1;
parameter USE_SS = 1'b1;

wire [23:0] CA;
wire        CPURD_N;
wire        CPUWR_N;
reg   [7:0] DI;
wire  [7:0] DO;
wire        RAMSEL_N;
wire        ROMSEL_N;
reg         IRQ_N;
wire  [7:0] PA;
wire        PARD_N;
wire        PAWR_N;
//wire        SYSCLKF_CE;
//wire        SYSCLKR_CE;
//wire        REFRESH;

wire  [15:0] SNES_ARAM_ADDR;
wire   [7:0] SNES_ARAM_D;
wire         SNES_ARAM_CE_N;
wire         SNES_ARAM_OE_N;
wire         SNES_ARAM_WE_N;

wire  [6:0] MAP_ACTIVE;

// Save-state nets, declared before the coprocessor generate blocks that
// connect them: an implicit net created inside a generate scope is not the
// module-level wire declared further down (Vivado left those undriven).
wire  [7:0] SS_DO;
wire [23:0] SS_ROM_ADDR;

wire [19:0] SS_EXT_ADDR;
wire  [7:0] SS_SPC_DI;
wire  [7:0] SS_PPU_DI;
wire  [7:0] SS_DSPN_DI;
wire  [7:0] SS_GSU_DI;
wire        SS_DO_OVR;
wire        SS_ROM_OVR;
wire        SS_ARAM_SEL, SS_DSP_REGS_SEL, SS_SMP_SEL;
wire        SS_BSRAM_SEL;
wire        SS_DSPN_REGS_SEL, SS_DSPN_RAM_SEL;
wire        SS_GSU_SEL;

wire  [7:0] SS_CX4_DO_REG;
wire  [7:0] SS_CX4_DO_CACHE;
wire  [7:0] SS_CX4_DI;
wire        SS_CX4_SEL;
wire        SS_CX4_CACHE_SEL;
wire        SS_CX4_IDLE;
assign SS_CX4_DI = SS_CX4_CACHE_SEL ? SS_CX4_DO_CACHE : SS_CX4_DO_REG;
// Hold the snapshot until the CX4 is idle (only when a CX4 cart is active).
wire        CX4_SS_OK = ~MAP_ACTIVE[0] | SS_CX4_IDLE;

SNES SNES
(
	.MCLK(MCLK),
	.DSPCLK(ACLK),

	.RST_N(RESET_N),
	.ENABLE(1),

	.CA(CA),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),
	.DI(DI),
	.DO(DO),

	.RAMSEL_N(RAMSEL_N),
	.ROMSEL_N(ROMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),

	.REFRESH(REFRESH),

	.IRQ_N(IRQ_N),

	.WSRAM_ADDR(WRAM_ADDR),
	.WSRAM_D(WRAM_D),
	.WSRAM_Q(WRAM_Q),
	.WSRAM_CE_N(WRAM_CE_N),
	.WSRAM_OE_N(WRAM_OE_N),
	.WSRAM_WE_N(WRAM_WE_N),

	.VRAM_ADDRA(VRAM1_ADDR),
	.VRAM_ADDRB(VRAM2_ADDR),
	.VRAM_DAI(VRAM1_DI),
	.VRAM_DBI(VRAM2_DI),
	.VRAM_DAO(VRAM1_DO),
	.VRAM_DBO(VRAM2_DO),
	.VRAM_RD_N(VRAM_OE_N),
	.VRAM_WRA_N(VRAM1_WE_N),
	.VRAM_WRB_N(VRAM2_WE_N),

	.ARAM_ADDR(SNES_ARAM_ADDR),
	.ARAM_D(SNES_ARAM_D),
	.ARAM_Q(ARAM_Q),
	.ARAM_CE_N(SNES_ARAM_CE_N),
	.ARAM_OE_N(SNES_ARAM_OE_N),
	.ARAM_WE_N(SNES_ARAM_WE_N),

	.JOY1_DI(JOY1_DI),
	.JOY2_DI(JOY2_DI),
	.JOY_STRB(JOY_STRB),
	.JOY1_CLK(JOY1_CLK),
	.JOY2_CLK(JOY2_CLK),
	.JOY1_P6(JOY1_P6),
	.JOY2_P6(JOY2_P6),
	.JOY2_P6_in(JOY2_P6_in),
	.SNI_JOY(SNI_JOY),

	.BLEND(BLEND),
	.PAL(PAL),
	.HIGH_RES(HIGH_RES),
	.FIELD_OUT(FIELD),
	.INTERLACE(INTERLACE),
	.V224_MODE(V224_MODE),
	.DOTCLK(DOTCLK),

	.RGB_OUT({B,G,R}),
	.HDE(HBLANKn),
	.VDE(VBLANKn),
	.HSYNC(HSYNC),
	.VSYNC(VSYNC),
	.HVCNT_ATZERO(HVCNT_ATZERO),

	.GG_EN(GG_EN),
	.GG_CODE(GG_CODE),
	.GG_RESET(GG_RESET),
	.GG_AVAILABLE(GG_AVAILABLE),
	
	.SPC_MODE(SPC_MODE),
	
	.IO_ADDR(IO_ADDR),
	.IO_DAT(IO_DAT),
	.IO_WR(IO_WR),

	.SS_ADDR(SS_EXT_ADDR[8:0]),
	.SS_REGS_SEL(SS_DSP_REGS_SEL),
	.SS_SMP_SEL(SS_SMP_SEL),
	.SS_BUSY(SS_BUSY),
	.SS_WR(~PAWR_N),
	.SS_DI(SS_DO),
	.SS_SPC_DO(SS_SPC_DI),
	.SS_PPU_DO(SS_PPU_DI),

	.DBG_BG_EN(DBG_BG_EN),
	.DBG_CPU_EN(DBG_CPU_EN),
	
	.TURBO(TURBO),
	
	.DSP_FREQ(DSP_FREQ),

	.AUDIO_L(AUDIO_L),
	.AUDIO_R(AUDIO_R)
);



wire  [7:0] MSU_DO;
wire        MSU_SEL;

generate
if (USE_MSU == 1'b1) begin
MSU MSU
(
	.CLK(MCLK),
	.RST_N(RESET_N),
	.ENABLE(MSU_ENABLE),

	.RD_N(CPURD_N),
	.WR_N(CPUWR_N),
	.SYSCLKF_CE(SYSCLKF_CE),

	.ADDR(CA),
	.DIN(DO),
	.DOUT(MSU_DO),
	.MSU_SEL(MSU_SEL),

	.data_addr(MSU_DATA_ADDR),
	.data(MSU_DATA),
	.data_ack(MSU_DATA_ACK),
	.data_seek(MSU_DATA_SEEK),
	.data_req(MSU_DATA_REQ),

	.track_num(MSU_TRACK_NUM),
	.track_request(MSU_TRACK_REQUEST),
	.track_mounting(MSU_TRACK_MOUNTING),

	.status_track_missing(MSU_TRACK_MISSING),
	.status_audio_repeat(MSU_AUDIO_REPEAT),
	.audio_resume(MSU_AUDIO_RESUME),
	.status_audio_playing(MSU_AUDIO_PLAYING),
	.audio_stop(MSU_AUDIO_STOP),
	.audio_sector(MSU_AUDIO_SECTOR),
	.resume_sector(MSU_RESUME_SECTOR),
	.audio_loop_index(MSU_AUDIO_LOOP_INDEX),
	.resume_loop_index(MSU_RESUME_LOOP_INDEX),

	.volume(MSU_VOLUME)
);
end else begin
	assign MSU_DO  = 0;
	assign MSU_SEL = 0;
	assign MSU_TRACK_NUM = 0;
	assign MSU_TRACK_REQUEST = 0;
	assign MSU_VOLUME = 0;
	assign MSU_AUDIO_REPEAT = 0;
	assign MSU_AUDIO_PLAYING = 0;
end
endgenerate

wire  [7:0] DLH_DO;
wire        DLH_IRQ_N;
wire [23:0] DLH_ROM_ADDR;
wire        DLH_ROM_CE_N;
wire        DLH_ROM_OE_N;
wire        DLH_ROM_WORD;
wire [19:0] DLH_BSRAM_ADDR;
wire  [7:0] DLH_BSRAM_D;
wire        DLH_BSRAM_CE_N;
wire        DLH_BSRAM_OE_N;
wire        DLH_BSRAM_WE_N;

generate
if (USE_DLH == 1'b1) begin

DSP_LHRomMap #(.USE_DSPn(USE_DSPn)) DSP_LHRomMap
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(DLH_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),
	
	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.IRQ_N(DLH_IRQ_N),

	.ROM_ADDR(DLH_ROM_ADDR),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(DLH_ROM_CE_N),
	.ROM_OE_N(DLH_ROM_OE_N),
	.ROM_WORD(DLH_ROM_WORD),

	.BSRAM_ADDR(DLH_BSRAM_ADDR),
	.BSRAM_D(DLH_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(DLH_BSRAM_CE_N),
	.BSRAM_OE_N(DLH_BSRAM_OE_N),
	.BSRAM_WE_N(DLH_BSRAM_WE_N),

	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK),

	.EXT_RTC(EXT_RTC),
	
	.CC_DIP(CC_DIP),

	.SS_BUSY(SS_BUSY),
	.SS_RAM_A(SS_EXT_ADDR[11:0]),
	.SS_DSPN_REGS_SEL(SS_DSPN_REGS_SEL),
	.SS_DSPN_RAM_SEL(SS_DSPN_RAM_SEL),
	.SS_DI(SS_DO),
	.SS_DO(SS_DSPN_DI)
);
end else begin
	assign DLH_DO = 0;
	assign DLH_IRQ_N = 1;
	assign DLH_ROM_ADDR = 0;
	assign DLH_ROM_CE_N = 1;
	assign DLH_ROM_OE_N = 1;
	assign DLH_BSRAM_ADDR = 0;
	assign DLH_BSRAM_D = 0;
	assign DLH_BSRAM_CE_N = 1;
	assign DLH_BSRAM_OE_N = 1;
	assign DLH_BSRAM_WE_N = 1;
	assign DLH_ROM_WORD = 0;
end
endgenerate

wire [7:0]  CX4_DO;
wire        CX4_IRQ_N;
wire [22:0] CX4_ROM_ADDR;
wire        CX4_ROM_CE_N;
wire        CX4_ROM_OE_N;
wire        CX4_ROM_WORD;
wire [19:0] CX4_BSRAM_ADDR;
wire [7:0]  CX4_BSRAM_D;
wire        CX4_BSRAM_CE_N;
wire        CX4_BSRAM_OE_N;
wire        CX4_BSRAM_WE_N;

generate
if (USE_CX4 == 1'b1) begin

CX4Map CX4Map
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(CX4_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.IRQ_N(CX4_IRQ_N),

	.ROM_ADDR(CX4_ROM_ADDR),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(CX4_ROM_CE_N),
	.ROM_OE_N(CX4_ROM_OE_N),
	.ROM_WORD(CX4_ROM_WORD),

	.BSRAM_ADDR(CX4_BSRAM_ADDR),
	.BSRAM_D(CX4_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(CX4_BSRAM_CE_N),
	.BSRAM_OE_N(CX4_BSRAM_OE_N),
	.BSRAM_WE_N(CX4_BSRAM_WE_N),

	.MAP_ACTIVE(MAP_ACTIVE[0]),
	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK),

	.SS_BUSY(SS_BUSY),
	.SS_WR(SS_BUSY & SS_CX4_SEL & ~CPUWR_N),
	.SS_DO(SS_CX4_DO_REG),

	.SS_CACHE_A(SS_EXT_ADDR[9:0]),
	.SS_CACHE_SEL(SS_CX4_CACHE_SEL),
	.SS_CACHE_DI(SS_DO),
	.SS_CACHE_DO(SS_CX4_DO_CACHE),

	.SS_IDLE(SS_CX4_IDLE)
);
end else begin
assign MAP_ACTIVE[0] = 0;
// Stub CX4Map SS readback wires when CX4 is compiled out so the
// SS_CX4_DI mux does not read undriven nets in the USE_CX4==0 config.
assign SS_CX4_DO_REG = 8'h00;
assign SS_CX4_DO_CACHE = 8'h00;
assign SS_CX4_IDLE = 1'b1;   // not a CX4 cart -> never holds the snapshot
end
endgenerate

wire [7:0]  SDD_DO;
wire        SDD_IRQ_N;
wire [22:0] SDD_ROM_ADDR;
wire        SDD_ROM_CE_N;
wire        SDD_ROM_OE_N;
wire        SDD_ROM_WORD;
wire [19:0] SDD_BSRAM_ADDR;
wire [7:0]  SDD_BSRAM_D;
wire        SDD_BSRAM_CE_N;
wire        SDD_BSRAM_OE_N;
wire        SDD_BSRAM_WE_N;

generate
if (USE_SDD1 == 1'b1) begin

SDD1Map SDD1Map
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(SDD_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.IRQ_N(SDD_IRQ_N),

	.ROM_ADDR(SDD_ROM_ADDR),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(SDD_ROM_CE_N),
	.ROM_OE_N(SDD_ROM_OE_N),
	.ROM_WORD(SDD_ROM_WORD),

	.BSRAM_ADDR(SDD_BSRAM_ADDR),
	.BSRAM_D(SDD_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(SDD_BSRAM_CE_N),
	.BSRAM_OE_N(SDD_BSRAM_OE_N),
	.BSRAM_WE_N(SDD_BSRAM_WE_N),

	.MAP_ACTIVE(MAP_ACTIVE[1]),
	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK)
);
end else
assign MAP_ACTIVE[1] = 0;
endgenerate

wire [7:0]  GSU_DO;
wire        GSU_IRQ_N;
wire [22:0] GSU_ROM_ADDR;
wire        GSU_ROM_CE_N;
wire        GSU_ROM_OE_N;
wire        GSU_ROM_WORD;
wire [19:0] GSU_BSRAM_ADDR;
wire [7:0]  GSU_BSRAM_D;
wire        GSU_BSRAM_CE_N;
wire        GSU_BSRAM_OE_N;
wire        GSU_BSRAM_WE_N;

generate
if (USE_GSU == 1'b1) begin

GSUMap GSUMap
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(GSU_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.IRQ_N(GSU_IRQ_N),

	.ROM_ADDR(GSU_ROM_ADDR),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(GSU_ROM_CE_N),
	.ROM_OE_N(GSU_ROM_OE_N),
	.ROM_WORD(GSU_ROM_WORD),

	.BSRAM_ADDR(GSU_BSRAM_ADDR),
	.BSRAM_D(GSU_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(GSU_BSRAM_CE_N),
	.BSRAM_OE_N(GSU_BSRAM_OE_N),
	.BSRAM_WE_N(GSU_BSRAM_WE_N),

	.MAP_ACTIVE(MAP_ACTIVE[2]),
	.ROM_SAMPLE(GSU_ROM_SAMPLE),
	.RAM_SAMPLE(GSU_RAM_SAMPLE),
	.RAM_OWNED(GSU_RAM_OWNED),
	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK),

	.TURBO(GSU_TURBO),
	.FASTROM(GSU_FASTROM),

	.SS_BUSY(SS_BUSY),
	.SS_WR(SS_BUSY & SS_GSU_SEL & ~CPUWR_N),
	.SS_DO(SS_GSU_DI)
);
end else begin
assign MAP_ACTIVE[2] = 0;
assign GSU_ROM_SAMPLE = 0;
assign GSU_RAM_SAMPLE = 0;
assign GSU_RAM_OWNED = 0;
end
endgenerate

assign GSU_ACTIVE = MAP_ACTIVE[2];

wire [7:0]  SA1_DO;
wire        SA1_IRQ_N;
wire [22:0] SA1_ROM_ADDR;
wire        SA1_ROM_CE_N;
wire        SA1_ROM_OE_N;
wire        SA1_ROM_WORD;
wire [19:0] SA1_BSRAM_ADDR;
wire [7:0]  SA1_BSRAM_D;
wire        SA1_BSRAM_CE_N;
wire        SA1_BSRAM_OE_N;
wire        SA1_BSRAM_WE_N;

wire [23:0] SA1_P65_A;
wire  [7:0] SA1_P65_DO;
wire        SA1_P65_RD_N;
wire        SA1_P65_WR_N;

wire        SS_SA1_ROMSEL;
wire        SS_SNS_ROMSEL;

generate
if (USE_SA1 == 1'b1) begin

SA1Map SA1Map
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(SA1_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.PAL(PAL),

	.IRQ_N(SA1_IRQ_N),

	.ROM_ADDR(SA1_ROM_ADDR),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(SA1_ROM_CE_N),
	.ROM_OE_N(SA1_ROM_OE_N),
	.ROM_SAMPLE(SA1_ROM_SAMPLE),
	.BWRAM_SAMPLE(SA1_BWRAM_SAMPLE),
	.BWRAM_OWNED(SA1_BWRAM_OWNED),
	.ROM_WORD(SA1_ROM_WORD),

	.BSRAM_ADDR(SA1_BSRAM_ADDR),
	.BSRAM_D(SA1_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(SA1_BSRAM_CE_N),
	.BSRAM_OE_N(SA1_BSRAM_OE_N),
	.BSRAM_WE_N(SA1_BSRAM_WE_N),

	.MAP_ACTIVE(MAP_ACTIVE[3]),
	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK),

	.SA1_P65_A(SA1_P65_A),
	.SA1_P65_DO(SA1_P65_DO),
	.SA1_P65_RD_N(SA1_P65_RD_N),
	.SA1_P65_WR_N(SA1_P65_WR_N),

	.SS_BUSY(SS_BUSY),

	.SS_SA1_ROMSEL(SS_SA1_ROMSEL),
	.SS_SNS_ROMSEL(SS_SNS_ROMSEL)
);
end else begin
assign MAP_ACTIVE[3] = 0;
assign SA1_ROM_SAMPLE = 0;
assign SA1_BWRAM_SAMPLE = 0;
assign SA1_BWRAM_OWNED = 0;
end
endgenerate

wire [7:0]  SPC7110_DO;
wire        SPC7110_IRQ_N;
wire [22:0] SPC7110_ROM_ADDR;
wire        SPC7110_ROM_CE_N;
wire        SPC7110_ROM_OE_N;
wire        SPC7110_ROM_WORD;
wire [19:0] SPC7110_BSRAM_ADDR;
wire [7:0]  SPC7110_BSRAM_D;
wire        SPC7110_BSRAM_CE_N;
wire        SPC7110_BSRAM_OE_N;
wire        SPC7110_BSRAM_WE_N;

generate
if (USE_SPC7110 == 1'b1) begin
SPC7110Map SPC7110Map
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(SPC7110_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.IRQ_N(SPC7110_IRQ_N),

	.ROM_ADDR(SPC7110_ROM_ADDR),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(SPC7110_ROM_CE_N),
	.ROM_OE_N(SPC7110_ROM_OE_N),
	.ROM_WORD(SPC7110_ROM_WORD),

	.BSRAM_ADDR(SPC7110_BSRAM_ADDR),
	.BSRAM_D(SPC7110_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(SPC7110_BSRAM_CE_N),
	.BSRAM_OE_N(SPC7110_BSRAM_OE_N),
	.BSRAM_WE_N(SPC7110_BSRAM_WE_N),

	.MAP_ACTIVE(MAP_ACTIVE[4]),
	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK),
	
	.EXT_RTC(EXT_RTC)
);
end else
assign MAP_ACTIVE[4] = 0;
endgenerate

wire [7:0]  BSX_DO;
wire        BSX_IRQ_N;
wire [22:0] BSX_ROM_ADDR;
wire [7:0]  BSX_ROM_D;
wire        BSX_ROM_CE_N;
wire        BSX_ROM_OE_N;
wire        BSX_ROM_WE_N;
wire        BSX_ROM_WORD;
wire [19:0] BSX_BSRAM_ADDR;
wire [7:0]  BSX_BSRAM_D;
wire        BSX_BSRAM_CE_N;
wire        BSX_BSRAM_OE_N;
wire        BSX_BSRAM_WE_N;

generate
if (USE_BSX == 1'b1) begin
BSXMap BSXMap
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(BSX_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.IRQ_N(BSX_IRQ_N),

	.ROM_ADDR(BSX_ROM_ADDR),
	.ROM_D(BSX_ROM_D),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(BSX_ROM_CE_N),
	.ROM_OE_N(BSX_ROM_OE_N),
	.ROM_WE_N(BSX_ROM_WE_N),
	.ROM_WORD(BSX_ROM_WORD),

	.BSRAM_ADDR(BSX_BSRAM_ADDR),
	.BSRAM_D(BSX_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(BSX_BSRAM_CE_N),
	.BSRAM_OE_N(BSX_BSRAM_OE_N),
	.BSRAM_WE_N(BSX_BSRAM_WE_N),

	.MAP_ACTIVE(MAP_ACTIVE[5]),
	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK),

	.EXT_RTC(EXT_RTC)
);
end else
assign MAP_ACTIVE[5] = 0;
endgenerate

wire [7:0]  SUFAMI_DO;
wire        SUFAMI_IRQ_N;
wire [22:0] SUFAMI_ROM_ADDR;
wire        SUFAMI_ROM_CE_N;
wire        SUFAMI_ROM_OE_N;
wire        SUFAMI_ROM_WORD;
wire [19:0] SUFAMI_BSRAM_ADDR;
wire [7:0]  SUFAMI_BSRAM_D;
wire        SUFAMI_BSRAM_CE_N;
wire        SUFAMI_BSRAM_OE_N;
wire        SUFAMI_BSRAM_WE_N;

generate
if (USE_SUFAMI == 1'b1) begin
SufamiMap SufamiMap
(
	.ENABLE(1'b1),
	.MCLK(MCLK),
	.RST_N(RESET_N),

	.CA(CA),
	.DI(DO),
	.DO(SUFAMI_DO),
	.CPURD_N(CPURD_N),
	.CPUWR_N(CPUWR_N),

	.PA(PA),
	.PARD_N(PARD_N),
	.PAWR_N(PAWR_N),

	.ROMSEL_N(ROMSEL_N),
	.RAMSEL_N(RAMSEL_N),

	.SYSCLKF_CE(SYSCLKF_CE),
	.SYSCLKR_CE(SYSCLKR_CE),
	.REFRESH(REFRESH),

	.IRQ_N(SUFAMI_IRQ_N),

	.ROM_ADDR(SUFAMI_ROM_ADDR),
	.ROM_Q(ROM_Q),
	.ROM_CE_N(SUFAMI_ROM_CE_N),
	.ROM_OE_N(SUFAMI_ROM_OE_N),
	.ROM_WORD(SUFAMI_ROM_WORD),

	.BSRAM_ADDR(SUFAMI_BSRAM_ADDR),
	.BSRAM_D(SUFAMI_BSRAM_D),
	.BSRAM_Q(BSRAM_Q),
	.BSRAM_CE_N(SUFAMI_BSRAM_CE_N),
	.BSRAM_OE_N(SUFAMI_BSRAM_OE_N),
	.BSRAM_WE_N(SUFAMI_BSRAM_WE_N),

	.MAP_ACTIVE(MAP_ACTIVE[6]),
	.MAP_CTRL(ROM_TYPE),
	.ROM_MASK(ROM_MASK),
	.BSRAM_MASK(RAM_MASK),

	.EXT_RTC(EXT_RTC),
	
	.CART_SWAP(SUFAMI_SWAP)
);
end else
assign MAP_ACTIVE[6] = 0;
endgenerate



generate
if (USE_SS == 1'b1) begin
savestates ss
(
	.reset_n(RESET_N),
	.clk(MCLK),

	.save(SS_SAVE),
	.save_sd(SS_TOSD),
	.load(SS_LOAD),
	.slot(SS_SLOT),

	.ram_size(RAM_SIZE),
	.rom_type(ROM_TYPE),

	.sysclkf_ce(SYSCLKF_CE),
	.sysclkr_ce(SYSCLKR_CE),

	.romsel_n(ROMSEL_N),
	.rom_q(ROM_Q),

	.ca(CA),
	.cpurd_n(CPURD_N),
	.cpuwr_n(CPUWR_N),

	.pa(PA),
	.pard_n(PARD_N),
	.pawr_n(PAWR_N),

	.di(DO),
	.ss_do(SS_DO),

	.rom_addr(SS_ROM_ADDR),

	.ddr_di(SS_DDR_DI),
	.ddr_ack(SS_DDR_ACK),
	.ddr_do(SS_DDR_DO),
	.ddr_addr(SS_DDR_ADDR),
	.ddr_we(SS_DDR_WE),
	.ddr_be(SS_DDR_BE),
	.ddr_req(SS_DDR_REQ),

	.ext_addr(SS_EXT_ADDR),

	.spc_di(SS_SPC_DI),
	.aram_sel(SS_ARAM_SEL),
	.dsp_regs_sel(SS_DSP_REGS_SEL),
	.smp_regs_sel(SS_SMP_SEL),

	.ppu_di(SS_PPU_DI),

	.bsram_sel(SS_BSRAM_SEL),
	.bsram_di(BSRAM_Q),

	.dspn_regs_sel(SS_DSPN_REGS_SEL),
	.dspn_ram_sel(SS_DSPN_RAM_SEL),
	.dspn_di(SS_DSPN_DI),

	.gsu_regs_sel(SS_GSU_SEL),
	.gsu_di(SS_GSU_DI),

	.cx4_regs_sel(SS_CX4_SEL),
	.cx4_di(SS_CX4_DI),
	.cx4_cache_sel(SS_CX4_CACHE_SEL),
	.cx4_ss_ok(CX4_SS_OK),

	.sa1_active(MAP_ACTIVE[3]),
	.sa1_a(SA1_P65_A),
	.sa1_di(SA1_P65_DO),
	.sa1_rd_n(SA1_P65_RD_N),
	.sa1_wr_n(SA1_P65_WR_N),
	.sa1_sa1_romsel(SS_SA1_ROMSEL),
	.sa1_sns_romsel(SS_SNS_ROMSEL),

	.ss_do_ovr(SS_DO_OVR),
	.ss_rom_ovr(SS_ROM_OVR),
	.ss_busy(SS_BUSY)
);
end else begin
	assign SS_DO = 0;
	assign SS_ROM_ADDR = 0;
	assign SS_EXT_ADDR = 0;
	assign SS_DDR_DO = 0;
	assign SS_DDR_ADDR = 0;
	assign SS_DDR_WE = 0;
	assign SS_DDR_BE = 0;
	assign SS_DDR_REQ = 0;
	assign SS_ARAM_SEL = 0;
	assign SS_DSP_REGS_SEL = 0;
	assign SS_SMP_SEL = 0;
	assign SS_BSRAM_SEL = 0;
	assign SS_DSPN_REGS_SEL = 0;
	assign SS_DSPN_RAM_SEL = 0;
	assign SS_GSU_SEL = 0;
	assign SS_CX4_SEL = 0;
	assign SS_CX4_CACHE_SEL = 0;
	assign SS_DO_OVR = 0;
	assign SS_ROM_OVR = 0;
	assign SS_BUSY = 0;
end
endgenerate

assign SS_AVAIL = ~|{ROM_TYPE[7:4]} | MAP_ACTIVE[3] | (ROM_TYPE[7:6] == 2'b10) | MAP_ACTIVE[2] | MAP_ACTIVE[0]; // Basic carts + SA1 + DSPn + GSU + CX4

assign TURBO_ALLOW = ~(MAP_ACTIVE[3] | MAP_ACTIVE[1] | SS_BUSY);

always @(*) begin
	case (MAP_ACTIVE)
	'b0000001:
		begin
			DI         = CX4_DO;
			IRQ_N      = CX4_IRQ_N;
			ROM_ADDR   = {1'b0,CX4_ROM_ADDR};
			ROM_D      = 8'h00;
			ROM_CE_N   = CX4_ROM_CE_N;
			ROM_OE_N   = CX4_ROM_OE_N;
			ROM_WE_N   = 1;
			BSRAM_ADDR = CX4_BSRAM_ADDR;
			BSRAM_D    = CX4_BSRAM_D;
			BSRAM_CE_N = CX4_BSRAM_CE_N;
			BSRAM_OE_N = CX4_BSRAM_OE_N;
			BSRAM_WE_N = CX4_BSRAM_WE_N;
			ROM_WORD   = CX4_ROM_WORD;
		end

	'b0000010:
		begin
			DI         = SDD_DO;
			IRQ_N      = SDD_IRQ_N;
			ROM_ADDR   = {1'b0,SDD_ROM_ADDR};
			ROM_D      = 8'h00;
			ROM_CE_N   = SDD_ROM_CE_N;
			ROM_OE_N   = SDD_ROM_OE_N;
			ROM_WE_N   = 1;
			BSRAM_ADDR = SDD_BSRAM_ADDR;
			BSRAM_D    = SDD_BSRAM_D;
			BSRAM_CE_N = SDD_BSRAM_CE_N;
			BSRAM_OE_N = SDD_BSRAM_OE_N;
			BSRAM_WE_N = SDD_BSRAM_WE_N;
			ROM_WORD   = SDD_ROM_WORD;
		end

	'b0000100:
		begin
			DI         = GSU_DO;
			IRQ_N      = GSU_IRQ_N;
			ROM_ADDR   = {1'b0,GSU_ROM_ADDR};
			ROM_D      = 8'h00;
			ROM_CE_N   = GSU_ROM_CE_N;
			ROM_OE_N   = GSU_ROM_OE_N;
			ROM_WE_N   = 1;
			BSRAM_ADDR = GSU_BSRAM_ADDR;
			BSRAM_D    = GSU_BSRAM_D;
			BSRAM_CE_N = GSU_BSRAM_CE_N;
			BSRAM_OE_N = GSU_BSRAM_OE_N;
			BSRAM_WE_N = GSU_BSRAM_WE_N;
			ROM_WORD   = GSU_ROM_WORD;
		end

	'b0001000:
		begin
			DI         = SA1_DO;
			IRQ_N      = SA1_IRQ_N;
			ROM_ADDR   = {1'b0,SA1_ROM_ADDR};
			ROM_D      = 8'h00;
			ROM_CE_N   = SA1_ROM_CE_N;
			ROM_OE_N   = SA1_ROM_OE_N;
			ROM_WE_N   = 1;
			BSRAM_ADDR = SA1_BSRAM_ADDR;
			BSRAM_D    = SA1_BSRAM_D;
			BSRAM_CE_N = SA1_BSRAM_CE_N;
			BSRAM_OE_N = SA1_BSRAM_OE_N;
			BSRAM_WE_N = SA1_BSRAM_WE_N;
			ROM_WORD   = SA1_ROM_WORD;
		end

	'b0010000:
		begin
			DI         = SPC7110_DO;
			IRQ_N      = SPC7110_IRQ_N;
			ROM_ADDR   = {1'b0,SPC7110_ROM_ADDR};
			ROM_D      = 8'h00;
			ROM_CE_N   = SPC7110_ROM_CE_N;
			ROM_OE_N   = SPC7110_ROM_OE_N;
			ROM_WE_N   = 1;
			BSRAM_ADDR = SPC7110_BSRAM_ADDR;
			BSRAM_D    = SPC7110_BSRAM_D;
			BSRAM_CE_N = SPC7110_BSRAM_CE_N;
			BSRAM_OE_N = SPC7110_BSRAM_OE_N;
			BSRAM_WE_N = SPC7110_BSRAM_WE_N;
			ROM_WORD   = SPC7110_ROM_WORD;
		end

	'b0100000:
		begin
			DI         = BSX_DO;
			IRQ_N      = BSX_IRQ_N;
			ROM_ADDR   = {1'b0,BSX_ROM_ADDR};
			ROM_D      = BSX_ROM_D;
			ROM_CE_N   = BSX_ROM_CE_N;
			ROM_OE_N   = BSX_ROM_OE_N;
			ROM_WE_N   = BSX_ROM_WE_N;
			BSRAM_ADDR = BSX_BSRAM_ADDR;
			BSRAM_D    = BSX_BSRAM_D;
			BSRAM_CE_N = BSX_BSRAM_CE_N;
			BSRAM_OE_N = BSX_BSRAM_OE_N;
			BSRAM_WE_N = BSX_BSRAM_WE_N;
			ROM_WORD   = BSX_ROM_WORD;
		end

	'b1000000:
		begin
			DI         = SUFAMI_DO;
			IRQ_N      = SUFAMI_IRQ_N;
			ROM_ADDR   = {1'b0,SUFAMI_ROM_ADDR};
			ROM_D      = 8'h00;
			ROM_CE_N   = SUFAMI_ROM_CE_N;
			ROM_OE_N   = SUFAMI_ROM_OE_N;
			ROM_WE_N   = 1;
			BSRAM_ADDR = SUFAMI_BSRAM_ADDR;
			BSRAM_D    = SUFAMI_BSRAM_D;
			BSRAM_CE_N = SUFAMI_BSRAM_CE_N;
			BSRAM_OE_N = SUFAMI_BSRAM_OE_N;
			BSRAM_WE_N = SUFAMI_BSRAM_WE_N;
			ROM_WORD   = SUFAMI_ROM_WORD;
		end
		
	default:
		begin
			DI         = DLH_DO;
			IRQ_N      = DLH_IRQ_N;
			ROM_ADDR   = DLH_ROM_ADDR;
			ROM_D      = 7'h00;
			ROM_CE_N   = DLH_ROM_CE_N;
			ROM_OE_N   = DLH_ROM_OE_N;
			ROM_WE_N   = 1;
			BSRAM_ADDR = DLH_BSRAM_ADDR;
			BSRAM_D    = DLH_BSRAM_D;
			BSRAM_CE_N = DLH_BSRAM_CE_N;
			BSRAM_OE_N = DLH_BSRAM_OE_N;
			BSRAM_WE_N = DLH_BSRAM_WE_N;
			ROM_WORD   = DLH_ROM_WORD;
		end
	endcase
	
	if(MSU_SEL)   DI = MSU_DO;

	if (SS_ARAM_SEL) begin
		ARAM_ADDR = SS_EXT_ADDR[15:0];
		ARAM_D    = SS_DO;
		ARAM_CE_N = 0;
		ARAM_OE_N = PARD_N;
		ARAM_WE_N = PAWR_N;
	end else begin
		ARAM_ADDR = SNES_ARAM_ADDR;
		ARAM_D    = SNES_ARAM_D;
		ARAM_CE_N = SNES_ARAM_CE_N;
		ARAM_OE_N = SNES_ARAM_OE_N;
		ARAM_WE_N = SNES_ARAM_WE_N;
	end

	if (SS_BSRAM_SEL) begin
		BSRAM_ADDR = SS_EXT_ADDR[19:0];
		BSRAM_D    = SS_DO;
		BSRAM_CE_N = 0;
		BSRAM_OE_N = PARD_N;
		BSRAM_WE_N = PAWR_N;
	end

	if (SS_DO_OVR) begin
		DI         = SS_DO;
	end

	if (SS_ROM_OVR) begin
		ROM_ADDR   = SS_ROM_ADDR;
	end
end

endmodule
