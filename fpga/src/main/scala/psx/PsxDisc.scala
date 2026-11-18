package psx

import chisel3._
import chisel3.experimental.ExtModule
import chisel3.util._
import lib.mem.MemoryInterface
import net.gamebub.framework.interface.HostV0

object PsxDisc {
  val SectorBytes = 2352
  val SectorWords = SectorBytes / 4
  /** The sector buffer (words): a sector, and a cue sheet's chunks. */
  val SectorBufferWords = 1024
  /** The sub-CPU's RAM: its program, data and stack. */
  val RamBytes = 16 * 1024
  val RamWords = RamBytes / 4

  /** Region codes, as the core's `region` input takes them. */
  object Region {
    val Us = 0
    val Japan = 1
    val Europe = 2
  }

  /** The sub-CPU's address space, by address bits 25-24. */
  object Space {
    val Ram = 0
    val SectorBuffer = 1
    val Registers = 2
  }

  /** The sub-CPU's registers (see the program, `fpga/cpu/psx-disc/src/main.rs`). */
  object Regs {
    val CmdWords = 0x00
    val CmdCtrl = 0x18
    val Events = 0x20
    val Status = 0x24
    val HpsLba = 0x28
    val Stream = 0x2C
    val TrackinfoData = 0x30
    val TrackinfoAddr = 0x34
    val Control = 0x38
    val Timer = 0x3C
    val Debug = 0x40
    val DebugLba = 0x44
  }

  /**
   * The program (`fpga/cpu/psx-disc`, built into the resources as
   * `psx_disc.bin`), as little-endian words.
   */
  lazy val programWords: Seq[Long] = {
    val stream = getClass.getClassLoader.getResourceAsStream("psx_disc.bin")
    require(stream != null, "psx_disc.bin is missing from the resources (see fpga/cpu/psx-disc/README.md)")
    val bytes = try stream.readAllBytes() finally stream.close()
    bytes.map(_ & 0xFF).padTo((bytes.length + 3) / 4 * 4, 0).grouped(4).map { b =>
      b(0).toLong | (b(1).toLong << 8) | (b(2).toLong << 16) | (b(3).toLong << 24)
    }.toSeq
  }
}

/** The PicoRV32's ports (the disc reader uses the native memory interface only). */
class PicoRV32Io extends Bundle {
  val clk = Input(Clock())
  val resetn = Input(Bool())
  val trap = Output(Bool())
  val mem_valid = Output(Bool())
  val mem_instr = Output(Bool())
  val mem_ready = Input(Bool())
  val mem_addr = Output(UInt(32.W))
  val mem_wdata = Output(UInt(32.W))
  val mem_wstrb = Output(UInt(4.W))
  val mem_rdata = Input(UInt(32.W))
  val mem_la_read = Output(Bool())
  val mem_la_write = Output(Bool())
  val mem_la_addr = Output(UInt(32.W))
  val mem_la_wdata = Output(UInt(32.W))
  val mem_la_wstrb = Output(UInt(4.W))
  val pcpi_valid = Output(Bool())
  val pcpi_insn = Output(UInt(32.W))
  val pcpi_rs1 = Output(UInt(32.W))
  val pcpi_rs2 = Output(UInt(32.W))
  val pcpi_wr = Input(Bool())
  val pcpi_rd = Input(UInt(32.W))
  val pcpi_wait = Input(Bool())
  val pcpi_ready = Input(Bool())
  val irq = Input(UInt(32.W))
  val eoi = Output(UInt(32.W))
  val trace_valid = Output(Bool())
  val trace_data = Output(UInt(36.W))
}

object PicoRV32 {
  /** RV32IMC, as the boot core has it (the program is built for riscv32imc). */
  val Parameters: Map[String, chisel3.experimental.Param] = Map(
    "BARREL_SHIFTER" -> 1,
    "ENABLE_MUL" -> 1,
    "ENABLE_DIV" -> 1,
    "COMPRESSED_ISA" -> 1,
  ).map { case (k, v) => k -> chisel3.experimental.IntParam(v) }

