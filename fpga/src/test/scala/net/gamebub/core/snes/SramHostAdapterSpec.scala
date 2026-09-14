package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

/**
 * Drives the adapter the way the SPI receiver drives the host window
 * (request held until done, the next one right after) against a model of
 * the [[lib.mem.sram.AsyncSramController]], and checks the 16-bit words
 * written and the 32-bit words read back.
 */
class SramHostAdapterSpec extends AnyFreeSpec {
  class Target {
    val memory = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0xFFFF)
    var state = 0 // 0 idle, 1 access, 2 done
    var address = 0
    var write = false
    var data = 0
    var strobe = 0
    var accesses = 0

    private def accept(dut: SramHostAdapter, differing: Boolean): Boolean = {
      if (dut.io.sram.enable.peek().litValue == 1) {
        val a = dut.io.sram.address.peek().litValue.toInt
        val w = dut.io.sram.write.peek().litValue == 1
        val d = dut.io.sram.dataWrite.peek().litValue.toInt
        if (!differing || a != address || w != write || d != data) {
          address = a; write = w; data = d
          strobe = dut.io.sram.writeStrobe.peek().litValue.toInt
          accesses += 1
          return true
        }
      }
      false
    }

    /** Drive the target for one cycle: call before stepping the clock. */
    def service(dut: SramHostAdapter): Unit = {
      dut.io.sram.done.poke((state == 2).B)
      dut.io.sram.dataRead.poke(memory(address).U)
      state match {
        case 0 => if (accept(dut, differing = false)) state = 1
        case 1 =>
          if (write) {
            val old = memory(address)
            val lo = if ((strobe & 1) != 0) data & 0xFF else old & 0xFF
            val hi = if ((strobe & 2) != 0) data & 0xFF00 else old & 0xFF00
            memory(address) = hi | lo
          }
          state = 2
        case 2 => state = if (accept(dut, differing = true)) 1 else 0
      }
    }
  }

  /** One host access held until done, as the SPI receiver does; returns the read data. */
  def hostAccess(dut: SramHostAdapter, target: Target, address: Int, write: Boolean, data: Long): Long = {
    dut.io.host.enable.poke(true.B)
    dut.io.host.address.poke(address.U)
    dut.io.host.write.poke(write.B)
    dut.io.host.dataWrite.poke(data.U)
    dut.io.host.writeStrobe.poke("b1111".U)
    var cycles = 0
    var result = 0L
    var done = false
    while (!done) {
      target.service(dut)
      if (dut.io.host.done.peek().litValue == 1) {
        result = dut.io.host.dataRead.peek().litValue.toLong
        done = true
      }
      dut.clock.step()
      cycles += 1
      assert(cycles < 20, "the access takes too long")
    }
    result
  }

  "writes two words per host word and reads them back" in {
    simulate(new SramHostAdapter) { dut =>
      val target = new Target
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.host.enable.poke(false.B)
      target.service(dut)
      dut.clock.step()

      // A run of writes at consecutive addresses, back to back.
      val words = Seq(0x11223344L, 0xAABBCCDDL, 0x00FF0080L)
      for ((w, i) <- words.zipWithIndex) {
        hostAccess(dut, target, 0x1000 + 4 * i, write = true, w)
      }
      dut.io.host.enable.poke(false.B)
      target.service(dut)
      dut.clock.step()
      assert(target.accesses == 6)
      assert(target.memory(0x800) == 0x3344 && target.memory(0x801) == 0x1122)
      assert(target.memory(0x802) == 0xCCDD && target.memory(0x803) == 0xAABB)
      assert(target.memory(0x804) == 0x0080 && target.memory(0x805) == 0x00FF)
      assert(!target.memory.contains(0x806))

      // Reads assemble the halves; an untouched word reads as 0xFFFF each half.
      for ((w, i) <- words.zipWithIndex) {
        assert(hostAccess(dut, target, 0x1000 + 4 * i, write = false, 0) == w)
      }
      assert(hostAccess(dut, target, 0x100C, write = false, 0) == 0xFFFFFFFFL)
      dut.io.host.enable.poke(false.B)
      target.service(dut)
      dut.clock.step()
      dut.io.host.done.expect(false.B)
    }
  }
}
