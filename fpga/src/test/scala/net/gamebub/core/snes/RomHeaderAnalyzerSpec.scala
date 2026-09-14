package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Feeds synthetic ROM images (the firmware driver's `header.rs` test
 * images and more) to the analyzer through a model of the pipelined SDRAM
 * port with random wait states, and checks the result against the values
 * the driver computes.
 */
class RomHeaderAnalyzerSpec extends AnyFreeSpec {
  import RomHeaderAnalyzer._

  val K = 1024

  /** A sparse ROM file: bytes default to zero. */
  class Image(val fileSize: Int) {
    val bytes = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0)
    def word(byteAddress: Int): Long =
      (0 until 4).map(i => bytes(byteAddress + i).toLong << (8 * i)).sum
    def put(at: Int, data: Seq[Int]): Unit = data.zipWithIndex.foreach { case (b, i) => bytes(at + i) = b & 0xFF }
    def put(at: Int, s: String): Unit = put(at, s.getBytes("ASCII").map(_.toInt).toSeq)
  }

  /** The driver test's `make_lorom`, at any candidate header address, with a copier header if asked. */
  def makeImage(
    size: Int, header: Int = 0x7FC0, title: String = "TEST", mapper: Int = 0x20, rtype: Int = 0x02,
    ram: Int = 0x03, region: Int = 0x01, company: Int = 0x01, checksumValid: Boolean = true,
    copier: Boolean = false, opcode: Int = 0x78,
  ): Image = {
    val offset = if (copier) 512 else 0
    val image = new Image(size + offset)
    val h = offset + header
    image.put(h, title)
    image.bytes(h + Mapper) = mapper
    image.bytes(h + RomType) = rtype
    image.bytes(h + RomSize) = 0x08
    image.bytes(h + RamSize) = ram
    image.bytes(h + CartRegion) = region
    image.bytes(h + Company) = company
    image.bytes(h + Checksum) = 0x34
    image.bytes(h + Checksum + 1) = 0x12
    image.bytes(h + Complement) = if (checksumValid) 0xCB else 0x00
    image.bytes(h + Complement + 1) = if (checksumValid) 0xED else 0x00
    image.bytes(h + ResetVector) = 0x00
    image.bytes(h + ResetVector + 1) = 0x80
    // The first opcode: at (header & ~0x7FFF) | (vector & 0x7FFF).
    image.bytes(offset + ((header & ~0x7FFF) | 0x0000)) = opcode
    image
  }

  case class Expected(
    romType: Int, romSizeCode: Int, ramSizeCode: Int, pal: Boolean, headerFound: Boolean,
    headerIndex: Int, copierHeader: Boolean, unsupported: Boolean,
  )

  class Port(image: Image, random: Random) {
    /** The request accepted last cycle, awaiting its data phase. */
    var pending: Option[Long] = None
    var reads = 0

    /** Drive the port for one cycle: call before stepping the clock. */
    def service(dut: RomHeaderAnalyzer): Unit = {
      val ready = random.nextInt(3) != 0
      dut.io.mem.ready.poke(ready.B)
      dut.io.mem.dataRead.poke(pending.map(a => image.word(a.toInt)).getOrElse(0L).U)
      if (ready) {
        pending = if (dut.io.mem.enable.peek().litValue == 1) {
          assert(dut.io.mem.isWrite.peek().litValue == 0, "the analyzer only reads")
          val address = dut.io.mem.address.peek().litValue.toLong
          assert(address % 4 == 0, s"unaligned read $address")
          reads += 1
          Some(address)
        } else None
      }
    }
  }

  def analyze(dut: RomHeaderAnalyzer, image: Image, random: Random): (Expected, Int) = {
    val port = new Port(image, random)
    dut.io.fileSize.poke(image.fileSize.U)
    dut.io.start.poke(true.B)
    port.service(dut)
    dut.clock.step()
    dut.io.start.poke(false.B)
    dut.io.busy.expect(true.B)
    var cycles = 0
    while (dut.io.busy.peek().litValue == 1) {
      port.service(dut)
      dut.clock.step()
      cycles += 1
      assert(cycles < 20000, "the analysis takes too long")
    }
    val r = dut.io.result
    (Expected(
      r.romType.peek().litValue.toInt,
      r.romSizeCode.peek().litValue.toInt,
      r.ramSizeCode.peek().litValue.toInt,
      r.pal.peek().litValue == 1,
      r.headerFound.peek().litValue == 1,
      r.headerIndex.peek().litValue.toInt,
      r.copierHeader.peek().litValue == 1,
      r.unsupported.peek().litValue == 1,
    ), port.reads)
  }

  def withDut(body: (RomHeaderAnalyzer, Random) => Unit): Unit = {
    simulate(new RomHeaderAnalyzer) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.start.poke(false.B)
      dut.io.mem.ready.poke(true.B)
      dut.io.mem.dataRead.poke(0.U)
      dut.clock.step()
      body(dut, new Random(1))
    }
  }

  def check(dut: RomHeaderAnalyzer, random: Random, image: Image, want: Expected, name: String): Unit = {
    val (got, _) = analyze(dut, image, random)
    assert(got == want, s"$name: got $got, want $want")
  }

  "the driver's test images" in withDut { (dut, random) =>
    // detects_lorom
    check(dut, random, makeImage(256 * K, ram = 0x03, region = 0x01),
      Expected(0x00, 8, 3, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "lorom")
    // detects_copier_header_and_dsp1
    check(dut, random, makeImage(1024 * K, title = "PILOTWINGS", rtype = 0x03, ram = 0x00, region = 0x02, copier = true),
      Expected(0x84, 10, 0, pal = true, headerFound = true, 0, copierHeader = true, unsupported = false), "dsp1 + copier")
    // non_pow2_rom_is_padded: 3 MiB pads to 4 MiB (code 12)
    check(dut, random, makeImage(3 * 1024 * K, title = "BIG", rtype = 0x00, ram = 0x00),
      Expected(0x00, 12, 0, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "3 MiB")
  }

  "the mappers" in withDut { (dut, random) =>
    check(dut, random, makeImage(1024 * K, header = 0xFFC0, mapper = 0x21, rtype = 0, ram = 0, region = 0),
      Expected(0x01, 10, 0, pal = false, headerFound = true, 1, copierHeader = false, unsupported = false), "hirom")
    check(dut, random, makeImage(6 * 1024 * K, header = 0x40FFC0, mapper = 0x25, rtype = 0, ram = 5, region = 0),
      Expected(0x02, 13, 5, pal = false, headerFound = true, 2, copierHeader = false, unsupported = false), "exhirom")
    // No header at all: LoROM, no RAM, size from the file.
    check(dut, random, new Image(256 * K),
      Expected(0x00, 8, 0, pal = false, headerFound = false, 0, copierHeader = false, unsupported = false), "no header")
    // A LoROM header with a bad checksum loses to a HiROM header with a good one.
    val competing = makeImage(1024 * K, header = 0xFFC0, mapper = 0x21, rtype = 0, ram = 0, region = 0)
    val lo = makeImage(1024 * K, checksumValid = false)
    for ((a, b) <- lo.bytes if a < 0x8000) competing.bytes(a) = b
    check(dut, random, competing,
      Expected(0x01, 10, 0, pal = false, headerFound = true, 1, copierHeader = false, unsupported = false), "competing")
    // Equal scores: the LoROM candidate wins.
    val tie = makeImage(1024 * K)
    for ((a, b) <- makeImage(1024 * K, header = 0xFFC0, mapper = 0x21).bytes) tie.bytes(a) = b
    check(dut, random, tie,
      Expected(0x00, 10, 3, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "tie")
    // A file too small for the HiROM candidate.
    check(dut, random, makeImage(32 * K),
      Expected(0x00, 5, 3, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "32 KiB")
  }

  "the coprocessors" in withDut { (dut, random) =>
    val gsu = makeImage(2 * 1024 * K, rtype = 0x13, ram = 0, region = 0)
    gsu.bytes(0x7FC0 - 3) = 0xFF // no RAM size given: StarFox
    check(dut, random, gsu,
      Expected(0x70, 11, 5, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "gsu")
    val gsuRam = makeImage(2 * 1024 * K, rtype = 0x15, ram = 0, region = 0)
    gsuRam.bytes(0x7FC0 - 3) = 0x07 // capped at 6
    check(dut, random, gsuRam,
      Expected(0x70, 11, 6, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "gsu ram")
    check(dut, random, makeImage(4 * 1024 * K, header = 0xFFC0, mapper = 0x23, rtype = 0x34, ram = 2, region = 0),
      Expected(0x61, 12, 2, pal = false, headerFound = true, 1, copierHeader = false, unsupported = false), "sa-1")
    check(dut, random, makeImage(4 * 1024 * K, mapper = 0x32, rtype = 0x43, ram = 3, region = 0),
      Expected(0x50, 12, 3, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "s-dd1")
    check(dut, random, makeImage(2 * 1024 * K, mapper = 0x20, rtype = 0xF3, ram = 0, region = 0),
      Expected(0x40, 11, 0, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "cx4")
    // ST010 with the header's ROM size byte below 10: an ST011.
    check(dut, random, makeImage(1024 * K, mapper = 0x30, rtype = 0xF6, ram = 0, region = 0),
      Expected(0xA8, 10, 1, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "st011")
    check(dut, random, makeImage(1024 * K, mapper = 0x30, rtype = 0x25, ram = 2, region = 0),
      Expected(0xC0, 10, 2, pal = false, headerFound = true, 0, copierHeader = false, unsupported = false), "obc1")
    check(dut, random, makeImage(2 * 1024 * K, header = 0xFFC0, mapper = 0x3A, rtype = 0xF9, ram = 0, region = 0),
      Expected(0xD9, 11, 0, pal = false, headerFound = true, 1, copierHeader = false, unsupported = true), "spc7110")
    // PAL region, and the size cap.
    check(dut, random, makeImage(1024 * K, region = 0x11),
      Expected(0x00, 10, 3, pal = true, headerFound = true, 0, copierHeader = false, unsupported = false), "pal")
    check(dut, random, makeImage(16 * 1024 * K + 32 * K, region = 0),
      Expected(0x00, 15, 3, pal = false, headerFound = true, 0, copierHeader = false, unsupported = true), "too large")
    // Sufami Turbo: its own size codes, never PAL, unsupported.
    val sufami = makeImage(512 * K, region = 0x02)
    sufami.put(0, "BANDAI SFC-ADX")
    sufami.bytes(0x7FC0 + 0x36) = 3
    sufami.bytes(0x7FC0 + 0x37) = 1
    check(dut, random, sufami,
      Expected(0x20, 9, 1, pal = false, headerFound = true, 0, copierHeader = false, unsupported = true), "sufami")
    // The BS-X BIOS.
    val bsx = makeImage(1024 * K, title = "Satellaview BS-X     ", ram = 0, region = 0)
    check(dut, random, bsx,
      Expected(0x30, 10, 0, pal = false, headerFound = true, 0, copierHeader = false, unsupported = true), "bs-x")
  }
}
