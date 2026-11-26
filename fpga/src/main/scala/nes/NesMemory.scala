package nes

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface
import psx.SdramEngine

object NesMemory {
  object Source {
    val Host = 0
    val Ch0 = 1      // PPU / GameLoader
    val Ch1 = 2      // CPU
    val Ch2 = 3      // Savestate SDRAM RAM copy
    val SaveState = 4 // Savestate 64-bit storage port
    val Streamer = 5  // ROM streamer / BIOS copy / RAM clear
  }
  val SourceBits = 3

  /** 1 KiB rows (512 16-bit columns), bank bits right above the row so PRG/CHR spread across banks. */
  def bank(address: UInt): UInt = address(11, 10)
  def row(address: UInt): UInt = address(24, 12)
  def column(address: UInt): UInt = Cat(address(9, 2), 0.U(1.W))

  /** Single 8-bit channel matching MiSTer NES sdram.sv semantics (edge-triggered rd/wr). */
  class ByteChannel extends Bundle {
    val addr = Input(UInt(25.W))
    val rd = Input(Bool())
    val wr = Input(Bool())
    val din = Input(UInt(8.W))
    val dout = Output(UInt(8.W))
    val busy = Output(Bool())
  }

  /** 64-bit savestate storage channel matching MiSTer NES SAVE_out_*. */
  class SaveStatePort extends Bundle {
    val din = Input(UInt(64.W))
    val dout = Output(UInt(64.W))
    val adr = Input(UInt(26.W))
    val rnw = Input(Bool())
    val ena = Input(Bool())
    val be = Input(UInt(8.W))
    val done = Output(Bool())
  }

  /** Base byte address in SDRAM of the 4 x 2 MiB save-state slots (8 MiB total, 0x180_0000..0x1FF_FFFF). */
  val SaveStateBase = 0x180_0000L
}

/**
 * Single-chip 32 MiB SDRAM memory subsystem for the MiSTer NES core,
 * running at `clk4x` (85.909 MHz, phase-aligned 4:1 with `clk1x` 21.477 MHz).
 *
 * Uses [[psx.SdramEngine]] (IOB-registered pins, open-page bank tracking) so
 * single-word reads complete in 5 `clk4x` cycles on a row hit and 9 on a row
 * miss (~1.25 to 2.25 NES master clocks). Each 8-bit channel (PPU, CPU,
 * savestate RAM) also keeps a coherent 32-bit last-word read cache so
 * sequential byte reads hit in 0 SDRAM cycles.
 *
 * Priority order (highest first): Host (SPI reads have tight dummy-cycle
 * budgets), Ch0 (PPU/loader), Ch1 (CPU), Ch2 (savestate RAM), SaveState
 * (64-bit DDR port), Streamer (setup-only ROM/BIOS/clear engine).
 */
class NesMemory(config: SdramEngine.Config) extends Module {
  import NesMemory._
  require(config.chips == 1)
  require(config.sourceBits == SourceBits)

  val io = IO(new Bundle {
    val clk1x = Input(Clock())
    val reset1x = Input(Bool())

    val host = new MemoryInterface(addressWidth = 25, dataWidth = 32)
    val ch0 = new ByteChannel
    /** True while a Ch0 SDRAM request is waiting to be accepted by SdramEngine (used to pace GameLoader writes). */
    val ch0Pending = Output(Bool())
    val ch1 = new ByteChannel
    val ch2 = new ByteChannel
    val saveState = new SaveStatePort
    val streamer = new MemoryInterface(addressWidth = 25, dataWidth = 32)

    /** Invalidate all channel read caches (e.g. during host transfers or reset). */
    val cacheInvalidate = Input(Bool())

    /** Savestate latch save/restore for ch0_dout (bits 7:0) and ch1_dout (bits 15:8). */
    val ssIn = Input(UInt(16.W))
    val ssLoad = Input(Bool())
    val ssOut = Output(UInt(16.W))

    val pins = new SdramEngine.Pins(1)
    val ready = Output(Bool())
    val rowHit = Output(Bool())
    val rowConflict = Output(Bool())
  })

