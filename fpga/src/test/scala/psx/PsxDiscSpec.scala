package psx

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import scala.util.Random

/**
 * Runs the disc reader (psx_disc.bin on the PicoRV32) in [[PsxDisc]]
 * against a model of the firmware's on-demand files (`FILE_GET_SIZE`,
 * `FILE_READ` into the sector buffer) and of the core's CD drive: the
 * region from the license sector while the core is held, the core's track
 * table after it leaves reset, sectors streamed in file order (zeros where
 * the image has none), another disc's track table while the core runs (a
 * disc change), and no disc at all.
 */
class PsxDiscSpec extends AnyFreeSpec {
  val SectorAddress = 0x7000_0000L
  val FileCue = 10
  val FileBin = 11
  val CommandGetSize = 0x0400
  val CommandRead = 0x0402
  val StatusErrIo = 2
  val S = PsxDisc.SectorBytes

  /** A file's size and contents (made up on demand: images are large). */
  case class FileModel(size: Long, byteAt: Long => Byte)

  def textFile(text: String): FileModel = {
    val bytes = text.getBytes("ASCII")
    FileModel(bytes.length, o => bytes(o.toInt))
  }

  /** An image of `sectors` raw sectors of noise; sector 4 (LBA 154) has the license text. */
  def imageFile(sectors: Int, seed: Int, license: Option[String]): FileModel = {
    val text = license.map(t => ("          Licensed  by          Sony Computer " + t).getBytes("ASCII"))
    FileModel(sectors.toLong * S, { o =>
      val sector = (o / S).toInt
      val at = (o % S).toInt
      text match {
        case Some(t) if sector == 4 && at >= 37 && at < 37 + t.length => t(at - 37)
        case _ => new Random(seed * 1000003L + o).nextInt(256).toByte
      }
    })
  }

  def sectorOf(file: FileModel, fileSector: Int): Seq[Int] =
    (0 until S).map(i => file.byteAt(fileSector.toLong * S + i) & 0xFF)

  def halfwords(bytes: Seq[Int]): Seq[Int] = bytes.grouped(2).map(b => b(0) | (b(1) << 8)).toSeq

  def msfBcd(lba: Int): Long = {
    def bcd(x: Int) = ((x / 10) << 4) | (x % 10)
    (bcd(lba / 75 / 60) << 16 | bcd((lba / 75) % 60) << 8 | bcd(lba % 75)).toLong
  }

  /** The firmware's and the core's side. */
  class Bench(val dut: PsxDisc, var files: Map[Int, FileModel]) {
    val trackinfo = mutable.ArrayBuffer[(Int, Long)]()
    val stream = mutable.ArrayBuffer[Int]()
    var acked = false
    var cycle = 0L
    var requests = 0

    dut.io.sector.enable.poke(false.B)
    dut.io.commandCore.busy.poke(false.B)
    dut.io.commandCore.done.poke(false.B)
    dut.io.commandCore.error.poke(false.B)
    dut.io.commandWrite.valid.poke(false.B)
    dut.io.start.poke(false.B)
    dut.io.discChanged.poke(false.B)
    dut.io.coreReset.poke(true.B)
    dut.io.hpsReq.poke(false.B)
    dut.io.hpsLba.poke(0.U)
    dut.io.clearSectorReads.poke(false.B)
    dut.io.clearReadErrors.poke(false.B)
    dut.io.clearWorstWait.poke(false.B)

    def tick(): Unit = {
      if (dut.io.trackinfoWrite.peek().litToBoolean)
        trackinfo += ((dut.io.trackinfoAddr.peek().litValue.toInt, dut.io.trackinfoData.peek().litValue.toLong))
      if (dut.io.hpsAck.peek().litToBoolean) acked = true
      if (dut.io.hpsWrite.peek().litToBoolean) {
        assert(acked, "data before the acknowledge")
        stream += dut.io.hpsData.peek().litValue.toInt
      }
      dut.clock.step()
      cycle += 1
    }

    def sectorWrite(address: Int, data: Long): Unit = {
      val port = dut.io.sector
      port.enable.poke(true.B)
      port.write.poke(true.B)
      port.address.poke(address.U)
      port.dataWrite.poke(data.U)
      tick()
      while (!port.done.peek().litToBoolean) tick()
      port.enable.poke(false.B)
      tick()
    }

