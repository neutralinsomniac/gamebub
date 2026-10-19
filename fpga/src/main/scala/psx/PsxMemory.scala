package psx

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface
import lib.fifo.RelatedClockFifo

object PsxMemory {
  /** Transaction sources (the engine's tags), in no particular order; see the arbiter for priorities. */
  object Source {
    val DmaFifo = 0
    val Cpu = 1
    val Ddram = 2
    val Display = 3
    val Host = 4
  }
  val SourceBits = 3

  /**
   * Chip byte address -> bank, row, column: 1 KiB rows (512 16-bit
   * columns), the bank from the two address bits above the row, so that a
   * sequential stream moves to the next bank every kilobyte (and a VRAM line,
   * 2 KiB, spans two banks).
   */
  def bank(address: UInt): UInt = address(11, 10)
  def row(address: UInt): UInt = address(24, 12)
  def column(address: UInt): UInt = address(9, 1)

  /**
   * Main RAM requests from the core's memory multiplexer / DMA (the
   * MiSTer `sdram.sv` channels 1 and 2): `ena` pulses for one `clk1x` cycle
   * with the other inputs valid.
   */
  class MainRam extends Bundle {
    val ena = Input(Bool())
    val adr = Input(UInt(25.W))
    val rnw = Input(Bool())
    val be = Input(UInt(4.W))
    val dataWrite = Input(UInt(32.W))
    val dma = Input(Bool())
    val cntDma = Input(UInt(2.W))
    val cache = Input(Bool())
    /** `clk1x`: the request is complete (a read's data in `dataRead`). */
    val done = Output(Bool())
    val dataRead = Output(UInt(32.W))
    /** `clk1x`: a DMA read was accepted (the next may be requested) / one of its words. */
    val dmaReqProcessed = Output(Bool())
    val dmaWr = Output(Bool())
    val dmaData = Output(UInt(32.W))
    /** `clk3x`: instruction cache fill. */
    val cacheWr = Output(UInt(4.W))
    val cacheData = Output(UInt(32.W))
    val cacheAddr = Output(UInt(8.W))
    /** `clk3x`: the core's DMA write FIFO (first-word fall-through). */
    val dmafifoAdr = Input(UInt(23.W))
    val dmafifoData = Input(UInt(32.W))
    val dmafifoEmpty = Input(Bool())
    val dmafifoRead = Output(Bool())
  }

  /** The core's DDR3 port (Avalon-MM, 64-bit words, `clk2x`). */
  class Ddram extends Bundle {
    val busy = Output(Bool())
    val burstCnt = Input(UInt(8.W))
    val addr = Input(UInt(29.W))
    val dout = Output(UInt(64.W))
    val doutReady = Output(Bool())
    val rd = Input(Bool())
    val din = Input(UInt(64.W))
    val be = Input(UInt(8.W))
    val we = Input(Bool())
  }

  /** A display fetch: `words` + 1 32-bit words from a chip 1 byte address, within one 1 KiB row. */
  class DisplayFetch extends Bundle {
    val address = UInt(25.W)
    val words = UInt(4.W)
  }

  /** Words of display fetches, in order. */
  class DisplayWord extends Bundle {
    val data = UInt(32.W)
    val index = UInt(4.W)
    val last = Bool()
  }

  /**
   * The core's DDR3 byte address -> chip 1 byte address. The core uses
   * VRAM at 0 (1 MiB), the memory cards at 1 and 2 MiB and the save states
   * at 224 MiB (4 x 4 MiB); the latter go to 16 MiB.
   */
  def ddramToChip(byteAddress: UInt): UInt = {
    Mux(byteAddress(27, 24) === 0xE.U, Cat(1.U(1.W), byteAddress(23, 0)), Cat(0.U(1.W), byteAddress(23, 0)))
  }
}

/**
 * The PSX core's external memory on the two SDRAM chips of the rev 4 board,
 * at the core's `clk3x` (the module's clock):
 * - chip 0: main RAM (2 MiB at 0) and BIOS (512 KiB at 8 MiB) through the
 *   core's main RAM interface, with the semantics of the MiSTer's
 *   `sdram.sv` (see [[PsxMemory.MainRam]]), and the DMA write FIFO;
 * - chip 1: what the core keeps in the MiSTer's DDR3 (VRAM, memory cards,
 *   save states) through its DDR3 port, which runs at `clk2x` and crosses
 *   over through FIFOs;
 * - both: the host (`host`, byte addresses, bit 25 selects the chip; for
 *   loading the BIOS and the memory cards while the core is held).
 *
 * `clk1x` and `clk3x` come from the same MMCM, `clk3x` exactly three times
 * `clk1x` and phase-aligned with it: signals cross between them directly
 * (timed by Vivado as one `clk3x` cycle), using the first `clk3x` cycle of
 * each `clk1x` cycle (`first`) to see a `clk1x` pulse exactly once, as the
 * MiSTer does.
 *
 * The engine runs one transaction at a time; the arbiter picks the next in
 * the order DMA write FIFO (it must drain before the CPU reads), CPU / DMA,
 * display, DDR3 port, host. Long DDR3 reads are cut into runs of
 * `ddramChunkWords` so that the CPU does not wait behind a whole VRAM line.
 */
