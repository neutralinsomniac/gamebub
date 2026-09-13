package lib.mem.cache

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface

object LineReadCache {
  object State extends ChiselEnum {
    val init, idle, lookup, fill, respond, waitPrefetch = Value
  }
  object PrefetchState extends ChiselEnum {
    val idle, lookup, fill = Value
  }
}

/**
 * A read-only cache with multi-word lines, next-line prefetch and one or two
 * ways.
 *
 * Compared to [[DirectReadCache]] (one word per entry), a miss fetches a whole
 * line of `wordsPerLine` consecutive words, so sequential fetches (CPU code,
 * DMA sources) miss once per line instead of once per word, and the
 * sequential fill benefits from the SDRAM CDC's read prefetch.
 *
 * Whenever a request touches a line for the first time (hit or miss), the
 * line `prefetchDistance` lines further on is looked up in the cache's idle
 * cycles and fetched if it is not present, so a sequential stream finds its
 * next line already filled. Use 2 with a [[LineBuffer]] in front, which pulls
 * the next line itself, so that the cache stays ahead of it. A demand miss
 * that arrives while a prefetch fill is in flight waits for it and then looks
 * up again (it is usually the line being prefetched). Prefetches only fill
 * while the demand side is idle, so they never evict a line a demand miss is
 * about to use.
 *
 * With `ways = 2` each set holds two lines (`numLines` is per way) and a
 * fill replaces the least recently used one, so two streams whose lines
 * alias in a direct-mapped cache (code and its data, a DMA source and the
 * code driving it) no longer evict each other.
 *
 * Hit latency: 1 cycle (request accepted in cycle N, data in cycle N+1). A
 * demand miss fetches the requested word first (the rest of the line follows,
 * wrapping around) and, with `eagerIssue`, issues it to `out` in the lookup
 * cycle itself. Every word that arrives is also shown on `fillWord*` in its
 * arrival cycle, so that a front buffer can hand the requested word to the
 * core before the whole line has been fetched and committed (the regular
 * response follows once it has).
 *
 * Writes are not supported (requests with isWrite are ignored). Addresses are
 * byte addresses; `dataWidth` must be 32 and requests are expected to be word
 * aligned.
 */
class LineReadCache(addressWidth: Int, dataWidth: Int, numLines: Int, wordsPerLine: Int, prefetch: Boolean = true, ways: Int = 1, prefetchDistance: Int = 1) extends Module {
  import LineReadCache._
  require(dataWidth == 32)
  require(isPow2(numLines) && isPow2(wordsPerLine))
  require(ways == 1 || ways == 2, "only direct-mapped and 2-way (LRU bit) are supported")

  val io = IO(new Bundle {
    val in = new PipelineMemoryInterface(addressWidth, dataWidth)
    val out = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
    /** Pulses for one cycle on a (demand) cache miss (statistics). */
    val miss = Output(Bool())
    /** High while a line is being fetched, for a demand miss or a prefetch (statistics). */
    val filling = Output(Bool())
    /** Pulses for one cycle when a prefetch fill starts (statistics). */
    val prefetch = Output(Bool())
    /** The whole line containing `in.dataRead`, valid whenever `in.dataRead` is. */
    val line = Output(Vec(wordsPerLine, UInt(dataWidth.W)))
    /** Issue the first word of a demand miss in the lookup cycle itself (else a cycle later). */
    val eagerIssue = Input(Bool())
    /** A word of a line being filled (demand or prefetch) arrives from `out` this cycle. */
    val fillWordValid = Output(Bool())
    val fillWordAddress = Output(UInt(addressWidth.W))
    val fillWordData = Output(UInt(dataWidth.W))
  })
  io.line := DontCare
  io.miss := false.B
  io.prefetch := false.B

  val wordOffsetBits = log2Ceil(dataWidth / 8) // 2
  val wordSelBits = log2Ceil(wordsPerLine)
  val indexBits = log2Ceil(numLines)
  val tagBits = addressWidth - indexBits - wordSelBits - wordOffsetBits
  val lineBytes = wordsPerLine * (dataWidth / 8)

