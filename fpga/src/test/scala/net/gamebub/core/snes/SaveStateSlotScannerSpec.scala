package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Scans slot regions with various headers through a model of the
 * pipelined SDRAM port with random wait states: valid slots are
 * recognized, slots beyond the loaded size are cleared, and the number of
 * slots to write back follows the last valid one.
 */
class SaveStateSlotScannerSpec extends AnyFreeSpec {
  import SaveStateSlotScanner._

  class Memory {
    val words = scala.collection.mutable.Map[Long, Long]().withDefaultValue(0xFFFFFFFFL)
    var writes = List[Long]()

    def setSlot(slot: Int, sizeWords: Long, magic: Long = Magic): Unit = {
      words(SlotsBase + slot.toLong * SlotSize + 4) = sizeWords
      words(SlotsBase + slot.toLong * SlotSize + 8) = magic
    }
  }

  def scan(dut: SaveStateSlotScanner, memory: Memory, loadedSize: Long, random: Random): (Int, Int) = {
    dut.io.loadedSize.poke(loadedSize.U)
    dut.io.start.poke(true.B)
    dut.clock.step()
    dut.io.start.poke(false.B)
    dut.io.busy.expect(true.B)
    var pending: Option[(Long, Boolean)] = None
    var cycles = 0
    while (dut.io.busy.peek().litValue == 1 || pending.isDefined) {
      val ready = random.nextInt(3) != 0
      dut.io.mem.ready.poke(ready.B)
      dut.io.mem.dataRead.poke(pending.map { case (a, _) => memory.words(a) }.getOrElse(0L).U)
      if (ready) {
        pending.foreach { case (address, write) =>
          if (write) {
            memory.words(address) = dut.io.mem.dataWrite.peek().litValue.toLong
            memory.writes ::= address
          }
        }
        pending = if (dut.io.mem.enable.peek().litValue == 1) {
          val address = dut.io.mem.address.peek().litValue.toLong
          assert(address % 4 == 0)
          Some((address, dut.io.mem.isWrite.peek().litValue == 1))
        } else None
      }
      dut.clock.step()
      cycles += 1
      assert(cycles < 1000, "the scan takes too long")
    }
    (dut.io.valid.peek().litValue.toInt, dut.io.usedSlots.peek().litValue.toInt)
  }

  "recognizes valid slots and reports the slots in use" in {
    simulate(new SaveStateSlotScanner) { dut =>
      val random = new Random(3)
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.start.poke(false.B)
      dut.io.mem.ready.poke(true.B)
      dut.io.mem.dataRead.poke(0.U)
      dut.clock.step()
      dut.io.valid.expect(0.U)
      dut.io.usedSlots.expect(0.U)

      // Nothing loaded (a cleared region): nothing valid, nothing written.
      val empty = new Memory
      assert(scan(dut, empty, FullSize, random) == (0, 0))
      assert(empty.writes.isEmpty)

      // Slots 0 and 2 valid, 1 with a bad magic, 3 with a size too small.
      val some = new Memory
      some.setSlot(0, 0x1000)
      some.setSlot(1, 0x1000, magic = 0x53454E54L)
      some.setSlot(2, 0x3FFFF)
      some.setSlot(3, 3)
      assert(scan(dut, some, FullSize, random) == (0x5, 3))
      assert(some.writes.isEmpty)
      // The size's upper bits are ignored (the MiSTer's save counter lives there).
      some.setSlot(3, 0xFFFC0004L)
      assert(scan(dut, some, FullSize, random) == (0xD, 4))

      // A file covering two slots: the others are stale and get cleared.
      val stale = new Memory
      for (s <- 0 until 4) stale.setSlot(s, 0x1000)
      assert(scan(dut, stale, 2L * SlotSize, random) == (0x3, 2))
      assert(stale.writes.toSet == Set(
        SlotsBase + 2L * SlotSize + 4, SlotsBase + 2L * SlotSize + 8,
        SlotsBase + 3L * SlotSize + 4, SlotsBase + 3L * SlotSize + 8,
      ))
      assert(stale.words(SlotsBase + 2L * SlotSize + 8) == 0 && stale.words(SlotsBase + 3L * SlotSize + 4) == 0)
      // A rescan with everything loaded finds them cleared.
      assert(scan(dut, stale, FullSize, random) == (0x3, 2))

      // A file covering half a slot loads none of it.
      val half = new Memory
      half.setSlot(0, 0x1000)
      assert(scan(dut, half, SlotSize / 2, random) == (0, 0))
      assert(half.words(SlotsBase + 8) == 0)
    }
  }
}
