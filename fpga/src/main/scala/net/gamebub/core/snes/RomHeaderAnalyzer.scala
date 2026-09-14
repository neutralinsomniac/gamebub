package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface

object RomHeaderAnalyzer {
  /** What the analysis found (valid while `busy` is low). */
  class Result extends Bundle {
    /** The MiSTer core's ROM_TYPE: bits 7:4 the coprocessor, bits 3:0 the mapper flags. */
    val romType = UInt(8.W)
    /** Padded ROM size = 1 KiB << code (from the file size, or the Sufami header). */
    val romSizeCode = UInt(4.W)
    /** Cartridge RAM size = 1 KiB << code, none when 0. */
    val ramSizeCode = UInt(4.W)
    /** The header's region byte says PAL. */
    val pal = Bool()
    /** A plausible internal header was found (else LoROM, no RAM, NTSC). */
    val headerFound = Bool()
    /** Which candidate header won: 0 LoROM (0x7FC0), 1 HiROM (0xFFC0), 2 ExHiROM (0x40FFC0). */
    val headerIndex = UInt(2.W)
    /** The file has a 512-byte copier header. */
    val copierHeader = Bool()
    /** The cartridge type is not supported by the bitstream, or the ROM is larger than 16 MiB. */
    val unsupported = Bool()
  }

  val Candidates = Seq(0x007FC0, 0x00FFC0, 0x40FFC0)
  /** Bytes read before the header (the extended header, for the BS-X slot and Super FX RAM size). */
  val Pre = 16
  val WindowWords = (Pre + 64) / 4

  // Header byte offsets.
  val Mapper = 0x15
  val RomType = 0x16
  val RomSize = 0x17
  val RamSize = 0x18
  val CartRegion = 0x19
  val Company = 0x1A
  val Complement = 0x1C
  val Checksum = 0x1E
  val ResetVector = 0x3C

  val BsxBiosTitle = "Satellaview BS-X     ".getBytes("ASCII").toSeq
  val SufamiId = "BANDAI SFC-ADX".getBytes("ASCII").toSeq
  val Cc92Header = Seq(
    0x00, 0x08, 0x22, 0x02, 0x1C, 0x00, 0x10, 0x00, 0x08, 0x65, 0x80, 0x84, 0x20, 0x00, 0x22, 0x25,
    0x00, 0x83, 0x0C, 0x80, 0x10, 0x00, 0x00, 0xA0, 0x80, 0x01, 0x80, 0x80, 0x00, 0x01, 0x02, 0x2D,
  )
  val Pf94_10kHeader = Seq(
    0xC9, 0x80, 0x80, 0x44, 0x15, 0x00, 0x62, 0x09, 0x29, 0xA0, 0x52, 0x70, 0x50, 0x12, 0x05, 0x35,
    0x31, 0x63, 0xC0, 0x22, 0x01, 0x80, 0xC2, 0x3A, 0x6C, 0xB0, 0xE8, 0x4A, 0x11, 0x20, 0xC0, 0xF8,
  )
  val Pf94_1mHeader = Seq(
    0x50, 0x52, 0x45, 0x48, 0x49, 0x53, 0x54, 0x4F, 0x52, 0x49, 0x4B, 0x20, 0x4D, 0x41, 0x4E, 0x20,
    0x20, 0x20, 0x20, 0x20, 0x20, 0x30, 0x00, 0x0A, 0x00, 0x01, 0x33, 0x00, 0xFF, 0xFF, 0x00, 0x00,
    0xFF, 0xFF, 0xFF, 0xFF, 0x2B, 0x80, 0x2B, 0x80, 0x2B, 0x80, 0xFE, 0x91, 0x2B, 0x80, 0xA4, 0xF7,
    0xFF, 0xFF, 0xFF, 0xFF, 0x2B, 0x80, 0x2B, 0x80, 0x2B, 0x80, 0x75, 0xF7, 0x00, 0x80, 0xA4, 0xF7,
  )
}

/**
 * Analyzes the ROM file in the SDRAM the way MiSTer's
 * `Main_MiSTer/support/snes/snes.cpp` does, so that the core needs no
 * firmware driver: scores the three candidate internal headers (LoROM,
 * HiROM, ExHiROM), and from the winner derives the MiSTer core's ROM_TYPE
 * (coprocessor and mapper), the ROM and RAM size codes and the region.
 *
 * `start` (with `fileSize`, the bytes transferred, a copier header
 * included) runs the analysis: some seventy single-word reads through
 * `mem` (the file's layout in the SDRAM, the copier header offset applied
 * here), a couple of thousand cycles; `busy` is high meanwhile and
 * `result` is valid afterwards.
 */
