package psx

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface
import net.gamebub.framework.interface.HostV0

object PsxDisc {
  /** Core -> host request: read a disc sector (see the firmware's `core::disc`). */
  val CommandDiscRead = 0x0400
  /** The disc's file ID (files.json). */
  val DiscFileId = 0
  val SectorBytes = 2352
  val SectorWords = SectorBytes / 4
  /** The sector holding the license text the region is read from. */
  val LicenseSector = 154

  /** Region codes, as the core's `region` input takes them. */
  object Region {
    val Us = 0
    val Japan = 1
    val Europe = 2
  }

  /** The license text's last words for each region. */
  val RegionPatterns = Seq(
    "Entertainment Amer  ica" -> Region.Us,
    "Entertainment Inc." -> Region.Japan,
    "Entertainment Euro pe" -> Region.Europe,
  )

  /** A RAM the host writes and reads (32-bit words, byte addresses) and the glue reads. */
  def hostRam(words: Int, host: MemoryInterface): SRAMInterface[UInt] = {
    val ram = SRAM(words, UInt(32.W), numReadPorts = 1, numWritePorts = 0, numReadwritePorts = 1)
    val port = ram.readwritePorts(0)
    port.enable := host.enable
    port.address := host.address >> 2
    port.isWrite := host.write
    port.writeData := host.dataWrite
    host.dataRead := port.readData
    host.done := RegNext(host.enable, false.B)
    ram
  }
}

/**
 * The PSX's CD drive, fed from a CD image on the SD card by the firmware
 * (in the clk1x domain, the module's clock).
 *
 * The firmware writes the image's table of contents (`toc`, the format of
 * the firmware's `DiscImage::toc`) and reads sectors on request: the glue
 * raises a core -> host request (`commandCore`, command words in
 * `commandWords`, which the host reads at 0xF000_1000) for a sector, which
 * the firmware writes into `sector`.
 *
 * - Region: when the core is first started (`start`, CoreRun) with a disc,
 *   it is held in reset (`holdCore`) while the license sector (154) is read
 *   and its text searched for the region ("...Entertainment Amer  ica",
 *   "... Inc.", "...Euro pe"), as the MiSTer does.
 * - Table of contents: every time the core leaves reset, it gets its track
 *   table through its `trackinfo` port (MiSTer's `disk_t` layout; the core
 *   ignores the port in reset), and again when the host loads another disc
 *   while it runs (`discChanged`): the core's drive takes that as a disc
 *   change, as on the MiSTer, and shows its lid opened and closed.
 * - Sectors: when the core asks for one (`hpsReq`, `hpsLba`), it is
 *   requested from the firmware, then acknowledged and streamed into the
 *   core as 1176 halfwords, low half of each word first (the bytes in file
 *   order).
 */
class PsxDisc(sectorHostAddress: Long) extends Module {
  import PsxDisc._

  val io = IO(new Bundle {
    val toc = new MemoryInterface(addressWidth = 11, dataWidth = 32)
    val sector = new MemoryInterface(addressWidth = 12, dataWidth = 32)
    val commandCore = Flipped(new HostV0.CommandChannel)
    val commandWords = Output(Vec(4, UInt(32.W)))

    /** A disc's table of contents has been loaded. */
    val discLoaded = Input(Bool())
    /** Pulse: a disc's table of contents has been loaded, into the RAM already. */
    val discChanged = Input(Bool())
    /** Pulse: the core is started (CoreRun). */
    val start = Input(Bool())
    /** Keep the core in reset (the region is being read). */
    val holdCore = Output(Bool())
    /** The core is in reset. */
    val coreReset = Input(Bool())
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
    /** Debug: the state machines and the core's request; the last LBA asked for. */
    val debugState = Output(UInt(32.W))
    val lastLba = Output(UInt(32.W))
  })

  val tocRam = hostRam(512, io.toc)
  val sectorRam = hostRam(1024, io.sector)
  val tocRead = tocRam.readPorts(0)
  val sectorRead = sectorRam.readPorts(0)
  tocRead.enable := true.B
  tocRead.address := 0.U
  sectorRead.enable := true.B
  sectorRead.address := 0.U

