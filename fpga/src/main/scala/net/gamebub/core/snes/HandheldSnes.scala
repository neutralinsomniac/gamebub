package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import HandheldSnes.CommandState
import lib.mem.cache.{LineBuffer, LineReadCache}
import lib.mem.{MemoryArbiter, MemoryInterface, MemoryMap, PipelineInterfaceBridge, PipelineMemoryArbiter, PipelineMemoryBurstCdc, PipelineMemoryInterface, PipelineMemoryLowPriorityMux, RegisterMap}
import lib.mem.sdram.BurstSdramController
import lib.mem.sram.AsyncSramController
import lib.util.ButtonFilter
import lib.video.ColorCorrection
import net.gamebub.framework.Core
import net.gamebub.framework.interface._
import snes.{SaveStateMemoryPort, SnesCore, SnesCoreConfig, SnesJoypad}
import xilinx.{BUFG, BUFGCE, MMCM, PLL}

object HandheldSnes {
  /**
   * SNES master clock: exactly 21.477 MHz (315/88 * 6 = 945/44 MHz).
   *
   * The other cores' 50 / 3 * 56.375 = 939.583 MHz MMCM VCO can't produce it
   * (939.583 / 44 = 21.354 MHz, -0.57 %, and the fractional divider 43.75 is
   * not available because the SDRAM clock has to be an integer multiple of
   * the system clock for [[lib.mem.PipelineMemoryBurstCdc]]). No single MMCM
   * setting reaches it with a 4x SDRAM clock either (the multiplier has 1/8
   * steps), so the clock tree is: MMCM 50 / 1 * 23.625 = 1181.25 MHz, divided
   * by 11 into a PLL (107.386 MHz) multiplying by 12 = 1288.636 MHz, then
   * divided by 60 (system, 21.4773 MHz) and 30 (SDRAM, 42.955 MHz). The
   * display and host SPI clocks are divided from the MMCM's VCO.
   *
   * The SDRAM runs at 2x (23.28 ns), not the MiSTer's 4x: the controller
   * has no source-synchronous I/O (outputs from plain flops, read data into
   * plain flops, the chip's clock forwarded through a pin), and the routed
   * pin paths spread over most of an 11.64 ns period across the corners.
   * The chip's clock is forwarded with a phase shift (`SdramClockPhaseNs`)
   * that puts its edge in the middle of the outputs' valid window, and read
   * data is captured on the falling edge, in the middle of its window; see
   * core_snes.xdc for the numbers. Cost: half the SDRAM bandwidth, which the
   * ROM cache's prefetch hides for all but the miss latency.
   */
  val ClockInHz = 50_000_000
  val MmcmDivide = 1
  val MmcmMultiply = 23.625
  val MmcmVcoHz = ClockInHz.toDouble / MmcmDivide * MmcmMultiply
  val PllInputDivider = 11
  val PllMultiply = 12
  val PllVcoHz = MmcmVcoHz / PllInputDivider * PllMultiply
  val SysDivider = 60
  /** SDRAM clock: exactly 2x the system clock (see above). */
  val SdramDivider = 30
  /**
   * Delay of the SDRAM chip's clock behind the controller's, at the PLL
   * (ns): the chip's edge lands 4-13 ns after the outputs settle and 8-12 ns
   * before they change again, and the beats it returns change within the
   * first quarter cycle after the controller's rising edge, so the
   * falling-edge capture sits 5-8 ns inside the data window, at the corners
   * (core_snes.xdc has the budget).
   */
  val SdramClockPhaseNs = 14.0
  val ClockSystemHz = (PllVcoHz / SysDivider).toInt
  val ClockSdramHz = (PllVcoHz / SdramDivider).toInt
  /**
   * The phase in degrees of the SDRAM clock period, snapped to the PLL's
   * resolution of 1/8 of a VCO period (360 / (8 x divider) degrees; Vivado
   * adjusts it with a critical warning otherwise). 216 degrees = 13.97 ns.
   */
  val SdramClockPhaseDegrees = {
    val step = 360.0 / (8 * SdramDivider)
    (360.0 * SdramClockPhaseNs * ClockSdramHz / 1e9 / step).round * step
  }
  /**
   * Host SPI receiver clock: the lowest the framework accepts (above
   * 160 MHz, see [[ClocksV0]]), 168.75 MHz. The other cores run it at
   * 187.9 MHz; the SNES fills the device (85 % of the slices), and at
   * 196.9 MHz the receiver's paths into the request FIFO were the ones
   * that failed timing, by placement luck, from one netlist to the next.
   */
  val SpiDivider = (MmcmVcoHz / 160_000_000).floor.toInt
  val ClockSpiHz = (MmcmVcoHz / SpiDivider).toInt
  require(ClockSpiHz > 160_000_000)

  object CommandState extends ChiselEnum {
    val idle, busy, error, done = Value
  }

  /**
   * Synthesize the audio debug facilities (test tone, sample capture buffer
   * and its playback; config register bits 3-6, host window 0x1xxxxx). Costs
   * 4 block RAMs, so off by default.
   */
  val DebugAudio = false
  /** Debug audio capture: 2^12 = 4096 stereo samples (128 ms at 32 kHz). */
  val CaptureIndexBits = 12

  /** NTSC frame: 262 lines of 1364 master clocks. */
  val MasterClocksPerFrame = 262 * 1364
  /** PAL frame: 312 lines of 1364 master clocks (50.0 Hz at `MasterClockPalHz`). */
  val MasterClocksPerFramePal = 312 * 1364
  /** The NTSC master-clock rate, as the core's DSP assumes it (`MCLK_NTSC_FREQ`). */
  val MasterClockNtscHz = 21477270
  /**
   * The PAL master-clock rate (`MCLK_PAL_FREQ`). The physical clock stays
   * at the NTSC rate; the S-CPU side's clock enable withholds edges so that
   * it sees this rate on average (see `palPace`). The APU's clock is not
   * paced: a PAL console has the same APU crystal as an NTSC one.
   */
  val MasterClockPalHz = 21281370
  /** Output frame height: the PPU's 224 or 239 visible lines, padded like a TV would show them. */
  val OutputLines = 240

  /** SRAM (512 KiB, 16-bit words) layout, in 16-bit word addresses. */
  object SramMap {
    /** Cartridge backup RAM (BSRAM), up to 256 KiB, byte address 0. */
    val BsramBase = 0x00000
    /** Work RAM, 128 KiB, byte address 0x40000. */
    val WramBase = 0x20000
  }

  /** SDRAM layout (byte addresses). */
  object SdramMap {
    /** Cartridge ROM, up to 16 MiB. The save-state program sits at 0xFF0000 within it. */
    val RomBase = 0x0
    /** Save-state slots: 4 x 1 MiB, the layout of the MiSTer's DDR3 region. */
    val SaveStateBase = 0x100_0000
    val SaveStateSlotSize = 0x10_0000
    val SaveStateSlots = 4
  }

  /**
   * Bridge from a level-sensitive async-SRAM-style 8-bit port (as driven by the
   * core: CE_N / OE_N / WE_N / address / data) to a 16-bit Game Bub
   * [[MemoryInterface]] with byte strobes.
   *
   * A read is issued when the port becomes active or its address changes; a
   * write is issued when the port becomes write-active or its address/data
   * changes. Requests go through a small in-order queue, so writes are
   * posted: `pendingRead` is high while a read is outstanding, `pending`
   * additionally while the queue is nearly full (the core must be stalled
   * then, since it can issue one more request before it notices). Reads
   * ordered behind posted writes see their data.
   *
   * Latency matters here: the core only has a few cycles between asserting a
   * read strobe and latching the data (see the stall control in
   * [[HandheldSnes]]), so a request is put on the memory interface in the
   * same cycle it is detected (when idle) and the read data is forwarded to
   * `dataRead` in the cycle it arrives, with `pendingRead` dropping in that
   * same cycle. With the [[AsyncSramController]] (done 2 cycles after the
   * request) read data is valid 2 cycles after the strobe.
   *
   * With `bufferWords > 0`, a 2-way cache of that many 16-bit words
   * (distributed RAM, read asynchronously, with a byte-valid pair per word
   * in registers) sits in front of the memory: a read of a byte it holds is
   * answered in the strobe cycle (no `pendingRead`); a write updates or
   * allocates its byte there (write-through, write-allocate: a
   * decompressor reading back what it just wrote hits) before going to the
   * memory; a completed read installs its word; and within a sequential
   * run the next word is prefetched when the queue is idle, so a byte
   * stream misses at most once per word. `invalidate` drops the contents
   * (needed when something else writes the memory, e.g. the MCU loading a
   * save while the core is in reset).
   */
  class BytePortBridge(addressWidth: Int, wordBase: Int, queueDepth: Int = 4, bufferWords: Int = 0) extends Module {
    val io = IO(new Bundle {
      val address = Input(UInt(addressWidth.W))
      val dataWrite = Input(UInt(8.W))
      val dataRead = Output(UInt(8.W))
      val ceN = Input(Bool())
      val oeN = Input(Bool())
      val weN = Input(Bool())
      /** Optional: only sample the port when high (edge-based ports). */
      val sample = Input(Bool())

      val mem = Flipped(new MemoryInterface(addressWidth = 18, dataWidth = 16))
      /** A read is outstanding, or the queue is nearly full: the core must not continue. */
      val pending = Output(Bool())
      /** A read is outstanding. */
      val pendingRead = Output(Bool())
      /** The queue is nearly full: the core must not continue, whatever the stall policy. */
      val full = Output(Bool())
      /** Pulses: a read / a write was detected on the port (statistics). */
      val readIssued = Output(Bool())
      val writeIssued = Output(Bool())
      /** Pulses: a read was answered by the word buffer (statistics). */
      val bufferHit = Output(Bool())
      /** Pulses: a read missed although its entry held another (valid) word — a conflict miss (statistics). */
      val bufferConflict = Output(Bool())
      /** What the memory is busy with, if anything (statistics). */
      val busyWrite = Output(Bool())
      val busyPrefetch = Output(Bool())
      val busyDemand = Output(Bool())
      /** Drop the word buffer's contents. */
      val invalidate = Input(Bool())
      /** Debug: never answer from or fill the word buffer (all misses). */
      val bufferOff = Input(Bool())
      /** Debug: use way 0 only (a direct-mapped cache of half the size). */
      val oneWay = Input(Bool())
    })

    class Request extends Bundle {
      val isWrite = Bool()
      /** A background read to fill the word buffer: nobody waits for it. */
      val isPrefetch = Bool()
      /** A demand read continuing a sequential run (worth prefetching after). */
      val isSequential = Bool()
      /** The cache way a read's word goes to when it completes. */
      val way = UInt(1.W)
      val address = UInt(addressWidth.W)
      val data = UInt(8.W)
    }

    val readActive = !io.ceN && !io.oeN
    val writeActive = !io.ceN && !io.weN
    val regReadActive = RegNext(readActive, false.B)
    val regWriteActive = RegNext(writeActive, false.B)
    val regAddress = RegNext(io.address)
    val regDataWrite = RegNext(io.dataWrite)
    val addressChanged = regAddress =/= io.address
    val dataChanged = regDataWrite =/= io.dataWrite

    val readRequest = io.sample && readActive && (!regReadActive || addressChanged)
    val writeRequest = io.sample && writeActive && (!regWriteActive || addressChanged || dataChanged)

