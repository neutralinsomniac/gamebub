package psx

import chisel3._
import chisel3.util._
import lib.video.ColorRGB

object PsxCrt {
  /** Cycles from a pixel in to its color out. */
  val Latency = 3
  /** How much the settings' strengths 1-3 (scanlines, mask) darken the darkest part. */
  val Strengths = Seq(0.25, 0.5, 0.75)
  /**
   * Where a source line's beam is, in lines from the line's top: on the
   * second of the two screen rows of a line in 240-line modes, so that the
   * first lies halfway between two beams.
   */
  val BeamCenter = 0.75
  /** The unit of `Pixel.phase`: phases per source line. */
  val PhasesPerLine = 30

  /** The user's settings (register 0x0020). N.B. the last field is bit 0. */
  class Settings extends Bundle {
    /** Bit 4: the mask's pattern: 0 red / green / blue stripes, 1 magenta / green. */
    val maskPattern = UInt(1.W)
    /** Bits 3-2: the mask's strength: 0 off, 1-3 [[Strengths]]. */
    val mask = UInt(2.W)
    /** Bits 1-0: the scanlines' strength: 0 off, 1-3 [[Strengths]]. */
    val scanlines = UInt(2.W)
  }

  /** A pixel of the scaled picture, in the order the LCD scans them: down each screen column. */
  class Pixel extends Bundle {
    val color = ColorRGB(8, 8, 8)
    /** The screen column (for the mask). */
    val column = UInt(10.W)
    /**
     * Where the center of the pixel's screen row is in its source line, in
     * 1/[[PhasesPerLine]] lines from the line's top (rounded down; it can be
     * past the line's end when the line is less than a row tall).
     */
    val phase = UInt(6.W)
    /** The first pixel of its source line in the column: the one before it showed the previous line (or none). */
    val lineStart = Bool()
  }

  /**
   * A beam's brightness `d` lines from its center, for scanline strength
   * `s`: cos^n, with n such that halfway between two lines each beam gives
   * (1 - s) / 2 (n >= 2, so two neighboring beams add up to 1 at most);
   * none from a line away.
   */
  def beam(d: Double, s: Double): Double = {
    val n = -2 * math.log((1 - s) / 2) / math.log(2)
    if (d >= 1) 0 else math.pow(math.cos(math.Pi / 2 * d), n)
  }

  /** A weight in 256ths, rounded down (weights adding up to 1 add up to 256 at most). */
  def weight(w: Double): Int = (w * 256 + 1e-9).floor.toInt

  /** The weights of a screen row's own source line and the previous one, by scanline strength (0-3) and phase. */
  val BeamWeights: Seq[Seq[(Int, Int)]] = Seq.tabulate(4, 64) { (strength, phase) =>
    if (strength == 0) {
      (256, 0)
    } else {
      val s = Strengths(strength - 1)
      val center = (phase + 0.5) / PhasesPerLine
      val weights = (weight(beam((center - BeamCenter).abs, s)), weight(beam(center - (BeamCenter - 1), s)))
      require(weights._1 + weights._2 <= 256)
      weights
    }
  }

  /** The mask's weights (red, green, blue) by pattern, column (mod 3 or 2; 3 does not occur) and strength (0-3). */
  val MaskWeights: Seq[Seq[Seq[Seq[Int]]]] = Seq.tabulate(2, 4, 4) { (pattern, column, strength) =>
    val lit = if (pattern == 0) Seq(column == 0, column == 1, column == 2) else Seq(column == 0, column == 1, column == 0)
    val dark = if (strength == 0) 256 else weight(1 - Strengths(strength - 1))
    lit.map(if (_) 256 else dark)
  }
}

/**
 * A CRT look for the PSX's scaled picture ([[PsxScanout]]), one pixel per
 * cycle, in its (the display's) clock domain: scanlines and a mask. With
 * both off, the picture comes out as it went in.
 *
 * Scanlines: each source line is drawn as a beam [[PsxCrt.BeamCenter]]
 * lines below its top, which gets darker away from its center (see
 * `beam`), and a screen row shows the beams of its own line and the
 * previous one (the pixel before it in its column, as the LCD scans). In
 * 240-line modes, the second row of each line is its beam, at full
 * brightness, and the first lies halfway between two: the two lines
 * blended, darker by the strength. With 288 lines (PAL), the rows fall at
 * other distances from the beams; the next line's beam is left out, which
 * makes the rows past a beam a little darker. Not in 480-line modes
 * (`scanlinesAllowed` low), where each line is a row of its own.
 *
 * Mask: the phosphor stripes of an aperture grille, by screen column:
 * red, green and blue, or magenta and green; the other colors in a column
 * are darker by the strength.
 */
class PsxCrt extends Module {
  import PsxCrt._

  val io = IO(new Bundle {
    val settings = Input(new Settings)
    /** Scanlines may be drawn: a source line spans more than one screen row. */
    val scanlinesAllowed = Input(Bool())
    val in = Input(new Pixel)
    /** The pixel's color, [[PsxCrt.Latency]] cycles later. */
    val out = Output(ColorRGB(8, 8, 8))
  })

  // Stage 1: the weights, and the previous source line's color: the last
  // pixel's when a line starts, kept for the rest of it.
  val strength = Mux(io.scanlinesAllowed, io.settings.scanlines, 0.U)
  val beamWeights = VecInit(BeamWeights.flatten.map { case (c, p) => VecInit(c.U(9.W), p.U(9.W)) })
  val beamWeight = RegNext(beamWeights(Cat(strength, io.in.phase)))
  val maskColumn = Mux(io.settings.maskPattern === 1.U, io.in.column(0), io.in.column % 3.U)
  val maskWeights = VecInit(MaskWeights.flatten.flatten.map(ws => VecInit(ws.map(_.U(9.W)))))
  val maskWeight = RegNext(maskWeights(Cat(io.settings.maskPattern, maskColumn(1, 0), io.settings.mask)))
  val last = RegNext(io.in.color)
  val previousLine = Reg(ColorRGB(8, 8, 8))
  when (io.in.lineStart) {
    previousLine := last
  }
  val current1 = RegNext(io.in.color)
  val previous1 = RegNext(Mux(io.in.lineStart, last, previousLine))

  // Stage 2: the beams (the weights add up to 256 at most).
  def channels(c: ColorRGB): Seq[UInt] = Seq(c.r, c.g, c.b)
  val beams = channels(current1).zip(channels(previous1)).map { case (c, p) =>
    RegNext(((c * beamWeight(0) +& p * beamWeight(1)) >> 8)(7, 0))
  }
  val maskWeight2 = RegNext(maskWeight)

  // Stage 3: the mask.
  val masked = beams.zip(maskWeight2).map { case (c, w) => RegNext(((c * w) >> 8)(7, 0)) }
  io.out.r := masked(0)
  io.out.g := masked(1)
  io.out.b := masked(2)
}