  //////////////////////////////////
  // Core -> host requests
  //////////////////////////////////
  // Raise the request with the words set; it ends with `done` or `error`
  // from the host, then waits for the host to clear its answer.
  object RequestState extends ChiselEnum {
    val idle, waiting, clearing = Value
  }
  val requestState = RegInit(RequestState.idle)
  val words = RegInit(VecInit(Seq.fill(4)(0.U(32.W))))
  io.commandWords := words
  val requestStart = WireDefault(false.B)
  val requestLba = WireDefault(0.U(32.W))
  /** Pulses when the host has answered; `requestFailed` then says how. */
  val requestDone = WireDefault(false.B)
  val requestFailed = RegInit(false.B)
  io.commandCore.request := requestState === RequestState.waiting
  switch (requestState) {
    is (RequestState.idle) {
      when (requestStart) {
        words(0) := CommandDiscRead.U
        words(1) := DiscFileId.U
        words(2) := requestLba
        words(3) := sectorHostAddress.U
        requestState := RequestState.waiting
      }
    }
    is (RequestState.waiting) {
      when (io.commandCore.done || io.commandCore.error) {
        requestFailed := io.commandCore.error
        requestState := RequestState.clearing
      }
    }
    is (RequestState.clearing) {
      when (!io.commandCore.done && !io.commandCore.error) {
        requestState := RequestState.idle
        requestDone := true.B
      }
    }
  }