class PsxMemory(config: SdramEngine.Config, ddramChunkWords: Int = 16) extends Module {
  import PsxMemory._
  require(config.sourceBits == SourceBits)

  val io = IO(new Bundle {
    val clk1x = Input(Clock())
    val reset1x = Input(Bool())
    val clk2x = Input(Clock())
    val reset2x = Input(Bool())

    val ram = new MainRam
    val ddram = new Ddram
    val host = new MemoryInterface(addressWidth = 26, dataWidth = 32)
    /** Display scanout fetches (clk3x). */
    val display = new Bundle {
      val request = Flipped(Decoupled(new DisplayFetch))
      val word = Valid(new DisplayWord)
    }

    val pins = new SdramEngine.Pins(2)
    val ready = Output(Bool())
    /** Statistics pulses (`clk3x`). */
    val rowHit = Output(Bool())
    val rowConflict = Output(Bool())
  })

  val engine = Module(new SdramEngine(config))
  io.pins <> engine.io.pins
  io.ready := engine.io.ready
  io.rowHit := engine.io.rowHit
  io.rowConflict := engine.io.rowConflict

  //////////////////////////////////
  // clk1x phase
  //////////////////////////////////
  val toggle1x = withClockAndReset(io.clk1x, io.reset1x) {
    val t = RegInit(false.B)
    t := !t
    t
  }
  val toggle3x = RegNext(toggle1x, false.B)
  val toggle3xDelayed = RegNext(toggle3x, false.B)
  /** The first clk3x cycle of a clk1x cycle (clk1x registers have just changed). */
  val first = RegNext(toggle3xDelayed === toggle1x, false.B)

  //////////////////////////////////
  // Arbiter
  //////////////////////////////////
  class Offer extends Bundle {
    val valid = Bool()
    val txn = new SdramEngine.Transaction(SourceBits)
  }
  def noOffer: Offer = {
    val o = Wire(new Offer)
    o.valid := false.B
    o.txn := DontCare
    o
  }
  def txnFor(chip: UInt, address: UInt, words: UInt, write: Bool, source: Int): SdramEngine.Transaction = {
    val t = Wire(new SdramEngine.Transaction(SourceBits))
    t.chip := chip
    t.bank := PsxMemory.bank(address)
    t.row := PsxMemory.row(address)
    t.column := PsxMemory.column(address)
    t.words := words
    t.write := write
    t.source := source.U
    t
  }
  val offers = Wire(Vec(5, new Offer))
  // Priority order of the sources (first wins).
  val priority = Seq(Source.DmaFifo, Source.Cpu, Source.Display, Source.Ddram, Source.Host)
  val chosen = PriorityMux(priority.map(s => (offers(s).valid, s.U(SourceBits.W))) :+ (true.B, 0.U(SourceBits.W)))
  engine.io.request.valid := offers.map(_.valid).reduce(_ || _)
  engine.io.request.bits := offers(chosen).txn
  /** The engine takes the chosen source's transaction this cycle. */
  def accepted(source: Int): Bool = engine.io.request.ready && engine.io.request.valid && chosen === source.U

  // Write data of the transaction being executed, per source.
  val writeData = Wire(Vec(5, UInt(32.W)))
  val writeMask = Wire(Vec(5, UInt(4.W)))
  writeData := DontCare
  writeMask := DontCare
  engine.io.writeData := writeData(engine.io.writeSource)
  engine.io.writeMask := writeMask(engine.io.writeSource)

  val read = engine.io.read
  def readFor(source: Int): Bool = read.valid && read.bits.source === source.U

  //////////////////////////////////
  // DMA write FIFO (clk3x)
  //////////////////////////////////
  // One word per transaction; the FIFO entry is taken when the engine
  // accepts it, the word itself is held for the engine.
  locally {
    val o = offers(Source.DmaFifo)
    o.valid := !io.ram.dmafifoEmpty
    o.txn := txnFor(0.U, Cat(0.U(2.W), io.ram.dmafifoAdr), 0.U, true.B, Source.DmaFifo)
    io.ram.dmafifoRead := accepted(Source.DmaFifo)
    val data = Reg(UInt(32.W))
    when (accepted(Source.DmaFifo)) {
      data := io.ram.dmafifoData
    }
    writeData(Source.DmaFifo) := data
    writeMask(Source.DmaFifo) := "b1111".U
  }