  class Entry extends Bundle {
    val valid = Bool()
    val tag = UInt(tagBits.W)
    val data = Vec(wordsPerLine, UInt(dataWidth.W))
  }
  val entryType = new Entry

  private def wordSel(address: UInt): UInt = address(wordOffsetBits + wordSelBits - 1, wordOffsetBits)
  private def index(address: UInt): UInt = address(wordOffsetBits + wordSelBits + indexBits - 1, wordOffsetBits + wordSelBits)
  private def tag(address: UInt): UInt = address(addressWidth - 1, wordOffsetBits + wordSelBits + indexBits)
  private def lineBase(address: UInt): UInt = Cat(address(addressWidth - 1, wordOffsetBits + wordSelBits), 0.U((wordOffsetBits + wordSelBits).W))

  val cache = Seq.fill(ways)(SRAM(numLines, UInt(entryType.getWidth.W), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0))
  val readPorts = cache.map(_.readPorts(0))
  val writePorts = cache.map(_.writePorts(0))
  // All ways are looked up together and written one at a time (`writeWay`).
  val readEnable = Wire(Bool())
  val readAddress = Wire(UInt(indexBits.W))
  val writeEnable = WireDefault(false.B)
  val writeWay = Wire(UInt(log2Ceil(ways).max(1).W))
  writeWay := DontCare
  val writeAddress = Wire(UInt(indexBits.W))
  writeAddress := DontCare
  val writeData = Wire(UInt(entryType.getWidth.W))
  writeData := DontCare
  for ((port, way) <- readPorts.zipWithIndex) {
    port.enable := readEnable
    port.address := readAddress
  }
  for ((port, way) <- writePorts.zipWithIndex) {
    port.enable := writeEnable && (if (ways == 1) true.B else writeWay === way.U)
    port.address := writeAddress
    port.data := writeData
  }
  /** Victim way per set: the way not used most recently (2-way only). */
  val lru = if (ways == 2) Some(RegInit(VecInit(Seq.fill(numLines)(false.B)))) else None
  private def victim(index: UInt): UInt = lru.map(_(index).asUInt).getOrElse(0.U)
  private def touch(index: UInt, way: UInt): Unit = lru.foreach(_(index) := !way(0))
  // Demand lookups have priority on the read port; prefetch lookups use it
  // in the remaining cycles.
  val demandRead = WireDefault(false.B)
  val demandReadAddress = Wire(UInt(indexBits.W))
  demandReadAddress := DontCare
  val prefetchRead = WireDefault(false.B)
  val prefetchReadAddress = Wire(UInt(indexBits.W))
  prefetchReadAddress := DontCare
  readEnable := demandRead || prefetchRead
  readAddress := Mux(demandRead, demandReadAddress, prefetchReadAddress)
  val readEntries = readPorts.map(_.data.asTypeOf(entryType))
  /** Which way (if any) holds the line of `address` in the entries just read. */
  private def hitVec(address: UInt): Vec[Bool] = VecInit(readEntries.map(e => e.valid && e.tag === tag(address)))
  // A read issued in the same cycle as a write to the memory may return
  // stale data (read-during-write): such lookups are simply repeated.
  val readInvalid = RegNext(writeEnable, false.B)

  val state = RegInit(State.init)
  val initIndex = RegInit(0.U(indexBits.W))
  val regAddress = Reg(UInt(addressWidth.W))
  val regPending = RegInit(false.B) // request received during init
  val regRespondLine = Reg(Vec(wordsPerLine, UInt(dataWidth.W)))