  val engine = Module(new SdramEngine(config))
  io.pins <> engine.io.pins
  io.ready := engine.io.ready
  io.rowHit := engine.io.rowHit
  io.rowConflict := engine.io.rowConflict

  //////////////////////////////////
  // clk1x phase detection (4:1 ratio)
  //////////////////////////////////
  val toggle1x = withClockAndReset(io.clk1x, io.reset1x) {
    val t = RegInit(false.B)
    t := !t
    t
  }
  val toggle4x = RegNext(toggle1x, false.B)
  val toggle4xPrev = RegNext(toggle4x, false.B)
  /** High on the first clk4x cycle after clk1x registers update. */
  val first = toggle4x =/= toggle4xPrev

  //////////////////////////////////
  // Arbiter
  //////////////////////////////////
  class Offer extends Bundle {
    val valid = Bool()
    val txn = new SdramEngine.Transaction(SourceBits)
  }
  def txnFor(address: UInt, words: UInt, write: Bool, source: Int): SdramEngine.Transaction = {
    val t = Wire(new SdramEngine.Transaction(SourceBits))
    t.chip := 0.U
    t.bank := NesMemory.bank(address)
    t.row := NesMemory.row(address)
    t.column := NesMemory.column(address)
    t.words := words
    t.write := write
    t.source := source.U
    t
  }
  val offers = Wire(Vec(6, new Offer))
  val priority = Seq(Source.Host, Source.Ch0, Source.Ch1, Source.Ch2, Source.SaveState, Source.Streamer)
  val chosen = PriorityMux(priority.map(s => (offers(s).valid, s.U(SourceBits.W))) :+ (true.B, 0.U(SourceBits.W)))
  engine.io.request.valid := offers.map(_.valid).reduce(_ || _)
  engine.io.request.bits := offers(chosen).txn
  def accepted(source: Int): Bool = engine.io.request.ready && engine.io.request.valid && chosen === source.U

  val writeData = Wire(Vec(6, UInt(32.W)))
  val writeMask = Wire(Vec(6, UInt(4.W)))
  writeData := DontCare
  writeMask := DontCare
  engine.io.writeData := writeData(engine.io.writeSource)
  engine.io.writeMask := writeMask(engine.io.writeSource)

  val read = engine.io.read
  def readFor(source: Int): Bool = read.valid && read.bits.source === source.U

  // Global write snoop for cache coherence across all channels: whenever any
  // write is accepted by the engine (or cacheInvalidate is high), invalidate
  // any channel's 32-bit last-word cache matching that word address.
  val snoopInvalidateAll = RegInit(true.B)
  when (first) {
    snoopInvalidateAll := io.cacheInvalidate
  }
  val snoopWriteValid = engine.io.request.ready && engine.io.request.valid && engine.io.request.bits.write
  val snoopWriteWord = Cat(
    engine.io.request.bits.row,
    engine.io.request.bits.bank,
    engine.io.request.bits.column(8, 1),
  ) // 23-bit 32-bit word address = byteAddr(24, 2)

  //////////////////////////////////
  // Helper: 32-bit clk1x MemoryInterface (Host & Streamer)
  //////////////////////////////////
  def wireWordPort(port: MemoryInterface, source: Int): Unit = {
    val active = RegInit(false.B)
    val acceptedReg = RegInit(false.B)
    val doneFlag = RegInit(false.B)
    val reqAddr = Reg(UInt(25.W))
    val reqWrite = Reg(Bool())
    val reqData = Reg(UInt(32.W))
    val reqMask = Reg(UInt(4.W))
    val respData = Reg(UInt(32.W))

    val done1x = withClockAndReset(io.clk1x, io.reset1x) { RegNext(doneFlag, false.B) }
    when (first && done1x) {
      doneFlag := false.B
    }
    port.done := done1x
    port.dataRead := withClock(io.clk1x) { RegEnable(respData, doneFlag && !done1x) }

    when (first && port.enable && !active && !doneFlag && !done1x) {
      active := true.B
      acceptedReg := false.B
      reqAddr := port.address
      reqWrite := port.write
      reqData := port.dataWrite
      reqMask := port.writeStrobe
    }

    val o = offers(source)
    o.valid := active && !acceptedReg
    o.txn := txnFor(reqAddr, 0.U, reqWrite, source)
    writeData(source) := reqData
    writeMask(source) := reqMask

    when (accepted(source)) {
      acceptedReg := true.B
      when (reqWrite) {
        active := false.B
        doneFlag := true.B
      }
    }
    when (readFor(source)) {
      respData := read.bits.data
      active := false.B
      doneFlag := true.B
    }
  }

