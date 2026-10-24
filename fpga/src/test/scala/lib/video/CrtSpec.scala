package lib.video

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import psx.PsxScanout

import scala.util.Random

/**
 * Feeds [[Crt]] screen columns of a 240-line picture as the PSX's scanout
 * does for the LCD (two rows per line, a little blanking before each
 * column) and checks the colors: unchanged with the effect off, the
 * scanlines' dark rows blending the two lines around them, the mask's
 * stripes, the blur's blend of each source column with the one to its
 * left. And rows of it, as for HDMI. And the SNES's picture, scaled by 2.
 */
class CrtSpec extends AnyFreeSpec {
  /**
   * A pixel in: color (0xRRGGBB), screen column, phase, first row of its
   * line, screen row (from 480 on, the blanking), the blur's weight of its
   * own source column, and (row-major) its line's parity.
   */
  case class In(color: Int, column: Int, phase: Int, lineStart: Boolean, row: Int = 0,
                columnStart: Boolean = true, columnWeight: Int = 0, lineParity: Int = 0)

  /** Columns `columns` of a 240-line picture with random lines; each column's pixels and their source lines (-1: blanking). */
  def picture(columns: Seq[Int], seed: Int): Seq[(In, Int, Int)] = {
    val random = new Random(seed)
    val lines = Seq.fill(240)(random.nextInt(1 << 24))
    columns.flatMap { x =>
      val blanking = (0 until 5).map(i => (In(0, x, 0, lineStart = false, row = 480 + i), -1, 0))
      val rows = (0 until 480).map { y =>
        (In(lines(y / 2), x, rowPhase(y), lineStart = y % 2 == 0, row = y), y / 2, if (y / 2 > 0) lines(y / 2 - 1) else 0)
      }
      blanking ++ rows
    }
  }

  /** As the scanout's DDA, in 240-line modes: the phase of screen row `y` (its center in its line, the line's top at y / 2). */
  def rowPhase(y: Int): Int = (2 * ((y * 240) % 480) + 240) >> 5

  /**
   * Screen columns 0 until `columns` of a picture `width` source pixels
   * across (from column 2, two black columns before it), `rows` rows
   * (two to a line) with random pixels, as the scanout's DDA scales it.
   * For each pixel: the pixel in, its line (-1: blanking, and column 0,
   * which fills the blur's buffer: it starts with anything in it), and the
   * source pixels the blur and the scanlines blend: this one, the one to
   * its left (black for the first source column), and the two of the line
   * above (black for the first line).
   */
  case class Source(in: In, line: Int, current: Int, left: Int, above: Int, aboveLeft: Int)
  def frame(width: Int, columns: Int, rows: Int, seed: Int): Seq[Source] = {
    val random = new Random(seed)
    val image = Seq.fill(rows / 2, width)(random.nextInt(1 << 24))
    def pixel(line: Int, c: Int): Int = if (line < 0 || c < 0) 0 else image(line)(c)
    var acc = 0
    var c = 0
    (0 until columns).flatMap { x =>
      val start = x <= 2 || acc + width >= 640
      if (x == 2) {
        acc = 0
        c = 0
      } else if (x > 2) {
        acc += width
        if (acc >= 640) {
          acc -= 640
          c += 1
        }
      }
      val picture = x >= 2
      val weight = if (picture) (acc * PsxScanout.BlurScale) >> 9 else 0
      val blanking = (0 until 5).map(i => Source(In(0, x, 0, lineStart = false, row = 480 + i), -1, 0, 0, 0, 0))
      blanking ++ (0 until rows).map { y =>
        val line = y / 2
        def at(l: Int, column: Int) = if (picture) pixel(l, column) else 0
        Source(In(at(line, c), x, rowPhase(y), lineStart = y % 2 == 0, row = y, columnStart = start, columnWeight = weight),
          if (x == 0) -1 else line, at(line, c), at(line, c - 1), at(line - 1, c), at(line - 1, c - 1))
      }
    }
  }