  //////////////////////////////////
  // Line filler, shared by demand misses and prefetches
  //////////////////////////////////
  val fillActive = RegInit(false.B)
  val fillIsPrefetch = Reg(Bool())
  val fillAddress = Reg(UInt(addressWidth.W)) // any address within the line
  val fillWay = Reg(UInt(log2Ceil(ways).max(1).W)) // way to replace
  val fillStart = Reg(UInt(wordSelBits.max(1).W)) // first word issued (the requested one)
  val fillWord = Reg(UInt(log2Ceil(wordsPerLine + 1).W)) // words issued
  val fillReceived = Reg(UInt(log2Ceil(wordsPerLine + 1).W)) // words received
  /** Index within the line of the n-th word issued / received. */
  private def fillIndex(n: UInt): UInt = if (wordsPerLine == 1) 0.U else (fillStart + n(wordSelBits - 1, 0))(wordSelBits - 1, 0)
  private def fillWordAddress(n: UInt): UInt = lineBase(fillAddress) | (fillIndex(n) << wordOffsetBits)
  val fillIssued = Reg(Bool()) // a request is in flight on io.out
  val lineBuffer = Reg(Vec(wordsPerLine, UInt(dataWidth.W)))
  /** Pulses in the cycle the last word arrives and the line is committed. */
  val fillDone = WireDefault(false.B)
  val fillDoneData = Wire(Vec(wordsPerLine, UInt(dataWidth.W)))
  fillDoneData := lineBuffer

  io.filling := fillActive
  io.out.enable := false.B
  io.out.address := DontCare
  io.out.isWrite := false.B
  io.out.writeStrobe := DontCare
  io.out.dataWrite := DontCare

  /**
   * Start filling the line of `address`, its own word first. The first word
   * is issued right away when the target can take it (the caller must not
   * be driving `out` itself: `fillActive` is low).
   */
  def startFill(address: UInt, isPrefetch: Boolean): Unit = {
    val start = if (wordsPerLine == 1) 0.U else wordSel(address)
    fillActive := true.B
    fillIsPrefetch := isPrefetch.B
    fillAddress := address
    fillWay := victim(index(address))
    fillStart := start
    fillReceived := 0.U
    when (io.out.ready && io.eagerIssue) {
      io.out.enable := true.B
      io.out.address := lineBase(address) | (start << wordOffsetBits)
      fillWord := 1.U
      fillIssued := true.B
    } .otherwise {
      fillWord := 0.U
      fillIssued := false.B
    }
  }

  io.fillWordValid := fillActive && io.out.ready && fillIssued
  io.fillWordAddress := fillWordAddress(fillReceived)
  io.fillWordData := io.out.dataRead

  when (fillActive && io.out.ready) {
    // Issue the next word whenever the target can take a request; collect
    // responses in order. At most one request in flight; a ready cycle both
    // delivers the in-flight response (if any) and accepts the next request.
    when (fillIssued) {
      lineBuffer(fillIndex(fillReceived)) := io.out.dataRead
      fillReceived := fillReceived + 1.U
      fillIssued := false.B
    }
    when (fillWord < wordsPerLine.U) {
      io.out.enable := true.B
      io.out.address := fillWordAddress(fillWord)
      fillWord := fillWord + 1.U
      fillIssued := true.B
    }
    when (fillIssued && fillReceived === (wordsPerLine - 1).U) {
      // Last word just arrived: commit the line.
      val entry = Wire(entryType)
      entry.valid := true.B
      entry.tag := tag(fillAddress)
      entry.data := lineBuffer
      entry.data(fillIndex(fillReceived)) := io.out.dataRead
      fillDoneData := entry.data
      writeEnable := true.B
      writeWay := fillWay
      writeAddress := index(fillAddress)
      writeData := entry.asUInt
      touch(index(fillAddress), fillWay)
      fillActive := false.B
      fillDone := true.B
    }
  }

  //////////////////////////////////
  // Demand side
  //////////////////////////////////
  io.in.ready := false.B
  io.in.dataRead := DontCare

  /** Start looking up `address` (read the cache entry). */
  def startLookup(address: UInt): Unit = {
    regAddress := address
    demandRead := true.B
    demandReadAddress := index(address)
    state := State.lookup
  }

  /** Line most recently touched by a demand access, to trigger prefetches once per line. */
  val touchedLine = RegInit(0.U(addressWidth.W))
  val touchedValid = RegInit(false.B)
  val prefetchRequest = RegInit(false.B)
  val prefetchAddress = Reg(UInt(addressWidth.W))