  wireWordPort(io.host, Source.Host)
  wireWordPort(io.streamer, Source.Streamer)

  //////////////////////////////////
  // Helper: 8-bit NES channel (Ch0 PPU, Ch1 CPU, Ch2 Savestate RAM)
  //////////////////////////////////
  def wireByteChannel(
    ch: ByteChannel,
    source: Int,
    ssRestore: Option[UInt] = None,
  ): (UInt, Bool) = {
    val oldRd = RegInit(false.B)
    val oldWr = RegInit(false.B)
    val pending = RegInit(false.B)
    val inFlightRead = RegInit(false.B)
    val reqAddr = Reg(UInt(25.W))
    val reqWrite = Reg(Bool())
    val reqData = Reg(UInt(8.W))

    val cacheValid = RegInit(false.B)
    val cacheWord = Reg(UInt(23.W))
    val cacheData = Reg(UInt(32.W))
    val dout = RegInit(0.U(8.W))
    // 2-bit generation counter tracking clk4x updates to `dout` until clk1x
    // latches them into `dout1x` (acknowledged on clk4x via `first`).
    val doutGen = RegInit(0.U(2.W))
    val dout1x = withClockAndReset(io.clk1x, io.reset1x) { RegNext(dout, 0.U(8.W)) }
    val doutGen1x = withClockAndReset(io.clk1x, io.reset1x) { RegNext(doutGen, 0.U(2.W)) }
    val doutGenAck = RegInit(0.U(2.W))
    when (first) {
      doutGenAck := doutGen1x
    }

    when (snoopInvalidateAll) {
      cacheValid := false.B
    } .elsewhen (snoopWriteValid && cacheValid && cacheWord === snoopWriteWord) {
      cacheValid := false.B
    }

    // Edge detect on clk1x-domain rd/wr signals. Hold oldRd/oldWr while signal
    // stays high so a multi-cycle pulse triggers exactly one transaction.
    val rdRise = ch.rd && !oldRd
    val wrRise = ch.wr && !oldWr
    when (first) {
      oldRd := ch.rd
      oldWr := ch.wr
    }

    val start = first && (rdRise || wrRise)
    val isWr = wrRise
    val wordAddr = ch.addr(24, 2)
    val byteIdx = ch.addr(1, 0)
    val cacheHit = !isWr && cacheValid && cacheWord === wordAddr && !(snoopWriteValid && snoopWriteWord === wordAddr) && !snoopInvalidateAll

    when (start) {
      when (cacheHit) {
        dout := cacheData.asTypeOf(Vec(4, UInt(8.W)))(byteIdx)
        doutGen := doutGen + 1.U
      } .otherwise {
        pending := true.B
        reqAddr := ch.addr
        reqWrite := isWr
        reqData := ch.din
        when (isWr) {
          // Match sdram.sv: a write also updates dout to the written byte.
          dout := ch.din
          doutGen := doutGen + 1.U
        }
      }
    }

    val o = offers(source)
    o.valid := pending
    o.txn := txnFor(reqAddr, 0.U, reqWrite, source)
    writeData(source) := Fill(4, reqData)
    writeMask(source) := UIntToOH(reqAddr(1, 0), 4)

    val inflightByte = Reg(UInt(2.W))
    val inflightWord = Reg(UInt(23.W))
    when (accepted(source)) {
      pending := false.B
      when (!reqWrite) {
        inFlightRead := true.B
        inflightByte := reqAddr(1, 0)
        inflightWord := reqAddr(24, 2)
      }
    }
    when (readFor(source)) {
      inFlightRead := false.B
      dout := read.bits.data.asTypeOf(Vec(4, UInt(8.W)))(inflightByte)
      doutGen := doutGen + 1.U
      // Install into this channel's 32-bit last-word cache unless invalidated
      // by a concurrent write to the same word.
      when (!snoopInvalidateAll && !(snoopWriteValid && snoopWriteWord === inflightWord)) {
        cacheValid := true.B
        cacheWord := inflightWord
        cacheData := read.bits.data
      }
    }

    // Savestate restore of ch0/ch1 output latches (MiSTer sdram.sv ss_load)
    ssRestore.foreach { value =>
      when (first && io.ssLoad) {
        dout := value
        doutGen := doutGen + 1.U
        cacheValid := false.B
      }
    }

    ch.dout := dout1x
    ch.busy := pending || inFlightRead || (doutGen =/= doutGenAck)
    (dout1x, pending)
  }