  /** `fpga/verilog/picorv32.v`, found from the working directory up (for simulation). */
  def source: String = {
    val roots = Option(System.getenv("MILL_WORKSPACE_ROOT")).toSeq.map(new java.io.File(_)) ++
      Iterator.iterate(new java.io.File(System.getProperty("user.dir")).getAbsoluteFile)(_.getParentFile)
        .takeWhile(_ != null).toSeq
    val file = roots.flatMap(r => Seq(new java.io.File(r, "fpga/verilog/picorv32.v"), new java.io.File(r, "verilog/picorv32.v")))
      .find(_.isFile)
      .getOrElse(throw new IllegalStateException("picorv32.v not found above " + System.getProperty("user.dir")))
    val s = scala.io.Source.fromFile(file)
    try s.mkString finally s.close()
  }
}

/** The PicoRV32 in a build: `verilog/picorv32.v` is in every target's file list. */
class PicoRV32Ext extends ExtModule(PicoRV32.Parameters) {
  override def desiredName = "picorv32"
  val io = FlatIO(new PicoRV32Io)
}

/** The PicoRV32 in simulation: its source inlined. */
class PicoRV32Sim extends BlackBox(PicoRV32.Parameters) with HasBlackBoxInline {
  override def desiredName = "picorv32"
  val io = IO(new PicoRV32Io)
  setInline("picorv32.v", PicoRV32.source)
}

/**
 * The disc reader's RAM: single-port, byte write enables, read-first, one
 * cycle; it holds `init` (the program) from configuration on.
 */
class PsxDiscRam(init: Seq[Long], depth: Int) extends BlackBox with HasBlackBoxInline {
  require(init.length <= depth, s"the program (${init.length * 4} bytes) does not fit in the RAM (${depth * 4} bytes)")
  val addressWidth = log2Ceil(depth)
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val en = Input(Bool())
    val we = Input(UInt(4.W))
    val addr = Input(UInt(addressWidth.W))
    val din = Input(UInt(32.W))
    val dout = Output(UInt(32.W))
  })
  private val words = init.zipWithIndex.filter(_._1 != 0).map { case (w, i) => f"    mem[$i] = 32'h$w%08x;" }
  setInline("PsxDiscRam.sv",
    s"""// The PSX disc reader's RAM, holding its program from configuration on
       |// (generated by psx.PsxDisc from psx_disc.bin).
       |module PsxDiscRam(
       |  input clk,
       |  input en,
       |  input [3:0] we,
       |  input [${addressWidth - 1}:0] addr,
       |  input [31:0] din,
       |  output reg [31:0] dout
       |);
       |  (* ram_style = "block" *) reg [31:0] mem [0:${depth - 1}];
       |  initial begin
       |`ifndef SYNTHESIS
       |    for (int i = 0; i < $depth; i++) mem[i] = 32'h0;
       |`endif
       |${words.mkString("\n")}
       |  end
       |  always @(posedge clk) begin
       |    if (en) begin
       |      for (int i = 0; i < 4; i++)
       |        if (we[i]) mem[addr][8 * i +: 8] <= din[8 * i +: 8];
       |      dout <= mem[addr];
       |    end
       |  end
       |endmodule
       |""".stripMargin)
}

/**
 * The PSX's CD drive, fed from a disc image on the SD card through the
 * framework's on-demand file requests (in the clk1x domain, the module's
 * clock): a sub-CPU (a PicoRV32 with 16 KiB of RAM) runs the disc reader,
 * `fpga/cpu/psx-disc`, which does what the MiSTer's firmware does for its
 * PSX core. Any firmware with on-demand files serves it.
 *
 * - When the core is first started (`start`, CoreRun), it is held in reset
 *   (`holdCore`) while the reader finds the image (the cue sheet named like
 *   the selected disc file, file 10, with the image named like it, file 11;
 *   or that image alone), parses it and reads the region from the license
 *   sector (154).
 * - Every time the core leaves reset, the reader gives it its track table
 *   through its `trackinfo` port (MiSTer's `disk_t` layout; the core ignores
 *   the port in reset), and again when the host signals another disc while
 *   it runs (`discChanged`): the core's drive takes that as a disc change.
 * - Sectors: when the core asks for one (`hpsReq`, `hpsLba`), the reader
 *   has the firmware write it into the sector buffer (host address
 *   0x7000_0000, `sector`), and the glue acknowledges and streams it into
 *   the core as 1176 halfwords, low half of each word first (the bytes in
 *   file order).
 *
 * Core -> host requests: the reader sets the six words (`commandWords`, the
 * host reads them at 0xF000_1000) and raises `commandCore`; the host writes
 * its three result words back into them (`commandWrite`).
 */
