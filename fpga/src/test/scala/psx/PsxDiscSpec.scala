package psx

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import scala.util.Random

/**
 * Plays the firmware (the table of contents, sector reads on request) and
 * the core's CD drive against [[PsxDisc]]: the region from the license
 * sector while the core is held, the core's track table after it leaves
 * reset, sectors streamed in file order.
 */
class PsxDiscSpec extends AnyFreeSpec {
  val SectorAddress = 0x7000_0000L

  /** Two tracks: data 150-20149, audio 20150-29999 (index 1 at 20300). */
  val toc: Seq[Long] = Seq(
    2 | (2 << 8), 30000, 0x064000, 0,
    150, 20149, 150, 0x000200,
    20150, 29999, 20300, (1L << 31) | 0x043035,
  )

  def sectorBytes(lba: Int, license: Option[String]): Array[Byte] = {
    val rng = new Random(lba)
    val bytes = Array.fill(PsxDisc.SectorBytes)(rng.nextInt(256).toByte)
    license.foreach { text =>
      val t = ("          Licensed  by          Sony Computer " + text).getBytes("ASCII")
      System.arraycopy(t, 0, bytes, 37, t.length) // not word-aligned on purpose
    }
    bytes
  }

  def run(license: Option[String], expectedRegion: Option[Int]): Unit = {
    simulate(new PsxDisc(SectorAddress)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      dut.io.toc.enable.poke(false.B)
      dut.io.sector.enable.poke(false.B)
      dut.io.commandCore.busy.poke(false.B)
      dut.io.commandCore.done.poke(false.B)
      dut.io.commandCore.error.poke(false.B)
      dut.io.start.poke(false.B)
      dut.io.discLoaded.poke(false.B)
      dut.io.coreReset.poke(true.B)
      dut.io.hpsReq.poke(false.B)
      dut.io.clearSectorReads.poke(false.B)
      dut.io.clearReadErrors.poke(false.B)
      dut.io.clearWorstWait.poke(false.B)

      def hostWrite(port: lib.mem.MemoryInterface, address: Int, data: Long): Unit = {
        port.enable.poke(true.B)
        port.write.poke(true.B)
        port.address.poke(address.U)
        port.dataWrite.poke(data.U)
        port.writeStrobe.poke(15.U)
        dut.clock.step()
        while (!port.done.peek().litToBoolean) dut.clock.step()
        port.enable.poke(false.B)
        dut.clock.step()
      }

      // The firmware: load the table of contents.
      for ((w, i) <- toc.zipWithIndex) hostWrite(dut.io.toc, 4 * i, w)
      dut.io.discLoaded.poke(true.B)

      // Trackinfo writes and streamed halfwords, as the core sees them.
      val trackinfo = mutable.ArrayBuffer[(Int, Long)]()
      val stream = mutable.ArrayBuffer[Int]()
      var acked = false
      var ackCycle = -1L
      var cycle = 0L
      def step(): Unit = {
        if (dut.io.trackinfoWrite.peek().litToBoolean)
          trackinfo += ((dut.io.trackinfoAddr.peek().litValue.toInt, dut.io.trackinfoData.peek().litValue.toLong))
        if (dut.io.hpsAck.peek().litToBoolean) { acked = true; ackCycle = cycle }
        if (dut.io.hpsWrite.peek().litToBoolean) {
          assert(acked && cycle > ackCycle, "data before the acknowledge")
          stream += dut.io.hpsData.peek().litValue.toInt
        }
        dut.clock.step()
        cycle += 1
      }

      /** Serve one request as the firmware does; returns its LBA. */
      def serve(sectorFor: Int => Array[Byte]): Int = {
        var n = 0
        while (!dut.io.commandCore.request.peek().litToBoolean) { step(); n += 1; assert(n < 10000, "no request") }
        val words = dut.io.commandWords.map(_.peek().litValue.toLong)
        assert(words(0) == PsxDisc.CommandDiscRead && words(1) == 0 && words(3) == SectorAddress, s"request $words")
        val lba = words(2).toInt
        val bytes = sectorFor(lba)
        for (i <- 0 until PsxDisc.SectorWords) {
          val w = (0 until 4).map(b => (bytes(4 * i + b) & 0xFFL) << (8 * b)).sum
          hostWrite(dut.io.sector, 4 * i, w)
        }
        dut.io.commandCore.done.poke(true.B)
        n = 0
        while (dut.io.commandCore.request.peek().litToBoolean) { step(); n += 1; assert(n < 100) }
        dut.io.commandCore.done.poke(false.B)
        step()
        lba
      }

      // CoreRun: the core is held while the license sector is read.
      dut.io.start.poke(true.B)
      step()
      dut.io.start.poke(false.B)
      step()
      assert(dut.io.holdCore.peek().litToBoolean)
      assert(serve(lba => sectorBytes(lba, license)) == PsxDisc.LicenseSector)
      var n = 0
      while (dut.io.holdCore.peek().litToBoolean) { step(); n += 1; assert(n < 20000, "still holding the core") }
      val region = dut.io.region.valid.peek().litToBoolean
      assert((if (region) Some(dut.io.region.bits.peek().litValue.toInt) else None) == expectedRegion)

      // The core leaves reset: its track table.
      dut.io.coreReset.poke(false.B)
      for (_ <- 0 until 200) step()
      val metadataRegion = expectedRegion.map(Map(0 -> 2, 1 -> 1, 2 -> 3)).getOrElse(0).toLong
      val expected = Seq(
        0 -> toc(0), 1 -> toc(1), 2 -> 0x0640L, 3 -> (metadataRegion << 16),
        4 -> 0L, 5 -> 20149L, 6 -> 0x0002L, 7 -> 0L,
        8 -> 20150L, 9 -> 29999L, 10 -> ((1L << 16) | 0x0430), 11 -> 0L,
      )
      assert(trackinfo == expected, s"trackinfo $trackinfo")

      // A sector read by the core.
      dut.io.hpsLba.poke(20300.U)
      dut.io.hpsReq.poke(true.B)
      // The core drops its request with the acknowledge.
      val served = {
        var n = 0
        while (!dut.io.commandCore.request.peek().litToBoolean) { step(); n += 1; assert(n < 1000) }
        serve(l => sectorBytes(l, None))
      }
      assert(served == 20300)
      n = 0
      while (!acked) { step(); n += 1; assert(n < 1000) }
      dut.io.hpsReq.poke(false.B)
      for (_ <- 0 until 1300) step()
      val bytes = sectorBytes(20300, None)
      val expectedHalfwords = (0 until PsxDisc.SectorBytes / 2).map(i => (bytes(2 * i) & 0xFF) | ((bytes(2 * i + 1) & 0xFF) << 8))
      assert(stream == expectedHalfwords)
      assert(dut.io.sectorReads.peek().litValue == 1)
    }
  }

  "Europe" in { run(Some("Entertainment Euro pe"), Some(PsxDisc.Region.Europe)) }
  "US" in { run(Some("Entertainment Amer  ica "), Some(PsxDisc.Region.Us)) }
  "Japan" in { run(Some("Entertainment Inc."), Some(PsxDisc.Region.Japan)) }
  "unknown" in { run(None, None) }
}