    //////////////////////////////////
    // Word buffer
    //////////////////////////////////
    require(bufferWords == 0 || isPow2(bufferWords))
    val wordBits = addressWidth - 1
    // Two ways with an LRU bit per set (92 % of the misses of a 4 KiB
    // direct-mapped cache landed on occupied entries with SA-1 games).
    val bufWays = if (bufferWords == 0) 1 else 2
    val bufSets = (bufferWords / bufWays).max(1)
    val indexBits = log2Ceil(bufSets.max(2))
    val tagBits = (wordBits - indexBits).max(1)
    /** Byte-valid bits per way and set (registers, so that `invalidate` clears them at once). */
    val bufValid = RegInit(VecInit(Seq.fill(bufSets)(VecInit(Seq.fill(bufWays)(0.U(2.W))))))
    /** The way used most recently per set (the other one is the victim). */
    val bufLru = RegInit(VecInit(Seq.fill(bufSets)(false.B)))
    val bufTag = Seq.fill(bufWays)(Mem(bufSets, UInt(tagBits.W)))
    val bufData = Seq.fill(bufWays)(Mem(bufSets, Vec(2, UInt(8.W))))
    private def indexOf(word: UInt): UInt = word(indexBits - 1, 0)
    private def tagOf(word: UInt): UInt = word(wordBits - 1, indexBits)
    // One asynchronous read port per memory (the address on the port): more
    // would turn the distributed RAM into mux trees.
    val portWord = io.address(addressWidth - 1, 1)
    val portSet = indexOf(portWord)
    /** Per way: the entry holds the word on the port (its tag, and at least one valid byte). */
    val portTagMatchWay = VecInit((0 until bufWays).map(w =>
      if (bufferWords == 0) false.B else bufValid(portSet)(w).orR && bufTag(w).read(portSet) === tagOf(portWord)))
    val portTagMatch = portTagMatchWay.asUInt.orR
    val portDataWay = (0 until bufWays).map(w => if (bufferWords == 0) VecInit(Seq.fill(2)(0.U(8.W))) else bufData(w).read(portSet))
    /** The way that holds (or would hold) the word on the port: the way holding it, else the LRU victim. */
    val portWay = Mux(portTagMatch, OHToUInt(portTagMatchWay), Mux(bufLru(portSet) || io.oneWay, 0.U, 1.U))
    def bufLookup(address: UInt): (Bool, UInt) = if (bufferWords == 0) (false.B, 0.U) else {
      val hitWay = VecInit((0 until bufWays).map(w => bufValid(portSet)(w)(address(0)) && portTagMatchWay(w)))
      (hitWay.asUInt.orR, Mux1H(hitWay, portDataWay.map(_(address(0)))))
    }
    /** The word of the last demand read: a request for the word after it continues a run. */
    val bufLastWord = RegInit(0.U(wordBits.W))
    val sequential = portWord === bufLastWord + 1.U
    // A single write port (an install and a core write never happen in the
    // same cycle): a second one would keep Vivado from inferring distributed
    // RAM and turn the cache into flip-flops and mux trees.
    val bufWrite = WireDefault(false.B)
    val bufWriteWay = Wire(UInt(1.W))
    val bufWriteIndex = Wire(UInt(indexBits.W))
    val bufWriteTag = Wire(UInt(tagBits.W))
    val bufWriteData = Wire(Vec(2, UInt(8.W)))
    val bufWriteMask = Wire(Vec(2, Bool()))
    bufWriteWay := DontCare; bufWriteIndex := DontCare; bufWriteTag := DontCare; bufWriteData := DontCare; bufWriteMask := DontCare
    if (bufferWords > 0) {
      for (w <- 0 until bufWays) {
        when (bufWrite && bufWriteWay === w.U) {
          bufTag(w).write(bufWriteIndex, bufWriteTag)
          bufData(w).write(bufWriteIndex, bufWriteData, bufWriteMask)
        }
      }
    }
    /**
     * Install a whole word (from a memory read). It goes to the way that
     * already holds the word if there is one (a second tag read port, for
     * the completing address: a prefetch and a demand read of the same word
     * can be in flight together, and the LRU may have moved between their
     * requests), else to `way`, chosen when the read was requested.
     */
    def bufInstall(word: UInt, way: UInt, data: UInt): Unit = if (bufferWords > 0) {
      val set = indexOf(word)
      val heldWay = VecInit((0 until bufWays).map(w => bufValid(set)(w).orR && bufTag(w).read(set) === tagOf(word)))
      bufWrite := true.B
      bufWriteWay := Mux(heldWay.asUInt.orR, OHToUInt(heldWay), way)
      bufWriteIndex := indexOf(word)
      bufWriteTag := tagOf(word)
      bufWriteData := data.asTypeOf(Vec(2, UInt(8.W)))
      bufWriteMask := VecInit(true.B, true.B)
      bufValid(set)(bufWriteWay) := 3.U
      bufLru(set) := bufWriteWay(0)
    }
    /** A byte written by the core: update its word if present, else allocate it (that byte only). */
    def bufUpdate(address: UInt, data: UInt): Unit = if (bufferWords > 0) {
      val word = address(addressWidth - 1, 1)
      val index = indexOf(word)
      bufWrite := true.B
      bufWriteWay := portWay
      bufWriteIndex := index
      bufWriteTag := tagOf(word)
      bufWriteData := VecInit(Seq.fill(2)(data))
      bufWriteMask := VecInit(!address(0), address(0))
      when (portTagMatch) {
        bufValid(index)(portWay) := bufValid(index)(portWay) | Mux(address(0), 2.U, 1.U)
      } .otherwise {
        bufValid(index)(portWay) := Mux(address(0), 2.U, 1.U)
      }
      bufLru(index) := portWay(0)
    }
    val (bufHit0, bufByte) = bufLookup(io.address)
    val bufHit = bufHit0 && !io.bufferOff
    val readHit = readRequest && bufHit
    io.bufferHit := readHit
    io.bufferConflict := readRequest && !bufHit && (if (bufferWords == 0) false.B else bufValid(portSet).asUInt.orR && !portTagMatch)
    when (readHit) { bufLru(portSet) := portWay(0) }
    when (io.invalidate) {
      bufValid.foreach(_.foreach(_ := 0.U))
    }
    // A read that was in flight when a write came in may return data older
    // than the write: don't install anything until the port drains.
    val bufDirty = RegInit(false.B)

    // Requests wait here; `flow` lets one issue in the cycle it arrives.
    val queue = Module(new Queue(new Request, queueDepth, flow = true))
    val prefetchRequest = WireDefault(false.B)
    val prefetchAddress = Wire(UInt(addressWidth.W))
    prefetchAddress := DontCare
    // A prefetch is only ever raised in a cycle without a demand enqueue (a
    // miss's done cycle without a new request, or a hit).
    queue.io.enq.valid := (readRequest && !readHit) || writeRequest || prefetchRequest
    queue.io.enq.bits.isWrite := writeRequest
    queue.io.enq.bits.isPrefetch := prefetchRequest
    queue.io.enq.bits.isSequential := sequential
    // A prefetch is for the next word: its way is chosen by the LRU of that set (an approximation).
    queue.io.enq.bits.way := Mux(prefetchRequest, Mux(bufLru(indexOf(prefetchAddress(addressWidth - 1, 1))) || io.oneWay, 0.U, 1.U), portWay)
    queue.io.enq.bits.address := Mux(prefetchRequest, prefetchAddress, io.address)
    queue.io.enq.bits.data := io.dataWrite
    assert(!(prefetchRequest && ((readRequest && !readHit) || writeRequest)), "prefetch raised with a demand request")
    assert(!(queue.io.enq.valid && !queue.io.enq.ready), "BytePortBridge queue overflow")
    when (writeRequest && !io.bufferOff) {
      bufUpdate(io.address, io.dataWrite)
    }

    /** The request at the memory. */
    val busy = RegInit(false.B)
    val busyIsWrite = Reg(Bool())
    val busyIsPrefetch = Reg(Bool())
    val busyIsSequential = Reg(Bool())
    val busyWay = Reg(UInt(1.W))
    val busyAddress = Reg(UInt(addressWidth.W))
    val busyData = Reg(UInt(8.W))
    val regDataRead = RegInit(0.U(8.W))

    val done = busy && io.mem.done
    val doneRead = done && !busyIsWrite && !busyIsPrefetch
    val doneData = io.mem.dataRead.asTypeOf(Vec(2, UInt(8.W)))(busyAddress(0))
    // Demand reads answered by the memory in order; a hit answers a newer
    // request at once, so the data of demand reads still outstanding at that
    // moment (e.g. the GSU's speculative read before a store) must not
    // replace the hit's data when they complete later.
    val demandReads = RegInit(0.U(log2Ceil(queueDepth + 2).W))
    val staleReads = RegInit(0.U(log2Ceil(queueDepth + 2).W))
    val doneStale = doneRead && (readHit || staleReads =/= 0.U)
    // The data follows the address on the port whenever the buffer has it,
    // not only in the request cycle: two consumers share the port (the
    // S-CPU and the SA-1 on BW-RAM), and one's hit between the other's
    // strobe and sample must not replace what the other samples — its own
    // read is installed when it completes, and its address is on the port
    // when it samples.
    io.dataRead := Mux(bufHit, bufByte, Mux(doneRead && !doneStale, doneData, regDataRead))
    when (readHit) {
      regDataRead := bufByte
      // Keep a sequential stream ahead: fetch the following word too — but
      // only when this access continues a run (it reads the word after the
      // previous demand read): a coprocessor reading all over its RAM would
      // otherwise spend half the SRAM's time on prefetches it never uses.
      if (bufferWords > 0) {
        val nextWord = portWord + 1.U
        when (queue.io.count === 0.U && sequential) {
          prefetchRequest := true.B
          prefetchAddress := Cat(nextWord, 0.U(1.W))
        }
      }
    }
    // (A write arriving in this very cycle makes the data stale too.)
    when (done && !busyIsWrite && !bufDirty && !writeRequest && !io.bufferOff) {
      bufInstall(busyAddress(addressWidth - 1, 1), busyWay, io.mem.dataRead)
      // After a demand read that continues a run, fetch the following word
      // if the port is otherwise idle.
      if (bufferWords > 0) {
        val nextWord = busyAddress(addressWidth - 1, 1) + 1.U
        when (!busyIsPrefetch && busyIsSequential && queue.io.count === 0.U && !readRequest && !writeRequest) {
          prefetchRequest := true.B
          prefetchAddress := Cat(nextWord, 0.U(1.W))
        }
      }
    }

    // Issue the queue head when the memory is free (or completing this cycle).
    // A read may issue in the cycle it arrives (`flow`); a write arriving
    // this cycle waits for the next one. Writes are posted, so the cycle
    // costs nothing, and it keeps the core's write strobe off the
    // combinational path into the SRAM controller's write-enable, which
    // otherwise runs the whole 46 ns cycle from the SA-1's address generator.
    val arrivingWrite = queue.io.count === 0.U && queue.io.deq.bits.isWrite
    val issue = queue.io.deq.valid && (!busy || done) && !arrivingWrite
    queue.io.deq.ready := issue
    val head = queue.io.deq.bits
    val memIsWrite = Mux(busy, busyIsWrite, head.isWrite)
    val memAddress = Mux(busy, busyAddress, head.address)
    val memData = Mux(busy, busyData, head.data)
    // The target only accepts a request while it is idle and then ignores
    // `enable` until `done`, so holding the request from the issue cycle
    // until `done` is safe whether or not it was accepted immediately.
    io.mem.enable := busy || issue
    io.mem.write := memIsWrite
    io.mem.address := (memAddress >> 1).asUInt + wordBase.U
    io.mem.dataWrite := Fill(2, memData)
    io.mem.writeStrobe := Mux(memAddress(0), "b10".U(2.W), "b01".U(2.W))

    when (done) {
      when (doneRead && !doneStale) {
        regDataRead := doneData
      }
      busy := false.B
    }
    val enqDemandRead = readRequest && !readHit
    when (readRequest) { bufLastWord := portWord }
    val demandReadsNext = demandReads + enqDemandRead - doneRead
    val staleReadsNext = Mux(readHit, demandReads - doneRead,
      Mux(doneRead && staleReads =/= 0.U, staleReads - 1.U, staleReads))
    demandReads := demandReadsNext
    staleReads := staleReadsNext
    when (issue) {
      busy := true.B
      busyIsWrite := head.isWrite
      busyIsPrefetch := head.isPrefetch
      busyIsSequential := head.isSequential
      busyWay := head.way
      busyAddress := head.address
      busyData := head.data
    }

    // Demand reads somebody may still be waiting for at the end of this
    // cycle: enqueued (or in flight) and neither completing now nor made
    // stale by a newer hit. Posted writes and prefetches ahead of them in
    // the queue delay them but don't count by themselves: a burst of
    // stores (the SA-1 decompressing into BW-RAM) used to hold this high
    // and stall the coprocessor at every sample instant for nothing.
    val readOutstanding = demandReadsNext =/= staleReadsNext
    val nearlyFull = queue.io.count >= (queueDepth - 1).U
    when (writeRequest && (busy || queue.io.deq.valid)) {
      bufDirty := true.B
    } .elsewhen (!busy && !queue.io.deq.valid) {
      bufDirty := false.B
    }
    io.pendingRead := readOutstanding
    io.full := nearlyFull
    io.readIssued := readRequest
    io.busyWrite := busy && busyIsWrite
    io.busyPrefetch := busy && !busyIsWrite && busyIsPrefetch
    io.busyDemand := busy && !busyIsWrite && !busyIsPrefetch
    io.writeIssued := writeRequest
    io.pending := readOutstanding || nearlyFull
  }

  /**
   * Fills a range of the SRAM with a constant or the WRAM power-on pattern,
   * so that the memories are initialized by the glue rather than by a
   * firmware driver (the core has none).
   *
   * `start` (with `base` and `words`, 16-bit word addresses / count) is
   * sampled when idle; `busy` is high until the last word is written. The
   * WRAM pattern is the MiSTer's: byte `a` of the region is 0x66 when bit 8
   * and bit 2 of `a` differ, 0x99 otherwise (some games rely on non-zero
   * power-on RAM); both bytes of a word share it, so it is bit 7 xor bit 1
   * of the word offset. Every output is registered and a word takes three
   * cycles at the [[AsyncSramController]] (accept, access, done): the next
   * address is presented in the cycle after `done`, which keeps the engine
   * off the SRAM controller's combinational accept path.
   */
  class SramFill extends Module {
    val io = IO(new Bundle {
      val start = Input(Bool())
      val base = Input(UInt(18.W))
      /** Number of 16-bit words to write (1 to 2^18). */
      val words = Input(UInt(19.W))
      /** False: fill with 0xFFFF; true: the WRAM power-on pattern. */
      val wramPattern = Input(Bool())
      val busy = Output(Bool())
      val mem = Flipped(new MemoryInterface(addressWidth = 18, dataWidth = 16))
    })