class PsxDisc(simulation: Boolean = false) extends Module {
  import PsxDisc._

  val io = IO(new Bundle {
    /** The sector buffer, as the host reads and writes it. */
    val sector = new MemoryInterface(addressWidth = 12, dataWidth = 32)
    val commandCore = Flipped(new HostV0.CommandChannel)
    val commandWords = Output(Vec(6, UInt(32.W)))
    /** The host writes a request word (its result words). */
    val commandWrite = Input(Valid(new Bundle {
      val index = UInt(3.W)
      val data = UInt(32.W)
    }))

    /** Pulse: the host has put another disc in (a disc file write). */
    val discChanged = Input(Bool())
    /** Pulse: the core is started (CoreRun). */
    val start = Input(Bool())
    /** Keep the core in reset (the disc is being read). */
    val holdCore = Output(Bool())
    /** The core is in reset. */
    val coreReset = Input(Bool())
    /** A disc can be read. */
    val hasDisc = Output(Bool())
    /** The disc's region, once read (valid: it was recognized). */
    val region = Output(Valid(UInt(2.W)))

    // The core's CD ports
    val hpsReq = Input(Bool())
    val hpsLba = Input(UInt(32.W))
    val hpsAck = Output(Bool())
    val hpsWrite = Output(Bool())
    val hpsData = Output(UInt(16.W))
    val trackinfoData = Output(UInt(32.W))
    val trackinfoAddr = Output(UInt(9.W))
    val trackinfoWrite = Output(Bool())

    /** Statistics: sectors read, failed reads, the longest wait for one (cycles); each cleared by its pulse. */
    val sectorReads = Output(UInt(32.W))
    val readErrors = Output(UInt(32.W))
    val worstWait = Output(UInt(32.W))
    val clearSectorReads = Input(Bool())
    val clearReadErrors = Input(Bool())
    val clearWorstWait = Input(Bool())
    /** Debug: the reader's debug word (bit 31: the CPU has trapped); the last LBA asked for. */
    val debugState = Output(UInt(32.W))
    val lastLba = Output(UInt(32.W))
  })

  //////////////////////////////////
  // CPU and memories
  //////////////////////////////////
  val cpu = if (simulation) Module(new PicoRV32Sim).io else Module(new PicoRV32Ext).io
  cpu.clk := clock
  cpu.resetn := !reset.asBool
  cpu.pcpi_wr := false.B
  cpu.pcpi_rd := 0.U
  cpu.pcpi_wait := false.B
  cpu.pcpi_ready := false.B
  cpu.irq := 0.U

  val ram = Module(new PsxDiscRam(programWords, RamWords))
  val sectorRam = SRAM.masked(SectorBufferWords, Vec(4, UInt(8.W)), numReadPorts = 1, numWritePorts = 0, numReadwritePorts = 1)
  val sectorPort = sectorRam.readwritePorts(0)
  val streamPort = sectorRam.readPorts(0)
  streamPort.enable := true.B
  streamPort.address := 0.U

