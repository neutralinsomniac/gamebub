package net.gamebub.core.snes

import chisel3._
import chisel3.util._

/**
 * Address translation that mirrors a non-power-of-two ROM up to the next
 * power of two the way a real cartridge's partially decoded address lines
 * do, so that the ROM file can sit linearly in the SDRAM and nothing has to
 * duplicate data.
 *
 * The 16 MiB ROM address space is divided into 512 blocks of 32 KiB. A table
 * (distributed RAM, read asynchronously on the ROM cache's request path)
 * maps each block the core may address to the block of the file that holds
 * its data: blocks below the ROM size map to themselves, blocks between the
 * ROM size and its padded size to their mirror (MiSTer's mirroring rule,
 * `mirrorAddress` in `RomMirrorTableSpec`, in block units; the mapping is
 * linear within a block when the ROM size is a multiple of the block
 * size), and blocks
 * above the padded size (the save-state program at 0xFF0000) to themselves.
 *
 * A ROM file may carry a 512-byte copier header: the file is transferred
 * as is, so ROM byte `r` sits at file offset `r + 512`. The offset is added
 * to the low 15 bits of the translated address, carrying into the block
 * number (the file is linear, so the next file block follows). Blocks above
 * the padded size are not part of the file (the save-state program is
 * written to the SDRAM directly) and get no offset; each entry carries a
 * flag saying whether the block is within the padded ROM.
 *
 * `build` (with `fileSize`, the number of bytes transferred) rebuilds the
 * table in about a thousand cycles; `busy` is high meanwhile and the
 * translation must not be used. A ROM size that is not a multiple of the
 * block size cannot be mirrored this way: the table is built as identity
 * and `supported` is low (no known dump has such a size). The table is
 * also built as identity after reset.
 *
 * Addresses are SDRAM byte addresses; bit 24 (the save-state slots) passes
 * through untouched.
 */
class RomMirrorTable extends Module {
  val BlockBits = 15
  val Blocks = 1 << (24 - BlockBits)
  val BlockIndexBits = log2Ceil(Blocks)
  val CopierHeaderSize = 512

  val io = IO(new Bundle {
    val build = Input(Bool())
    val fileSize = Input(UInt(25.W))
    val busy = Output(Bool())
    /** The last build's ROM size was a multiple of the block size (valid when not busy). */
    val supported = Output(Bool())
    /** The last build's file had a copier header. */
    val copierHeader = Output(Bool())

    val in = Input(UInt(25.W))
    val out = Output(UInt(25.W))
  })

  /** Per block: bit 9 the block is within the padded ROM (the file), bits 8:0 the file block. */
  val table = Mem(Blocks, UInt((BlockIndexBits + 1).W))

  //////////////////////////////////
  // Translation
  //////////////////////////////////
  val copierHeader = RegInit(false.B)
  val inBlock = io.in(23, BlockBits)
  val inLow = io.in(BlockBits - 1, 0)
  val entry = table.read(inBlock)
  val inRom = entry(BlockIndexBits)
  val offsetLow = inLow +& Mux(copierHeader && inRom, CopierHeaderSize.U, 0.U)
  val carry = offsetLow(BlockBits)
  val outBlock = entry(BlockIndexBits - 1, 0) + carry
  io.out := Mux(io.in(24), io.in, Cat(0.U(1.W), outBlock, offsetLow(BlockBits - 1, 0)))

  //////////////////////////////////
  // Builder
  //////////////////////////////////
  object State extends ChiselEnum {
    val init, idle, step, write = Value
  }
  val state = RegInit(State.init)
  val supported = RegInit(false.B)
  /** ROM size and padded size in blocks (up to 512, so one bit more than an index). */
  val sizeBlocks = Reg(UInt((BlockIndexBits + 1).W))
  val paddedBlocks = Reg(UInt((BlockIndexBits + 1).W))
  /** The block being computed. */
  val block = Reg(UInt(BlockIndexBits.W))
  /** The iteration of `mirror_address`, in block units: (addr, size, base). */
  val addr = Reg(UInt((BlockIndexBits + 1).W))
  val size = Reg(UInt((BlockIndexBits + 1).W))
  val base = Reg(UInt((BlockIndexBits + 1).W))

  io.busy := state =/= State.idle
  io.supported := supported
  io.copierHeader := copierHeader

  /** The highest set bit of `x` (`x` is never zero where this is used). */
  def highestOneBit(x: UInt): UInt = {
    val w = x.getWidth
    val bits = (0 until w).map(i => x(w - 1, i).orR && !(if (i == w - 1) false.B else x(w - 1, i + 1).orR))
    VecInit(bits).asUInt
  }

  def startBuild(fileSizeIn: UInt): Unit = {
    val fileSize = fileSizeIn.pad(25)
    val header = fileSize(9)
    val romSize = fileSize - Mux(header, CopierHeaderSize.U, 0.U)
    val blocks = romSize(24, BlockBits)
    val exact = romSize(BlockBits - 1, 0) === 0.U && blocks =/= 0.U
    // The padded size: the size itself when it is a power of two, else the
    // next one; 1 << (highest bit + 1).
    val top = highestOneBit(blocks)
    val padded = Mux(blocks === top, top, (top << 1)(BlockIndexBits, 0))
    copierHeader := header
    supported := exact
    sizeBlocks := Mux(exact, blocks, 0.U)
    paddedBlocks := Mux(exact, padded, 0.U)
    block := 0.U
    addr := 0.U
    size := Mux(exact, blocks, 0.U)
    base := 0.U
    state := State.step
  }

  switch (state) {
    is (State.init) {
      startBuild(0.U)
    }
    is (State.idle) {
      when (io.build) {
        startBuild(io.fileSize)
      }
    }
    is (State.step) {
      // One iteration of mirror_address(block, sizeBlocks): strip the
      // highest bit of `addr` and, if the ROM extends past it, descend into
      // the remainder above it. Blocks outside [size, padded) are identity.
      val mirrored = block >= sizeBlocks && block < paddedBlocks
      when (!mirrored || addr < size) {
        state := State.write
      } .otherwise {
        val mask = highestOneBit(addr)
        addr := addr - mask
        when (size > mask) {
          size := size - mask
          base := base + mask
        }
      }
    }
    is (State.write) {
      val mirrored = block >= sizeBlocks && block < paddedBlocks
      val inRom = block < paddedBlocks
      table.write(block, Cat(inRom, Mux(mirrored, (base + addr)(BlockIndexBits - 1, 0), block)))
      block := block + 1.U
      // Next block: restart the iteration.
      addr := block + 1.U
      size := sizeBlocks
      base := 0.U
      state := Mux(block === (Blocks - 1).U, State.idle, State.step)
    }
  }
}
