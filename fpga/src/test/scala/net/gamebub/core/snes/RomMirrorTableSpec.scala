package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

/**
 * Builds the table for a set of ROM sizes and checks every block's
 * translation against a port of the firmware driver's `mirror_address`
 * (byte granularity), including the copier header offset and its carry
 * into the next block, and the identity for unsupported sizes.
 */
class RomMirrorTableSpec extends AnyFreeSpec {
  val K = 1024L
  val M = 1024L * 1024
  val Block = 32 * K
  val RomSpace = 16 * M

  /** `header::mirror_address` from the firmware driver. */
  def mirrorAddress(addrIn: Long, sizeIn: Long): Long = {
    var addr = addrIn
    var size = sizeIn
    if (size == 0) return 0
    var base = 0L
    var mask = 1L
    while (mask < size) mask <<= 1
    while (addr >= size) {
      while (mask != 0 && (addr & mask) == 0) mask >>= 1
      if (mask == 0) return addr % size
      addr -= mask
      if (size > mask) {
        size -= mask
        base += mask
      }
      mask >>= 1
    }
    base + addr
  }

  def paddedSize(size: Long): Long = {
    var p = 1L
    while (p < size) p <<= 1
    p
  }

  /**
   * Where the file holds the byte the core addresses at `a`. Outside the
   * padded ROM (the save-state program, the slots) and for an unsupported
   * size the address is untouched: nothing there comes from the file.
   */
  def expected(a: Long, romSize: Long, header: Boolean, supported: Boolean): Long = {
    val offset = if (header) 512 else 0
    if (a >= RomSpace || !supported || a >= paddedSize(romSize)) a
    else mirrorAddress(a, romSize) + offset
  }

  def check(dut: RomMirrorTable, romSize: Long, header: Boolean): Unit = {
    val supported = romSize % Block == 0 && romSize != 0
    val fileSize = romSize + (if (header) 512 else 0)
    dut.io.fileSize.poke(fileSize.U)
    dut.io.build.poke(true.B)
    dut.clock.step()
    dut.io.build.poke(false.B)
    dut.io.busy.expect(true.B)
    var cycles = 0
    while (dut.io.busy.peek().litValue == 1) {
      dut.clock.step()
      cycles += 1
      assert(cycles < 20000, "the build takes too long")
    }
    dut.io.supported.expect(supported.B)
    dut.io.copierHeader.expect(header.B)

    // The sample addresses per block: the start, one in the middle, the
    // last word (which the header offset carries into the next block).
    val padded = paddedSize(romSize)
    val samples = Seq(0L, 0x1234L, Block - 4)
    for (block <- 0 until (RomSpace / Block).toInt; low <- samples) {
      val a = block * Block + low
      dut.io.in.poke(a.U)
      val got = dut.io.out.peek().litValue.toLong
      val want = expected(a, romSize, header, supported)
      assert(got == want, f"size $romSize%x header $header: $a%07x -> $got%07x, want $want%07x")
    }
    // The save-state region passes through.
    for (a <- Seq(RomSpace, RomSpace + 0x12345L, 2 * RomSpace - 4)) {
      dut.io.in.poke(a.U)
      dut.io.out.expect(a.U)
    }
  }

  "is identity after reset" in {
    simulate(new RomMirrorTable) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      var cycles = 0
      while (dut.io.busy.peek().litValue == 1) {
        dut.clock.step()
        cycles += 1
        assert(cycles < 20000)
      }
      dut.io.supported.expect(false.B)
      for (a <- Seq(0L, 0x7FFFL, 3 * M, 0xFF0000L, RomSpace - 4)) {
        dut.io.in.poke(a.U)
        dut.io.out.expect(a.U)
      }
    }
  }

  "mirrors the sizes that matter, with and without a copier header" in {
    simulate(new RomMirrorTable) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      while (dut.io.busy.peek().litValue == 1) dut.clock.step()

      val sizes = Seq(
        32 * K, 96 * K, 256 * K, 512 * K, 768 * K,
        1 * M, 3 * M / 2, 2 * M, 5 * M / 2, 3 * M, 4 * M, 5 * M, 6 * M, 10 * M, 12 * M, 16 * M,
        // Odd but block-aligned: 7 x 512K, 11 x 256K.
        7 * 512 * K, 11 * 256 * K,
      )
      for (size <- sizes; header <- Seq(false, true)) {
        if (!(size == 16 * M && header)) {
          check(dut, size, header)
        }
      }
    }
  }

  "builds an identity table for a size that is not a multiple of a block" in {
    simulate(new RomMirrorTable) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      while (dut.io.busy.peek().litValue == 1) dut.clock.step()
      // First a real mirror, then the unsupported size: the table is rebuilt.
      check(dut, 3 * M, header = false)
      check(dut, 3 * M + 16 * K, header = false)
      check(dut, 3 * M + 16 * K, header = true)
    }
  }
}
