package psx

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import scala.util.Random

/**
 * Runs the engine against a cycle-level model of the two chips on the bus,
 * which checks the command timing (tRCD, tRP, tRAS, tRRD, tRC, tWR, tRFC,
 * the refresh interval, bursts not cut short), the data bus (the chips and
 * the FPGA never drive it at once) and the data: random transactions with
 * random byte masks against a shadow memory.
 */
class SdramEngineSpec extends AnyFreeSpec {
  val ClockHz = 101.5625e6
  val config = SdramEngine.Config(clockHz = ClockHz, tInit = 2000, ioPrimitives = false)

  case class Txn(chip: Int, bank: Int, row: Int, column: Int, words: Int, write: Boolean, source: Int,
                 data: Seq[Long], masks: Seq[Int])

  def run(transactions: Seq[Txn], minCycles: Long = 3000): SdramChipsModel = {
    val model = new SdramChipsModel(config, 2)
    simulate(new SdramEngine(config)) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)

      val shadow = mutable.Map[(Int, Int, Int, Int), Int]()
      val pending = mutable.Queue[Txn](transactions: _*)
      /** Reads in flight: words expected, in order. */
      val expected = mutable.Queue[(Int, Int, Long, Boolean)]() // source, index, data, last
      var current: Option[Txn] = None // the transaction the engine is executing (for write data)
      var n = 0L
      var idle = 0
      while ((pending.nonEmpty || expected.nonEmpty || idle < 50 || n < minCycles) && n < 2_000_000) {
        // The pins, as registered at the edge that started this cycle.
        val dqIn = model.cycle(n,
          dut.io.pins.csN.peek().litValue.toInt,
          dut.io.pins.rasN.peek().litValue.toInt,
          dut.io.pins.casN.peek().litValue.toInt,
          dut.io.pins.weN.peek().litValue.toInt,
          dut.io.pins.bank.peek().litValue.toInt,
          dut.io.pins.address.peek().litValue.toInt,
          dut.io.pins.dqm.peek().litValue.toInt,
          dut.io.pins.dqOut.peek().litValue.toInt,
          dut.io.pins.dqOe.peek().litValue.toInt)
        dut.io.pins.dqIn.poke(dqIn.U)

        // Request
        val ready = dut.io.ready.peek().litToBoolean
        if (ready && pending.nonEmpty) {
          val t = pending.head
          dut.io.request.valid.poke(true.B)
          dut.io.request.bits.chip.poke(t.chip.U)
          dut.io.request.bits.bank.poke(t.bank.U)
          dut.io.request.bits.row.poke(t.row.U)
          dut.io.request.bits.column.poke(t.column.U)
          dut.io.request.bits.words.poke((t.words - 1).U)
          dut.io.request.bits.write.poke(t.write.B)
          dut.io.request.bits.source.poke(t.source.U)
        } else {
          dut.io.request.valid.poke(false.B)
        }
        // Write data for the transaction being executed
        current.foreach { t =>
          if (t.write) {
            val i = dut.io.writeIndex.peek().litValue.toInt.min(t.words - 1)
            dut.io.writeData.poke(t.data(i).U)
            dut.io.writeMask.poke(t.masks(i).U)
          }
        }
        if (dut.io.writeTake.peek().litToBoolean) {
          val t = current.get
          assert(t.write)
          assert(dut.io.writeSource.peek().litValue.toInt == t.source)
        }
        // Read data
        if (dut.io.read.valid.peek().litToBoolean) {
          val got = (dut.io.read.bits.source.peek().litValue.toInt, dut.io.read.bits.index.peek().litValue.toInt,
            dut.io.read.bits.data.peek().litValue.toLong, dut.io.read.bits.last.peek().litToBoolean)
          assert(expected.nonEmpty, s"cycle $n: unexpected read word $got")
          val e = expected.dequeue()
          assert(got == e, f"cycle $n: read $got, expected $e")
        }
        if (ready && pending.nonEmpty && dut.io.request.ready.peek().litToBoolean) {
          val t = pending.dequeue()
          current = Some(t)
          for (w <- 0 until t.words) {
            val c0 = t.column + 2 * w
            if (t.write) {
              for ((c, shift) <- Seq((c0, 0), (c0 + 1, 16))) {
                val key = (t.chip, t.bank, t.row, c)
                val old = shadow.getOrElse(key, 0)
                val d = ((t.data(w) >> shift) & 0xFFFF).toInt
                val m = (t.masks(w) >> (shift / 8)) & 3
                val lo = if ((m & 1) != 0) d & 0xFF else old & 0xFF
                val hi = if ((m & 2) != 0) d & 0xFF00 else old & 0xFF00
                shadow(key) = hi | lo
              }
            } else {
              val lo = shadow.getOrElse((t.chip, t.bank, t.row, c0), 0).toLong
              val hi = shadow.getOrElse((t.chip, t.bank, t.row, c0 + 1), 0).toLong
              expected.enqueue((t.source, w, (hi << 16) | lo, w == t.words - 1))
            }
          }
        }
        idle = if (pending.isEmpty && expected.isEmpty) idle + 1 else 0
        dut.clock.step()
        n += 1
      }
      assert(pending.isEmpty && expected.isEmpty, s"stuck: ${pending.size} pending, ${expected.size} reads outstanding")
    }
    model
  }

  def randomTransactions(count: Int, seed: Int): Seq[Txn] = {
    val rng = new Random(seed)
    (0 until count).map { _ =>
      val chip = rng.nextInt(2)
      val bank = rng.nextInt(4)
      // Few rows, so that there are hits and conflicts.
      val row = rng.nextInt(3) * 1000 + rng.nextInt(2)
      val words = 1 + rng.nextInt(16)
      val column = 2 * rng.nextInt(256 - words + 1)
      val write = rng.nextInt(3) != 0
      Txn(chip, bank, row, column, words, write, rng.nextInt(8),
        Seq.fill(words)(rng.nextLong() & 0xFFFFFFFFL),
        Seq.fill(words)(if (rng.nextInt(4) == 0) rng.nextInt(16) else 15))
    }
  }

  "initializes both chips" in {
    val model = run(Seq())
    assert(model.errors.isEmpty, model.errors.mkString("\n"))
    assert(model.modeSet.forall(identity))
    assert(model.initRefreshes.forall(_ == 8))
  }

  "random transactions keep timing and data" in {
    val model = run(randomTransactions(1500, 1))
    assert(model.errors.isEmpty, model.errors.mkString("\n"))
    assert(model.refreshes.forall(_ > 8), s"refreshes ${model.refreshes.toSeq}")
    assert(model.maxRefreshGap <= config.refi + 32, s"refresh gap ${model.maxRefreshGap}")
  }

  "back-to-back single words alternate chips at full rate" in {
    // Reads of single words to open rows on alternating chips: one burst
    // every other cycle plus the arbitration cycle.
    val warm = Seq(Txn(0, 0, 5, 0, 1, true, 0, Seq(0x11112222L), Seq(15)), Txn(1, 0, 5, 0, 1, true, 0, Seq(0x33334444L), Seq(15)))
    val reads = (0 until 200).map(i => Txn(i % 2, 0, 5, 0, 1, false, i % 8, Seq(), Seq()))
    val model = run(warm ++ reads)
    assert(model.errors.isEmpty, model.errors.mkString("\n"))
  }
}
