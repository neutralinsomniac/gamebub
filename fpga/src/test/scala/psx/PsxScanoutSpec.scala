package psx

import chisel3._
import chisel3.util.{Decoupled, Valid}
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable
import scala.util.Random

object PsxScanoutSpec {
  /** The scanout with its fetcher on the test's clock and the display clock at a quarter of it. */
  class Harness extends Module {
    val io = IO(new Bundle {
      val phase = Output(UInt(2.W))
      val x = Input(UInt(10.W))
      val y = Input(UInt(10.W))
      val frameStart = Input(Bool())
      val data = Output(UInt(24.W))
      val params = Input(new PsxScanout.Params)
      val fetch = Decoupled(new PsxMemory.DisplayFetch)
      val word = Flipped(Valid(new PsxMemory.DisplayWord))
    })
    val (phase, displayClock) = withReset(false.B) {
      val p = RegInit(0.U(2.W))
      p := p + 1.U
      (p, RegNext((p + 1.U) < 2.U, false.B))
    }
    io.phase := phase
    val scanout = withClockAndReset(displayClock.asClock, reset) { Module(new PsxScanout(simulation = true)) }
    scanout.io.x := io.x
    scanout.io.y := io.y
    scanout.io.screenWidth := 800.U
    scanout.io.columnMajor := true.B
    scanout.io.frameStart := io.frameStart
    io.data := scanout.io.data.asUInt
    scanout.io.clk1x := clock
    scanout.io.params := io.params
    scanout.io.clk3x := clock
    scanout.io.reset3x := reset.asBool
    io.fetch <> scanout.io.fetch
    scanout.io.word := io.word
  }
}

/**
 * Scans a frame out of a random VRAM (served by a model of the memory's
 * display port) as the LCD does, column by column, and compares every
 * pixel with the picture computed here: scaling, the strips and their
 * fetches, VRAM wrap-around, 15- and 24-bit color, and in 480-line modes
 * the field's rows shown twice (and only they fetched).
 */
class PsxScanoutSpec extends AnyFreeSpec {
  import PsxScanoutSpec._

  /**
   * `height`: the TV window's lines; `top` black, then `picture` from VRAM
   * (all by default); `bob`: each pair of rows shows the one of parity `field`.
   */
  case class Mode(offsetX: Int, offsetY: Int, width: Int, height: Int, depth24: Boolean, top: Int = 0, picture: Int = -1,
                  bob: Boolean = false, field: Int = 0) {
    val pictureRows = if (picture < 0) height - top else picture
  }