  // An access takes two cycles: issued in the first (the sector buffer
  // waits while the host uses it), answered (`mem_ready`) in the second.
  val space = cpu.mem_addr(25, 24)
  val ready = RegInit(false.B)
  val hostSector = io.sector.enable
  val issue = cpu.mem_valid && !ready && !(space === Space.SectorBuffer.U && hostSector)
  val write = cpu.mem_wstrb.orR
  ready := issue
  val issuedSpace = RegEnable(space, 0.U, issue)
  val registerRead = Reg(UInt(32.W))
  cpu.mem_ready := ready
  cpu.mem_rdata := MuxLookup(issuedSpace, registerRead)(Seq(
    Space.Ram.U -> ram.io.dout,
    Space.SectorBuffer.U -> sectorPort.readData.asUInt,
  ))

  ram.io.clk := clock
  ram.io.en := issue && space === Space.Ram.U
  ram.io.we := Mux(write, cpu.mem_wstrb, 0.U)
  ram.io.addr := cpu.mem_addr(log2Ceil(RamBytes) - 1, 2)
  ram.io.din := cpu.mem_wdata

  val cpuSector = issue && space === Space.SectorBuffer.U
  sectorPort.enable := hostSector || cpuSector
  sectorPort.address := Mux(hostSector, io.sector.address >> 2, cpu.mem_addr(log2Ceil(SectorBufferWords) + 1, 2))
  sectorPort.isWrite := Mux(hostSector, io.sector.write, write)
  sectorPort.writeData := Mux(hostSector, io.sector.dataWrite, cpu.mem_wdata).asTypeOf(sectorPort.writeData)
  sectorPort.mask.get := Mux(hostSector, "b1111".U, cpu.mem_wstrb).asBools
  io.sector.dataRead := sectorPort.readData.asUInt
  io.sector.done := RegNext(hostSector, false.B)

  //////////////////////////////////
  // Registers
  //////////////////////////////////
  val regWrite = issue && space === Space.Registers.U && write
  val regRead = issue && space === Space.Registers.U && !write
  val regAddress = cpu.mem_addr(7, 0)
  def written(address: Int): Bool = regWrite && regAddress === address.U

  // Core -> host requests
  val words = RegInit(VecInit(Seq.fill(6)(0.U(32.W))))
  val request = RegInit(false.B)
  io.commandWords := words
  io.commandCore.request := request
  for (i <- 0 until 6) {
    when (written(Regs.CmdWords + 4 * i)) { words(i) := cpu.mem_wdata }
  }
  when (io.commandWrite.valid && io.commandWrite.bits.index < 6.U) {
    words(io.commandWrite.bits.index) := io.commandWrite.bits.data
  }
  when (written(Regs.CmdCtrl)) { request := cpu.mem_wdata(0) }
  val commandStatus = Cat(io.commandCore.error, io.commandCore.done, io.commandCore.busy, request)

  // Events (sticky, cleared by writing ones)
  val evStart = RegInit(false.B)
  val evLeftReset = RegInit(false.B)
  val evDiscChanged = RegInit(false.B)
  val evSector = RegInit(false.B)
  when (written(Regs.Events)) {
    when (cpu.mem_wdata(0)) { evStart := false.B }
    when (cpu.mem_wdata(1)) { evLeftReset := false.B }
    when (cpu.mem_wdata(2)) { evDiscChanged := false.B }
  }
  when (io.start) { evStart := true.B }
  when (RegNext(io.coreReset, true.B) && !io.coreReset) { evLeftReset := true.B }
  when (io.discChanged) { evDiscChanged := true.B }
  val sectorAsked = io.hpsReq && !RegNext(io.hpsReq, false.B)

  // Control: the core is held, with no disc, until the reader has run.
  val hold = RegInit(true.B)
  val hasDisc = RegInit(false.B)
  val region = RegInit(0.U.asTypeOf(Valid(UInt(2.W))))
  when (written(Regs.Control)) {
    hold := cpu.mem_wdata(0)
    hasDisc := cpu.mem_wdata(1)
    region.valid := cpu.mem_wdata(4)
    region.bits := cpu.mem_wdata(6, 5)
  }
  io.holdCore := hold
  io.hasDisc := hasDisc
  io.region := region