    val busy = RegInit(false.B)
    val base = Reg(UInt(18.W))
    val offset = Reg(UInt(19.W))
    val remaining = Reg(UInt(19.W))
    val pattern = Reg(Bool())

    val wramWord = Mux(offset(7) ^ offset(1), 0x6666.U(16.W), 0x9999.U(16.W))
    io.mem.enable := busy
    io.mem.write := true.B
    io.mem.address := base + offset(17, 0)
    io.mem.dataWrite := Mux(pattern, wramWord, 0xFFFF.U(16.W))
    io.mem.writeStrobe := "b11".U
    io.busy := busy

    when (!busy) {
      when (io.start) {
        busy := true.B
        base := io.base
        offset := 0.U
        remaining := io.words
        pattern := io.wramPattern
      }
    } .elsewhen (io.mem.done) {
      // The word is written: step to the next one at this edge, so that it
      // is on the interface in the cycle after done (the controller is
      // free then; the arbiter released the port on seeing the completed
      // request still held in the done cycle).
      offset := offset + 1.U
      remaining := remaining - 1.U
      when (remaining === 1.U) {
        busy := false.B
      }
    }
  }
}

/**
 * SNES core, wrapping the vendored MiSTer core (`fpga/verilog/snes`).
 *
 * Clocked at 21.477 MHz (the SNES master clock). The S-CPU side of the
 * core runs on a gated copy of that clock: whenever an external memory
 * access (ROM in SDRAM, WRAM / BSRAM in SRAM) is outstanding, its clock is
 * stopped so the core observes single-cycle memory just like on the MiSTer.
 * The APU (SMP, DSP, ARAM in BRAM) runs on its own, free-running copy that
 * only stops with the S-CPU's for a pause, so a stalling game slows down
 * without its music dropping in pitch, as on a real console, whose APU has
 * its own crystal. Both are the same physical clock with edges removed, so
 * paths between them are timed as one domain; the S-CPU's one-cycle port
 * write strobe is qualified in the core with `MCLK_EN` (the gated clock's
 * enable) so a stall does not repeat it.
 *
 * Memory map:
 *  - SDRAM: cartridge ROM file (byte address 0, up to 16 MiB plus a copier
 *           header), loaded by the MCU as is and read through a 32 KiB
 *           2-way line cache whose SDRAM requests are translated by
 *           [[RomMirrorTable]] (mirrors of a non-power-of-two ROM, the
 *           header offset); the MiSTer save-state
 *           program at 0xFF0000 (written by the glue after SetupComplete,
 *           [[SaveStateProgramLoader]]); save-state slots
 *           (4 x 1 MiB) at 0x1000000, written and read by the core's
 *           save-state port and copied to / from the SD card by the MCU
 *  - SRAM:  BSRAM at byte 0 (256 KiB), WRAM at byte 0x40000 (128 KiB)
 *  - BRAM:  VRAM (2 x 32 KiB), ARAM (64 KiB)
 *
 * Host windows (from the MCU): the registers below at 0x0xxx_xxxx, the
 * SDRAM at 0x3xxx_xxxx, the SRAM at 0x4xxx_xxxx, the color correction at
 * 0x5xxx_xxxx and the framework's command interface at 0xF0xx_xxxx, like
 * the other cores. Registers:
 *  - 0x0000 config: bit 0 PAL, bit 1 BLEND (hi-res pseudo-transparency),
 *           bit 2 latency hiding in the mode that is safe for the cartridge
 *           type in ROM_TYPE, bit 7 Super FX RAM latency hiding, bit 8
 *           Super FX ROM latency hiding (experimental, unsafe), bit 9 SA-1 ROM
 *           latency hiding, bit 10 SA-1 BW-RAM latency hiding (see the stall
 *           control below and the `configReg` fields), bit 11 ROM early
 *           answers, bit 12 ROM eager issue (the two halves of the ROM miss
 *           path, separately switchable for A/B tests), bit 13 BSRAM cache
 *           off, bit 14 BSRAM cache way 0 only (debug), bit 15 region from
 *           the ROM header instead of bit 0, bit 16 APU clock in lockstep
 *           with the S-CPU's (A/B switch, see `configReg`). With `DebugAudio`:
 *           bit 3 1 kHz test tone instead of the core's audio, bit 4 tone at
 *           full scale, bit 5 play the capture buffer through the audio path,
 *           bit 6 capture sample periods (ungated clocks) instead of samples.
 *  - 0x0004 ROM_TYPE, 0x0008 ROM_MASK, 0x000C RAM_MASK, 0x0010 RAM_SIZE
 *           (the MiSTer core's ROM header encoding; see the firmware).
 *           Written by the glue's header analysis at the end of the ROM
 *           transfer (writable over SPI for experiments).
 *  - 0x0030 (read-only) the header analysis: bits 7:0 ROM_TYPE, 11:8 the
 *           ROM size code, 15:12 the RAM size code, 17:16 which header
 *           (0 LoROM, 1 HiROM, 2 ExHiROM), bit 18 PAL, bit 19 copier header,
 *           bit 20 a header was found, bit 21 unsupported cartridge, bit 22
 *           the ROM size can be mirrored by the address translation
 *
 * Framework commands (0xF0xx_xxxx): the file commands carry the file id and,
 * for FileWriteEnd, the size; the glue records the ROM and states file
 * sizes, analyzes the ROM header and builds the mirror table at the end of
 * the ROM transfer (held busy meanwhile), fills the BSRAM with 0xFF when
 * the ROM transfer starts and the WRAM with its power-on pattern after
 * SetupComplete (reporting the "setup" status until that is done; an
 * unsupported cartridge fails SetupComplete), and answers FileReadStart of
 * the save file with the cartridge RAM size. See the command interface
 * below.
 *  - 0x0014 save states: write bit 0 to request a save, bit 1 to request a
 *           load (of / into the slot in bits 3:2, or in register 0x0018
 *           when bit 4 is set; the write also sets the slot); the request
 *           is handed to the core at its next clock and acted on at the
 *           game's next NMI (or IRQ). The core runs without focus from the
 *           request until the program has finished (status bit 10), at
 *           most 5 s; a write with bits 1:0 clear cancels that. A load of
 *           a slot holding no state (status bits 14:11) is ignored. Reads
 *           back the slot in bits 3:2.
 *  - 0x0018 the save-state slot for requests with bit 4 set (bits 1:0).
 *  - 0x0100 status (read-only): bit 0 HIGH_RES, bit 1 V224_MODE, bit 2 INTERLACE,
 *           bit 3 FIELD, bit 4 GSU_ACTIVE, bit 5 TURBO_ALLOW, bit 6 SS_AVAIL
 *           (the cartridge type supports save states), bit 7 SS_BUSY (the
 *           save-state program is running), bit 8 a save has completed
 *           since the last save request (its header was written), bit 9 a
 *           save / load request has not been taken by the core yet, bit 10
 *           a save / load is in progress (the core runs without focus),
 *           bits 14:11 the slots holding a state (scanned when the states
 *           file has been transferred and after every save)
 *  - With `DebugAudio`: 0x0020 bit 0 capture run (ring buffer records raw DSP
 *           samples while set; clear to freeze), 0x0024 (read-only) next write
 *           index, 0x1xxxxx the capture buffer, 4096 x 32-bit (left in bits
 *           31:16, right in 15:0), oldest sample at the index read from 0x0024
 *  - 0x1000-0x1074 statistics, 32-bit counters, write to clear (a stall cycle
 *           can count for more than one cause):
 *           0x1000 stall cycles, 0x1004 run cycles, 0x1008 / 0x100C / 0x1010
 *           stall cycles caused by ROM / WRAM / BSRAM, 0x1014 ROM cache misses,
 *           0x1018 cycles spent filling cache lines (misses and prefetches),
 *           0x101C ROM requests, 0x1020 ROM line prefetches, 0x1024 ROM
 *           requests answered by the line buffer without a stall, 0x1028 stall
 *           cycles caused by a nearly full SRAM write queue, 0x102C / 0x1030
 *           BSRAM reads / writes issued, 0x1034 BSRAM reads answered by the
 *           word cache, 0x1038 / 0x103C BSRAM stall cycles at an S-CPU latch /
 *           at a coprocessor sample, 0x1040 stall cycles of the worst
 *           2^20-cycle window since the last clear, 0x1044 / 0x1048 / 0x104C
 *           that window's ROM / WRAM / BSRAM stalls, 0x1050 / 0x1054 / 0x1058 /
 *           0x105C BSRAM stall cycles while the BSRAM bridge had a write / a
 *           prefetch / a demand read / nothing at the SRAM, 0x1060 BSRAM stall
 *           cycles while the WRAM bridge had an access at the SRAM, 0x1064 /
 *           0x1068 / 0x106C / 0x1070 BSRAM misses whose next coprocessor
 *           sample came 1 / 2 / 3 / 4+ core cycles after the strobe, 0x1074
 *           BSRAM misses whose cache entry held another word (conflict misses),
 *           0x1078 stall cycles caused by the save-state port
 */
class HandheldSnes extends Module with Core {
  import HandheldSnes._
  private val debugAudio = DebugAudio