  //////////////////////////////////
  // CPU / DMA (clk1x requests)
  //////////////////////////////////
  locally {
    class Request extends Bundle {
      val adr = UInt(25.W)
      val rnw = Bool()
      val be = UInt(4.W)
      val dataWrite = UInt(32.W)
      val dma = Bool()
      val cntDma = UInt(2.W)
      val cache = Bool()
    }
    // A DMA asks for its next read when the previous one is accepted, so up
    // to two requests can be waiting.
    val requests = Module(new Queue(new Request, 2))
    requests.io.enq.valid := io.ram.ena && first
    requests.io.enq.bits.adr := io.ram.adr
    requests.io.enq.bits.rnw := io.ram.rnw
    requests.io.enq.bits.be := io.ram.be
    requests.io.enq.bits.dataWrite := io.ram.dataWrite
    requests.io.enq.bits.dma := io.ram.dma
    requests.io.enq.bits.cntDma := io.ram.cntDma
    requests.io.enq.bits.cache := io.ram.cache

    val head = requests.io.deq.bits
    // Reads of the instruction cache and the DMA take four words from the
    // requested one (as the MiSTer does, not wrapping at the cache line).
    val readWords = Mux(head.cache || head.dma, 3.U, 0.U)
    // DMA read words wait in a ring for the clk1x side, which takes one per
    // cycle; they come faster (the DMA asks for the next four as soon as a
    // read is accepted), so a read is only accepted when the ring has room
    // for its words: a 32-word MDEC block overflowed an unchecked ring of
    // eight and the DMA then waited forever for the lost words.
    val RingWords = 16
    val ringRead = Wire(UInt(log2Ceil(RingWords).W)) // clk1x, below
    val ringReadSeen = RegInit(0.U(log2Ceil(RingWords).W))
    /** Ring slots taken by accepted DMA reads' words, until the clk1x side takes them. */
    val ringAllocated = RegInit(0.U(log2Ceil(RingWords + 1).W))
    val ringFreed = ringRead =/= ringReadSeen // at most one per clk1x cycle (three of these)
    when (ringFreed) {
      ringReadSeen := ringReadSeen + 1.U
    }
    val dmaWords = head.cntDma +& 1.U
    val dmaRoom = ringAllocated +& dmaWords <= RingWords.U
    ringAllocated := ringAllocated + Mux(accepted(Source.Cpu) && head.dma, dmaWords, 0.U) - ringFreed.asUInt
    val o = offers(Source.Cpu)
    o.valid := requests.io.deq.valid && (!head.dma || dmaRoom)
    o.txn := txnFor(0.U, head.adr, Mux(head.rnw, readWords, 0.U), !head.rnw, Source.Cpu)
    requests.io.deq.ready := accepted(Source.Cpu)

    val data = Reg(UInt(32.W))
    val mask = Reg(UInt(4.W))
    when (accepted(Source.Cpu)) {
      data := head.dataWrite
      mask := head.be
    }
    writeData(Source.Cpu) := data
    writeMask(Source.Cpu) := mask

    // Reads in flight (in the engine's order).
    class InFlight extends Bundle {
      val cache = Bool()
      val dma = Bool()
      val cntDma = UInt(2.W)
      val startWord = UInt(2.W)
      val cacheAddr = UInt(8.W)
    }
    val inFlight = Module(new Queue(new InFlight, 2))
    inFlight.io.enq.valid := accepted(Source.Cpu) && head.rnw
    inFlight.io.enq.bits.cache := head.cache
    inFlight.io.enq.bits.dma := head.dma
    inFlight.io.enq.bits.cntDma := head.cntDma
    inFlight.io.enq.bits.startWord := head.adr(3, 2)
    inFlight.io.enq.bits.cacheAddr := head.adr(11, 4)
    val word = readFor(Source.Cpu)
    val f = inFlight.io.deq.bits
    inFlight.io.deq.ready := word && read.bits.last

    // Completion towards clk1x: set here, cleared once the clk1x side has
    // seen it (a one-cycle pulse there).
    val doneFlag = RegInit(false.B)
    val doneData = Reg(UInt(32.W))
    val done1x = withClockAndReset(io.clk1x, io.reset1x) { RegNext(doneFlag, false.B) }
    when (done1x) {
      doneFlag := false.B
    }
    io.ram.done := done1x
    io.ram.dataRead := withClock(io.clk1x) { RegEnable(doneData, doneFlag && !done1x) }
    // Writes complete when accepted (posted), plain reads with their word,
    // cache fills with the second word (the CPU reads the rest from the
    // cache after the MiSTer's fixed delay; they arrive faster).
    when (accepted(Source.Cpu) && !head.rnw) {
      doneFlag := true.B
    }
    when (word && !f.dma && read.bits.index === 0.U) {
      doneData := read.bits.data
      when (!f.cache) {
        doneFlag := true.B
      }
    }
    when (word && f.cache && read.bits.index === 1.U) {
      doneFlag := true.B
    }

    // Instruction cache fill: each word of the line from the requested one
    // goes into the cache as it arrives.
    val cacheWr = RegInit(0.U(4.W))
    val cacheData = Reg(UInt(32.W))
    val cacheAddr = Reg(UInt(8.W))
    cacheWr := 0.U
    when (word && f.cache) {
      val slot = f.startWord +& read.bits.index
      cacheWr := Mux(slot < 4.U, UIntToOH(slot(1, 0), 4), 0.U)
      cacheData := read.bits.data
      cacheAddr := f.cacheAddr
    }
    io.ram.cacheWr := cacheWr
    io.ram.cacheData := cacheData
    io.ram.cacheAddr := cacheAddr

    // DMA: accepted (pulse in clk1x), then its words (cntDma + 1 of the
    // four read) one per clk1x cycle through a small ring.
    val reqProcessedFlag = RegInit(false.B)
    val reqProcessed1x = withClockAndReset(io.clk1x, io.reset1x) { RegNext(reqProcessedFlag, false.B) }
    when (reqProcessed1x) {
      reqProcessedFlag := false.B
    }
    when (accepted(Source.Cpu) && head.dma) {
      reqProcessedFlag := true.B
    }
    io.ram.dmaReqProcessed := reqProcessed1x
    val ring = Reg(Vec(RingWords, UInt(32.W)))
    val ringWrite = RegInit(0.U(log2Ceil(RingWords).W))
    when (word && f.dma && read.bits.index <= f.cntDma) {
      ring(ringWrite) := read.bits.data
      ringWrite := ringWrite + 1.U
    }
    withClockAndReset(io.clk1x, io.reset1x) {
      val ringRead1x = RegInit(0.U(log2Ceil(RingWords).W))
      val dmaWr = RegInit(false.B)
      val dmaData = Reg(UInt(32.W))
      dmaWr := false.B
      when (ringRead1x =/= ringWrite) {
        dmaWr := true.B
        dmaData := ring(ringRead1x)
        ringRead1x := ringRead1x + 1.U
      }
      io.ram.dmaWr := dmaWr
      io.ram.dmaData := dmaData
      ringRead := ringRead1x
    }
  }

