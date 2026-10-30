package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import lib.video.Crt
import net.gamebub.framework.interface.VideoScalerV0
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Scans a random SNES frame through [[SnesVideoFilter]] as the framework
 * does (the screen's positions; the frame pixel it names read and handed
 * back [[VideoScalerV0.ReadLatency]] cycles later): column by column, as on
 * the LCD, and row by row, as on HDMI, around the picture's edges; checks
 * the screen's pixels against the scaling and the effect computed here.
 */
class SnesVideoFilterSpec extends AnyFreeSpec {
  import SnesVideoFilter._

  case class Screen(width: Int, columnMajor: Boolean, hdmi: Boolean)
  val Lcd = Screen(800, columnMajor = true, hdmi = false)
  val Hdmi = Screen(720, columnMajor = false, hdmi = true)

  /** The settings: the CRT effect's (scanlines, mask, pattern, blur), the aspect ratio, the frame's lines. */
  case class Mode(scanlines: Int, mask: Int, pattern: Int, blur: Int, aspect: Int, v224: Boolean) {
    def scaling(screen: Screen): Scaling =
      Scalings((if (screen.hdmi) 8 else 0) | (aspect << 1) | (if (v224) 1 else 0))
  }

  /** A random frame of 15-bit colors (0bBBBBBGGGGGRRRRR), its padding rows too (the filter must not show them). */
  def frame(seed: Int): Seq[Seq[Int]] = {
    val random = new Random(seed)
    Seq.fill(Height, Width)(random.nextInt(1 << 15))
  }

  /** A 15-bit color's 8-bit channels (red, green, blue). */
  def channels(c: Int): Seq[Int] = Seq(c & 31, (c >> 5) & 31, (c >> 10) & 31).map(v => (v << 3) | (v >> 2))

  /**
   * The screen pixel at (x, y): black left and right of the picture; in
   * it, the frame pixels around its middle (interpolated, or the one it is
   * in), blurred across, the lines' beams down (or interpolated), the mask.
   * Above and below it, its rows are black, but with scanlines the last
   * line's beam reaches into the row below the picture (as it did when the
   * framework scaled the frame and its padding rows by 2).
   */
  def model(image: Seq[Seq[Int]], mode: Mode, screen: Screen)(x: Int, y: Int): Int = {
    val s = mode.scaling(screen)
    val (u, v) = (s.u(x, screen.width), s.v(y))
    if (u < 0 || u >= Width.toLong * One) return 0
    val inRows = v >= s.top.toLong * One && v < s.bottom.toLong * One
    val interpolateX = mode.blur == 1
    val interpolateY = interpolateX && mode.scanlines == 0
    val ownX = u + (if (interpolateX) One / 2 else 0)
    val ownY = v + (if (interpolateY) One / 2 else 0)
    val (column, line) = (Math.floorDiv(ownX, One.toLong).toInt, Math.floorDiv(ownY, One.toLong).toInt)
    val w = if (interpolateX) (Math.floorMod(ownX, One.toLong) >> 8).toInt else 256
    val lineWeight = if (interpolateY && inRows) (Math.floorMod(ownY, One.toLong) >> 8).toInt else 256
    def pixel(line: Int, column: Int): Seq[Int] =
      if (line < s.top || line >= s.bottom || column < 0 || column >= Width) Seq(0, 0, 0) else channels(image(line)(column))
    /** A line's light and value in each channel, blurred. */
    def blurred(line: Int): Seq[(Int, Int)] = pixel(line, column - 1).zip(pixel(line, column)).map { case (l, c) =>
      ((Crt.ToLinear(l) * (256 - w) + Crt.ToLinear(c) * w) >> 8, (l * (256 - w) + c * w) >> 8)
    }
    val phase = phaseOf(v)
    val maskColumn = if (mode.pattern == 1) x % 2 else x % 3
    val out = blurred(if (inRows) line else -1).zip(blurred(line - 1)).zipWithIndex.map { case (((light, value), (aboveLight, aboveValue)), i) =>
      val (current, previous) =
        if (mode.scanlines == 0) (lineWeight, 256 - lineWeight)
        else (Crt.CurrentWeights(mode.scanlines)(phase)(value >> 4), Crt.PreviousWeights(mode.scanlines)(phase)(aboveValue >> 4))
      val beams = (light * current + aboveLight * previous) >> 8
      Crt.ToGamma(((beams * Crt.MaskWeights(mode.pattern)(maskColumn)(mode.mask)(i)) >> 8).min(Crt.LinearMax))
    }
    (out(0) << 16) | (out(1) << 8) | out(2)
  }

