package nes

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

object NesRomLoader {
  /** Raw uploaded ROM staging base in SDRAM (12 MiB: 0x040_0000..0x0FF_FFFF). */
  val StagingBase = 0x040_0000L
  /** Optional FDS BIOS staging base in SDRAM (8 KiB: 0x03B_0000..0x03B_1FFF). */
  val FdsBiosStageBase = 0x03B_0000L
  /** FDS BIOS destination in NES SDRAM map (0x000_0000..0x000_1FFF). */
  val FdsBiosDestBase = 0x000_0000L
  val FdsBiosSize = 0x2000
  /** NES CPU-RAM (2 KiB at 0x380000) and CHR-VRAM (2 KiB at 0x3A0000). */
  val CpuRamBase = 0x038_0000L
  val CpuRamSize = 0x800 // 2 KiB
  val VramBase = 0x03A_0000L
  val VramSize = 0x800 // 2 KiB
  /** CARTRAM / EEPROM transfer base in SDRAM (cleared/loaded by files.json id 2). */
  val CartRamBase = 0x03C_0000L
  val EepromSize = 0x800 // 2 KiB

  /** Little-endian 32-bit magic values at offset 0 of uploaded ROM. */
  val MagicNes = 0x1A53454EL // "NES\x1A"
  val MagicNsf = 0x4D53454EL // "NESM"

  object Op extends ChiselEnum {
    val none, loadRom, copyFdsBios, loadEeprom, saveEeprom = Value
  }
}

/**
 * Hardware loader that bridges Game Bub's memory-mapped file uploads to the
 * MiSTer NES `GameLoader` and EEPROM BRAM:
 *
 *  - `loadRom`: clears NES RAM/VRAM/CARTRAM (`0x380000..0x3FFFFF`), inspects
 *    the first 4 bytes of the uploaded file at `0x040_0000` to auto-detect
 *    `.nes`, `.nsf`, or `.fds`, resets `GameLoader`, and streams every byte
 *    from SDRAM through `GameLoader` into the NES SDRAM layout (plus copies
 *    `fds_bios.bin` from `0x03B_0000` to `0x000_0000` if FDS & BIOS loaded).
 *  - `copyFdsBios`: copies `0x03B_0000 -> 0x000_0000` (8 KiB) when `fds_bios.bin`
 *    arrives after an `.fds` file.
 *  - `loadEeprom` / `saveEeprom`: copies 2 KiB between SDRAM `0x03C_0000` and
 *    the core's `eeprom` BRAM for Mapper 16/159 games.
 */
class NesRomLoader extends Module {
  import NesRomLoader._

  val io = IO(new Bundle {
    val start = Input(Bool())
    val op = Input(Op())
    val romSize = Input(UInt(25.W))
    val biosLoaded = Input(Bool())
    val busy = Output(Bool())
    val error = Output(Bool())
    val isFds = Output(Bool())
    val isNsf = Output(Bool())
    val maxDiskside = Output(UInt(2.W))

    /** 32-bit SDRAM port (NesMemory.streamer). */
    val mem = Flipped(new MemoryInterface(addressWidth = 25, dataWidth = 32))

    /** GameLoader pins on `nes_gamebub`. */
    val loaderReset = Output(Bool())
    val loaderDownloading = Output(Bool())
    val loaderFiletype = Output(UInt(4.W))
    val loaderIndata = Output(UInt(8.W))
    val loaderIndataClk = Output(Bool())
    val loaderMemAddr = Input(UInt(25.W))
    val loaderMemWrite = Input(Bool())
    val loaderDone = Input(Bool())
    val loaderError = Input(Bool())
    /** High while ch0 SDRAM write is still busy (paces GameLoader bytes). */
    val ch0Busy = Input(Bool())

    /** EEPROM BRAM port B on `nes_gamebub`. */
    val eepromAddr = Output(UInt(11.W))
    val eepromDin = Output(UInt(8.W))
    val eepromWren = Output(Bool())
    val eepromDout = Input(UInt(8.W))
  })

  object State extends ChiselEnum {
    val idle,
      clearCpuRam,
      clearVram,
      readMagic,
      resetLoader,
      fetchWord,
      feedByte,
      waitWrite,
      waitLoaderDone,
      biosRead,
      biosWrite,
      eepromLoadRead,
      eepromLoadWrite,
      eepromSaveRead,
      eepromSaveWait,
      eepromSaveWrite = Value
  }