  //////////////////////////////////
  // DDR3 port (clk2x)
  //////////////////////////////////
  locally {
    class Request extends Bundle {
      val write = Bool()
      val addr = UInt(25.W) // 64-bit words
      val burstCnt = UInt(8.W)
      val din = UInt(64.W)
      val be = UInt(8.W)
    }
    val requests = Module(new RelatedClockFifo(new Request, 16))
    requests.io.writeClock := io.clk2x
    requests.io.writeReset := io.reset2x
    requests.io.readClock := clock
    requests.io.readReset := reset.asBool
    val responses = Module(new RelatedClockFifo(UInt(64.W), 16))
    responses.io.writeClock := clock
    responses.io.writeReset := reset.asBool
    responses.io.readClock := io.clk2x
    responses.io.readReset := io.reset2x

    val busy = !requests.io.enq.ready
    io.ddram.busy := busy
    requests.io.enq.valid := io.ddram.rd || io.ddram.we
    requests.io.enq.bits.write := io.ddram.we
    requests.io.enq.bits.addr := io.ddram.addr(24, 0)
    requests.io.enq.bits.burstCnt := io.ddram.burstCnt
    requests.io.enq.bits.din := io.ddram.din
    requests.io.enq.bits.be := io.ddram.be

    // The core takes a word whenever one is there.
    responses.io.deq.ready := true.B
    io.ddram.doutReady := withClock(io.clk2x) { RegNext(responses.io.deq.valid, false.B) }
    io.ddram.dout := withClock(io.clk2x) { RegEnable(responses.io.deq.bits, responses.io.deq.valid) }

    // clk3x side: one request at a time, a read cut into runs.

    val active = RegInit(false.B)
    val write = Reg(Bool())
    /** Chip byte address of the next run. */
    val address = Reg(UInt(25.W))
    /** 64-bit words still to request. */
    val remaining = Reg(UInt(9.W))
    val din = Reg(UInt(64.W))
    val be = Reg(UInt(8.W))
    requests.io.deq.ready := !active
    when (requests.io.deq.fire) {
      val r = requests.io.deq.bits
      active := true.B
      write := r.write
      address := ddramToChip(Cat(r.addr, 0.U(3.W)))(24, 0)
      remaining := Mux(r.write, 1.U, Mux(r.burstCnt === 0.U, 256.U, r.burstCnt))
      din := r.din
      be := r.be
    }
    // The run: up to the chunk size, the end of the row, or the request.
    val wordsToRowEnd = (1024.U(11.W) - address(9, 0)) >> 2 // 32-bit words
    val runWords = Mux(write, 2.U, (remaining << 1).min(wordsToRowEnd).min(ddramChunkWords.U))
    val o = offers(Source.Ddram)
    o.valid := active
    o.txn := txnFor(1.U, address, runWords - 1.U, write, Source.Ddram)
    when (accepted(Source.Ddram)) {
      address := address + (runWords << 2)
      remaining := remaining - (runWords >> 1)
      when (remaining === (runWords >> 1)) {
        active := false.B
      }
    }
    // The write data is held for the engine from its acceptance (the next
    // request may be taken in the meantime).
    val writeDin = Reg(UInt(64.W))
    val writeBe = Reg(UInt(8.W))
    when (accepted(Source.Ddram)) {
      writeDin := din
      writeBe := be
    }
    writeData(Source.Ddram) := Mux(engine.io.writeIndex(0), writeDin(63, 32), writeDin(31, 0))
    writeMask(Source.Ddram) := Mux(engine.io.writeIndex(0), writeBe(7, 4), writeBe(3, 0))

    // Read words pair up into 64-bit words (runs are whole 64-bit words).
    val low = Reg(UInt(32.W))
    val word = readFor(Source.Ddram)
    when (word && !read.bits.index(0)) {
      low := read.bits.data
    }
    // (The core takes response words faster than they come: no back pressure.)
    responses.io.enq.valid := word && read.bits.index(0)
    responses.io.enq.bits := Cat(read.bits.data, low)
  }

