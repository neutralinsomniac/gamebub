package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import lib.video.Crt
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Scans a random SNES frame through [[SnesVideoFilter]] as the framework
 * does (each screen pixel's position from the frame's top-left corner, with
 * whatever the frame buffer holds outside the frame): column by column, as
 * on the LCD, and row by row, as on HDMI; checks the frame's pixels against
 * the effect computed here.
 */
class SnesVideoFilterSpec extends AnyFreeSpec {
  import SnesVideoFilter._

  /** Screen rows scanned (the frame's top ones; the effect keeps no state across a column's rows). */
  val Rows = 24

  /** A random frame of 15-bit colors (0bBBBBBGGGGGRRRRR). */
  def frame(seed: Int): Seq[Seq[Int]] = {
    val random = new Random(seed)
    Seq.fill(Rows / Scale, Width)(random.nextInt(1 << 15))
  }

  /** A 15-bit color's 8-bit channels (red, green, blue). */
  def channels(c: Int): Seq[Int] = Seq(c & 31, (c >> 5) & 31, (c >> 10) & 31).map(v => (v << 3) | (v >> 2))

  /** The effect on the frame's pixel at screen (x, y) (settings: scanlines, mask, pattern, blur). */
  def model(image: Seq[Seq[Int]], settings: (Int, Int, Int, Int))(x: Int, y: Int): Int = {
    val (scanlines, mask, pattern, blur) = settings
    def pixel(line: Int, column: Int): Seq[Int] =
      if (line < 0 || column < 0) Seq(0, 0, 0) else channels(image(line)(column))
    val (column, line) = (x / Scale, y / Scale)
    val w = if (blur == 1) ColumnWeights(x % Scale) else 256
    /** A line's light and value in each channel, blurred. */
    def blurred(line: Int): Seq[(Int, Int)] = pixel(line, column - 1).zip(pixel(line, column)).map { case (l, c) =>
      ((Crt.ToLinear(l) * (256 - w) + Crt.ToLinear(c) * w) >> 8, (l * (256 - w) + c * w) >> 8)
    }
    val phase = Phases(y % Scale)
    val maskColumn = if (pattern == 1) x % 2 else x % 3
    val out = blurred(line).zip(blurred(line - 1)).zipWithIndex.map { case (((light, value), (aboveLight, aboveValue)), i) =>
      val beams = (light * Crt.CurrentWeights(scanlines)(phase)(value >> 4) +
        aboveLight * Crt.PreviousWeights(scanlines)(phase)(aboveValue >> 4)) >> 8
      Crt.ToGamma(((beams * Crt.MaskWeights(pattern)(maskColumn)(mask)(i)) >> 8).min(Crt.LinearMax))
    }
    (out(0) << 16) | (out(1) << 8) | out(2)
  }

  /** A pixel the framework asks for: its position (10-bit, wrapping), and whether it is in the frame's scanned part. */
  case class At(x: Int, y: Int) {
    def inFrame: Boolean = x >= 0 && x < Width * Scale && y >= 0 && y < Rows
  }

  /** The LCD's scan: each column (from a few left of the frame to a few past it), a few blanking rows, then its rows. */
  def columnMajor: Seq[At] = (-4 until Width * Scale + 4).flatMap { x =>
    (-8 until 0).map(y => At(x, y + 1000)) ++ (0 until Rows).map(y => At(x, y))
  }

  /** HDMI's scan: two blanking rows (of line -1, in the line buffer's other half), then the rows, each from a few left of the frame to a few past it. */
  def rowMajor: Seq[At] = (-2 until Rows).flatMap { y =>
    (-6 until Width * Scale + 6).map(x => At(x, y))
  }

  def run(image: Seq[Seq[Int]], settings: (Int, Int, Int, Int), scan: Seq[At], columnMajor: Boolean): Seq[Int] = {
    var out = Seq[Int]()
    simulate(new SnesVideoFilter) { dut =>
      val (scanlines, mask, pattern, blur) = settings
      dut.io.settings.scanlines.poke(scanlines.U)
      dut.io.settings.mask.poke(mask.U)
      dut.io.settings.maskPattern.poke(pattern.U)
      dut.io.settings.blur.poke(blur.U)
      dut.io.columnMajor.poke(columnMajor.B)
      val garbage = new Random(99)
      val results = Seq.newBuilder[Int]
      for ((at, i) <- (scan ++ Seq.fill(Latency)(At(1000, 1000))).zipWithIndex) {
        // Outside the frame, the frame buffer's read is of any pixel.
        val c = if (at.inFrame) image(at.y / Scale)(at.x / Scale) else garbage.nextInt(1 << 15)
        dut.io.dataIn.r.poke((c & 31).U)
        dut.io.dataIn.g.poke(((c >> 5) & 31).U)
        dut.io.dataIn.b.poke(((c >> 10) & 31).U)
        dut.io.x.poke((at.x & 1023).U)
        dut.io.y.poke((at.y & 1023).U)
        if (i >= Latency) {
          val o = dut.io.dataOut
          results += (o.r.peek().litValue.toInt << 16) | (o.g.peek().litValue.toInt << 8) | o.b.peek().litValue.toInt
        }
        dut.clock.step()
      }
      out = results.result()
    }
    out
  }

  def check(settings: (Int, Int, Int, Int), columnMajor: Boolean, seed: Int)(expected: (Seq[Seq[Int]], At) => Int): Unit = {
    val image = frame(seed)
    val scan = if (columnMajor) this.columnMajor else rowMajor
    val got = run(image, settings, scan, columnMajor)
    val wrong = got.zip(scan).collect { case (g, at) if at.inFrame && g != expected(image, at) => (g, expected(image, at), at) }
    assert(wrong.isEmpty, s"${wrong.size} of ${scan.count(_.inFrame)} pixels wrong, first: " +
      wrong.take(5).map { case (g, e, at) => f"x ${at.x} y ${at.y}: $g%06x, expected $e%06x" }.mkString(", "))
  }

  "off: the frame, scaled" in {
    check((0, 0, 0, 0), columnMajor = true, seed = 1) { (image, at) =>
      val c = channels(image(at.y / Scale)(at.x / Scale))
      (c(0) << 16) | (c(1) << 8) | c(2)
    }
  }

  "soft: a source column's left screen column half of the one to its left, its right one its own" in {
    check((0, 0, 0, 1), columnMajor = true, seed = 2) { (image, at) => model(image, (0, 0, 0, 1))(at.x, at.y) }
  }

  "LCD: 75 % scanlines, a 25 % magenta / green mask, soft" in {
    check((2, 1, 1, 1), columnMajor = true, seed = 3) { (image, at) => model(image, (2, 1, 1, 1))(at.x, at.y) }
  }

  "LCD: 100 % scanlines, a 50 % RGB mask, sharp" in {
    check((3, 2, 0, 0), columnMajor = true, seed = 4) { (image, at) => model(image, (3, 2, 0, 0))(at.x, at.y) }
  }

  "HDMI: 75 % scanlines, a 25 % magenta / green mask, soft" in {
    check((2, 1, 1, 1), columnMajor = false, seed = 5) { (image, at) => model(image, (2, 1, 1, 1))(at.x, at.y) }
  }

  "HDMI: 50 % scanlines, a 75 % RGB mask, sharp" in {
    check((1, 3, 0, 0), columnMajor = false, seed = 6) { (image, at) => model(image, (1, 3, 0, 0))(at.x, at.y) }
  }
}