class RomHeaderAnalyzer extends Module {
  import RomHeaderAnalyzer._

  val io = IO(new Bundle {
    val start = Input(Bool())
    val fileSize = Input(UInt(25.W))
    val busy = Output(Bool())
    val mem = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
    val result = Output(new Result)
  })

  object State extends ChiselEnum {
    val idle, readSufami, readWindow, readOpcode, evaluate = Value
  }
  val state = RegInit(State.idle)
  val result = Reg(new Result)
  io.result := result
  io.busy := state =/= State.idle

  /** The file's ROM offset (the copier header) and the ROM size. */
  val base = Reg(UInt(25.W))
  val size = Reg(UInt(25.W))
  /** The size code from the file size, before a Sufami override. */
  val fileSizeCode = Reg(UInt(4.W))
  val cand = Reg(UInt(2.W))
  val bestScore = Reg(UInt(6.W))
  /** The 80 bytes around the current candidate header: 16 before it, then the 64 of the header. */
  val window = Reg(Vec(WindowWords, UInt(32.W)))
  /** The first 16 bytes of the ROM (the Sufami Turbo id). */
  val head = Reg(Vec(4, UInt(32.W)))
  /** The word holding the first opcode at the reset vector, and which byte it is. */
  val opcodeWord = Reg(UInt(32.W))
  val opcodeByte = Reg(UInt(2.W))
  /** Special cartridges recognized from the LoROM window (candidate 0) and the ROM start. */
  val isBsxBios = Reg(Bool())
  val isCc92 = Reg(Bool())
  val isPf94 = Reg(Bool())
  val isSufami = Reg(Bool())

  /** The highest set bit of `x`, as a one-hot (0 when `x` is 0). */
  def highestOneBit(x: UInt): UInt = {
    val w = x.getWidth
    VecInit((0 until w).map(i => x(i) && !(if (i == w - 1) false.B else x(w - 1, i + 1).orR))).asUInt
  }

  //////////////////////////////////
  // Read sequencer: one word at a time (issue, then data)
  //////////////////////////////////
  val readBase = Reg(UInt(25.W))
  val readIndex = Reg(UInt(5.W))
  val readCount = Reg(UInt(5.W))
  val readDataPhase = RegInit(false.B)
  val reading = state === State.readSufami || state === State.readWindow || state === State.readOpcode
  val readDone = WireDefault(false.B)
  io.mem.enable := false.B
  io.mem.address := readBase + (readIndex << 2)
  io.mem.isWrite := false.B
  io.mem.writeStrobe := 0.U
  io.mem.dataWrite := 0.U
  when (reading) {
    when (!readDataPhase) {
      io.mem.enable := true.B
      when (io.mem.ready) {
        readDataPhase := true.B
      }
    } .elsewhen (io.mem.ready) {
      readDataPhase := false.B
      switch (state) {
        is (State.readSufami) { head(readIndex(1, 0)) := io.mem.dataRead }
        is (State.readWindow) { window(readIndex) := io.mem.dataRead }
        is (State.readOpcode) { opcodeWord := io.mem.dataRead }
      }
      readIndex := readIndex + 1.U
      when (readIndex === readCount - 1.U) {
        readDone := true.B
      }
    }
  }
  def startRead(address: UInt, count: Int, next: State.Type): Unit = {
    readBase := address
    readIndex := 0.U
    readCount := count.U
    state := next
  }

  //////////////////////////////////
  // The window's bytes
  //////////////////////////////////
  def windowByte(i: Int): UInt = window(i / 4)((i % 4) * 8 + 7, (i % 4) * 8)
  /** Header byte `k` (0..63). */
  def hdr(k: Int): UInt = windowByte(Pre + k)
  /** Extended header byte `PRE - n` (the n-th byte before the header). */
  def ext(n: Int): UInt = windowByte(Pre - n)
  def headByte(i: Int): UInt = head(i / 4)((i % 4) * 8 + 7, (i % 4) * 8)
  def matches(bytes: Seq[Int], at: Int => UInt): Bool =
    bytes.zipWithIndex.map { case (b, i) => at(i) === b.U }.reduce(_ && _)

  val candAddress = VecInit(Candidates.map(_.U(25.W)))(cand)
  val candIs = (0 until 3).map(i => cand === i.U)
  val resetVector = Cat(hdr(ResetVector + 1), hdr(ResetVector))
  val opcode = opcodeWord.asTypeOf(Vec(4, UInt(8.W)))(opcodeByte)