  /** A position the framework asks for (10-bit, wrapping), and whether to check its color (not after a jump in the scan). */
  case class At(x: Int, y: Int, checked: Boolean = true)

  /** The picture's first and last screen columns. */
  def pictureColumns(mode: Mode, screen: Screen): (Int, Int) = {
    val s = mode.scaling(screen)
    val columns = (0 until screen.width).filter { x => val u = s.u(x, screen.width); u >= 0 && u < Width.toLong * One }
    (columns.head, columns.last)
  }

  /** The picture's first and last screen rows. */
  def pictureRows(mode: Mode): (Int, Int) = {
    val s = mode.scaling(Lcd)
    val rows = (0 until ScreenRows).filter { y => val v = s.v(y); v >= s.top.toLong * One && v < s.bottom.toLong * One }
    (rows.head, rows.last)
  }

  /**
   * The LCD's scan, around the picture's left and right edges: each column
   * a few blanking rows, then its rows. The first three columns after the
   * jump are not checked (until a source column starts, the blur's buffer
   * holds the column before the jump; a source column spans up to three).
   */
  def columnMajor(mode: Mode): Seq[At] = {
    val (first, last) = pictureColumns(mode, Lcd)
    def column(x: Int, checked: Boolean): Seq[At] = (-8 until 0).map(y => At(x, y + 1024, checked)) ++ (0 until ScreenRows).map(y => At(x, y, checked))
    ((first - 3) until (first + 10)).flatMap(x => column(x, checked = true)) ++
      ((last - 10) until (last + 4)).flatMap(x => column(x, checked = x >= last - 7))
  }

  /**
   * HDMI's scan: two blanking rows, the rows down to 16 into the picture,
   * then (after four rows that are not checked, the line buffer holding the
   * lines before the jump) from 16 before its end to the screen's; each row
   * from a few left of the screen to a few past it.
   */
  def rowMajor(mode: Mode): Seq[At] = {
    val (first, last) = pictureRows(mode)
    def row(y: Int, checked: Boolean): Seq[At] = (-6 until Hdmi.width + 6).map(x => At(x & 1023, y, checked))
    Seq(1022, 1023).flatMap(row(_, checked = true)) ++ (0 until first + 16).flatMap(row(_, checked = true)) ++
      ((last - 19) until ScreenRows).flatMap(y => row(y, checked = y > last - 16))
  }