    /** Serve a core request, as the firmware's `handle_core_request` does. */
    def serve(): Unit = {
      requests += 1
      val words = dut.io.commandWords.map(_.peek().litValue.toLong)
      dut.io.commandCore.busy.poke(true.B)
      val id = words(1).toInt
      val (ok, result) = (words(0).toInt, files.get(id)) match {
        case (CommandGetSize, Some(f)) => (true, Seq(0L, f.size & 0xFFFFFFFFL, f.size >> 32))
        case (CommandRead, Some(f)) =>
          val address = words(2)
          val offset = words(3) | (words(4) << 32)
          val length = words(5)
          assert(address >= SectorAddress && address + length <= SectorAddress + 4 * PsxDisc.SectorBufferWords,
            s"read into $address")
          val n = (f.size - offset).max(0).min(length)
          // Whole words only: the SPI receiver drops a partial last word.
          for (w <- 0 until (n / 4).toInt) {
            val word = (0 until 4).map(b => (f.byteAt(offset + 4 * w + b) & 0xFFL) << (8 * b)).sum
            sectorWrite((address - SectorAddress).toInt + 4 * w, word)
          }
          (true, Seq(0L, n, 0L))
        case (CommandGetSize | CommandRead, None) => (false, Seq(StatusErrIo.toLong, 0L, 0L))
        case (c, _) => fail(f"unknown request $c%04x")
      }
      for ((r, i) <- result.zipWithIndex) {
        dut.io.commandWrite.valid.poke(true.B)
        dut.io.commandWrite.bits.index.poke(i.U)
        dut.io.commandWrite.bits.data.poke(r.U)
        tick()
      }
      dut.io.commandWrite.valid.poke(false.B)
      dut.io.commandCore.busy.poke(false.B)
      dut.io.commandCore.done.poke(true.B)
      dut.io.commandCore.error.poke((!ok).B)
      var n = 0
      while (dut.io.commandCore.request.peek().litToBoolean) { tick(); n += 1; assert(n < 1000, "request not dropped") }
      dut.io.commandCore.done.poke(false.B)
      dut.io.commandCore.error.poke(false.B)
    }

    /** Run, serving requests, for `cycles` or until `until`. */
    def run(cycles: Long, until: => Boolean = false): Unit = {
      val end = cycle + cycles
      while (cycle < end && !until) {
        if (dut.io.commandCore.request.peek().litToBoolean) serve() else tick()
      }
    }

    def runUntil(what: String, limit: Long)(until: => Boolean): Unit = {
      run(limit, until)
      assert(until, s"timed out waiting for $what (debug ${dut.io.debugState.peek().litValue.toString(16)})")
    }

    def pulse(port: Bool): Unit = {
      port.poke(true.B)
      tick()
      port.poke(false.B)
    }

    /** The core asks for a sector: what it gets streamed. */
    def readSector(lba: Int): Seq[Int] = {
      stream.clear()
      acked = false
      dut.io.hpsLba.poke(lba.U)
      dut.io.hpsReq.poke(true.B)
      runUntil(s"sector $lba", 2_000_000)(acked)
      // The core drops its request with the acknowledge.
      dut.io.hpsReq.poke(false.B)
      runUntil(s"sector $lba's data", 5000)(stream.length == S / 2)
      tick()
      stream.toSeq
    }

    /** CoreRun: held while the disc is read; then out of reset. */
    def startCore(): Unit = {
      pulse(dut.io.start)
      tick()
      assert(dut.io.holdCore.peek().litToBoolean, "not holding the core")
      runUntil("the core's release", 5_000_000)(!dut.io.holdCore.peek().litToBoolean)
      dut.io.coreReset.poke(false.B)
    }

    def region: Option[Int] =
      if (dut.io.region.valid.peek().litToBoolean) Some(dut.io.region.bits.peek().litValue.toInt) else None
  }

  def withBench(files: Map[Int, FileModel])(body: Bench => Unit): Unit = {
    simulate(new PsxDisc(simulation = true)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(4)
      dut.reset.poke(false.B)
      val bench = new Bench(dut, files)
      // The reader waits for CoreRun before asking for anything.
      bench.run(20000)
      assert(bench.requests == 0, "requests before CoreRun")
      assert(dut.io.holdCore.peek().litToBoolean)
      assert(!dut.io.debugState.peek().litValue.testBit(31), "the CPU trapped")
      body(bench)
      assert(!dut.io.debugState.peek().litValue.testBit(31), "the CPU trapped")
    }
  }

  /**
   * A data track and an audio track, with its pregap in the file (INDEX 00);
   * no line break at the end, and a size that is not a whole number of words
   * (its last bytes come from the file's last word).
   */
  val cue = "FILE \"Game (Europe).bin\" BINARY\r\n  TRACK 01 MODE2/2352\r\n    INDEX 01 00:00:00\r\n" +
    "  TRACK 02 AUDIO\r\n    INDEX 00 04:26:00\r\n    INDEX 01 04:29:74"
  require(cue.length % 4 != 0)