  //////////////////////////////////
  // Scoring (score_header)
  //////////////////////////////////
  val score = {
    val mapper = hdr(Mapper) & ~0x10.U(8.W)
    val checksum = Cat(hdr(Checksum + 1), hdr(Checksum))
    val complement = Cat(hdr(Complement + 1), hdr(Complement))
    def oneOf(values: Int*): Bool = values.map(v => opcode === v.U).reduce(_ || _)
    val terms = Seq[(Bool, Int)](
      // Most likely opcodes: sei, clc, sec, stz, jmp, jml
      (oneOf(0x78, 0x18, 0x38, 0x9C, 0x4C, 0x5C), 8),
      // Plausible: rep, sep, lda/ldx/ldy, jsr, jsl
      (oneOf(0xC2, 0xE2, 0xAD, 0xAE, 0xAC, 0xAF, 0xA9, 0xA2, 0xA0, 0x20, 0x22), 4),
      // Implausible: rti, rts, rtl, cmp, cpx, cpy
      (oneOf(0x40, 0x60, 0x6B, 0xCD, 0xEC, 0xCC), -4),
      // Least likely: brk, cop, stp, wdm, sbc
      (oneOf(0x00, 0x02, 0xDB, 0x42, 0xFF), -8),
      ((checksum +& complement)(15, 0) === 0xFFFF.U && checksum =/= 0.U && complement =/= 0.U, 4),
      (candIs(0) && mapper === 0x20.U, 2),
      (candIs(1) && mapper === 0x21.U, 2),
      (candIs(0) && mapper === 0x22.U, 2),
      (candIs(2) && mapper === 0x25.U, 2),
      (hdr(Company) === 0x33.U, 2),
      (hdr(RomType) < 0x08.U, 1),
      (hdr(RomSize) < 0x10.U, 1),
      (hdr(RamSize) < 0x08.U, 1),
      (hdr(CartRegion) < 14.U, 1),
    )
    val sum = terms.map { case (cond, v) => Mux(cond, v.S(8.W), 0.S(8.W)) }.reduce(_ +& _)
    val clamped = Mux(sum < 0.S, 0.U, sum.asUInt(5, 0))
    // $00:[0000-7fff] is RAM/MMIO; the reset vector must point into ROM.
    val valid = resetVector >= 0x8000.U
    // Favor ExHiROM on images > 32 Mbit.
    Mux(!valid, 0.U(6.W), Mux(candIs(2) && clamped =/= 0.U, clamped +& 4.U, clamped))
  }

  //////////////////////////////////
  // Decision (analyze)
  //////////////////////////////////
  val bsxBiosNow = matches(BsxBiosTitle.map(_.toInt), hdr)
  val cc92Now = matches(Cc92Header, hdr)
  val pf94Now = matches(Pf94_10kHeader, hdr) || matches(Pf94_1mHeader, hdr)
  // Candidate 0's window is the LoROM probe the flags come from: use them
  // as computed for its own decision, latched for the others.
  val bsxBios = Mux(candIs(0), bsxBiosNow, isBsxBios)
  val cc92 = Mux(candIs(0), cc92Now, isCc92)
  val pf94 = Mux(candIs(0), pf94Now, isPf94)

