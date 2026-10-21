package psx

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Feeds [[PsxCrt]] screen columns of a 240-line picture as the scanout
 * does (two rows per line, a little blanking before each column) and
 * checks the colors: unchanged with the effect off, the scanlines' dark
 * rows blending the two lines around them, the mask's stripes.
 */
class PsxCrtSpec extends AnyFreeSpec {
  /** A pixel in: color (0xRRGGBB), screen column, phase, first row of its line. */
  case class In(color: Int, column: Int, phase: Int, lineStart: Boolean)

  /** Columns `columns` of a 240-line picture with random lines; each column's pixels and their source lines (-1: blanking). */
  def picture(columns: Seq[Int], seed: Int): Seq[(In, Int, Int)] = {
    val random = new Random(seed)
    val lines = Seq.fill(240)(random.nextInt(1 << 24))
    columns.flatMap { x =>
      val blanking = Seq.fill(5)((In(0, x, 0, lineStart = false), -1, 0))
      val rows = (0 until 480).map { y =>
        // As the scanout's DDA: the row's top at (y * 240) / 480 lines.
        val rowAcc = (y * 240) % 480
        val phase = (2 * rowAcc + 240) >> 5
        (In(lines(y / 2), x, phase, lineStart = y % 2 == 0), y / 2, if (y / 2 > 0) lines(y / 2 - 1) else 0)
      }
      blanking ++ rows
    }
  }

  def run(settings: (Int, Int, Int), scanlinesAllowed: Boolean, pixels: Seq[In]): Seq[Int] = {
    var out = Seq[Int]()
    simulate(new PsxCrt) { dut =>
      val (scanlines, mask, pattern) = settings
      dut.io.settings.scanlines.poke(scanlines.U)
      dut.io.settings.mask.poke(mask.U)
      dut.io.settings.maskPattern.poke(pattern.U)
      dut.io.scanlinesAllowed.poke(scanlinesAllowed.B)
      val results = Seq.newBuilder[Int]
      for ((p, i) <- (pixels ++ Seq.fill(PsxCrt.Latency)(In(0, 0, 0, lineStart = false))).zipWithIndex) {
        dut.io.in.color.r.poke((p.color >> 16).U)
        dut.io.in.color.g.poke(((p.color >> 8) & 255).U)
        dut.io.in.color.b.poke((p.color & 255).U)
        dut.io.in.column.poke(p.column.U)
        dut.io.in.phase.poke(p.phase.U)
        dut.io.in.lineStart.poke(p.lineStart.B)
        if (i >= PsxCrt.Latency) {
          val c = dut.io.out
          results += (c.r.peek().litValue.toInt << 16) | (c.g.peek().litValue.toInt << 8) | c.b.peek().litValue.toInt
        }
        dut.clock.step()
      }
      out = results.result()
    }
    out
  }

  def channels(c: Int): Seq[Int] = Seq(c >> 16, (c >> 8) & 255, c & 255)
  def color(cs: Seq[Int]): Int = (cs(0) << 16) | (cs(1) << 8) | cs(2)
  /** A color's light (linear, 12 bits per channel), and back (clipped at full light). */
  def light(c: Int): Seq[Int] = channels(c).map(PsxCrt.ToLinear(_))
  def fromLight(ls: Seq[Int]): Int = color(ls.map(l => PsxCrt.ToGamma(l.min(PsxCrt.LinearMax))))
  /** Compares the picture's pixels (not the blanking's, where the last line's beam may still show). */
  def check(got: Seq[Int], pixels: Seq[(In, Int, Int)])(expected: (In, Int) => Int): Unit = {
    val wrong = got.zip(pixels).zipWithIndex.collect {
      case ((g, (p, line, previous)), i) if line >= 0 && g != expected(p, previous) => ((g, expected(p, previous)), i)
    }
    assert(wrong.isEmpty, s"${wrong.size} of ${got.size} pixels wrong, first: " +
      wrong.take(5).map { case ((g, e), i) => f"pixel $i: $g%06x, expected $e%06x" }.mkString(", "))
  }

  "every 8-bit value comes back from linear light as it was" in {
    for (c <- 0 until 256) assert(PsxCrt.ToGamma(PsxCrt.ToLinear(c)) == c, s"value $c")
  }

  "off: the picture unchanged" in {
    val pixels = picture(Seq(100, 101, 102), seed = 1)
    check(run((0, 0, 0), scanlinesAllowed = true, pixels.map(_._1)), pixels)((p, _) => p.color)
  }

  "scanlines not allowed (480-line modes): the picture unchanged" in {
    val pixels = picture(Seq(100), seed = 2)
    check(run((3, 0, 0), scanlinesAllowed = false, pixels.map(_._1)), pixels)((p, _) => p.color)
  }

  "scanlines 50 %: each line's second row as it is, the first a quarter of the light of each line around it" in {
    val pixels = picture(Seq(100, 101), seed = 3)
    def expected(p: In, previous: Int): Int =
      if (!p.lineStart) p.color
      else fromLight(light(p.color).zip(light(previous)).map { case (c, q) => (c * 64 + q * 64) >> 8 })
    check(run((2, 0, 0), scanlinesAllowed = true, pixels.map(_._1)), pixels)(expected)
  }

  "scanlines 75 %: the dark rows an eighth of the light of each line" in {
    val pixels = picture(Seq(7), seed = 4)
    def expected(p: In, previous: Int): Int =
      if (!p.lineStart) p.color
      else fromLight(light(p.color).zip(light(previous)).map { case (c, q) => (c * 32 + q * 32) >> 8 })
    check(run((3, 0, 0), scanlinesAllowed = true, pixels.map(_._1)), pixels)(expected)
  }

  "mask 50 %, red / green / blue stripes: the other colors' light halved, the column's own doubled" in {
    val pixels = picture(Seq(300, 301, 302, 303), seed = 5)
    check(run((0, 2, 0), scanlinesAllowed = true, pixels.map(_._1)), pixels) { (p, _) =>
      fromLight(light(p.color).zipWithIndex.map { case (l, i) => (l * (if (p.column % 3 == i) 512 else 128)) >> 8 })
    }
  }

  "mask 25 %, magenta / green: the other colors' light at 3/4, the column's own at 5/4" in {
    val pixels = picture(Seq(300, 301), seed = 6)
    check(run((0, 1, 1), scanlinesAllowed = true, pixels.map(_._1)), pixels) { (p, _) =>
      val lit = if (p.column % 2 == 0) Seq(true, false, true) else Seq(false, true, false)
      fromLight(light(p.color).zip(lit).map { case (l, on) => (l * (if (on) 320 else 192)) >> 8 })
    }
  }

  "the mask keeps a gray's light, over its columns" in {
    for ((setting, columns) <- Seq((2, 0) -> 3, (3, 0) -> 3, (1, 1) -> 2, (3, 1) -> 2); gray <- Seq(0x30, 0x60, 0x90)) {
      val (mask, pattern) = setting
      val g = (gray << 16) | (gray << 8) | gray
      val pixels = (0 until columns).map(x => In(g, x, 0, lineStart = false))
      val out = run((0, mask, pattern), scanlinesAllowed = true, pixels)
      for (channel <- 0 until 3) {
        val average = out.map(c => PsxCrt.ToLinear(channels(c)(channel))).sum.toDouble / columns
        val original = PsxCrt.ToLinear(gray).toDouble
        assert((average - original).abs <= original * 0.01 + 2, s"mask $mask pattern $pattern gray $gray%x channel $channel: $average, not $original")
      }
    }
  }
}