  val (ch0Dout, ch0Pending) = wireByteChannel(io.ch0, Source.Ch0, Some(io.ssIn(7, 0)))
  val (ch1Dout, _) = wireByteChannel(io.ch1, Source.Ch1, Some(io.ssIn(15, 8)))
  wireByteChannel(io.ch2, Source.Ch2, None)
  io.ch0Pending := ch0Pending
  io.ssOut := Cat(ch1Dout, ch0Dout)

  //////////////////////////////////
  // 64-bit Savestate storage port (2 x 32-bit burst at 0x180_0000..0x1FF_FFFF)
  //////////////////////////////////
  locally {
    val active = RegInit(false.B)
    val acceptedReg = RegInit(false.B)
    val doneFlag = RegInit(false.B)
    val reqAddr = Reg(UInt(25.W))
    val reqWrite = Reg(Bool())
    val reqData = Reg(UInt(64.W))
    val reqBe = Reg(UInt(8.W))
    val respLo = Reg(UInt(32.W))
    val respHi = Reg(UInt(32.W))

    val done1x = withClockAndReset(io.clk1x, io.reset1x) { RegNext(doneFlag, false.B) }
    when (first && done1x) {
      doneFlag := false.B
    }
    io.saveState.done := done1x
    io.saveState.dout := withClock(io.clk1x) { RegEnable(Cat(respHi, respLo), doneFlag && !done1x) }

    // Map 26-bit DWORD address from statemanager (0x0380_0000 + slot*0x80000)
    // into 8 MiB SDRAM region 0x180_0000..0x1FF_FFFF.
    val mappedAddr = SaveStateBase.U(25.W) + Cat(io.saveState.adr(20, 0), 0.U(2.W))

    when (first && io.saveState.ena && !active && !doneFlag && !done1x) {
      active := true.B
      acceptedReg := false.B
      reqAddr := mappedAddr
      reqWrite := !io.saveState.rnw
      reqData := io.saveState.din
      reqBe := io.saveState.be
    }

    val o = offers(Source.SaveState)
    o.valid := active && !acceptedReg
    // 2 words (words = 1) -> 64 bits in a single aligned 4-beat burst
    o.txn := txnFor(reqAddr, 1.U, reqWrite, Source.SaveState)
    writeData(Source.SaveState) := Mux(engine.io.writeIndex === 0.U, reqData(31, 0), reqData(63, 32))
    writeMask(Source.SaveState) := Mux(engine.io.writeIndex === 0.U, reqBe(3, 0), reqBe(7, 4))

    when (accepted(Source.SaveState)) {
      acceptedReg := true.B
    }
    // Complete write when the second word is taken by the engine
    when (active && acceptedReg && reqWrite && engine.io.writeTake &&
      engine.io.writeSource === Source.SaveState.U && engine.io.writeIndex === 1.U) {
      active := false.B
      doneFlag := true.B
    }
    when (readFor(Source.SaveState)) {
      when (read.bits.index === 0.U) {
        respLo := read.bits.data
      } .otherwise {
        respHi := read.bits.data
        active := false.B
        doneFlag := true.B
      }
    }
  }
}