  val decided = Wire(new Bundle {
    val romType = UInt(8.W)
    val romSizeCode = UInt(4.W)
    val ramSizeCode = UInt(4.W)
    val pal = Bool()
  })
  locally {
    val mapper = hdr(Mapper)
    val rtype = hdr(RomType)
    val company = hdr(Company)
    val hasBsxSlot = ext(14) === 'Z'.toInt.U && ext(11) === 'J'.toInt.U &&
      ((ext(13) >= 'A'.toInt.U && ext(13) <= 'Z'.toInt.U) || (ext(13) >= '0'.toInt.U && ext(13) <= '9'.toInt.U)) &&
      (company === 0x33.U || (ext(10) === 0.U && ext(4) === 0.U))
    // Rom type: 0-LoROM, 1-HiROM, 2-ExHiROM, 3-Special LoROM
    val romTypeBase = Mux(candIs(1), 1.U(8.W), Mux(candIs(2), 2.U(8.W), Mux(hasBsxSlot, 3.U(8.W), 0.U(8.W))))
    val ramsz0 = Mux(hdr(RamSize) >= 9.U, 0.U(4.W), hdr(RamSize)(3, 0))

    // The coprocessor detection of the general case.
    val romType = WireDefault(romTypeBase)
    val ramsz = WireDefault(ramsz0)
    val romSizeCode = WireDefault(fileSizeCode)
    // DSPn types 8..B, OBC1 type C (exclusive chain)
    when (mapper === 0x20.U && rtype === 0x03.U) {
      romType := romTypeBase | 0x84.U // DSP1
    } .elsewhen (mapper === 0x21.U && rtype === 0x03.U) {
      romType := romTypeBase | 0x80.U // DSP1B
    } .elsewhen (mapper === 0x30.U && rtype === 0x05.U && company =/= 0xB2.U) {
      romType := romTypeBase | 0x80.U // DSP1B
    } .elsewhen (mapper === 0x31.U && (rtype === 0x03.U || rtype === 0x05.U)) {
      romType := romTypeBase | 0x80.U // DSP1B
    } .elsewhen (mapper === 0x20.U && rtype === 0x05.U) {
      romType := romTypeBase | 0x90.U // DSP2
    } .elsewhen (mapper === 0x30.U && rtype === 0x05.U && company === 0xB2.U) {
      romType := romTypeBase | 0xA0.U // DSP3
    } .elsewhen (mapper === 0x30.U && rtype === 0x03.U) {
      romType := romTypeBase | 0xB0.U // DSP4
    } .elsewhen (mapper === 0x30.U && rtype === 0xF6.U) {
      // ST010, or ST011 for a small ROM
      romType := romTypeBase | Mux(hdr(RomSize) < 10.U, 0xA8.U, 0x88.U)
      ramsz := 1.U
    } .elsewhen (mapper === 0x30.U && rtype === 0x25.U) {
      romType := romTypeBase | 0xC0.U // OBC1
    }
    // Further chips OR onto the above (as MiSTer does).
    val spc7110 = mapper === 0x3A.U && (rtype === 0xF5.U || rtype === 0xF9.U)
    val srtc = mapper === 0x35.U && rtype === 0x55.U
    val cx4 = mapper === 0x20.U && rtype === 0xF3.U
    val sdd1 = mapper === 0x32.U && (rtype === 0x43.U || rtype === 0x45.U) && fileSizeCode < 14.U
    val sa1 = mapper === 0x23.U && (rtype === 0x32.U || rtype === 0x33.U || rtype === 0x34.U || rtype === 0x35.U)
    val gsu = mapper === 0x20.U && (rtype === 0x13.U || rtype === 0x14.U || rtype === 0x15.U || rtype === 0x1A.U)
    val extra =
      Mux(spc7110, 0xD0.U(8.W) | Mux(rtype === 0xF9.U, 0x08.U(8.W), 0.U(8.W)), 0.U(8.W)) | // SPC7110 (+ RTC)
      Mux(srtc, 0x08.U(8.W), 0.U(8.W)) | // S-RTC (+ExHiROM)
      Mux(cx4, 0x40.U(8.W), 0.U(8.W)) |
      Mux(sdd1, 0x50.U(8.W), 0.U(8.W)) | // (except Star Ocean un-SDD1)
      Mux(sa1, 0x60.U(8.W), 0.U(8.W)) |
      Mux(gsu, 0x70.U(8.W), 0.U(8.W))
    // GSU: the RAM size from the extended header (StarFox has none)
    val gsuRam = ext(3)
    val extraRamsz = Mux(gsu, Mux(gsuRam === 0xFF.U, 5.U(4.W), Mux(gsuRam > 6.U, 6.U(4.W), gsuRam(3, 0))), ramsz)
    val generalType = romType | extra

    val region = hdr(CartRegion)
    // Sufami Turbo (base cart only): sizes from its own header.
    val sufamiRomSize = VecInit(Seq(0, 7, 8, 9, 9, 10, 10, 10, 10).map(_.U(4.W)))
    val sufamiRamSize = VecInit(Seq(0, 1, 2, 3, 3).map(_.U(4.W)))
    val sufamiRom = Mux(hdr(0x36) >= 8.U, 10.U, sufamiRomSize(hdr(0x36)(3, 0)))
    val sufamiRam = Mux(hdr(0x37) >= 4.U, 3.U, sufamiRamSize(hdr(0x37)(2, 0)))

    when (bsxBios) {
      decided.romType := 0x30.U
      decided.ramSizeCode := ramsz0
      decided.romSizeCode := fileSizeCode
    } .elsewhen (isSufami) {
      decided.romType := 0x20.U
      decided.ramSizeCode := sufamiRam
      decided.romSizeCode := sufamiRom
    } .elsewhen (cc92) {
      decided.romType := 0xE4.U
      decided.ramSizeCode := 3.U
      decided.romSizeCode := fileSizeCode
    } .elsewhen (pf94) {
      decided.romType := 0xF4.U
      decided.ramSizeCode := 3.U
      decided.romSizeCode := fileSizeCode
    } .otherwise {
      decided.romType := generalType
      decided.ramSizeCode := extraRamsz
      decided.romSizeCode := romSizeCode
    }
    decided.pal := ((region >= 2.U && region <= 12.U) || region === 17.U) && !isSufami && !cc92 && !pf94
  }