  // Track table words
  val trackinfoData = Reg(UInt(32.W))
  when (written(Regs.TrackinfoData)) { trackinfoData := cpu.mem_wdata }
  io.trackinfoWrite := RegNext(written(Regs.TrackinfoAddr), false.B)
  io.trackinfoAddr := RegEnable(cpu.mem_wdata(8, 0), 0.U, written(Regs.TrackinfoAddr))
  io.trackinfoData := trackinfoData

  val timer = RegInit(0.U(32.W))
  timer := timer + 1.U
  val debug = RegInit(0.U(32.W))
  val debugLba = RegInit(0.U(32.W))
  when (written(Regs.Debug)) { debug := cpu.mem_wdata }
  when (written(Regs.DebugLba)) { debugLba := cpu.mem_wdata }
  io.debugState := Cat(cpu.trap, debug(30, 0))
  io.lastLba := debugLba

  //////////////////////////////////
  // Sectors into the core
  //////////////////////////////////
  object StreamState extends ChiselEnum {
    val idle, ack, stream = Value
  }
  val streamState = RegInit(StreamState.idle)
  val streaming = streamState =/= StreamState.idle
  val halfword = Reg(UInt(11.W))
  val streamValid = RegInit(false.B)
  val streamHigh = Reg(Bool())
  streamValid := false.B
  io.hpsAck := false.B
  val startStream = written(Regs.Stream) && !streaming
  switch (streamState) {
    is (StreamState.idle) {
      when (startStream) { streamState := StreamState.ack }
    }
    is (StreamState.ack) {
      // The core takes the data from the cycle after the acknowledge.
      io.hpsAck := true.B
      halfword := 0.U
      streamState := StreamState.stream
    }
    is (StreamState.stream) {
      streamPort.address := halfword >> 1
      streamValid := true.B
      streamHigh := halfword(0)
      halfword := halfword + 1.U
      when (halfword === (SectorBytes / 2 - 1).U) {
        streamState := StreamState.idle
      }
    }
  }
  io.hpsWrite := streamValid
  io.hpsData := Mux(streamHigh, streamPort.data.asUInt(31, 16), streamPort.data.asUInt(15, 0))
  when (startStream) { evSector := false.B }
  when (sectorAsked) { evSector := true.B }

  // Statistics
  val sectorReads = RegInit(0.U(32.W))
  val readErrors = RegInit(0.U(32.W))
  val worstWait = RegInit(0.U(32.W))
  val waitCycles = RegInit(0.U(32.W))
  when (sectorAsked) {
    waitCycles := 0.U
  } .elsewhen (evSector && waitCycles =/= ~0.U(32.W)) {
    waitCycles := waitCycles + 1.U
  }
  when (io.clearSectorReads) {
    sectorReads := 0.U
  } .elsewhen (startStream) {
    sectorReads := sectorReads + 1.U
  }
  when (io.clearReadErrors) {
    readErrors := 0.U
  } .elsewhen (startStream && cpu.mem_wdata(0)) {
    readErrors := readErrors + 1.U
  }
  when (io.clearWorstWait) {
    worstWait := 0.U
  } .elsewhen (startStream && waitCycles > worstWait) {
    worstWait := waitCycles
  }
  io.sectorReads := sectorReads
  io.readErrors := readErrors
  io.worstWait := worstWait

  // Register reads (answered in the next cycle)
  when (regRead) {
    registerRead := MuxLookup(regAddress, 0.U)(
      (0 until 6).map(i => (Regs.CmdWords + 4 * i).U -> words(i)) ++ Seq(
        Regs.CmdCtrl.U -> commandStatus,
        Regs.Events.U -> Cat(evSector, evDiscChanged, evLeftReset, evStart),
        Regs.Status.U -> Cat(streaming, io.coreReset),
        Regs.HpsLba.U -> io.hpsLba,
        Regs.Control.U -> Cat(region.bits, region.valid, 0.U(2.W), hasDisc, hold),
        Regs.Timer.U -> timer,
        Regs.Debug.U -> debug,
        Regs.DebugLba.U -> debugLba,
      ))
  }
}