  def run(mode: Mode, lineCycles: Int, rows: Int, lines: Int, seed: Int): Unit = {
    val rng = new Random(seed)
    val vram = Array.fill(512 * 2048)(rng.nextInt(256)) // bytes
    def byte(line: Int, b: Int): Int = vram((line & 511) * 2048 + (b & 2047))
    def expand(v: Int): Int = (v << 3) | (v >> 2)
    def expected(x: Int, y: Int): Int = {
      val c = ((x - 80) * mode.width) / 640
      val shown = (y * mode.height) / 480 - mode.top
      val r = if (mode.bob) (shown & ~1) | mode.field else shown
      val line = mode.offsetY + r
      if (shown < 0 || shown >= mode.pictureRows) {
        0
      } else if (mode.depth24) {
        val b = 2 * mode.offsetX + 3 * c
        (byte(line, b) << 16) | (byte(line, b + 1) << 8) | byte(line, b + 2)
      } else {
        val b = 2 * ((mode.offsetX + c) & 1023)
        val p = byte(line, b) | (byte(line, b + 1) << 8)
        (expand(p & 31) << 16) | (expand((p >> 5) & 31) << 8) | expand((p >> 10) & 31)
      }
    }

    simulate(new Harness) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step(8)
      dut.reset.poke(false.B)
      dut.io.params.base.poke(0.U)
      dut.io.params.offsetX.poke(mode.offsetX.U)
      dut.io.params.offsetY.poke(mode.offsetY.U)
      dut.io.params.areaWidth.poke(mode.width.U)
      dut.io.params.areaHeight.poke(mode.height.U)
      dut.io.params.topRows.poke(mode.top.U)
      dut.io.params.rows.poke(mode.pictureRows.U)
      dut.io.params.depth24.poke(mode.depth24.B)
      dut.io.params.disabled.poke(false.B)
      dut.io.params.bob.poke(mode.bob.B)
      dut.io.params.field.poke(mode.field.U)
      dut.io.word.valid.poke(false.B)
      dut.clock.step(16)

      // The display port model: requests taken at once, words back in order
      // after a few cycles, one per cycle.
      val pending = mutable.Queue[(Long, Int)]() // (byte address, words left)
      var index = 0
      var delay = 0

      // The scan: (x, y, frameStart) per display cycle; requests checked `Latency` cycles later.
      val scan = mutable.ArrayBuffer[(Int, Int, Boolean)]()
      scan += ((0, 600, true))
      // The column where the frame's state is taken, then the first strips' fetches.
      for (_ <- 0 until 2500) scan += ((80 - PsxScanout.LatchColumns, 600, false))
      scan += ((79, 600, false))
      for (x <- 80 until 80 + lines) {
        for (i <- 0 until lineCycles) {
          // As the driver: a 9-bit counter starting a few pixels before 0.
          val y = (i - 7 + 512) % 512
          scan += ((x, y, false))
        }
      }
      for (_ <- 0 until 10) scan += ((800, 600, false))

      var checked = 0
      var errors = 0
      val firstErrors = mutable.ArrayBuffer[String]()
      var displayCycle = 0
      val history = mutable.Queue[(Int, Int)]()
      while (displayCycle < scan.size) {
        val phase = dut.io.phase.peek().litValue.toInt
        if (phase == 0) {
          // Output of this display cycle: the request from `Latency` cycles ago.
          if (history.size == PsxScanout.Latency) {
            val (x, y) = history.dequeue()
            if (x >= 80 && x < 80 + lines && y < rows) {
              val got = dut.io.data.peek().litValue.toInt
              val e = expected(x, y)
              checked += 1
              if (got != e) {
                errors += 1
                if (firstErrors.size < 10) {
                  // Where in VRAM (15-bit) does the wrong pixel come from?
                  val p = ((got >> 19) & 31) | (((got >> 11) & 31) << 5) | (((got >> 3) & 31) << 10)
                  val where = (for (l <- 0 until 512; b <- 0 until 64 by 2 if ((byte(l, b) | (byte(l, b + 1) << 8)) & 0x7FFF) == p) yield (l, b)).take(3)
                  firstErrors += f"x $x y $y: $got%06x, expected $e%06x (found at $where)"
                }
              }
            }
          }
          val (x, y, start) = scan(displayCycle)
          dut.io.x.poke(x.U)
          dut.io.y.poke(y.U)
          dut.io.frameStart.poke(start.B)
          history.enqueue((x, y))
          displayCycle += 1
        }
        // Display port (every base cycle)
        dut.io.fetch.ready.poke(true.B)
        if (dut.io.fetch.valid.peek().litToBoolean) {
          val a = dut.io.fetch.bits.address.peek().litValue.toLong
          val n = dut.io.fetch.bits.words.peek().litValue.toInt + 1
          assert((a & 1023) + 4 * n <= 1024, f"fetch crosses a row: $a%x x $n")
          if (mode.bob) {
            val r = ((a >> 11).toInt - mode.offsetY) & 511
            assert((r & 1) == mode.field, f"bob: fetch of row $r from the other field ($a%x)")
          }
          pending.enqueue((a, n))
        }
        if (pending.nonEmpty && delay >= 3) {
          val (a, n) = pending.head
          val w = (0 until 4).map(i => vram(((a + 4 * index + i) % vram.length).toInt).toLong << (8 * i)).sum
          dut.io.word.valid.poke(true.B)
          dut.io.word.bits.data.poke(w.U)
          dut.io.word.bits.index.poke(index.U)
          dut.io.word.bits.last.poke((index == n - 1).B)
          index += 1
          if (index == n) {
            pending.dequeue()
            index = 0
            delay = 0
          }
        } else {
          dut.io.word.valid.poke(false.B)
          if (pending.nonEmpty) delay += 1
        }
        dut.clock.step()
      }
      assert(checked > 0)
      assert(errors == 0, s"$errors of $checked pixels wrong:\n" + firstErrors.mkString("\n"))
    }
  }

  "320 x 240, 15-bit, odd x offset, wrapping VRAM lines" in {
    run(Mode(offsetX = 3, offsetY = 500, width = 320, height = 240, depth24 = false), lineCycles = 80, rows = 60, lines = 120, seed = 1)
  }

  "368 x 240, 24-bit, wrapping at the end of the VRAM line" in {
    run(Mode(offsetX = 900, offsetY = 10, width = 368, height = 240, depth24 = true), lineCycles = 80, rows = 60, lines = 120, seed = 2)
  }

  "640 x 480, 15-bit" in {
    run(Mode(offsetX = 0, offsetY = 0, width = 640, height = 480, depth24 = false), lineCycles = 250, rows = 200, lines = 40, seed = 3)
  }

  "640 x 480, 24-bit" in {
    run(Mode(offsetX = 5, offsetY = 7, width = 640, height = 480, depth24 = true), lineCycles = 250, rows = 200, lines = 40, seed = 4)
  }

  "320 x 240 window, a 224-line display range in the middle" in {
    run(Mode(offsetX = 0, offsetY = 16, width = 320, height = 240, depth24 = false, top = 8, picture = 224), lineCycles = 500, rows = 480, lines = 40, seed = 5)
  }

  "640 x 480 bob, field 1, 15-bit, odd x offset" in {
    run(Mode(offsetX = 3, offsetY = 5, width = 640, height = 480, depth24 = false, bob = true, field = 1), lineCycles = 250, rows = 200, lines = 40, seed = 7)
  }

  "640 x 480 bob, field 0, 24-bit, wrapping at the end of the VRAM line" in {
    run(Mode(offsetX = 700, offsetY = 7, width = 640, height = 480, depth24 = true, bob = true, field = 0), lineCycles = 250, rows = 200, lines = 40, seed = 8)
  }

  "PAL 576-line window, 512 rows, bob" in {
    run(Mode(offsetX = 10, offsetY = 0, width = 512, height = 576, depth24 = false, top = 32, picture = 512, bob = true, field = 1), lineCycles = 500, rows = 480, lines = 40, seed = 9)
  }

  "PAL 576-line window, 512 rows from VRAM" in {
    run(Mode(offsetX = 10, offsetY = 0, width = 512, height = 576, depth24 = false, top = 32, picture = 512), lineCycles = 500, rows = 480, lines = 40, seed = 6)
  }
}