  def run(image: Seq[Seq[Int]], mode: Mode, screen: Screen, scan: Seq[At]): Seq[Int] = {
    var out = Seq[Int]()
    simulate(new SnesVideoFilter) { dut =>
      dut.io.crt.scanlines.poke(mode.scanlines.U)
      dut.io.crt.mask.poke(mode.mask.U)
      dut.io.crt.maskPattern.poke(mode.pattern.U)
      dut.io.crt.blur.poke(mode.blur.U)
      dut.io.settings.aspect.poke(mode.aspect.U)
      dut.io.v224.poke(mode.v224.B)
      dut.io.screenWidth.poke(screen.width.U)
      dut.io.screenHeight.poke(ScreenRows.U)
      dut.io.columnMajor.poke(screen.columnMajor.B)
      dut.io.hdmi.poke(screen.hdmi.B)
      val garbage = new Random(99)
      // The frame buffer's reads, by cycle (outside the frame, any pixel).
      val reads = scala.collection.mutable.ArrayBuffer[Int]()
      val results = Seq.newBuilder[Int]
      for ((at, i) <- (scan ++ Seq.fill(Latency)(At(1000, 1000))).zipWithIndex) {
        dut.io.x.poke(at.x.U)
        dut.io.y.poke(at.y.U)
        val (frameX, frameY) = (dut.io.frameX.peek().litValue.toInt, dut.io.frameY.peek().litValue.toInt)
        reads += (if (frameX < Width && frameY < Height) image(frameY)(frameX) else garbage.nextInt(1 << 15))
        val c = if (i >= VideoScalerV0.ReadLatency) reads(i - VideoScalerV0.ReadLatency) else garbage.nextInt(1 << 15)
        dut.io.dataIn.r.poke((c & 31).U)
        dut.io.dataIn.g.poke(((c >> 5) & 31).U)
        dut.io.dataIn.b.poke(((c >> 10) & 31).U)
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

  def check(mode: Mode, screen: Screen, seed: Int): Unit = {
    val image = frame(seed)
    val scan = if (screen.columnMajor) columnMajor(mode) else rowMajor(mode)
    val got = run(image, mode, screen, scan)
    val expected = model(image, mode, screen) _
    val inScreen = (at: At) => at.checked && at.x < screen.width && at.y < ScreenRows
    val wrong = got.zip(scan).collect { case (g, at) if inScreen(at) && g != expected(at.x, at.y) => (g, expected(at.x, at.y), at) }
    assert(wrong.isEmpty, s"${wrong.size} of ${scan.count(inScreen)} pixels wrong, first: " +
      wrong.take(5).map { case (g, e, at) => f"x ${at.x} y ${at.y}: $g%06x, expected $e%06x" }.mkString(", "))
  }

  "the pictures: 4:3 or 8:7, the screen's height or twice the lines, centered" in {
    def square(s: Scaling): Double = s.width * (if (s.hdmi) 8.0 / 9 else 1)
    for (s <- Scalings; screen = if (s.hdmi) Hdmi else Lcd) {
      val mode = Mode(0, 0, 0, 0, s.aspect, s.v224)
      val (first, last) = pictureColumns(mode, screen)
      val shown = last - first + 1
      assert((shown - s.width).abs <= 1, s"$s: $shown columns")
      assert((first - (screen.width - 1 - last)).abs <= 1, s"$s: columns $first-$last")
      val lines = s.bottom - s.top
      val (top, bottom) = pictureRows(mode)
      val rows = if (s.aspect == Aspect87Integer) 2 * lines else ScreenRows
      assert(bottom - top + 1 == rows && top == ScreenRows - 1 - bottom, s"$s: rows $top-$bottom")
      val aspect = square(s) / rows
      assert((aspect - (if (s.aspect == Aspect43) 4.0 / 3 else Width.toDouble / lines)).abs < 0.001, s"$s: $aspect")
    }
    assert(Seq(false, true).map(v224 => pictureColumns(Mode(0, 0, 0, 0, Aspect43, v224), Lcd)) == Seq((80, 719), (80, 719)))
    assert(pictureColumns(Mode(0, 0, 0, 0, Aspect87Integer, true), Lcd) == (144, 655))
    assert(pictureRows(Mode(0, 0, 0, 0, Aspect87Integer, true)) == (16, 463))
  }

  "phases: the rows' middles mid-phase, 15 with 224 lines to the screen's rows, 2 at two rows to a line" in {
    for (s <- Scalings; y <- 0 until ScreenRows) {
      val inPhase = ((s.v(y) & (One - 1)) * Crt.PhasesPerLine).toDouble / One % 1
      // (The rounded step drifts by up to 0.06 of a phase at the screen's edges.)
      assert((inPhase - 0.5).abs < 0.1, s"$s row $y: ${inPhase}")
    }
    assert(Phases == (1 until 30 by 2).appended(22).sorted)
  }

  "LCD, 4:3, 224 lines, bilinear, no scanlines or mask" in {
    check(Mode(0, 0, 0, 1, aspect = Aspect43, v224 = true), Lcd, seed = 1)
  }

  "LCD, 8:7, 224 lines, nearest, no scanlines or mask" in {
    val mode = Mode(0, 0, 0, 0, aspect = Aspect87, v224 = true)
    check(mode, Lcd, seed = 2)
    // (The model, for once without it: the pixel each screen pixel's middle is in.)
    val image = frame(2)
    val s = mode.scaling(Lcd)
    val (first, _) = pictureColumns(mode, Lcd)
    for (y <- Seq(0, 1, 2, 3, 240, 479); x <- first until first + 6) {
      val (u, v) = ((x - 400 + 0.5) * 7 / 15 + 128, (y - 240 + 0.5) * 7 / 15 + 120)
      val c = channels(image(v.floor.toInt)(u.floor.toInt))
      assert(model(image, mode, Lcd)(x, y) == ((c(0) << 16) | (c(1) << 8) | c(2)), s"x $x y $y")
    }
  }

  "LCD, 4:3, 224 lines: 75 % scanlines, a 25 % magenta / green mask, bilinear" in {
    check(Mode(2, 1, 1, 1, aspect = Aspect43, v224 = true), Lcd, seed = 3)
  }

  "LCD, 8:7, 239 lines: 100 % scanlines, a 50 % RGB mask, nearest" in {
    check(Mode(3, 2, 0, 0, aspect = Aspect87, v224 = false), Lcd, seed = 4)
  }

  "LCD, 4:3, 239 lines, bilinear, no scanlines: the last line blends into black" in {
    check(Mode(0, 0, 0, 1, aspect = Aspect43, v224 = false), Lcd, seed = 5)
  }

  "HDMI, 4:3, 224 lines, bilinear, no scanlines or mask" in {
    check(Mode(0, 0, 0, 1, aspect = Aspect43, v224 = true), Hdmi, seed = 6)
  }

  "HDMI, 8:7, 224 lines: 75 % scanlines, a 25 % magenta / green mask, bilinear" in {
    check(Mode(2, 1, 1, 1, aspect = Aspect87, v224 = true), Hdmi, seed = 7)
  }

  "HDMI, 8:7, 239 lines: 50 % scanlines, a 75 % RGB mask, nearest" in {
    check(Mode(1, 3, 0, 0, aspect = Aspect87, v224 = false), Hdmi, seed = 8)
  }

  "LCD, 8:7 Integer, 224 lines, nearest, no scanlines or mask: each frame pixel 2 x 2, black bars" in {
    val mode = Mode(0, 0, 0, 0, aspect = Aspect87Integer, v224 = true)
    check(mode, Lcd, seed = 9)
    // (The model, for once without it.)
    val image = frame(9)
    for (y <- Seq(0, 15, 16, 17, 18, 240, 463, 464, 479); x <- Seq(143, 144, 145, 146, 400, 655, 656)) {
      val expected = if (x < 144 || x > 655 || y < 16 || y > 463) 0 else {
        val c = channels(image(8 + (y - 16) / 2)((x - 144) / 2))
        (c(0) << 16) | (c(1) << 8) | c(2)
      }
      assert(model(image, mode, Lcd)(x, y) == expected, s"x $x y $y")
    }
  }

  "LCD, 8:7 Integer, 224 lines: 75 % scanlines, a 25 % magenta / green mask, bilinear" in {
    check(Mode(2, 1, 1, 1, aspect = Aspect87Integer, v224 = true), Lcd, seed = 10)
  }

  "HDMI, 8:7 Integer, 224 lines, bilinear, no scanlines or mask: 576 across" in {
    check(Mode(0, 0, 0, 1, aspect = Aspect87Integer, v224 = true), Hdmi, seed = 11)
  }
}