  "a cue sheet: region, track table, sectors and a disc change" in {
    val image = imageFile(30000, 1, Some("Entertainment Euro pe"))
    withBench(Map(0 -> textFile(cue), FileCue -> textFile(cue), FileBin -> image)) { b =>
      b.startCore()
      assert(b.region.contains(PsxDisc.Region.Europe))
      assert(b.dut.io.hasDisc.peek().litToBoolean)

      // The core left reset: its track table. Track 2 starts at the
      // INDEX 00 (04:26:00 into the file, 19950), index 1 at 20249 + 150 (04:31).
      b.runUntil("the track table", 200_000)(b.trackinfo.length == 12)
      val leadOut = 150 + 30000
      val expected = Seq(
        0 -> (2L | (2L << 8)), 1 -> leadOut.toLong, 2 -> (msfBcd(leadOut) >> 8), 3 -> (3L << 16),
        4 -> 0L, 5 -> (150 + 19950 - 1).toLong, 6 -> (msfBcd(150) >> 8), 7 -> 0L,
        8 -> (150 + 19950).toLong, 9 -> (leadOut - 1).toLong, 10 -> ((1L << 16) | (msfBcd(150 + 20249) >> 8)), 11 -> 0L,
      )
      assert(b.trackinfo.toSeq == expected, s"trackinfo ${b.trackinfo}")

      // Sectors: one in track 2, one before track 1 (zeros), one past the end.
      assert(b.readSector(20300) == halfwords(sectorOf(image, 20300 - 150)))
      assert(b.readSector(149) == Seq.fill(S / 2)(0))
      assert(b.readSector(150) == halfwords(sectorOf(image, 0)))
      assert(b.readSector(leadOut + 10) == Seq.fill(S / 2)(0))
      assert(b.dut.io.sectorReads.peek().litValue == 4)
      assert(b.dut.io.readErrors.peek().litValue == 0)

      // Another disc while the core runs: one track, lead-out 40000 + 150.
      val image2 = imageFile(40000, 2, None)
      b.files = Map(0 -> image2, FileBin -> image2)
      b.trackinfo.clear()
      b.pulse(b.dut.io.discChanged)
      b.runUntil("the new track table", 500_000)(b.trackinfo.length == 8)
      val expected2 = Seq(
        0 -> (1L | (1L << 8)), 1 -> 40150L, 2 -> (msfBcd(40150) >> 8), 3 -> (3L << 16),
        4 -> 0L, 5 -> 40149L, 6 -> (msfBcd(150) >> 8), 7 -> 0L,
      )
      assert(b.trackinfo.toSeq == expected2, s"trackinfo after the change ${b.trackinfo}")
      assert(b.readSector(1000) == halfwords(sectorOf(image2, 1000 - 150)))

      // A reset: the table again when the core leaves it.
      b.dut.io.coreReset.poke(true.B)
      b.run(100)
      b.trackinfo.clear()
      b.dut.io.coreReset.poke(false.B)
      b.runUntil("the table after a reset", 200_000)(b.trackinfo.length == 8)
      assert(b.trackinfo.toSeq == expected2)
    }
  }

  "a lone image, region unknown" in {
    val image = imageFile(1000, 3, None)
    withBench(Map(0 -> image, FileBin -> image)) { b =>
      b.startCore()
      assert(b.region.isEmpty)
      assert(b.dut.io.hasDisc.peek().litToBoolean)
      b.runUntil("the track table", 200_000)(b.trackinfo.length == 8)
      assert(b.trackinfo.take(2).toSeq == Seq(0 -> (1L | (1L << 8)), 1 -> 1150L))
      assert(b.readSector(1149) == halfwords(sectorOf(image, 999)))
    }
  }

  "a cue sheet naming one file per track: no disc" in {
    val split = "FILE \"Game (Track 1).bin\" BINARY\nTRACK 01 MODE2/2352\nINDEX 01 00:00:00\n" +
      "FILE \"Game (Track 2).bin\" BINARY\nTRACK 02 AUDIO\nINDEX 01 00:00:00\n"
    withBench(Map(0 -> textFile(split), FileCue -> textFile(split))) { b =>
      b.startCore()
      assert(!b.dut.io.hasDisc.peek().litToBoolean)
      // Debug: error 3 (several files).
      assert(((b.dut.io.debugState.peek().litValue >> 8) & 0xFF) == 3)
      b.run(50_000)
      assert(b.trackinfo.isEmpty)
      // A sector asked for anyway: zeros, counted as failed.
      assert(b.readSector(150) == Seq.fill(S / 2)(0))
      assert(b.dut.io.readErrors.peek().litValue == 1)
    }
  }
}
