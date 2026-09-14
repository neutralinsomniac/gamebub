package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import HandheldSnes.SramFill

/**
 * Runs the fill engine against a model of the [[AsyncSramController]]
 * behind the arbiter (a request is accepted when idle or, if it differs
 * from the completed one, in its done cycle; `done` pulses two cycles after
 * the accept; a request identical to the completed one in the done cycle is
 * the old one still held and is dropped) and checks the memory contents,
 * the range written and the pace.
 */
class SramFillSpec extends AnyFreeSpec {
  class Target {
    val memory = scala.collection.mutable.Map[Int, Int]()
    var state = 0 // 0 idle, 1 access, 2 done
    var address = 0
    var data = 0
    var strobe = 0
    var write = false
    var accesses = 0

    /** Take the request on the interface, if any: true when accepted. */
    private def accept(dut: SramFill, differing: Boolean): Boolean = {
      if (dut.io.mem.enable.peek().litValue == 1) {
        val newAddress = dut.io.mem.address.peek().litValue.toInt
        val newData = dut.io.mem.dataWrite.peek().litValue.toInt
        if (!differing || newAddress != address || newData != data) {
          address = newAddress
          data = newData
          write = dut.io.mem.write.peek().litValue == 1
          strobe = dut.io.mem.writeStrobe.peek().litValue.toInt
          accesses += 1
          return true
        }
      }
      false
    }

    /** Drive the target for one cycle: call before stepping the clock. */
    def service(dut: SramFill): Unit = {
      dut.io.mem.done.poke((state == 2).B)
      dut.io.mem.dataRead.poke(0.U)
      state match {
        case 0 =>
          if (accept(dut, differing = false)) state = 1
        case 1 =>
          assert(write, "the engine only writes")
          assert(strobe == 3, "both bytes are written")
          memory(address) = data
          state = 2
        case 2 =>
          state = if (accept(dut, differing = true)) 1 else 0
      }
    }
  }

  /** The MiSTer WRAM power-on pattern for byte offset `a` of the region. */
  def wramByte(a: Int): Int = if ((((a >> 8) ^ (a >> 2)) & 1) != 0) 0x66 else 0x99

  def run(base: Int, words: Int, wramPattern: Boolean): (Target, Int) = {
    var cycles = 0
    val target = new Target
    simulate(new SramFill) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.start.poke(false.B)
      dut.io.base.poke(base.U)
      dut.io.words.poke(words.U)
      dut.io.wramPattern.poke(wramPattern.B)
      target.service(dut)
      dut.clock.step()
      dut.io.busy.expect(false.B)

      dut.io.start.poke(true.B)
      target.service(dut)
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.busy.expect(true.B)

      while (dut.io.busy.peek().litValue == 1) {
        target.service(dut)
        dut.clock.step()
        cycles += 1
        assert(cycles < words * 4 + 8, "the fill takes too long")
      }
      // Stays idle afterwards.
      for (_ <- 0 until 8) {
        target.service(dut)
        dut.clock.step()
        dut.io.busy.expect(false.B)
        dut.io.mem.enable.expect(false.B)
      }
    }
    (target, cycles)
  }

  "fills a range with 0xFFFF and nothing else" in {
    val (target, cycles) = run(base = 0x100, words = 20, wramPattern = false)
    assert(target.accesses == 20)
    assert(target.memory.keys.toSet == (0x100 until 0x114).toSet)
    assert(target.memory.values.forall(_ == 0xFFFF))
    // Three cycles per word (accept, access, done), give or take the edges.
    assert(cycles <= 20 * 3 + 2, s"$cycles cycles")
  }

  "writes the WRAM pattern relative to the region" in {
    val base = 0x20000
    val words = 1024
    val (target, _) = run(base = base, words = words, wramPattern = true)
    assert(target.accesses == words)
    for (w <- 0 until words) {
      val a = 2 * w
      val expected = (wramByte(a + 1) << 8) | wramByte(a)
      assert(target.memory(base + w) == expected, f"word $w%d: ${target.memory(base + w)}%04x != $expected%04x")
    }
    // Both pattern values occur.
    assert(target.memory.values.exists(_ == 0x6666) && target.memory.values.exists(_ == 0x9999))
  }

  "handles a single word" in {
    val (target, _) = run(base = 0x3FFFF, words = 1, wramPattern = false)
    assert(target.memory.toMap == Map(0x3FFFF -> 0xFFFF))
  }
}