  //////////////////////////////////
  // Region (once per disc)
  //////////////////////////////////
  object InitState extends ChiselEnum {
    val idle, request, waiting, scanRead, scanBytes, done = Value
  }
  val initState = RegInit(InitState.idle)
  val regionReg = RegInit(0.U.asTypeOf(Valid(UInt(2.W))))
  io.region := regionReg
  io.holdCore := initState =/= InitState.idle && initState =/= InitState.done
  val maxPattern = RegionPatterns.map(_._1.length).max
  /** The last bytes scanned, newest first. */
  val window = Reg(Vec(maxPattern, UInt(8.W)))
  val scanWord = Reg(UInt(10.W))
  val scanByte = Reg(UInt(2.W))
  val scanData = Reg(UInt(32.W))
  switch (initState) {
    is (InitState.idle) {
      when (io.start && io.discLoaded) {
        initState := InitState.request
      }
    }
    is (InitState.request) {
      when (requestState === RequestState.idle) {
        requestStart := true.B
        requestLba := LicenseSector.U
        initState := InitState.waiting
      }
    }
    is (InitState.waiting) {
      when (requestDone) {
        scanWord := 0.U
        initState := Mux(requestFailed, InitState.done, InitState.scanRead)
      }
    }
    is (InitState.scanRead) {
      // The word is out of the RAM in the next cycle.
      sectorRead.address := scanWord
      scanByte := 0.U
      initState := InitState.scanBytes
    }
    is (InitState.scanBytes) {
      val data = Mux(scanByte === 0.U, sectorRead.data, scanData)
      when (scanByte === 0.U) {
        scanData := sectorRead.data
      }
      val byte = (data >> (scanByte ## 0.U(3.W)))(7, 0)
      val shifted = VecInit(byte +: window.init)
      window := shifted
      for ((text, code) <- RegionPatterns) {
        val bytes = text.getBytes("ASCII").toSeq
        // newest byte = the pattern's last
        val hit = bytes.reverse.zipWithIndex.map { case (b, i) => shifted(i) === (b & 0xFF).U }.reduce(_ && _)
        when (hit && !regionReg.valid) {
          regionReg.valid := true.B
          regionReg.bits := code.U
        }
      }
      scanByte := scanByte + 1.U
      when (scanByte === 3.U) {
        scanWord := scanWord + 1.U
        initState := Mux(scanWord === (SectorWords - 1).U, InitState.done, InitState.scanRead)
      }
    }
    is (InitState.done) {}
  }

  //////////////////////////////////
  // Track table, whenever the core leaves reset
  //////////////////////////////////
  // Core word w (MiSTer's disk_t): 0 track count, 1 lead-out LBA, 2 lead-out
  // minutes / seconds (BCD), 3 libcrypt mask / region / reset, then per
  // track 4 words: first LBA (0 for track 1, as on the MiSTer), last LBA,
  // index 1 minutes / seconds (BCD) with bit 16 = audio, and a word whose
  // write stores the track. They come from the same TOC words, except the
  // third of a track's (TOC word 4n + 7, the index 1 position).
  val trackCount = RegInit(0.U(7.W))
  val tocWrite = RegInit(false.B)
  val tocWord = Reg(UInt(9.W))
  val tocPhase = RegInit(false.B)
  val leftReset = io.discLoaded && !io.coreReset && RegNext(io.coreReset, true.B)
  // (In reset, a new disc waits for the core to leave it.)
  val changedRunning = io.discChanged && !io.coreReset
  val settle = RegInit(0.U(5.W))
  when (leftReset || changedRunning) {
    settle := 16.U
  }
  when (settle =/= 0.U) {
    settle := settle - 1.U
    when (settle === 1.U) {
      tocWrite := true.B
      tocWord := 0.U
      tocPhase := false.B
    }
  }
  io.trackinfoWrite := false.B
  io.trackinfoAddr := tocWord
  io.trackinfoData := 0.U
  val isTrackWord = tocWord >= 4.U
  val trackField = tocWord(1, 0)
  when (tocWrite) {
    // Phase 0: the TOC word is read; phase 1: the core gets it.
    tocRead.address := Mux(isTrackWord && trackField === 2.U, tocWord + 1.U, tocWord)
    tocPhase := !tocPhase
    when (tocPhase) {
      val toc = tocRead.data
      val data = WireDefault(toc)
      when (tocWord === 0.U) {
        trackCount := toc(6, 0)
      } .elsewhen (tocWord === 2.U) {
        data := toc(23, 8)
      } .elsewhen (tocWord === 3.U) {
        // MiSTer metadata region: 0 unknown, 1 Japan, 2 US, 3 Europe
        val code = MuxLookup(regionReg.bits, 0.U(2.W))(Seq(
          Region.Us.U -> 2.U, Region.Japan.U -> 1.U, Region.Europe.U -> 3.U))
        data := Cat(Mux(regionReg.valid, code, 0.U(2.W)), 0.U(16.W))
      } .elsewhen (isTrackWord) {
        switch (trackField) {
          is (0.U) { data := Mux(tocWord === 4.U, 0.U, toc) }
          is (2.U) { data := Cat(toc(31), toc(23, 8)) }
          is (3.U) { data := 0.U }
        }
      }
      io.trackinfoWrite := true.B
      io.trackinfoData := data
      val last = tocWord >= 3.U && tocWord === (Cat(Mux(tocWord === 0.U, toc(6, 0), trackCount), 0.U(2.W)) + 3.U)
      tocWord := tocWord + 1.U
      when (tocWord =/= 0.U && last) {
        tocWrite := false.B
      }
    }
  }

  //////////////////////////////////
  // Sectors
  //////////////////////////////////
  object SectorState extends ChiselEnum {
    val idle, request, waiting, ack, stream = Value
  }
  val sectorState = RegInit(SectorState.idle)
  val halfword = Reg(UInt(11.W))
  val waitCycles = Reg(UInt(32.W))
  val sectorReads = RegInit(0.U(32.W))
  val readErrors = RegInit(0.U(32.W))
  val worstWait = RegInit(0.U(32.W))
  io.sectorReads := sectorReads
  io.readErrors := readErrors
  io.worstWait := worstWait
  when (io.clearSectorReads) { sectorReads := 0.U }
  when (io.clearReadErrors) { readErrors := 0.U }
  when (io.clearWorstWait) { worstWait := 0.U }
  io.hpsAck := false.B
  val streamValid = RegInit(false.B)
  val streamHigh = Reg(Bool())
  streamValid := false.B
  switch (sectorState) {
    is (SectorState.idle) {
      when (io.hpsReq && initState =/= InitState.request && initState =/= InitState.waiting) {
        sectorState := SectorState.request
        waitCycles := 0.U
      }
    }
    is (SectorState.request) {
      waitCycles := waitCycles + 1.U
      when (requestState === RequestState.idle) {
        requestStart := true.B
        requestLba := io.hpsLba
        sectorState := SectorState.waiting
      }
    }
    is (SectorState.waiting) {
      waitCycles := waitCycles + 1.U
      when (requestDone) {
        sectorReads := sectorReads + 1.U
        when (requestFailed) {
          readErrors := readErrors + 1.U
        }
        when (waitCycles > worstWait) {
          worstWait := waitCycles
        }
        sectorState := SectorState.ack
      }
    }
    is (SectorState.ack) {
      // The core takes the data from the cycle after the acknowledge.
      io.hpsAck := true.B
      halfword := 0.U
      sectorState := SectorState.stream
    }
    is (SectorState.stream) {
      sectorRead.address := halfword >> 1
      streamValid := true.B
      streamHigh := halfword(0)
      halfword := halfword + 1.U
      when (halfword === (SectorBytes / 2 - 1).U) {
        sectorState := SectorState.idle
      }
    }
  }
  io.hpsWrite := streamValid

  io.debugState := Cat(io.hpsReq, 0.U(3.W), sectorState.asUInt.pad(4), requestState.asUInt.pad(4), initState.asUInt.pad(4))
  io.lastLba := RegEnable(requestLba, 0.U(32.W), requestStart)
  io.hpsData := Mux(streamHigh, sectorRead.data(31, 16), sectorRead.data(15, 0))
}