  switch (state) {
    is (State.init) {
      // Invalidate every entry after reset.
      for (port <- writePorts) {
        port.enable := true.B
        port.address := initIndex
        port.data := 0.U
      }
      initIndex := initIndex + 1.U
      when (initIndex === (numLines - 1).U) {
        state := State.idle
      }
      when (io.in.enable) {
        regAddress := io.in.address
        regPending := true.B
      }
      io.in.ready := !regPending
    }

    is (State.idle) {
      io.in.ready := true.B
      when (regPending) {
        io.in.ready := false.B
        regPending := false.B
        startLookup(regAddress)
      } .elsewhen (io.in.enable) {
        startLookup(io.in.address)
      }
    }

    is (State.lookup) {
      when (readInvalid) {
        startLookup(regAddress)
      } .otherwise {
        if (prefetch) {
          val line = lineBase(regAddress)
          when (!touchedValid || line =/= touchedLine) {
            touchedValid := true.B
            touchedLine := line
            prefetchRequest := true.B
            prefetchAddress := line + (lineBytes * prefetchDistance).U
          }
        }
        val hits = hitVec(regAddress)
        when (hits.asUInt.orR) {
          // Hit
          io.in.ready := true.B
          val line = Mux1H(hits, readEntries.map(_.data))
          io.in.dataRead := line(wordSel(regAddress))
          io.line := line
          touch(index(regAddress), OHToUInt(hits))
          state := State.idle
          when (io.in.enable) {
            startLookup(io.in.address)
          }
        } .elsewhen (fillActive) {
          // Miss while a prefetch is filling (the demand side never has its
          // own fill outstanding here): wait for it, then look up again.
          io.miss := true.B
          state := State.waitPrefetch
        } .otherwise {
          // Miss: fetch the line.
          io.miss := true.B
          startFill(regAddress, isPrefetch = false)
          state := State.fill
        }
      }
    }

    is (State.waitPrefetch) {
      when (!fillActive) {
        startLookup(regAddress)
      }
    }

    is (State.fill) {
      when (fillDone) {
        regRespondLine := fillDoneData
        state := State.respond
      }
    }

    is (State.respond) {
      io.in.ready := true.B
      io.in.dataRead := regRespondLine(wordSel(regAddress))
      io.line := regRespondLine
      state := State.idle
      when (io.in.enable) {
        startLookup(io.in.address)
      }
    }
  }

  //////////////////////////////////
  // Prefetch side: uses the read port when the demand side doesn't, and the
  // filler when it is free.
  //////////////////////////////////
  if (prefetch) {
    val prefetchState = RegInit(PrefetchState.idle)
    val regPrefetchAddress = Reg(UInt(addressWidth.W))
    switch (prefetchState) {
      is (PrefetchState.idle) {
        when (prefetchRequest && !demandRead && state =/= State.init) {
          prefetchRequest := false.B
          regPrefetchAddress := prefetchAddress
          prefetchRead := true.B
          prefetchReadAddress := index(prefetchAddress)
          prefetchState := PrefetchState.lookup
        }
      }
      is (PrefetchState.lookup) {
        when (readInvalid) {
          // Repeat the lookup (the demand side has priority on the read port).
          when (!demandRead) {
            prefetchRead := true.B
            prefetchReadAddress := index(regPrefetchAddress)
          } .otherwise {
            prefetchRequest := true.B
            prefetchState := PrefetchState.idle
          }
        } .elsewhen (hitVec(regPrefetchAddress).asUInt.orR) {
          prefetchState := PrefetchState.idle
        } .elsewhen (fillActive || state === State.lookup || state === State.fill) {
          // The filler is busy (typically with the demand miss that touched
          // the previous line) or about to be claimed by a demand miss: retry
          // once it is free.
          prefetchRequest := true.B
          prefetchState := PrefetchState.idle
        } .otherwise {
          io.prefetch := true.B
          startFill(regPrefetchAddress, isPrefetch = true)
          prefetchState := PrefetchState.fill
        }
      }
      is (PrefetchState.fill) {
        when (fillDone) {
          prefetchState := PrefetchState.idle
        }
      }
    }
  }
}
