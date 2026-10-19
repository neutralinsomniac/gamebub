package psx

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable

/**
 * Plays the core's blanking signals against [[PsxFrameGrab]] and checks the
 * line copies it asks for (which VRAM line, where in it, how much, where to)
 * and the frames it publishes.
 */
class PsxFrameGrabSpec extends AnyFreeSpec {
  import PsxFrameGrab._

  case class Mode(offsetX: Int, offsetY: Int, width: Int, depth24: Boolean, rows: Int, top: Int, skip: Int, doubled: Boolean)

  def run(mode: Mode, frameLines: Int): Unit = {
    simulate(new PsxFrameGrab) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(2)
      dut.reset.poke(false.B)
      val d = dut.io.display
      d.base.poke(0.U)
      d.offsetX.poke(mode.offsetX.U)
      d.offsetY.poke(mode.offsetY.U)
      d.areaWidth.poke(mode.width.U)
      d.areaHeight.poke((if (mode.doubled) 480 else 240).U)
      d.topRows.poke(mode.top.U)
      d.rows.poke(mode.rows.U)
      d.depth24.poke(mode.depth24.B)
      d.disabled.poke(false.B)
      dut.io.skipLines.poke(mode.skip.U)
      dut.io.doubled.poke(mode.doubled.B)
      dut.io.copy.ready.poke(true.B)

      val copies = mutable.ArrayBuffer[(Int, Int, Int, Long)]()
      def step(vblank: Boolean, hblank: Boolean): Unit = {
        dut.io.vblank.poke(vblank.B)
        dut.io.hblank.poke(hblank.B)
        if (dut.io.copy.valid.peek().litToBoolean) {
          val c = dut.io.copy.bits
          copies += ((c.fromLine.peek().litValue.toInt, c.fromWord.peek().litValue.toInt, c.words.peek().litValue.toInt, c.to.peek().litValue.toLong))
        }
        dut.clock.step()
      }
      def frame(): Unit = {
        for (_ <- 0 until 50) step(vblank = true, hblank = true)
        for (_ <- 0 until frameLines) {
          for (_ <- 0 until 10) step(vblank = false, hblank = true)
          for (_ <- 0 until 30) step(vblank = false, hblank = false)
        }
        step(vblank = true, hblank = true)
        step(vblank = true, hblank = true)
      }

      val bytes = mode.width * (if (mode.depth24) 3 else 2) + 2 * (mode.offsetX & 1)
      val words = (bytes + 3) / 4
      for (n <- 0 until 2) {
        copies.clear()
        frame()
        val f = dut.io.frame
        if (mode.doubled) {
          // 480-line modes: no copies, the display itself
          assert(copies.isEmpty, s"frame $n: copies in a 480-line mode: ${copies.take(4)}")
          assert(f.base.peek().litValue == 0)
          assert(f.offsetX.peek().litValue == mode.offsetX)
          assert(f.offsetY.peek().litValue == mode.offsetY)
        } else {
          val base = CopyBase + n * CopyStride
          val shown = (frameLines - mode.skip).min(mode.rows).max(0)
          val expected = (0 until shown).map(r => ((mode.offsetY + r) & 511, mode.offsetX >> 1, words, base + r * 2048L))
          assert(copies == expected, s"frame $n: ${copies.take(4)} ..., expected ${expected.take(4)} ...")
          assert(f.base.peek().litValue == base)
          assert(f.offsetX.peek().litValue == (mode.offsetX & 1))
          assert(f.offsetY.peek().litValue == 0)
        }
        assert(f.rows.peek().litValue == mode.rows)
        assert(f.topRows.peek().litValue == mode.top)
        assert(!f.disabled.peek().litToBoolean)
      }
    }
  }

  "a 224-line picture in the TV's 240, odd x" in {
    run(Mode(offsetX = 3, offsetY = 500, width = 320, depth24 = false, rows = 224, top = 8, skip = 0, doubled = false), frameLines = 224)
  }

  "lines above the TV picture skipped, 24-bit" in {
    run(Mode(offsetX = 0, offsetY = 0, width = 368, depth24 = true, rows = 240, top = 0, skip = 3, doubled = false), frameLines = 243)
  }

  "480-line mode: no copies, VRAM itself" in {
    run(Mode(offsetX = 10, offsetY = 0, width = 640, depth24 = false, rows = 480, top = 0, skip = 0, doubled = true), frameLines = 240)
  }
}