  /**
   * The same picture scanned row by row, as for HDMI: rows -2 until `rows`
   * (the first two black, of line -1, which fill the line buffer), each
   * `columns` screen columns of a picture `width` source pixels across 720
   * (from column 2) after a little blanking.
   */
  def frameRows(width: Int, columns: Int, rows: Int, seed: Int): Seq[Source] = {
    val random = new Random(seed)
    val image = Seq.fill(rows / 2, width)(random.nextInt(1 << 24))
    def pixel(line: Int, c: Int): Int = if (line < 0 || c < 0) 0 else image(line)(c)
    (-2 until rows).flatMap { y =>
      val line = Math.floorDiv(y, 2)
      val row = if (y < 0) 480 + y + 2 else y
      val blanking = (0 until 5).map(i => Source(In(0, 1000 + i, 0, lineStart = false, row = row, lineParity = line & 1), -1, 0, 0, 0, 0))
      var acc = 0
      var c = 0
      blanking ++ (0 until columns).map { x =>
        val start = x <= 2 || acc + width >= 720
        if (x == 2) {
          acc = 0
          c = 0
        } else if (x > 2) {
          acc += width
          if (acc >= 720) {
            acc -= 720
            c += 1
          }
        }
        val picture = x >= 2 && y >= 0
        val weight = if (x >= 2) (acc * PsxScanout.RowMajorBlurScale) >> 9 else 0
        def at(l: Int, column: Int) = if (picture) pixel(l, column) else 0
        Source(In(at(line, c), x, if (y < 0) 0 else rowPhase(y), lineStart = y % 2 == 0, row = row, columnStart = start,
          columnWeight = weight, lineParity = line & 1),
          if (y < 0) -1 else line, at(line, c), at(line, c - 1), at(line - 1, c), at(line - 1, c - 1))
      }
    }
  }

  /** The whole effect on a pixel, computed here: blur, scanlines, mask (settings as for [[run]]). */
  def model(settings: (Int, Int, Int), blur: Int)(p: Source): Int = {
    val (scanlines, mask, pattern) = settings
    val w = if (blur == 1) p.in.columnWeight else 256
    def blurred(left: Int, current: Int): (Int, Int) =
      ((Crt.ToLinear(left) * (256 - w) + Crt.ToLinear(current) * w) >> 8, (left * (256 - w) + current * w) >> 8)
    color((0 until 3).map { i =>
      val (light, value) = blurred(channels(p.left)(i), channels(p.current)(i))
      val (aboveLight, aboveValue) = blurred(channels(p.aboveLeft)(i), channels(p.above)(i))
      val beams = (light * Crt.CurrentWeights(scanlines)(p.in.phase)(value >> 4) +
        aboveLight * Crt.PreviousWeights(scanlines)(p.in.phase)(aboveValue >> 4)) >> 8
      val column = if (pattern == 1) p.in.column % 2 else p.in.column % 3
      val masked = (beams * Crt.MaskWeights(pattern)(column)(mask)(i)) >> 8
      Crt.ToGamma(masked.min(Crt.LinearMax))
    })
  }

  /** Runs `frame` (or `frameRows`) pixels and compares the picture's (not the blanking's) with `expected`. */
  def checkFrame(settings: (Int, Int, Int), blur: Int, pixels: Seq[Source], columnMajor: Boolean = true,
                 phases: Seq[Int] = Crt.AllPhases, columns: Int = 1024)(expected: Source => Int): Unit = {
    val got = run(settings, scanlinesAllowed = true, pixels.map(_.in), blur, columnMajor, phases, columns)
    val wrong = got.zip(pixels).zipWithIndex.collect {
      case ((g, p), i) if p.line >= 0 && g != expected(p) => (g, expected(p), p.in.column, p.in.row)
    }
    assert(wrong.isEmpty, s"${wrong.size} of ${got.size} pixels wrong, first: " +
      wrong.take(5).map { case (g, e, x, y) => f"x $x y $y: $g%06x, expected $e%06x" }.mkString(", "))
  }