  val state = RegInit(State.idle)
  val offset = Reg(UInt(25.W))
  val remaining = Reg(UInt(25.W))
  val wordBuf = Reg(UInt(32.W))
  val byteIdx = Reg(UInt(2.W))
  val resetCnt = Reg(UInt(3.W))
  val filetype = RegInit(0.U(4.W))
  val isFdsReg = RegInit(false.B)
  val isNsfReg = RegInit(false.B)
  val errorReg = RegInit(false.B)
  val maxDisksideReg = RegInit(3.U(2.W))
  val copyBiosAfter = RegInit(false.B)

  io.busy := state =/= State.idle
  io.error := errorReg
  io.isFds := isFdsReg
  io.isNsf := isNsfReg
  io.maxDiskside := maxDisksideReg

  io.mem.enable := false.B
  io.mem.write := false.B
  io.mem.address := 0.U
  io.mem.dataWrite := 0.U
  io.mem.writeStrobe := "b1111".U

  io.loaderReset := state === State.resetLoader
  io.loaderDownloading := state === State.fetchWord || state === State.feedByte || state === State.waitWrite
  io.loaderFiletype := filetype
  io.loaderIndata := wordBuf.asTypeOf(Vec(4, UInt(8.W)))(byteIdx)
  io.loaderIndataClk := state === State.feedByte

  io.eepromAddr := 0.U
  io.eepromDin := 0.U
  io.eepromWren := false.B

  // Track highest FDS disk side written (matches NES.sv line 1253)
  when (io.loaderDownloading && io.loaderMemWrite && isFdsReg) {
    maxDisksideReg := io.loaderMemAddr(17, 16)
  }