  /**
   * NTSC frame period. A PAL game's 312-line frame at the PAL master-clock
   * rate is 20 % longer (`MasterClocksPerFramePal`, `MasterClockPalHz`); the
   * display drivers follow a slower source with their vertical front porch.
   */
  val FramePeriod = MasterClocksPerFrame.toDouble / ClockSystemHz
  /** Display clock: the lowest the revision's display driver accepts, like the other cores. */
  val displayDivider = (MmcmVcoHz / ClocksV0.getClockDisplayHz(FramePeriod)._1).floor.toInt

  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      clockSystemHz = ClockSystemHz,
      clockDisplayHz = (MmcmVcoHz / displayDivider).toInt,
      clockSpiHz = ClockSpiHz,
    )
    val video = new VideoV0(
      videoWidth = 256,
      videoHeight = OutputLines,
      colorDepthR = 5,
      colorDepthG = 5,
      colorDepthB = 5,
      framePeriod = FramePeriod,
    )
    val videoFilter = new VideoFilterBasicV0(
      colorInDepthR = 5,
      colorInDepthG = 5,
      colorInDepthB = 5,
      latency = 3,
    )
    val audio = new AudioV0()
    val host = new HostV0()
    val input = new InputV0()
    val sram = new SramV0()
    val sdram = new SdramV0()
  })

  //////////////////////////////////
  // Clocks (the tree is described in the companion object)
  //////////////////////////////////
  val mmcm = Module(new MMCM(
    clockInHz = ClockInHz,
    divide = MmcmDivide,
    multiply = MmcmMultiply,
    clockOutConfig = Seq(
      MMCM.ClockOut(PllInputDivider), // PLL input
      MMCM.ClockOut(displayDivider),  // Display
      MMCM.ClockOut(SpiDivider),      // Host SPI
    )
  ))
  mmcm.io.clockIn := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B
  // core_snes.xdc names this instance's SDRAM clock output (core/pll/pll/CLKOUT1).
  val pll = Module(new PLL(
    clockInHz = (MmcmVcoHz / PllInputDivider).round.toInt,
    divide = 1,
    multiply = PllMultiply,
    clockOutConfig = Seq(
      PLL.ClockOut(SysDivider),   // System
      PLL.ClockOut(SdramDivider), // SDRAM controller (2x)
      PLL.ClockOut(SdramDivider, phase = SdramClockPhaseDegrees), // SDRAM chip (forwarded)
    )
  ))
  pll.io.clockIn := BUFG(mmcm.io.clockOuts(0))
  pll.io.powerDown := false.B
  pll.io.reset := !mmcm.io.locked
  io.clocks.clockOutSystem := pll.io.clockOuts(0)
  val clockSdram = pll.io.clockOuts(1)
  // core_snes.xdc derives the SDRAM pin clock from this output (core/pll/pll/CLKOUT2).
  val clockSdramPin = BUFG(pll.io.clockOuts(2))
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(1)
  io.clocks.clockOutSpi := mmcm.io.clockOuts(2)
  io.clocks.locked := mmcm.io.locked && pll.io.locked

  //////////////////////////////////
  // Framework state (driven by the command interface below)
  //////////////////////////////////
  /** The host has finished loading files (SetupComplete). */
  val regCoreSetup = RegInit(false.B)
  /** The core is halted, i.e. held in reset: before CoreRun and after CoreHalt. */
  val regCoreReset = RegInit(true.B)
  /** The core has the user's focus; it only runs then (the pause menu takes it away). */
  val regCoreFocus = RegInit(false.B)
  /** A one-cycle reset requested through register 0x2000. */
  val regCoreResetOnce = RegInit(false.B)
  regCoreResetOnce := false.B
  /**
   * File sizes reported by the host at the end of each transfer (bytes): the
   * ROM file (including a copier header, if any) and the states file.
   */
  val romFileSize = RegInit(0.U(25.W))
  val statesFileSize = RegInit(0.U(23.W))
  /**
   * The ROM header says PAL. Written by the glue's header analysis; used
   * when config bit 15 selects the automatic region.
   */
  val headerPal = RegInit(false.B)
  /** The WRAM initialization requested by SetupComplete has not started yet (the fill engine was busy). */
  val wramFillPending = RegInit(false.B)

  //////////////////////////////////
  // Memory arbitration
  //////////////////////////////////
  // SRAM: the host, WRAM and BSRAM (16-bit word addresses). Round-robin, and
  // a port may take the SRAM in another's done cycle: the S-CPU's WRAM
  // traffic must not hold up the SA-1's BW-RAM reads.
  val sramArbiter = Module(new MemoryArbiter(addressWidth = 18, dataWidth = 16, n = 3, fair = true))
  // The host sees the SRAM as a 32-bit window (the firmware transfers an
  // external core's files as 32-bit words): SramHostAdapter splits each
  // access into two 16-bit ones.
  val sramHost = Wire(new MemoryInterface(addressWidth = 19, dataWidth = 32))
  val sramHostAdapter = Module(new SramHostAdapter)
  sramHostAdapter.io.host <> sramHost
  // The fill engine (memory initialization, see the command interface) takes
  // the host's port while it runs rather than a fourth arbiter port, to keep
  // the arbiter's target mux, which is on the core's critical path to the
  // SRAM controller, as it is. The command interface guarantees the host
  // has no SRAM transfer in flight then.
  val sramFill = Module(new SramFill())
  locally {
    val port = sramArbiter.io.initiator(0)
    val host = sramHostAdapter.io.sram
    port.enable := Mux(sramFill.io.busy, sramFill.io.mem.enable, host.enable)
    port.write := Mux(sramFill.io.busy, sramFill.io.mem.write, host.write)
    port.address := Mux(sramFill.io.busy, sramFill.io.mem.address, host.address)
    port.dataWrite := Mux(sramFill.io.busy, sramFill.io.mem.dataWrite, host.dataWrite)
    port.writeStrobe := Mux(sramFill.io.busy, sramFill.io.mem.writeStrobe, host.writeStrobe)
    sramFill.io.mem.done := port.done && sramFill.io.busy
    sramFill.io.mem.dataRead := port.dataRead
    host.done := port.done && !sramFill.io.busy
    host.dataRead := port.dataRead
  }
  // SDRAM: the host and the ROM cache (below).
  val sdramArbiter = Module(new PipelineMemoryArbiter(addressWidth = 25, dataWidth = 32, n = 2))
  val sdramHost = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))

  {
    val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
    bridge.io.source <> sdramHost
    bridge.io.dest <> sdramArbiter.io.initiator(0)
  }

  //////////////////////////////////
  // Host registers
  //////////////////////////////////
  // N.B. the last field is bit 0 of the register. Reset value: both halves
  // of the ROM miss path on (bits 11 and 12), which the settings do not
  // touch.
  val configReg = RegInit(0x1800.U(17.W).asTypeOf(new Bundle {
    /**
     * Bit 16: the APU's clock follows the S-CPU's exactly (stalls and PAL
     * pacing included), as before the APU got its own clock. A/B switch:
     * the free-running APU changes only what a game sees on the APU ports.
     */
    val apuLockstep = Bool()
    /**
     * Bit 15: region from the ROM header (`headerPal`, set by the glue's
     * header analysis) instead of bit 0. Lets a settings descriptor offer
     * Auto / NTSC / PAL as plain register values.
     */
    val regionAuto = Bool()
    /** Bit 14 (debug): the BSRAM cache uses way 0 only (direct-mapped, half size). */
    val bsramOneWay = Bool()
    /** Bit 13 (debug): the BSRAM cache is off (every read goes to the SRAM). */
    val bsramCacheOff = Bool()
    /** Bit 12: the ROM cache issues a miss's first word in its lookup cycle. */
    val romEagerIssue = Bool()
    /** Bit 11: the ROM line buffer answers a miss with the word as it arrives, before the line is complete. */
    val romEarlyAnswers = Bool()
    /**
     * Bit 10: SA-1 BW-RAM latency hiding: BSRAM reads only stall the core
     * when the S-CPU (SYSCLKF_CE, while it owns the BW-RAM bus) or the SA-1
     * side (BWRAM_SAMPLE, a Game Bub addition to SA1.vhd) is about to latch
     * the data. The SA-1 side presents a read address for a whole SA-1
     * clock before consuming the data in its EN cycle.
     */
    val sa1BwramLatencyHiding = Bool()
    /**
     * Bit 9: SA-1 ROM latency hiding: ROM reads only stall the core when
     * the S-CPU (SYSCLKF_CE) or the SA-1 side (ROM_SAMPLE, a Game Bub
     * addition to SA1.vhd) is about to latch the data. Unlike the GSU, the
     * SA-1 changes the ROM address only in its CLK_CE cycle and samples one
     * cycle later, so a request is never superseded before it is consumed.
     */
    val sa1RomLatencyHiding = Bool()
    /**
     * Bit 8: Super FX ROM latency hiding: ROM reads only stall the core when
     * the S-CPU (SYSCLKF_CE) or the GSU (ROM_SAMPLE) is about to latch the
     * data. NOT SAFE as is: the GSU strobes ROM_RD_N every other cycle with
     * whatever address INT_ROM_A shows, so a miss can be superseded by a
     * strobe for another address before the GSU samples (it relies on the
     * MiSTer SDRAM's fixed latency). Kept for experiments; the firmware
     * leaves it clear.
     */
    val gsuRomLatencyHiding = Bool()
    /**
     * Bit 7: Super FX RAM latency hiding. BSRAM reads only stall the core
     * when the S-CPU (SYSCLKF_CE) or the GSU (its RAM_SAMPLE strobe, a Game
     * Bub addition to GSU.vhd) is about to latch the data. The GSU keeps
     * RAM_CE_N low and its address stable for the whole access, and the
     * bridge queue delivers reads in order, so the data at the sample
     * instant is that of the current address.
     */
    val gsuRamLatencyHiding = Bool()
    /** Bit 6 (debug): capture the clocks between DSP samples instead of the samples. */
    val capturePeriod = Bool()
    /** Bit 5 (debug): loop the audio capture buffer through the audio path (at ~32 kHz). */
    val playCapture = Bool()
    /** Bit 4 (debug): test tone at full scale instead of -12 dBFS. */
    val loudTone = Bool()
    /** Bit 3 (debug): replace the audio with a 1 kHz test tone (-12 dBFS sine). */
    val testTone = Bool()
    /**
     * Bit 2: memory latency hiding, in the mode that is safe for the loaded
     * cartridge (from ROM_TYPE, see `hidingBase` and friends below): for a
     * base cartridge the core is only stalled if a memory access is still
     * outstanding when the CPU is about to latch data (SYSCLKF_CE), which
     * hides the memory latency completely for the CPU; a Super FX cartridge
     * gets the RAM hiding of bit 7, an SA-1 cartridge the ROM and BW-RAM
     * hiding of bits 9 and 10, and a CX4 cartridge none (it accesses ROM on
     * its own schedule and exports no sampling instants).
     */
    val latencyHiding = Bool()
    /** Bit 1: pseudo-transparency blending in hi-res modes */
    val blend = Bool()
    /** Bit 0: PAL timing */
    val pal = Bool()
  }))
  val romTypeReg = RegInit(0.U(8.W))
  val romMaskReg = RegInit(0.U(24.W))
  val ramMaskReg = RegInit(0.U(24.W))
  val ramSizeReg = RegInit(0.U(4.W))
  val statRegStalls = RegInit(0.U(32.W))
  val statRegCycles = RegInit(0.U(32.W))
  /** Stall cycles attributable to each memory port (they can overlap). */
  val statRegStallsRom = RegInit(0.U(32.W))
  val statRegStallsWram = RegInit(0.U(32.W))
  val statRegStallsBsram = RegInit(0.U(32.W))
  /** Number of ROM reads that missed the cache. */
  val statRegRomMisses = RegInit(0.U(32.W))
  /** Cycles spent filling cache lines (miss latency). */
  val statRegRomFillCycles = RegInit(0.U(32.W))
  /** Number of ROM read requests. */
  val statRegRomRequests = RegInit(0.U(32.W))
  /** Number of ROM cache line prefetches. */
  val statRegRomPrefetches = RegInit(0.U(32.W))
  /** ROM requests answered by the line buffer in the same cycle. */
  val statRegRomBufferHits = RegInit(0.U(32.W))
  /** Stall cycles caused by a nearly full SRAM write queue (either port). */
  val statRegStallsFull = RegInit(0.U(32.W))
  /** BSRAM reads and writes issued by the core, and reads answered by the word buffer. */
  val statRegBsramReads = RegInit(0.U(32.W))
  val statRegBsramWrites = RegInit(0.U(32.W))
  val statRegBsramBufferHits = RegInit(0.U(32.W))
  /** BSRAM stall cycles at an S-CPU latch / at a coprocessor sample (a cycle can be both). */
  val statRegStallsBsramCpu = RegInit(0.U(32.W))
  val statRegStallsBsramGsu = RegInit(0.U(32.W))
  /**
   * Worst window: the stall cycles of the 2^20-cycle (49 ms) window with
   * the most of them since the last clear, and that window's ROM / WRAM /
   * BSRAM shares. Brief slowdowns (an area transition) that the totals
   * average away show up here.
   */
  val WindowBits = 20
  val statRegWorstStalls = RegInit(0.U(32.W))
  val statRegWorstStallsRom = RegInit(0.U(32.W))
  val statRegWorstStallsWram = RegInit(0.U(32.W))
  val statRegWorstStallsBsram = RegInit(0.U(32.W))
  /** BSRAM stall cycles by what the SRAM was doing for the BSRAM bridge: a write, a prefetch, a demand read, nothing. */
  val statRegStallsBsramWrite = RegInit(0.U(32.W))
  val statRegStallsBsramPrefetch = RegInit(0.U(32.W))
  val statRegStallsBsramDemand = RegInit(0.U(32.W))
  val statRegStallsBsramIdle = RegInit(0.U(32.W))
  /** BSRAM stall cycles while the WRAM bridge had an access at the SRAM. */
  val statRegStallsBsramWram = RegInit(0.U(32.W))
  /**
   * Histogram of the distance, in core cycles, from a BSRAM read strobe that
   * missed the word buffer to the coprocessor's next sample instant:
   * 1, 2, 3, 4 or more.
   */
  val statRegSampleAge = Seq.fill(4)(RegInit(0.U(32.W)))
  /** BSRAM reads that missed because their cache entry held another word. */
  val statRegBsramConflicts = RegInit(0.U(32.W))
  /** Stall cycles while a save-state transfer to / from the SDRAM was outstanding. */
  val statRegStallsSaveState = RegInit(0.U(32.W))
  val statusWire = Wire(UInt(15.W))
  /** What the glue's ROM header analysis found (register 0x0030, read-only; see the command interface). */
  val romInfoWire = Wire(UInt(23.W))
  /** Save-state control register (0x0014): the write strobe and data, acted on below. */
  val ssControlWriteLevel = WireDefault(false.B)
  val ssControlWriteData = WireDefault(0.U(32.W))
  /**
   * One cycle per host write. The register interface holds its write strobe
   * for as long as the request sits at the head of the SPI receiver's FIFO
   * (two cycles at least: one to take it, one to see `done` and pop it), so
   * the level would present a request to the core over several of the core's
   * clock edges. The first edge arms the request in `savestates.sv` and the
   * cancel that comes with the write then drops it again at the second one
   * (the edge detector there has already consumed the request's rising
   * edge), and nothing ever happens.
   */
  val ssControlWrite = ssControlWriteLevel && !RegNext(ssControlWriteLevel, false.B)
  val ssSlot = RegInit(0.U(2.W))
  /** Slot register (0x0018): the slot a request with bit 4 set uses (a settings descriptor's "State Slot"). */
  val ssSlotReg = RegInit(0.U(2.W))
  /** Debug: audio capture running (ring buffer of raw DSP samples). */
  val captureRunReg = RegInit(false.B)
  val captureIndexWire = Wire(UInt(CaptureIndexBits.W))

  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val captureInterface = Wire(new MemoryInterface(addressWidth = 20, dataWidth = 32))
  val colorCorrectInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 16))
  val commandInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val memoryMap = MemoryMap(
    addressWidth = 32,
    dataWidth = 32,
    entries = Seq(
      0x0.U(4.W) -> registerInterface,
    ) ++ (if (debugAudio) Seq(0x1.U(4.W) -> captureInterface) else Seq()) ++ Seq(
      0x3.U(4.W) -> sdramHost,
      0x4.U(4.W) -> sramHost,
      0x5.U(4.W) -> colorCorrectInterface,
      0xF0.U(8.W) -> commandInterface,
    ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U
  if (!debugAudio) {
    captureInterface.address := DontCare
    captureInterface.enable := false.B
    captureInterface.write := false.B
    captureInterface.dataWrite := DontCare
    captureInterface.writeStrobe := DontCare
  }

  registerInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries = Seq(
      0x0000 -> RegisterMap.Entry.rw(configReg),
      0x0004 -> RegisterMap.Entry.rw(romTypeReg),
      0x0008 -> RegisterMap.Entry.rw(romMaskReg),
      0x000C -> RegisterMap.Entry.rw(ramMaskReg),
      0x0010 -> RegisterMap.Entry.rw(ramSizeReg),
      0x0014 -> RegisterMap.Entry(32,
        RegisterMap.ReadFn(_ => Cat(ssSlot, 0.U(2.W))),
        RegisterMap.WriteFn((write, data) => {
          ssControlWriteLevel := write
          ssControlWriteData := data
        })),
      0x0018 -> RegisterMap.Entry.rw(ssSlotReg),
      0x0030 -> RegisterMap.Entry.r(romInfoWire),
      0x0100 -> RegisterMap.Entry.r(statusWire),
    ) ++ (if (debugAudio) Seq(
      0x0020 -> RegisterMap.Entry.rw(captureRunReg),
      0x0024 -> RegisterMap.Entry.r(captureIndexWire),
    ) else Seq()) ++ Seq(
      0x1000 -> RegisterMap.Entry.rw(statRegStalls),
      0x1004 -> RegisterMap.Entry.rw(statRegCycles),
      0x1008 -> RegisterMap.Entry.rw(statRegStallsRom),
      0x100C -> RegisterMap.Entry.rw(statRegStallsWram),
      0x1010 -> RegisterMap.Entry.rw(statRegStallsBsram),
      0x1014 -> RegisterMap.Entry.rw(statRegRomMisses),
      0x1018 -> RegisterMap.Entry.rw(statRegRomFillCycles),
      0x101C -> RegisterMap.Entry.rw(statRegRomRequests),
      0x1020 -> RegisterMap.Entry.rw(statRegRomPrefetches),
      0x1024 -> RegisterMap.Entry.rw(statRegRomBufferHits),
      0x1028 -> RegisterMap.Entry.rw(statRegStallsFull),
      0x102C -> RegisterMap.Entry.rw(statRegBsramReads),
      0x1030 -> RegisterMap.Entry.rw(statRegBsramWrites),
      0x1034 -> RegisterMap.Entry.rw(statRegBsramBufferHits),
      0x1038 -> RegisterMap.Entry.rw(statRegStallsBsramCpu),
      0x103C -> RegisterMap.Entry.rw(statRegStallsBsramGsu),
      0x1040 -> RegisterMap.Entry.rw(statRegWorstStalls),
      0x1044 -> RegisterMap.Entry.rw(statRegWorstStallsRom),
      0x1048 -> RegisterMap.Entry.rw(statRegWorstStallsWram),
      0x104C -> RegisterMap.Entry.rw(statRegWorstStallsBsram),
      0x1050 -> RegisterMap.Entry.rw(statRegStallsBsramWrite),
      0x1054 -> RegisterMap.Entry.rw(statRegStallsBsramPrefetch),
      0x1058 -> RegisterMap.Entry.rw(statRegStallsBsramDemand),
      0x105C -> RegisterMap.Entry.rw(statRegStallsBsramIdle),
      0x1060 -> RegisterMap.Entry.rw(statRegStallsBsramWram),
      0x1064 -> RegisterMap.Entry.rw(statRegSampleAge(0)),
      0x1068 -> RegisterMap.Entry.rw(statRegSampleAge(1)),
      0x106C -> RegisterMap.Entry.rw(statRegSampleAge(2)),
      0x1070 -> RegisterMap.Entry.rw(statRegSampleAge(3)),
      0x1074 -> RegisterMap.Entry.rw(statRegBsramConflicts),
      0x1078 -> RegisterMap.Entry.rw(statRegStallsSaveState),
    ) ++ Seq(
      0x2000 -> RegisterMap.Entry.w(regCoreResetOnce),
    )
  )

  //////////////////////////////////
  // Framework command interface
  //////////////////////////////////
  // The host writes a command and up to three arguments to the four words
  // (FileWriteEnd: id, size, 0) and reads a result from word 0.
  val commandHostState = RegInit(CommandState.idle)
  val regCommandHost = Reg(Vec(4, UInt(32.W)))
  commandInterface <> RegisterMap(
    addressWidth = 16,
    dataWidth = 32,
    entries =
      regCommandHost.zipWithIndex.map { case (reg, i) => (0x0000 + (4 * i) -> RegisterMap.Entry.rw(reg)) }
  )
  // Memory initialization by the glue (the core has no firmware driver to
  // do it): the BSRAM is filled with 0xFF when the ROM transfer starts (a
  // save file shorter than the cartridge's RAM leaves the rest at 0xFF, as
  // on a cartridge; a missing one is cleared by the host anyway), running
  // in the background behind the ROM transfer, which goes to the SDRAM; the
  // WRAM gets its power-on pattern after SetupComplete. The fill engine
  // borrows the host's SRAM port, so a file transfer to the SRAM (the save
  // file) waits for it: FileWriteStart is held busy until the engine is
  // idle. SetupComplete completes right away (its host timeout is short)
  // and the status stays "setup" until the WRAM fill has finished (the host
  // waits much longer for "halt").
  val bsramFillStart = WireDefault(false.B)
  val wramFillStart = WireDefault(false.B)
  sramFill.io.start := bsramFillStart || wramFillStart
  sramFill.io.base := Mux(wramFillStart, SramMap.WramBase.U, SramMap.BsramBase.U)
  sramFill.io.words := Mux(wramFillStart, (128 * 1024 / 2).U, (256 * 1024 / 2).U)
  sramFill.io.wramPattern := wramFillStart
  when (wramFillPending && !sramFill.io.busy) {
    wramFillStart := true.B
    wramFillPending := false.B
  }
  // ROM mirroring: the translation table is rebuilt from the file size at
  // the end of the ROM transfer (FileWriteEnd is held busy meanwhile, about
  // a thousand cycles). It reads the size from the command word directly,
  // in the decode cycle.
  val romMirror = Module(new RomMirrorTable)
  romMirror.io.build := false.B
  romMirror.io.fileSize := regCommandHost(2)
  // ROM header analysis, at the same time (it reads the file through the
  // save-state port's side of the SDRAM mux, see the ROM section). When it
  // finishes, the core's ROM registers take its results.
  val romAnalyzer = Module(new RomHeaderAnalyzer)
  romAnalyzer.io.start := false.B
  romAnalyzer.io.fileSize := regCommandHost(2)
  /** The analysis found the cartridge unsupported: SetupComplete fails. */
  val romUnsupported = RegInit(false.B)
  when (RegNext(romAnalyzer.io.busy, false.B) && !romAnalyzer.io.busy) {
    val r = romAnalyzer.io.result
    romTypeReg := r.romType
    romMaskReg := ((1024.U(25.W) << r.romSizeCode)(24, 0) - 1.U)(23, 0)
    ramMaskReg := Mux(r.ramSizeCode === 0.U, 0.U, ((1024.U(25.W) << r.ramSizeCode)(24, 0) - 1.U)(23, 0))
    ramSizeReg := r.ramSizeCode
    headerPal := r.pal
    romUnsupported := r.unsupported
  }
  // The save-state program is written above the ROM after SetupComplete
  // (through the same side port), unless the padded ROM reaches its
  // address, in which case save states are unavailable (`ssAvailable`).
  val ssProgramLoader = Module(new SaveStateProgramLoader)
  ssProgramLoader.io.start := false.B
  val ssProgramFits = romAnalyzer.io.result.romSizeCode <= SaveStateProgramLoader.MaxRomSizeCode.U
  val ssProgramPending = RegInit(false.B)
  when (ssProgramPending && !romAnalyzer.io.busy) {
    ssProgramLoader.io.start := true.B
    ssProgramPending := false.B
  }
  /** Setup is complete and the post-setup memory initialization has finished. */
  val setupReady = regCoreSetup && !wramFillPending && !sramFill.io.busy &&
    !ssProgramPending && !ssProgramLoader.io.busy
  // Save-state slots: scanned when the states file has been transferred
  // (FileWriteEnd 2, held busy; slots the file did not cover are cleared)
  // and again after every save, so that a load of an empty slot can be
  // refused and FileReadStart 2 can answer with the slots in use.
  val ssSlotScanner = Module(new SaveStateSlotScanner)
  val ssSlotScanFromFile = WireDefault(false.B)
  val ssSlotRescan = WireDefault(false.B)
  ssSlotScanner.io.start := ssSlotScanFromFile || ssSlotRescan
  ssSlotScanner.io.loadedSize := Mux(ssSlotScanFromFile, regCommandHost(2)(22, 0), SaveStateSlotScanner.FullSize.U)
  val ssSlotValid = ssSlotScanner.io.valid
  /** Bytes of the states file to write back: whole slots up to the last one holding a state. */
  val statesFileSizeUsed = (ssSlotScanner.io.usedSlots << log2Ceil(SaveStateSlotScanner.SlotSize))(22, 0)
  romInfoWire := Cat(
    romMirror.io.supported,
    romAnalyzer.io.result.unsupported,
    romAnalyzer.io.result.headerFound,
    romAnalyzer.io.result.copierHeader,
    romAnalyzer.io.result.pal,
    romAnalyzer.io.result.headerIndex,
    romAnalyzer.io.result.ramSizeCode,
    romAnalyzer.io.result.romSizeCode,
    romAnalyzer.io.result.romType,
  )
  /** Bytes of cartridge RAM to write back to the save file (the RAM size code, capped at the BSRAM). */
  val saveFileSize = Mux(ramSizeReg === 0.U, 0.U, ((1024.U(26.W) << ramSizeReg)(25, 0).min((256 * 1024).U)))
  // Host -> Core commands
  io.host.commandHost.busy := commandHostState === CommandState.busy
  io.host.commandHost.done := commandHostState === CommandState.done
  io.host.commandHost.error := commandHostState === CommandState.error
  when (io.host.commandHost.request) {
    when (commandHostState === CommandState.idle) {
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
        // An unsupported cartridge (per the header analysis) fails the
        // setup; the firmware reports a core error instead of running it.
        when (romUnsupported) {
          commandHostState := CommandState.error
        } .otherwise {
          regCoreSetup := true.B
          wramFillPending := true.B
          ssProgramPending := ssProgramFits
        }
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      } .elsewhen (command === HostV0.CommandFileWriteStart.U) {
        // The files go straight to the SDRAM / SRAM windows.
        when (fileId === 0.U) {
          bsramFillStart := true.B
        } .elsewhen (sramFill.io.busy) {
          commandHostState := CommandState.busy
        }
      } .elsewhen (command === HostV0.CommandFileWriteEnd.U) {
        when (fileId === 0.U) {
          romFileSize := fileSize
          romMirror.io.build := true.B
          romAnalyzer.io.start := true.B
          commandHostState := CommandState.busy
        } .elsewhen (fileId === 2.U) {
          statesFileSize := fileSize
          ssSlotScanFromFile := true.B
          commandHostState := CommandState.busy
        }
      } .elsewhen (command === HostV0.CommandFileReadStart.U) {
        // Word 0: the number of bytes to write back.
        when (fileId === 1.U) {
          regCommandHost(0) := saveFileSize
        } .elsewhen (fileId === 2.U) {
          regCommandHost(0) := statesFileSizeUsed
        }
      } .elsewhen (command === HostV0.CommandFileReadEnd.U) {
        // Nothing to do.
      } .otherwise {
        // Unknown command
        commandHostState := CommandState.error
      }
    } .elsewhen (commandHostState === CommandState.busy) {
      // FileWriteStart of a file bound for the SRAM waits for the fill
      // engine; FileWriteEnd of the ROM for the mirror table and the
      // header analysis.
      when (!sramFill.io.busy && !romMirror.io.busy && !romAnalyzer.io.busy && !ssSlotScanner.io.busy) {
        commandHostState := CommandState.done
      }
    }
  } .otherwise {
    commandHostState := CommandState.idle
  }
  // Core -> Host commands
  io.host.commandCore.request := false.B

  //////////////////////////////////
  // Core and gated clock
  //////////////////////////////////
  val coreRun = Wire(Bool())
  val coreClock = BUFGCE(clock, coreRun)
  /** True in the cycle after a core clock edge was delivered (core outputs are fresh). */
  val tick = RegNext(coreRun, false.B)
  /**
   * The APU's clock: `coreRun` without the memory stalls and the PAL pacing
   * (see the class comment). The APU has no external memory of its own, so
   * it never has to wait; the S-CPU's port writes are qualified with
   * `MCLK_EN` in the core, every other signal from the gated side is a
   * level or an idempotent multi-cycle write (the save-state bus), and the
   * gated side's inputs from the APU (the ports, save-state reads, ARAM)
   * are stable by its next delivered edge because the APU's edges are a
   * superset of its own.
   */
  val apuRun = Wire(Bool())
  val apuClock = BUFGCE(clock, apuRun)
  /** True in the cycle after an APU clock edge was delivered. */
  val apuTick = RegNext(apuRun, false.B)

  val core = Module(new SnesCore(SnesCoreConfig(savestates = true)))
  core.tieOffUnused()
  core.io.MCLK := coreClock
  core.io.ACLK := apuClock
  core.io.MCLK_EN := coreRun
  // The APU's clock is at the NTSC rate in both regions, like a console's
  // (in lockstep it is paced like the S-CPU's, so the DSP is told the region).
  core.io.DSP_PAL := configReg.apuLockstep && core.io.PAL
  // Like the MiSTer top level, release the core's reset only when the PPU's
  // H/V counters wrap (HVCNT_ATZERO): the counters run through reset, so
  // this starts the CPU at the top of a frame and gives stable video from
  // the first frame. The core clock must run for the counters to get there,
  // which it does while the core is halted or has focus (see `coreRun`).
  //
  // The register lives in the core's (gated) clock domain. Keeping it in the
  // system domain and qualifying HVCNT_ATZERO with `tick` is equivalent, but
  // Vivado then optimizes every coprocessor (whose reset is RESET_N gated by
  // its mapper select) down to nothing - the SNES itself survives, so the
  // bitstream looks fine until a Super FX / SA-1 / S-DD1 cartridge is loaded.
  val hostReset = reset.asBool || regCoreReset || regCoreResetOnce
  val coreResetN = withClockAndReset(coreClock, hostReset) {
    val resetN = RegInit(false.B)
    when (core.io.HVCNT_ATZERO) {
      resetN := true.B
    }
    resetN
  }
  core.io.RESET_N := coreResetN
  // Real Super FX carts have fast ROM in banks $80+ (MiSTer's default).
  core.io.GSU_FASTROM := true.B
  core.io.ROM_TYPE := romTypeReg
  core.io.ROM_MASK := romMaskReg
  core.io.RAM_MASK := ramMaskReg
  core.io.RAM_SIZE := ramSizeReg
  /** The region the core runs at: the ROM header's, or the setting's. */
  val regionPal = Mux(configReg.regionAuto, headerPal, configReg.pal)
  core.io.PAL := regionPal
  core.io.BLEND := configReg.blend

  //////////////////////////////////
  // Save states
  //////////////////////////////////
  // The MiSTer mechanism: on a request, the core hijacks the game's next NMI
  // (or IRQ) vector and runs a 65816 program from ROM address 0xFF0000 that
  // streams the machine state through the core's 64-bit "DDR" port, or
  // restores it from there. The port is adapted to the SDRAM below (the
  // slots live above the ROM); the program is written by the glue after
  // SetupComplete (SaveStateProgramLoader, above).
  //
  // Save / load requests are edge-detected by the core, so a register write
  // arms a request that is presented to the core until it has clocked once
  // with it high (`coreRun`: the core samples its inputs at that edge). A
  // write in the same cycle wins over the clearing.
  val ssSaveRequest = RegInit(false.B)
  val ssLoadRequest = RegInit(false.B)
  /** A save has completed (its header was written) since the last save request. */
  val ssSaveDone = RegInit(false.B)
  /** A save / load is in progress: the core runs without focus from the
   *  request until the program has finished (SS_BUSY falls), a cancelling
   *  write (bits 1:0 clear) or a host reset. The firmware requests states
   *  from the settings menu, i.e. while the game is paused. */
  val ssRunPending = RegInit(false.B)
  /** A request the core has latched but not taken yet is to be dropped
   *  (the timeout, any control write): held until the core has run a
   *  cycle with it, so it is seen even while the core has no focus. */
  val ssCancel = RegInit(false.B)
  val ssPort = Module(new SaveStateMemoryPort(SdramMap.SaveStateBase))
  ssPort.io.req := core.io.SS_DDR_REQ
  ssPort.io.address := core.io.SS_DDR_ADDR
  ssPort.io.write := core.io.SS_DDR_WE
  ssPort.io.byteEnable := core.io.SS_DDR_BE
  ssPort.io.dataWrite := core.io.SS_DDR_DO
  core.io.SS_DDR_ACK := ssPort.io.ack
  core.io.SS_DDR_DI := ssPort.io.dataRead
  core.io.SS_SAVE := ssSaveRequest
  core.io.SS_LOAD := ssLoadRequest
  core.io.SS_CANCEL := ssCancel
  core.io.SS_SLOT := ssSlot
  // Always write the save counter to the header (the MiSTer's "save to SD
  // card" mode), so the firmware can tell a save has finished.
  core.io.SS_TOSD := true.B

  statusWire := Cat(
    ssSlotValid,
    ssRunPending,
    ssSaveRequest || ssLoadRequest,
    ssSaveDone,
    core.io.SS_BUSY,
    core.io.SS_AVAIL && ssProgramFits,
    core.io.TURBO_ALLOW,
    core.io.GSU_ACTIVE,
    core.io.FIELD,
    core.io.INTERLACE,
    core.io.V224_MODE,
    core.io.HIGH_RES,
  )

  //////////////////////////////////
  // ROM (SDRAM, through a read cache)
  //////////////////////////////////
  // 2 ways x 1024 lines x 16 bytes (32 KiB), with next-line prefetch so that
  // sequential fetches only miss on the first line of a run; the SDRAM CDC
  // prefetches the following words during a line fill. A miss fetches the
  // requested word first and hands it on as it arrives, so a random read
  // costs the SDRAM latency, not the whole line's. In front of it, a
  // 16-line register buffer answers hits in the request cycle itself (the
  // cache needs a cycle even for a hit, which stalls the core in the
  // conservative mode) and pulls the next line in the background.
  val romCache = Module(new LineReadCache(addressWidth = 25, dataWidth = 32, numLines = 1024, wordsPerLine = 4, ways = 2, prefetchDistance = 2))
  // The save-state port shares the SDRAM, taking it only when the cache is
  // idle so that the cache's miss latency is unchanged.
  val sdramMux = Module(new PipelineMemoryLowPriorityMux(addressWidth = 25, dataWidth = 32))
  sdramArbiter.io.initiator(1) <> sdramMux.io.target
  sdramMux.io.main <> romCache.io.out
  // The header analyzer, the save-state program loader and the slot
  // scanner borrow the side port while they run (during setup, or right
  // after a save, when the save-state port is idle; never two at once);
  // the file's layout, untranslated.
  locally {
    val side = sdramMux.io.side
    val engines = Seq(romAnalyzer.io.mem, ssProgramLoader.io.mem, ssSlotScanner.io.mem)
    val busy = Seq(romAnalyzer.io.busy, ssProgramLoader.io.busy, ssSlotScanner.io.busy)
    val setup = busy.reduce(_ || _)
    def select[T <: Data](field: PipelineMemoryInterface => T): T =
      PriorityMux(busy :+ true.B, (engines :+ ssPort.io.mem).map(field))
    side.enable := select(_.enable)
    side.address := select(_.address)
    side.isWrite := select(_.isWrite)
    side.writeStrobe := select(_.writeStrobe)
    side.dataWrite := select(_.dataWrite)
    for ((engine, i) <- engines.zipWithIndex) {
      engine.ready := side.ready && busy(i) && !busy.take(i).foldLeft(false.B)(_ || _)
      engine.dataRead := side.dataRead
    }
    ssPort.io.mem.ready := side.ready && !setup
    ssPort.io.mem.dataRead := side.dataRead
  }
  // The cache's requests (misses and prefetches, in the core's ROM address
  // space) are translated to the file's layout in the SDRAM: mirrors of a
  // non-power-of-two ROM and the copier header offset (see RomMirrorTable).
  // The cache tags stay in the core's address space, so hits are untouched.
  // (The table is instantiated with the command interface, which builds it.)
  romMirror.io.in := romCache.io.out.address
  sdramMux.io.main.address := romMirror.io.out
  val romBuffer = Module(new LineBuffer(addressWidth = 25, numLines = 16, wordsPerLine = 4))
  romCache.io.in <> romBuffer.io.out
  romBuffer.io.outLine := romCache.io.line
  romBuffer.io.earlyValid := romCache.io.fillWordValid && configReg.romEarlyAnswers
  romCache.io.eagerIssue := configReg.romEagerIssue
  romBuffer.io.earlyAddress := romCache.io.fillWordAddress
  romBuffer.io.earlyData := romCache.io.fillWordData

  val romReadActive = !core.io.ROM_OE_N
  val regRomReadActive = RegNext(romReadActive, false.B)
  val regRomAddress = RegNext(core.io.ROM_ADDR)
  val romAddressChanged = regRomAddress(23, 1) =/= core.io.ROM_ADDR(23, 1)
  val romRequest = romReadActive && (!regRomReadActive || romAddressChanged)

  // A buffer hit is answered in the request cycle (no stall); otherwise the
  // core is stalled until the buffer delivers the word.
  romBuffer.io.request := romRequest
  romBuffer.io.address := Cat(core.io.ROM_ADDR(23, 2), 0.U(2.W))
  val romHit = romBuffer.io.hit
  val romPending = RegInit(false.B)
  val romData = RegInit(0.U(32.W))
  val romDataNow = WireDefault(romData)
  // A hit or an answer replaces the data; a new request supersedes an older
  // miss (the buffer never answers one in a request cycle), so the request
  // decides `romPending` in its cycle.
  when (romHit || romBuffer.io.respValid) {
    romDataNow := romBuffer.io.dataRead
    romData := romBuffer.io.dataRead
    romPending := false.B
  }
  when (romRequest && !romHit) {
    romPending := true.B
  }
  // The word / byte select follows the address presented with the request:
  // the current one on a hit, the registered one while stalled or after.
  val romSelectAddress = Mux(romHit, core.io.ROM_ADDR, regRomAddress)
  // Match the MiSTer SDRAM controller: byte reads at odd addresses return the
  // upper byte in both halves; word reads return the aligned 16-bit word.
  val romWord = romDataNow.asTypeOf(Vec(2, UInt(16.W)))(romSelectAddress(1))
  core.io.ROM_Q := Mux(romSelectAddress(0) && !core.io.ROM_WORD, Cat(romWord(15, 8), romWord(15, 8)), romWord)

  //////////////////////////////////
  // WRAM and BSRAM (external SRAM)
  //////////////////////////////////
  // (The arbiter is above, with the host's port.)
  // WRAM accesses are edge-driven by the CPU clock enables, like the MiSTer:
  // reads one cycle after SYSCLKR_CE, writes on SYSCLKF_CE.
  val wram = Module(new BytePortBridge(addressWidth = 17, wordBase = SramMap.WramBase))
  sramArbiter.io.initiator(1) <> wram.io.mem
  wram.io.address := core.io.WRAM_ADDR
  wram.io.dataWrite := core.io.WRAM_D
  wram.io.ceN := core.io.WRAM_CE_N
  wram.io.oeN := core.io.WRAM_OE_N
  wram.io.weN := core.io.WRAM_WE_N
  wram.io.sample := true.B
  wram.io.invalidate := hostReset
  wram.io.bufferOff := false.B
  wram.io.oneWay := false.B
  core.io.WRAM_Q := wram.io.dataRead

  // A deeper queue for the Super FX's pixel cache flushes (bursts of
  // read-modify-writes at the GSU's full pace).
  val bsram = Module(new BytePortBridge(addressWidth = 18, wordBase = SramMap.BsramBase, queueDepth = 8, bufferWords = 1024))
  sramArbiter.io.initiator(2) <> bsram.io.mem
  bsram.io.address := core.io.BSRAM_ADDR(17, 0)
  bsram.io.dataWrite := core.io.BSRAM_D
  bsram.io.ceN := core.io.BSRAM_CE_N
  bsram.io.oeN := core.io.BSRAM_OE_N
  bsram.io.weN := core.io.BSRAM_WE_N
  bsram.io.sample := true.B
  bsram.io.invalidate := hostReset
  bsram.io.bufferOff := configReg.bsramCacheOff
  bsram.io.oneWay := configReg.bsramOneWay
  core.io.BSRAM_Q := bsram.io.dataRead

  //////////////////////////////////
  // Stall control
  //////////////////////////////////
  // WRAM is only ever accessed by the S-CPU and its DMA, which latch data on
  // SYSCLKF_CE, so its reads are always latency-hidden. ROM and BSRAM are
  // also read by coprocessors on their own schedule, so their reads are only
  // hidden when the firmware says it is safe: at the S-CPU latch alone for
  // base cartridges, DSP-n and S-DD1 (bit 2); also at the GSU's RAM sampling
  // instants for Super FX (bit 7, BSRAM only; see the config bits for why not
  // ROM); also at the SA-1 side's sampling instants for SA-1 (bits 9 and 10).
  // Otherwise the core stalls for as long as a read is outstanding. A nearly
  // full write queue stalls the core in every mode.
  //
  // Config bit 2 asks for hiding in whatever mode is safe for the loaded
  // cartridge (ROM_TYPE bits 7:4 name the coprocessor: 0x7 Super FX, 0x6
  // SA-1, 0x4 CX4); bits 7, 9 and 10 select the coprocessor modes directly
  // and bit 8 (unsafe) is only ever explicit. The "Memory Latency Hiding"
  // setting writes bit 2; the explicit bits are for experiments over SPI.
  val romChip = romTypeReg(7, 4)
  val chipGsu = romChip === 0x7.U
  val chipSa1 = romChip === 0x6.U
  val chipCx4 = romChip === 0x4.U
  val hidingBase = configReg.latencyHiding && !chipGsu && !chipSa1 && !chipCx4
  val hidingGsuRam = configReg.gsuRamLatencyHiding || (configReg.latencyHiding && chipGsu)
  val hidingGsuRom = configReg.gsuRomLatencyHiding
  val hidingSa1Rom = configReg.sa1RomLatencyHiding || (configReg.latencyHiding && chipSa1)
  val hidingSa1Bwram = configReg.sa1BwramLatencyHiding || (configReg.latencyHiding && chipSa1)
  val romHidden = hidingBase || hidingGsuRom || hidingSa1Rom
  val bsramHidden = hidingBase || hidingGsuRam || hidingSa1Bwram
  val romLatch = core.io.SYSCLKF_CE || (hidingGsuRom && core.io.GSU_ROM_SAMPLE) ||
    (hidingSa1Rom && core.io.SA1_ROM_SAMPLE)
  // The S-CPU's clock enable fires every S-CPU cycle whether or not it reads
  // BSRAM; while the GSU / SA-1 owns the RAM bus the S-CPU cannot be reading
  // it, so only the coprocessor's own sampling instants count then.
  val bsramOwned = (hidingGsuRam && core.io.GSU_RAM_OWNED) ||
    (hidingSa1Bwram && core.io.SA1_BWRAM_OWNED)
  val bsramSample = (hidingGsuRam && core.io.GSU_RAM_SAMPLE) ||
    (hidingSa1Bwram && core.io.SA1_BWRAM_SAMPLE)
  val bsramLatch = (core.io.SYSCLKF_CE && !bsramOwned) || bsramSample
  // A ROM read whose data arrives this cycle is forwarded to ROM_Q already.
  val romOutstanding = romPending && !romBuffer.io.respValid
  val stallRom = romOutstanding && Mux(romHidden, romLatch, true.B)
  val stallWram = (wram.io.pendingRead && core.io.SYSCLKF_CE) || wram.io.full
  val stallBsram = (bsram.io.pendingRead && Mux(bsramHidden, bsramLatch, true.B)) || bsram.io.full
  // The save-state program expects its memory port to answer within an
  // access, so the core waits for every transfer (see SaveStateMemoryPort).
  val stallSaveState = ssPort.io.busy
  val stall = stallRom || stallWram || stallBsram || stallSaveState
  // The core runs while it has focus. It also runs while it is halted (held
  // in reset), so that a reset takes effect (the reset register lives in
  // the gated domain) and the PPU counters reach the release point: the
  // firmware halts and runs the core from the pause menu, without focus.
  // And it runs, without focus, for a save-state request (`ssRunPending`)
  // and for the cycle that cancels one (`ssCancel`).
  val coreWant = (regCoreFocus || hostReset || ssRunPending || ssCancel) && !stall
  apuRun := Mux(configReg.apuLockstep, coreRun, regCoreFocus || hostReset || ssRunPending)
  // PAL master clock. A PAL SNES runs its master clock at 21.281 MHz, 0.9 %
  // below NTSC (the MiSTer retunes its PLL). Here the physical clock stays
  // at the NTSC rate and the core's clock enable withholds one edge in ~110,
  // paced by a phase accumulator over the cycles the core would otherwise
  // run, so the core sees exactly `MasterClockPalHz` on average: a 50.0 Hz
  // frame. The 47 ns hole is far shorter than a memory stall, and
  // everything outside the core is qualified with `tick`. The APU is not
  // paced (`apuRun`).
  // The accumulator restarts whenever the region is NTSC, so a region change
  // starts it from zero.
  val palPace = RegInit(0.U(25.W))
  val palSkip = WireDefault(false.B)
  when (!regionPal) {
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
  // Save-state requests: cleared once the core has sampled them, set by a
  // register write (which wins in the same cycle), dropped by a host reset.
  when (coreRun) {
    ssSaveRequest := false.B
    ssLoadRequest := false.B
    ssCancel := false.B
  }
  // The slot comes with the write (bits 3:2) or, with bit 4 set, from the
  // slot register, so that a settings descriptor can request a save or a
  // load with a fixed value. A load of a slot that holds no state is
  // ignored; a save is remembered so the slots are rescanned when it has
  // finished.
  val ssRequestSlot = Mux(ssControlWriteData(4), ssSlotReg, ssControlWriteData(3, 2))
  val ssSaveWrite = ssControlWrite && ssControlWriteData(0)
  val ssLoadWrite = ssControlWrite && ssControlWriteData(1) && ssSlotValid(ssRequestSlot)
  val ssLastWasSave = RegInit(false.B)
  when (ssControlWrite) {
    when (ssControlWriteData(0)) {
      ssSaveRequest := true.B
      ssSaveDone := false.B
      ssLastWasSave := true.B
    }
    when (ssLoadWrite) {
      ssLoadRequest := true.B
      ssLastWasSave := false.B
    }
    ssSlot := ssRequestSlot
  }
  when (ssPort.io.headerWritten) {
    ssSaveDone := true.B
  }
  // A game that waits with interrupts off never runs the program: give up
  // after 5 s, as a cancelling write does.
  val ssTimeout = RegInit(0.U(log2Ceil(5 * ClockSystemHz + 1).W))
  val ssTimedOut = ssRunPending && ssTimeout === (5 * ClockSystemHz).U
  ssTimeout := Mux(ssRunPending, ssTimeout + 1.U, 0.U)
  val regSsBusy = RegNext(core.io.SS_BUSY, false.B)
  when (regSsBusy && !core.io.SS_BUSY) {
    ssRunPending := false.B
    when (ssLastWasSave) {
      ssSlotRescan := true.B
    }
  }
  when (ssTimedOut) {
    ssSaveRequest := false.B
    ssLoadRequest := false.B
    ssRunPending := false.B
    ssCancel := true.B
  }
  when (ssControlWrite) {
    ssRunPending := ssSaveWrite || ssLoadWrite
    // Every write drops the request the core may still hold from before:
    // a cancelling write leaves nothing armed, a new request replaces it
    // (the core takes the request that arrives with the cancel).
    ssCancel := true.B
  }
  when (hostReset) {
    ssSaveRequest := false.B
    ssLoadRequest := false.B
    ssSaveDone := false.B
    ssRunPending := false.B
    ssCancel := false.B
  }
  when (regCoreFocus) {
    when (coreRun) {
      statRegCycles := statRegCycles + 1.U
    } .elsewhen (!palSkip) {
      statRegStalls := statRegStalls + 1.U
      when (stallRom) { statRegStallsRom := statRegStallsRom + 1.U }
      when (stallWram) { statRegStallsWram := statRegStallsWram + 1.U }
      when (stallBsram) {
        statRegStallsBsram := statRegStallsBsram + 1.U
        when (core.io.SYSCLKF_CE) { statRegStallsBsramCpu := statRegStallsBsramCpu + 1.U }
        when (bsramSample) { statRegStallsBsramGsu := statRegStallsBsramGsu + 1.U }
        when (bsram.io.busyWrite) { statRegStallsBsramWrite := statRegStallsBsramWrite + 1.U }
        when (bsram.io.busyPrefetch) { statRegStallsBsramPrefetch := statRegStallsBsramPrefetch + 1.U }
        when (bsram.io.busyDemand) { statRegStallsBsramDemand := statRegStallsBsramDemand + 1.U }
        when (!bsram.io.busyWrite && !bsram.io.busyPrefetch && !bsram.io.busyDemand) { statRegStallsBsramIdle := statRegStallsBsramIdle + 1.U }
        when (wram.io.busyWrite || wram.io.busyDemand) { statRegStallsBsramWram := statRegStallsBsramWram + 1.U }
      }
      when (wram.io.full || bsram.io.full) { statRegStallsFull := statRegStallsFull + 1.U }
      when (stallSaveState) { statRegStallsSaveState := statRegStallsSaveState + 1.U }
    }
  }
  // Worst window: count the current window's stalls and keep the maximum.
  val windowCounter = RegInit(0.U(WindowBits.W))
  val windowStalls = RegInit(0.U(WindowBits.W))
  val windowStallsRom = RegInit(0.U(WindowBits.W))
  val windowStallsWram = RegInit(0.U(WindowBits.W))
  val windowStallsBsram = RegInit(0.U(WindowBits.W))
  when (regCoreFocus) {
    windowCounter := windowCounter + 1.U
    when (!coreRun) {
      windowStalls := windowStalls + 1.U
      when (stallRom) { windowStallsRom := windowStallsRom + 1.U }
      when (stallWram) { windowStallsWram := windowStallsWram + 1.U }
      when (stallBsram) { windowStallsBsram := windowStallsBsram + 1.U }
    }
    when (windowCounter.andR) {
      windowStalls := 0.U
      windowStallsRom := 0.U
      windowStallsWram := 0.U
      windowStallsBsram := 0.U
      when (windowStalls > statRegWorstStalls) {
        statRegWorstStalls := windowStalls
        statRegWorstStallsRom := windowStallsRom
        statRegWorstStallsWram := windowStallsWram
        statRegWorstStallsBsram := windowStallsBsram
      }
    }
  }
  when (romCache.io.miss) {
    statRegRomMisses := statRegRomMisses + 1.U
  }
  when (romCache.io.filling) {
    statRegRomFillCycles := statRegRomFillCycles + 1.U
  }
  when (romRequest) {
    statRegRomRequests := statRegRomRequests + 1.U
  }
  when (romCache.io.prefetch) {
    statRegRomPrefetches := statRegRomPrefetches + 1.U
  }
  when (romHit) {
    statRegRomBufferHits := statRegRomBufferHits + 1.U
  }
  when (bsram.io.readIssued) { statRegBsramReads := statRegBsramReads + 1.U }
  when (bsram.io.bufferConflict) { statRegBsramConflicts := statRegBsramConflicts + 1.U }
  // Strobe-to-sample distance of buffer misses, in core cycles.
  val sampleAge = RegInit(0.U(3.W))
  val sampleArmed = RegInit(false.B)
  when (bsram.io.readIssued && !bsram.io.bufferHit) {
    sampleAge := 0.U
    sampleArmed := true.B
  } .elsewhen (coreRun && sampleAge =/= 7.U) {
    sampleAge := sampleAge + 1.U
  }
  when (sampleArmed && bsramSample && !(bsram.io.readIssued && !bsram.io.bufferHit)) {
    sampleArmed := false.B
    val bucket = Mux(sampleAge >= 4.U, 3.U, Mux(sampleAge === 0.U, 0.U, sampleAge - 1.U))
    for (i <- 0 until 4) { when (bucket === i.U) { statRegSampleAge(i) := statRegSampleAge(i) + 1.U } }
  }
  when (bsram.io.writeIssued) { statRegBsramWrites := statRegBsramWrites + 1.U }
  when (bsram.io.bufferHit) { statRegBsramBufferHits := statRegBsramBufferHits + 1.U }

  //////////////////////////////////
  // Core-clocked glue: VRAM, ARAM, video latch, joypad
  //////////////////////////////////
  val pixelStrobe = Wire(Bool())
  val pixelR = Wire(UInt(8.W))
  val pixelG = Wire(UInt(8.W))
  val pixelB = Wire(UInt(8.W))
  val hblank = Wire(Bool())
  val vblank = Wire(Bool())

  // Single-port RAM with 1-cycle read latency and write-first semantics
  // (matches the `dpram` used by the MiSTer core), in the caller's clock.
  def singlePortRam(depth: Int, address: UInt, dataIn: UInt, write: Bool): UInt = {
    val mem = SyncReadMem(depth, UInt(8.W))
    val readData = mem.read(address)
    when (write) {
      mem.write(address, dataIn)
    }
    Mux(RegNext(write, false.B), RegNext(dataIn), readData)
  }

  // ARAM belongs to the APU. The save-state program on the S-CPU side also
  // reads and writes it (main.v muxes the bus): its accesses hold for one
  // gated cycle, i.e. one or more APU cycles, so a write repeats harmlessly
  // and a read's data is ready by the S-CPU's next edge.
  withClock (apuClock) {
    core.io.ARAM_Q := singlePortRam(64 * 1024, core.io.ARAM_ADDR, core.io.ARAM_D, !core.io.ARAM_CE_N && !core.io.ARAM_WE_N)
  }

  withClock (coreClock) {
    val vramOeDelayed = RegNext(core.io.VRAM_OE_N, true.B)
    val vramReadable = !core.io.VRAM_OE_N && !vramOeDelayed
    val vram1 = singlePortRam(32 * 1024, core.io.VRAM1_ADDR(14, 0), core.io.VRAM1_DO, !core.io.VRAM1_WE_N)
    val vram2 = singlePortRam(32 * 1024, core.io.VRAM2_ADDR(14, 0), core.io.VRAM2_DO, !core.io.VRAM2_WE_N)
    core.io.VRAM1_DI := Mux(vramReadable, vram1, 0xFF.U)
    core.io.VRAM2_DI := Mux(vramReadable, vram2, 0xFF.U)

    // Video: DOTCLK is a 50% clock at the pixel rate (4 master clocks per
    // pixel, 2 in 512-pixel modes); the PPU presents a new pixel on each
    // rising edge.
    val dotclkOld = RegNext(core.io.DOTCLK, false.B)
    val dotclkEdge = core.io.DOTCLK && !dotclkOld
    pixelStrobe := RegNext(dotclkEdge && core.io.HBLANKn && core.io.VBLANKn, false.B)
    pixelR := RegEnable(core.io.R, dotclkEdge)
    pixelG := RegEnable(core.io.G, dotclkEdge)
    pixelB := RegEnable(core.io.B, dotclkEdge)
    hblank := RegEnable(!core.io.HBLANKn, true.B, dotclkEdge)
    vblank := RegEnable(!core.io.VBLANKn, true.B, dotclkEdge)

    // Joypad 1
    val joypad = Module(new SnesJoypad)
    val buttonFilter = Module(new ButtonFilter(new InputV0.Buttons))
    buttonFilter.io.enable := true.B
    buttonFilter.io.input := io.input.buttons
    joypad.io.buttons := buttonFilter.io.output
    joypad.io.latch := core.io.JOY_STRB
    joypad.io.clk := core.io.JOY1_CLK
    core.io.JOY1_DI := Cat(true.B, joypad.io.data)
  }

  //////////////////////////////////
  // Video output (framebuffer is 256 x 240)
  //////////////////////////////////
  // Registers in the gated domain only change on delivered clock edges, so a
  // one-cycle pulse there is consumed here exactly once by qualifying with `tick`.
  //
  // The PPU shows 224 lines, or 239 with overscan enabled ($2133). Its vsync
  // moves down by 8 lines in overscan mode, so on a TV the 239-line picture
  // starts 8 lines above the 224-line one and extends 7 lines below it. The
  // framebuffer has 240 rows: PPU line 1 goes to row 8 (224 lines) or row 0
  // (239 lines), and the rest is padded with black rows generated here
  // during the PPU's vertical blank, so that switching modes leaves no stale
  // rows. The host counts rows on `hblank` edges and restarts on `vblank`,
  // so the PPU's blanking signals are replaced by generated ones: `vblank`
  // pulses after the bottom padding, then the next frame's top padding is
  // written before the PPU's first line arrives (the PPU's vertical blank
  // lasts 38 lines, the padding takes at most 16 rows of 257 cycles).
  val pixelValid = pixelStrobe && tick
  val lineX = RegInit(0.U(10.W))
  val hblankEdge = hblank && !RegNext(hblank, false.B)
  val vblankEdge = vblank && !RegNext(vblank, false.B)
  when (vblank || hblankEdge) {
    lineX := 0.U
  } .elsewhen (pixelValid) {
    lineX := lineX + 1.U
  }
  // In 512-pixel (hi-res) modes, keep every other pixel.
  val highRes = RegEnable(core.io.HIGH_RES, false.B, hblankEdge)
  val pixelKeep = Mux(highRes, !lineX(0), lineX < 256.U)

  object PadState extends ChiselEnum {
    val active, bottom, vsync, top = Value
  }
  val padState = RegInit(PadState.active)
  /** Rows written to the framebuffer so far this frame (PPU lines and padding). */
  val outRow = RegInit(0.U(log2Ceil(OutputLines + 1).W))
  val padX = RegInit(0.U(8.W))
  val padHblank = RegInit(false.B)
  val padPixel = WireDefault(false.B)
  val padRowsLeft = RegInit(0.U(4.W))
  val outVblank = RegInit(false.B)
  val vsyncTimer = RegInit(0.U(6.W))
  padHblank := false.B

  /** Emit one black row: 256 pixels then an hblank edge; true in the cycle the row completes. */
  def padRow(): Bool = {
    val done = WireDefault(false.B)
    when (padHblank) {
      // hblank edge just delivered: row done
      done := true.B
    } .otherwise {
      padPixel := true.B
      padX := padX + 1.U
      when (padX === 255.U) {
        padHblank := true.B
        outRow := outRow + 1.U
      }
    }
    done
  }

  val ppuHblank = hblank && !vblank
  switch (padState) {
    is (PadState.active) {
      when (hblankEdge && !vblank) {
        outRow := outRow + 1.U
      }
      when (vblankEdge) {
        padState := PadState.bottom
        padX := 0.U
      }
    }
    is (PadState.bottom) {
      when (outRow >= OutputLines.U) {
        padState := PadState.vsync
        outVblank := true.B
        vsyncTimer := 63.U
      } .otherwise {
        padRow()
      }
    }
    is (PadState.vsync) {
      vsyncTimer := vsyncTimer - 1.U
      when (vsyncTimer === 0.U) {
        outVblank := false.B
        outRow := 0.U
        padX := 0.U
        padRowsLeft := Mux(core.io.V224_MODE, 8.U, 0.U)
        padState := PadState.top
      }
    }
    is (PadState.top) {
      when (padRowsLeft === 0.U) {
        padState := PadState.active
      } .elsewhen (padRow()) {
        padRowsLeft := padRowsLeft - 1.U
      }
    }
  }

  val ppuPixel = padState === PadState.active && pixelValid && pixelKeep && outRow < OutputLines.U
  io.video.dataEnable := ppuPixel || padPixel
  io.video.data.r := Mux(padPixel, 0.U, pixelR(7, 3))
  io.video.data.g := Mux(padPixel, 0.U, pixelG(7, 3))
  io.video.data.b := Mux(padPixel, 0.U, pixelB(7, 3))
  io.video.hblank := Mux(padState === PadState.active, ppuHblank, padHblank)
  io.video.vblank := outVblank

  //////////////////////////////////
  // Audio
  //////////////////////////////////
  // The DSP emits a sample every 128 of its clock enables, i.e. every
  // 128 * 21477270 / 4096000 = 671.16 APU clocks (~32 kHz). The APU's clock
  // runs free (`apuRun`), so the output register updates at that rate
  // whatever the S-CPU side is doing, and the framework samples it at the
  // DAC rate like the other cores' audio (a zero-order hold). Before the
  // APU had its own clock the samples shared the S-CPU's stalls, bursty
  // within the frame, which frequency-modulated the audio at the frame rate
  // (a fast warble on sustained notes); a rate-servo'd, interpolating FIFO
  // re-timed them then, and its servo made the pitch ramp up for ~200 ms
  // after every pause. With the APU free-running there is no jitter left to
  // remove.
  io.audio.left := core.io.AUDIO_L.asSInt
  io.audio.right := core.io.AUDIO_R.asSInt

  captureIndexWire := 0.U
  captureInterface.dataRead := 0.U
  captureInterface.done := true.B
  if (debugAudio) {
  // Debug: a synthetic 1 kHz sine (256-entry table, phase accumulator on the
  // ungated clock) to test the framework's audio path independently of the
  // core.
  val toneTable = VecInit((0 until 256).map(i => math.round(8191 * math.sin(2 * math.Pi * i / 256)).toInt.S(16.W)))
  val tonePhase = RegInit(0.U(32.W))
  // 1000 Hz * 256 entries / clockSystemHz = table entries per clock, in 24.8 fixed point.
  tonePhase := tonePhase + math.round(1000.0 * 256 * (1 << 24) / ClockSystemHz).toLong.U
  val toneSample = toneTable(tonePhase(31, 24))
  val toneOut = Mux(configReg.loudTone, (toneSample * 4.S)(15, 0).asSInt, toneSample)
  when (configReg.testTone) {
    io.audio.left := toneOut
    io.audio.right := toneOut
  }

  // Sample boundaries for the capture come from a replica of the DSP's
  // CEGen (4.096 MHz enables from the master clock, one sample per 128
  // enables) that runs on the same APU ticks from the same reset, so it
  // stays phase locked to the DSP and sees every sample exactly once. The
  // DSP is told the clock is at the NTSC rate in both regions (`DSP_PAL`),
  // so the modulus is fixed.
  val sampleCeSum = RegInit(0.U(25.W))
  val sampleCe = WireDefault(false.B)
  val sampleCeCount = RegInit(0.U(7.W))
  val sampleStrobe = WireDefault(false.B)
  /** The DSP's `MCLK_FREQ`: `MCLK_NTSC_FREQ` in `DSP_PKG`, `DSP_PAL` being tied low. */
  val sampleCeModulus = MasterClockNtscHz.U(25.W)
  when (!core.io.RESET_N) {
    sampleCeSum := 0.U
    sampleCeCount := 0.U
  } .elsewhen (apuTick) {
    val sum = sampleCeSum + 4096000.U
    when (sum >= sampleCeModulus) {
      sampleCeSum := sum - sampleCeModulus
      sampleCe := true.B
    } .otherwise {
      sampleCeSum := sum
    }
  }
  when (sampleCe) {
    sampleCeCount := sampleCeCount + 1.U
    sampleStrobe := sampleCeCount === 127.U
  }
  // Delay by one clock so the sample register has been updated by the tick
  // the strobe was derived from.
  val sampleValid = RegNext(sampleStrobe, false.B)

  // Debug capture: the raw DSP output (or, with config bit 6, the number of
  // ungated clocks between consecutive DSP samples: 671 / 672 with the APU
  // running free, unless it was paused).
  val captureStrobe = sampleValid
  val capturePeriodCounter = RegInit(0.U(32.W))
  capturePeriodCounter := Mux(captureStrobe, 0.U, capturePeriodCounter + 1.U)
  val captureBuffer = SRAM(1 << CaptureIndexBits, UInt(32.W), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
  val captureIndex = RegInit(0.U(CaptureIndexBits.W))
  captureIndexWire := captureIndex
  captureBuffer.writePorts(0).enable := captureRunReg && captureStrobe
  captureBuffer.writePorts(0).address := captureIndex
  captureBuffer.writePorts(0).data := Mux(configReg.capturePeriod, capturePeriodCounter, Cat(core.io.AUDIO_L, core.io.AUDIO_R))
  when (captureRunReg && captureStrobe) {
    captureIndex := captureIndex + 1.U
  }
  // Playback of the capture buffer through the audio path, at ~32 kHz
  // (config bit 5).
  val playSample = RegInit(0.U(32.W))
  val playCounter = RegInit(0.U(10.W))
  val playIndex = RegInit(0.U(CaptureIndexBits.W))
  val playStrobe = configReg.playCapture && playCounter === 670.U
  playCounter := Mux(playStrobe || !configReg.playCapture, 0.U, playCounter + 1.U)
  when (playStrobe) { playIndex := playIndex + 1.U }
  when (RegNext(playStrobe, false.B)) { playSample := captureBuffer.readPorts(0).data }
  when (configReg.playCapture) {
    io.audio.left := playSample(31, 16).asSInt
    io.audio.right := playSample(15, 0).asSInt
  }
  captureBuffer.readPorts(0).enable := Mux(configReg.playCapture, playStrobe, captureInterface.enable && !captureInterface.write)
  captureBuffer.readPorts(0).address := Mux(configReg.playCapture, playIndex, captureInterface.address(CaptureIndexBits + 1, 2))
  captureInterface.dataRead := captureBuffer.readPorts(0).data
  captureInterface.done := RegNext(captureInterface.enable, false.B)
  } // debugAudio

  //////////////////////////////////
  // SRAM and SDRAM controllers
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
  sramController.io.mem <> sramArbiter.io.target

  withClock(clockSdram) {
    val config = BurstSdramController.Config(
      clockFrequency = ClockSdramHz,
      accessLength = 2,
      timeRsc = (2 * 1_000_000_000) / ClockSdramHz, /* 2 clocks */
      timeWr = (2 * 1_000_000_000) / ClockSdramHz, /* 2 clocks */
      enableBurst = true,
      // See the clock tree notes in the companion object and core_snes.xdc.
      readCaptureFalling = true,
    )
    val sdram = Module(new BurstSdramController(config))
    val cdc = Module(new PipelineMemoryBurstCdc(
      addressWidth = 25,
      dataWidth = 32,
      addressBurstIncrement = 4,
      enablePrefetch = true,
    ))
    cdc.io.slowClock := clock
    cdc.io.initiator <> sdramArbiter.io.target
    cdc.io.target <> sdram.io.mem

    io.sdram.clock := clockSdramPin
    io.sdram.cke := sdram.io.signals.cke
    io.sdram.cs := sdram.io.signals.cs
    io.sdram.ras := sdram.io.signals.ras
    io.sdram.cas := sdram.io.signals.cas
    io.sdram.we := sdram.io.signals.we
    io.sdram.dqm := sdram.io.signals.dqm
    io.sdram.bank := sdram.io.signals.bank
    io.sdram.address := sdram.io.signals.address
    sdram.io.signals.dataIn := io.sdram.dataIn
    io.sdram.dataOut := sdram.io.signals.dataOut
    io.sdram.dataDir := sdram.io.signals.dataDir
  }

  // Video filter (color correction). The SNES outputs 15-bit RGB and wants
  // none: colors pass through until a host loads a table (the firmware
  // loads none for an external core).
  ColorCorrection.setup(
    clock = clock,
    reset = reset,
    videoFilter = io.videoFilter,
    memInterface = colorCorrectInterface,
    passThroughUntilLoaded = true,
  )
}