  //////////////////////////////////
  // Display (clk3x)
  //////////////////////////////////
  locally {
    val o = offers(Source.Display)
    o.valid := io.display.request.valid
    o.txn := txnFor(1.U, io.display.request.bits.address, io.display.request.bits.words, false.B, Source.Display)
    io.display.request.ready := accepted(Source.Display)
    writeData(Source.Display) := DontCare
    writeMask(Source.Display) := DontCare
    io.display.word.valid := readFor(Source.Display)
    io.display.word.bits.data := read.bits.data
    io.display.word.bits.index := read.bits.index
    io.display.word.bits.last := read.bits.last
  }

  //////////////////////////////////
  // Host (clk1x, held until done)
  //////////////////////////////////
  locally {
    val busy = RegInit(false.B)
    val doneFlag = RegInit(false.B)
    val done1x = withClockAndReset(io.clk1x, io.reset1x) { RegNext(doneFlag, false.B) }
    when (done1x) {
      doneFlag := false.B
    }
    val address = Reg(UInt(26.W))
    val write = Reg(Bool())
    val data = Reg(UInt(32.W))
    val mask = Reg(UInt(4.W))
    val readData = Reg(UInt(32.W))
    // A request is taken in the first clk3x cycle of a clk1x cycle, unless
    // the previous one is just completing (its initiator updates the request
    // in the next clk1x cycle).
    when (!busy && !doneFlag && !done1x && first && io.host.enable) {
      busy := true.B
      address := io.host.address
      write := io.host.write
      data := io.host.dataWrite
      mask := io.host.writeStrobe
    }
    val o = offers(Source.Host)
    val issued = RegInit(false.B)
    o.valid := busy && !issued
    o.txn := txnFor(address(25), address(24, 0), 0.U, write, Source.Host)
    when (accepted(Source.Host)) {
      issued := true.B
    }
    writeData(Source.Host) := data
    writeMask(Source.Host) := mask
    val writeDone = write && engine.io.writeTake && engine.io.writeSource === Source.Host.U
    val readDone = readFor(Source.Host)
    when (readDone) {
      readData := read.bits.data
    }
    when (busy && issued && (writeDone || readDone)) {
      busy := false.B
      issued := false.B
      doneFlag := true.B
    }
    io.host.done := done1x
    io.host.dataRead := withClock(io.clk1x) { RegEnable(readData, doneFlag && !done1x) }
  }
}