  def isUnsupported(romType: UInt, romSizeCode: UInt): Bool = {
    val chip = romType(7, 4)
    // Keep in sync with the coprocessors enabled in `snes.SnesCoreConfig`.
    chip === 0x2.U || chip === 0x3.U || chip === 0xD.U || chip === 0xE.U || chip === 0xF.U ||
      romSizeCode > 14.U
  }

  //////////////////////////////////
  // Sequence
  //////////////////////////////////
  switch (state) {
    is (State.idle) {
      when (io.start) {
        val header = io.fileSize(9)
        val romBase = Mux(header, 512.U(25.W), 0.U(25.W))
        val romSize = io.fileSize - romBase
        // The size code from the file size: the highest bit of size - 1
        // (bit 24 or above: code 15, i.e. too large).
        val sizeMinusOne = romSize - 1.U
        val top = highestOneBit(sizeMinusOne)
        val topBit = OHToUInt(top)
        val code = Mux(sizeMinusOne(24) || sizeMinusOne === 0.U, Mux(sizeMinusOne(24), 15.U, 0.U),
          Mux(topBit < 9.U, 0.U, (topBit - 9.U)(3, 0)))
        base := romBase
        size := romSize
        fileSizeCode := code
        bestScore := 0.U
        cand := 0.U
        result.romType := 0.U
        result.romSizeCode := code
        result.ramSizeCode := 0.U
        result.pal := false.B
        result.headerFound := false.B
        result.headerIndex := 0.U
        result.copierHeader := header
        result.unsupported := code > 14.U
        isBsxBios := false.B
        isCc92 := false.B
        isPf94 := false.B
        isSufami := false.B
        startRead(romBase, 4, State.readSufami)
      }
    }
    is (State.readSufami) {
      when (readDone) {
        state := State.evaluate // dispatches to the first candidate below
      }
    }
    is (State.readWindow) {
      when (readDone) {
        // The first opcode executed at the reset vector.
        val opAddress = (candAddress & ~0x7FFF.U(25.W)) | (resetVector & 0x7FFF.U)
        val fileAddress = base + opAddress
        opcodeByte := fileAddress(1, 0)
        startRead(Cat(fileAddress(24, 2), 0.U(2.W)), 1, State.readOpcode)
      }
    }
    is (State.readOpcode) {
      when (readDone) {
        state := State.evaluate
      }
    }
    is (State.evaluate) {
      // Entered after the Sufami read (no candidate scored yet: `cand` is
      // 0 and nothing was read) and after each candidate's reads.
      val afterSufami = RegNext(state === State.readSufami, false.B)
      when (afterSufami) {
        isSufami := size >= 14.U && matches(SufamiId.map(_.toInt), headByte)
      } .otherwise {
        when (candIs(0)) {
          isBsxBios := bsxBiosNow
          isCc92 := cc92Now
          isPf94 := pf94Now
        }
        when (score > bestScore) {
          bestScore := score(5, 0)
          result.headerFound := true.B
          result.headerIndex := cand
          result.romType := decided.romType
          result.romSizeCode := decided.romSizeCode
          result.ramSizeCode := decided.ramSizeCode
          result.pal := decided.pal
          result.unsupported := isUnsupported(decided.romType, decided.romSizeCode)
        }
      }
      // Next candidate, skipping those the ROM is too small for.
      val next = Mux(afterSufami, 0.U, cand + 1.U)
      val nextAddress = VecInit(Candidates.map(_.U(25.W)))(next(1, 0))
      when (!afterSufami && cand === 2.U) {
        state := State.idle
      } .elsewhen (size >= nextAddress + 64.U) {
        cand := next
        startRead(base + nextAddress - Pre.U, WindowWords, State.readWindow)
      } .otherwise {
        // Too small for this candidate (and so for the larger ones).
        state := State.idle
      }
    }
  }
}