  switch (state) {
    is (State.idle) {
      when (io.start) {
        switch (io.op) {
          is (Op.loadRom) {
            errorReg := false.B
            offset := 0.U
            remaining := io.romSize
            copyBiosAfter := io.biosLoaded
            maxDisksideReg := 3.U
            state := State.clearCpuRam
          }
          is (Op.copyFdsBios) {
            offset := 0.U
            state := State.biosRead
          }
          is (Op.loadEeprom) {
            offset := 0.U
            state := State.eepromLoadRead
          }
          is (Op.saveEeprom) {
            offset := 0.U
            byteIdx := 0.U
            state := State.eepromSaveRead
          }
        }
      }
    }

    // Clear 2 KiB CPU-RAM (0x380000) and 2 KiB CHR-VRAM (0x3A0000) in 32-bit words (~0.14 ms total)
    is (State.clearCpuRam) {
      io.mem.enable := true.B
      io.mem.write := true.B
      io.mem.address := CpuRamBase.U(25.W) + offset
      io.mem.dataWrite := 0.U
      when (io.mem.done) {
        when (offset === (CpuRamSize - 4).U) {
          offset := 0.U
          state := State.clearVram
        } .otherwise {
          offset := offset + 4.U
        }
      }
    }

    is (State.clearVram) {
      io.mem.enable := true.B
      io.mem.write := true.B
      io.mem.address := VramBase.U(25.W) + offset
      io.mem.dataWrite := 0.U
      when (io.mem.done) {
        when (offset === (VramSize - 4).U) {
          state := State.readMagic
        } .otherwise {
          offset := offset + 4.U
        }
      }
    }

    // Read first 4 bytes of uploaded ROM to detect .nes / .nsf / .fds
    is (State.readMagic) {
      io.mem.enable := true.B
      io.mem.write := false.B
      io.mem.address := StagingBase.U(25.W)
      when (io.mem.done) {
        val m = io.mem.dataRead
        val nes = m === MagicNes.U(32.W)
        val nsf = m === MagicNsf.U(32.W)
        val fds = !nes && !nsf
        filetype := Cat(nsf, fds, nes, false.B)
        isFdsReg := fds
        isNsfReg := nsf
        resetCnt := 0.U
        offset := 0.U
        state := State.resetLoader
      }
    }

    is (State.resetLoader) {
      resetCnt := resetCnt + 1.U
      when (resetCnt === 3.U) {
        state := Mux(remaining === 0.U, State.waitLoaderDone, State.fetchWord)
      }
    }

    is (State.fetchWord) {
      io.mem.enable := true.B
      io.mem.write := false.B
      io.mem.address := StagingBase.U(25.W) + offset
      when (io.mem.done) {
        wordBuf := io.mem.dataRead
        byteIdx := 0.U
        state := State.feedByte
      }
    }

    // One-cycle indata_clk pulse to GameLoader
    is (State.feedByte) {
      state := State.waitWrite
    }

    // One idle clk1x cycle (indata_clk = 0 so NesMemory resets ch0 oldWr) plus
    // wait for SdramEngine to accept the write (typically already done within
    // the feedByte cycle -> ~2.6 clk1x cycles/byte = ~8.2 MiB/s).
    is (State.waitWrite) {
      when (!io.ch0Busy) {
        val nextRem = remaining - 1.U
        remaining := nextRem
        when (nextRem === 0.U) {
          state := State.waitLoaderDone
        } .elsewhen (byteIdx === 3.U) {
          offset := offset + 4.U
          state := State.fetchWord
        } .otherwise {
          byteIdx := byteIdx + 1.U
          state := State.feedByte
        }
      }
    }

    is (State.waitLoaderDone) {
      when (io.loaderDone) {
        errorReg := io.loaderError
        when (isFdsReg && copyBiosAfter && !io.loaderError) {
          offset := 0.U
          state := State.biosRead
        } .otherwise {
          state := State.idle
        }
      }
    }

    // Copy 8 KiB FDS BIOS from SDRAM 0x03B_0000 to SDRAM 0x000_0000
    is (State.biosRead) {
      io.mem.enable := true.B
      io.mem.write := false.B
      io.mem.address := FdsBiosStageBase.U(25.W) + offset
      when (io.mem.done) {
        wordBuf := io.mem.dataRead
        state := State.biosWrite
      }
    }

    is (State.biosWrite) {
      io.mem.enable := true.B
      io.mem.write := true.B
      io.mem.address := FdsBiosDestBase.U(25.W) + offset
      io.mem.dataWrite := wordBuf
      when (io.mem.done) {
        when (offset === (FdsBiosSize - 4).U) {
          state := State.idle
        } .otherwise {
          offset := offset + 4.U
          state := State.biosRead
        }
      }
    }

    // Copy 2 KiB EEPROM from SDRAM 0x03C_0000 to eeprom BRAM
    is (State.eepromLoadRead) {
      io.mem.enable := true.B
      io.mem.write := false.B
      io.mem.address := CartRamBase.U(25.W) + offset
      when (io.mem.done) {
        wordBuf := io.mem.dataRead
        byteIdx := 0.U
        state := State.eepromLoadWrite
      }
    }

    is (State.eepromLoadWrite) {
      io.eepromAddr := offset(10, 0) + byteIdx
      io.eepromDin := wordBuf.asTypeOf(Vec(4, UInt(8.W)))(byteIdx)
      io.eepromWren := true.B
      when (byteIdx === 3.U) {
        when (offset === (EepromSize - 4).U) {
          state := State.idle
        } .otherwise {
          offset := offset + 4.U
          state := State.eepromLoadRead
        }
      } .otherwise {
        byteIdx := byteIdx + 1.U
      }
    }

    // Copy 2 KiB EEPROM from eeprom BRAM to SDRAM 0x03C_0000
    is (State.eepromSaveRead) {
      io.eepromAddr := offset(10, 0) + byteIdx
      state := State.eepromSaveWait
    }

    is (State.eepromSaveWait) {
      io.eepromAddr := offset(10, 0) + byteIdx
      val bytes = Wire(Vec(4, UInt(8.W)))
      bytes := wordBuf.asTypeOf(Vec(4, UInt(8.W)))
      bytes(byteIdx) := io.eepromDout
      wordBuf := bytes.asUInt
      when (byteIdx === 3.U) {
        state := State.eepromSaveWrite
      } .otherwise {
        byteIdx := byteIdx + 1.U
        state := State.eepromSaveRead
      }
    }

    is (State.eepromSaveWrite) {
      io.mem.enable := true.B
      io.mem.write := true.B
      io.mem.address := CartRamBase.U(25.W) + offset
      io.mem.dataWrite := wordBuf
      when (io.mem.done) {
        when (offset === (EepromSize - 4).U) {
          state := State.idle
        } .otherwise {
          offset := offset + 4.U
          byteIdx := 0.U
          state := State.eepromSaveRead
        }
      }
    }
  }
}