  /** Runs `pixels` through a [[Crt]] (whose `phases` the pixels' phases are among) and returns its colors. */
  def run(settings: (Int, Int, Int), scanlinesAllowed: Boolean, pixels: Seq[In], blur: Int = 0, columnMajor: Boolean = true,
          phases: Seq[Int] = Crt.AllPhases, columns: Int = 1024): Seq[Int] = {
    var out = Seq[Int]()
    simulate(new Crt(phases, columns)) { dut =>
      val (scanlines, mask, pattern) = settings
      dut.io.settings.scanlines.poke(scanlines.U)
      dut.io.settings.mask.poke(mask.U)
      dut.io.settings.maskPattern.poke(pattern.U)
      dut.io.settings.blur.poke(blur.U)
      dut.io.scanlinesAllowed.poke(scanlinesAllowed.B)
      dut.io.columnMajor.poke(columnMajor.B)
      val results = Seq.newBuilder[Int]
      for ((p, i) <- (pixels ++ Seq.fill(Crt.Latency)(In(0, 0, phases.head, lineStart = false, row = 500))).zipWithIndex) {
        dut.io.in.color.r.poke((p.color >> 16).U)
        dut.io.in.color.g.poke(((p.color >> 8) & 255).U)
        dut.io.in.color.b.poke((p.color & 255).U)
        dut.io.in.column.poke(p.column.U)
        assert(phases.contains(p.phase), s"phase ${p.phase}")
        dut.io.in.phase.poke(phases.indexOf(p.phase).U)
        dut.io.in.lineStart.poke(p.lineStart.B)
        dut.io.in.row.poke(p.row.U)
        dut.io.in.columnStart.poke(p.columnStart.B)
        dut.io.in.columnWeight.poke(p.columnWeight.U)
        dut.io.in.lineParity.poke(p.lineParity.U)
        if (i >= Crt.Latency) {
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
  def light(c: Int): Seq[Int] = channels(c).map(Crt.ToLinear(_))
  def fromLight(ls: Seq[Int]): Int = color(ls.map(l => Crt.ToGamma(l.min(Crt.LinearMax))))
  /** Compares the picture's pixels (not the blanking's, where the last line's beam may still show). */
  def check(got: Seq[Int], pixels: Seq[(In, Int, Int)])(expected: (In, Int) => Int): Unit = {
    val wrong = got.zip(pixels).zipWithIndex.collect {
      case ((g, (p, line, previous)), i) if line >= 0 && g != expected(p, previous) => ((g, expected(p, previous)), i)
    }
    assert(wrong.isEmpty, s"${wrong.size} of ${got.size} pixels wrong, first: " +
      wrong.take(5).map { case ((g, e), i) => f"pixel $i: $g%06x, expected $e%06x" }.mkString(", "))
  }

  "every 8-bit value comes back from linear light as it was" in {
    for (c <- 0 until 256) assert(Crt.ToGamma(Crt.ToLinear(c)) == c, s"value $c")
  }

  "off: the picture unchanged" in {
    val pixels = picture(Seq(100, 101, 102), seed = 1)
    check(run((0, 0, 0), scanlinesAllowed = true, pixels.map(_._1)), pixels)((p, _) => p.color)
  }

  "scanlines not allowed (480-line modes): the picture unchanged" in {
    val pixels = picture(Seq(100), seed = 2)
    check(run((3, 0, 0), scanlinesAllowed = false, pixels.map(_._1)), pixels)((p, _) => p.color)
  }

  /**
   * With scanlines at `strength`: each line's second row as it is, the
   * first the light of each line around it, weighted by that line's
   * brightness in each color.
   */
  def scanlines(strength: Int)(p: In, previous: Int): Int =
    if (!p.lineStart) {
      p.color
    } else {
      val weights = channels(p.color).zip(channels(previous)).map { case (c, q) =>
        (Crt.CurrentWeights(strength)(p.phase)(c >> 4), Crt.PreviousWeights(strength)(p.phase)(q >> 4))
      }
      fromLight(light(p.color).zip(light(previous)).zip(weights).map { case ((c, q), (wc, wq)) => (c * wc + q * wq) >> 8 })
    }

  "scanlines 75 %: the dark rows, each line's light by its brightness" in {
    val pixels = picture(Seq(100, 101), seed = 3)
    check(run((2, 0, 0), scanlinesAllowed = true, pixels.map(_._1)), pixels)(scanlines(2))
  }

  "scanlines 100 %" in {
    val pixels = picture(Seq(7), seed = 4)
    check(run((3, 0, 0), scanlinesAllowed = true, pixels.map(_._1)), pixels)(scanlines(3))
  }

  "scanlines: between dark lines nearly the strength's darkening, between bright ones little of it" in {
    // The dark row's light against its lines' (both the same gray), for each strength and brightness.
    def gap(strength: Int, gray: Int): Double = {
      val phase = 240 >> 5 // the first of a line's two rows in 240-line modes
      val w = Crt.CurrentWeights(strength)(phase)(gray >> 4) + Crt.PreviousWeights(strength)(phase)(gray >> 4)
      w / 256.0
    }
    for ((s, i) <- Crt.ScanlineStrengths.zipWithIndex; strength = i + 1) {
      assert(gap(strength, 0x00) < 1 - 0.9 * s, s"strength $strength: black lines' gap ${gap(strength, 0)}")
      assert(gap(strength, 0xFF) > 1 - 0.2 * s, s"strength $strength: white lines' gap ${gap(strength, 0xFF)}")
      val gaps = (0 until 256 by 16).map(gap(strength, _))
      assert(gaps.zip(gaps.tail).forall { case (a, b) => b >= a }, s"strength $strength: not filling up with brightness: $gaps")
    }
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
        val average = out.map(c => Crt.ToLinear(channels(c)(channel))).sum.toDouble / columns
        val original = Crt.ToLinear(gray).toDouble
        assert((average - original).abs <= original * 0.01 + 2, s"mask $mask pattern $pattern gray $gray%x channel $channel: $average, not $original")
      }
    }
  }

  "sharp: each source column as it is" in {
    val pixels = frame(width = 256, columns = 14, rows = 40, seed = 20)
    checkFrame((0, 0, 0), blur = 0, pixels)(_.current)
  }

  "soft, 320 across: every other screen column the source column to its left, the others half of each" in {
    val pixels = frame(width = 320, columns = 14, rows = 40, seed = 21)
    checkFrame((0, 0, 0), blur = 1, pixels) { p =>
      if (p.in.columnWeight == 0) p.left
      else color(channels(p.left).zip(channels(p.current)).map { case (l, c) =>
        Crt.ToGamma((Crt.ToLinear(l) * 128 + Crt.ToLinear(c) * 128) >> 8)
      })
    }
  }

  "soft, 256 and 368 across: blended by where the screen column is" in {
    for ((width, seed) <- Seq(256 -> 22, 368 -> 23)) {
      val pixels = frame(width, columns = 16, rows = 40, seed)
      checkFrame((0, 0, 0), blur = 1, pixels)(model((0, 0, 0), blur = 1))
    }
  }

  "soft with 75 % scanlines and a 25 % magenta / green mask" in {
    val pixels = frame(width = 256, columns = 16, rows = 40, seed = 24)
    checkFrame((2, 1, 1), blur = 1, pixels)(model((2, 1, 1), blur = 1))
  }

  "row-major (HDMI): sharp, each source column as it is" in {
    val pixels = frameRows(width = 256, columns = 20, rows = 12, seed = 30)
    checkFrame((0, 0, 0), blur = 0, pixels, columnMajor = false)(_.current)
  }

  "row-major: scanlines 100 %, the line above from the line buffer" in {
    val pixels = frameRows(width = 320, columns = 20, rows = 12, seed = 31)
    checkFrame((3, 0, 0), blur = 0, pixels, columnMajor = false)(model((3, 0, 0), blur = 0))
  }

  "row-major: soft, 256, 320, 368 and 640 across, in 720ths of a source column" in {
    for ((width, seed) <- Seq(256 -> 32, 320 -> 33, 368 -> 34, 640 -> 35)) {
      val pixels = frameRows(width, columns = 24, rows = 6, seed)
      checkFrame((0, 0, 0), blur = 1, pixels, columnMajor = false)(model((0, 0, 0), blur = 1))
    }
  }

  "row-major: soft with 75 % scanlines and a 25 % magenta / green mask" in {
    val pixels = frameRows(width = 368, columns = 24, rows = 12, seed = 36)
    checkFrame((2, 1, 1), blur = 1, pixels, columnMajor = false)(model((2, 1, 1), blur = 1))
  }
}
