package psx

import chisel3._
import chisel3.util._
import lib.video.ColorRGB

object PsxCrt {
  /** Cycles from a pixel in to its color out. */
  val Latency = 6
  /** How much the mask's strengths 1-3 darken the other colors of a column. */
  val MaskStrengths = Seq(0.25, 0.5, 0.75)
  /**
   * How much the scanlines' strengths 1-3 darken the middle between two
   * black lines (brighter lines fill it, see [[BrightFill]], so that these
   * go up to all of it).
   */
  val ScanlineStrengths = Seq(0.5, 0.75, 1.0)
  /**
   * How much less dark the middle between two lines gets as they get
   * brighter, up to this part of the darkening at full brightness: a TV's
   * beam spreads with its current, so that bright lines nearly meet and dark
   * ones are thin.
   */
  val BrightFill = 0.85
  /** A line's brightness for its beam's width, per color: the top bits of its value. */
  val BrightnessBits = 4
  /**
   * Where a source line's beam is, in lines from the line's top: on the
   * second of the two screen rows of a line in 240-line modes, so that the
   * first lies halfway between two beams.
   */
  val BeamCenter = 0.75
  /** The unit of `Pixel.phase`: phases per source line. */
  val PhasesPerLine = 30
  /** Bits of a channel in linear light (see [[ToLinear]]). */
  val LinearBits = 12
  val LinearMax = (1 << LinearBits) - 1

  /** The user's settings (register 0x0020). N.B. the last field is bit 0. */
  class Settings extends Bundle {
    /** Bit 4: the mask's pattern: 0 red / green / blue stripes, 1 magenta / green. */
    val maskPattern = UInt(1.W)
    /** Bits 3-2: the mask's strength: 0 off, 1-3 [[MaskStrengths]]. */
    val mask = UInt(2.W)
    /** Bits 1-0: the scanlines' strength: 0 off, 1-3 [[ScanlineStrengths]]. */
    val scanlines = UInt(2.W)
  }

  /** A source line's pixel, in light (see [[ToLinear]]) and value. */
  class LinePixel extends Bundle {
    val light = Vec(3, UInt(LinearBits.W))
    val value = Vec(3, UInt(8.W))
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

  /** The sRGB curve (the LCD's, and close to a TV's), between a channel's value and its light (0-1). */
  def srgbToLinear(c: Double): Double = if (c <= 0.04045) c / 12.92 else math.pow((c + 0.055) / 1.055, 2.4)
  def linearToSrgb(l: Double): Double = if (l <= 0.0031308) l * 12.92 else 1.055 * math.pow(l, 1 / 2.4) - 0.055

  /** A channel's light, by its 8-bit value. */
  val ToLinear: Seq[Int] = Seq.tabulate(256)(c => math.round(LinearMax * srgbToLinear(c / 255.0)).toInt)
  /** A channel's 8-bit value, by its light. */
  val ToGamma: Seq[Int] = Seq.tabulate(LinearMax + 1)(l => math.round(255 * linearToSrgb(l.toDouble / LinearMax)).toInt)
  // Every value comes back as it was (so that the effect, off, changes
  // nothing): the curve's straight start keeps the dark values apart.
  require((0 until 256).forall(c => ToGamma(ToLinear(c)) == c))

  /**
   * The weight of a screen row's own source line (`previous` false) or the
   * previous one, by scanline strength (0-3), phase and that line's
   * brightness (its value's top [[BrightnessBits]] bits). A row's two
   * weights add up to 256 at most, whatever the lines' brightness: each
   * beam's cos^n with n >= 2 is at most cos^2, or sin^2 on the other side.
   */
  def beamWeights(previous: Boolean): Seq[Seq[Seq[Int]]] = Seq.tabulate(4, 64, 1 << BrightnessBits) {
    (strength, phase, brightness) =>
      if (strength == 0) {
        if (previous) 0 else 256
      } else {
        val b = (brightness + 0.5) / (1 << BrightnessBits)
        val s = ScanlineStrengths(strength - 1) * (1 - BrightFill * b)
        val center = (phase + 0.5) / PhasesPerLine
        weight(beam(if (previous) center - (BeamCenter - 1) else (center - BeamCenter).abs, s))
      }
  }
  val CurrentWeights = beamWeights(previous = false)
  val PreviousWeights = beamWeights(previous = true)
  require((0 until 4).forall(s => (0 until 64).forall(p => CurrentWeights(s)(p).max + PreviousWeights(s)(p).max <= 256)))

  /**
   * The mask's weights (red, green, blue) by pattern, column (mod 3 or 2;
   * 3 does not occur) and strength (0-3). A color is lit in one of the
   * pattern's columns (of 3, or 2) and darker by the strength in the others;
   * the lit column is brighter by as much as the others are darker
   * together, so that the pattern gives the picture's light (as a CRT, run
   * brighter for its mask), up to full light, where it clips.
   */
  val MaskWeights: Seq[Seq[Seq[Seq[Int]]]] = Seq.tabulate(2, 4, 4) { (pattern, column, strength) =>
    val (columns, lit) =
      if (pattern == 0) (3, Seq(column == 0, column == 1, column == 2))
      else (2, Seq(column == 0, column == 1, column == 0))
    if (strength == 0) {
      Seq(256, 256, 256)
    } else {
      val m = MaskStrengths(strength - 1)
      lit.map(l => weight(if (l) 1 + (columns - 1) * m else 1 - m))
    }
  }
}

/**
 * A CRT look for the PSX's scaled picture ([[PsxScanout]]), one pixel per
 * cycle, in its (the display's) clock domain: scanlines and a mask, in
 * linear light (as light adds up and dims: a darkened channel's 8-bit value
 * falls less than its light does). With both off, the picture comes out as
 * it went in.
 *
 * Scanlines: each source line is drawn as a beam [[PsxCrt.BeamCenter]]
 * lines below its top, which gets darker away from its center (see
 * `beam`), and a screen row shows the beams of its own line and the
 * previous one (the pixel before it in its column, as the LCD scans). A
 * beam is wider the brighter its line is, in each color (see
 * [[PsxCrt.BrightFill]]). In 240-line modes, the second row of each line is
 * its beam, at full brightness, and the first lies halfway between two:
 * the two lines blended, darker by the strength for black lines, less for
 * bright ones. With 288 lines (PAL), the rows fall at
 * other distances from the beams; the next line's beam is left out, which
 * makes the rows past a beam a little darker. Not in 480-line modes
 * (`scanlinesAllowed` low), where each line is a row of its own.
 *
 * Mask: the phosphor stripes of an aperture grille, by screen column:
 * red, green and blue, or magenta and green; the other colors in a column
 * are darker by the strength, and its own colors brighter, keeping the
 * picture's light (see `MaskWeights`).
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

  // Stage 1: into linear light; the mask's weights.
  def channels(c: ColorRGB): Seq[UInt] = Seq(c.r, c.g, c.b)
  val toLinear = VecInit(ToLinear.map(_.U(LinearBits.W)))
  val pixel1 = Reg(new LinePixel)
  pixel1.light := VecInit(channels(io.in.color).map(toLinear(_)))
  pixel1.value := VecInit(channels(io.in.color))
  val lineStart1 = RegNext(io.in.lineStart)
  val phase1 = RegNext(io.in.phase)
  val strength1 = RegNext(Mux(io.scanlinesAllowed, io.settings.scanlines, 0.U))
  val maskColumn = Mux(io.settings.maskPattern === 1.U, io.in.column(0), io.in.column % 3.U)
  val maskWeights = VecInit(MaskWeights.flatten.flatten.map(ws => VecInit(ws.map(_.U(10.W)))))
  val maskWeight1 = RegNext(maskWeights(Cat(io.settings.maskPattern, maskColumn(1, 0), io.settings.mask)))

  // Stage 2: the previous source line's pixel: the last one when a line
  // starts, kept for the rest of it.
  val last = RegNext(pixel1)
  val previousLine = Reg(new LinePixel)
  when (lineStart1) {
    previousLine := last
  }
  val current2 = RegNext(pixel1)
  val previous2 = RegNext(Mux(lineStart1, last, previousLine))
  val phase2 = RegNext(phase1)
  val strength2 = RegNext(strength1)
  val maskWeight2 = RegNext(maskWeight1)

  // Stage 3: the beams' weights, by their lines' brightness (ROMs, from the
  // registers above).
  def brightness(value: UInt): UInt = value(7, 8 - BrightnessBits)
  val currentWeights = VecInit(CurrentWeights.flatten.flatten.map(_.U(9.W)))
  val previousWeights = VecInit(PreviousWeights.flatten.flatten.map(_.U(9.W)))
  val currentWeight3 = current2.value.map(v => RegNext(currentWeights(Cat(strength2, phase2, brightness(v)))))
  val previousWeight3 = previous2.value.map(v => RegNext(previousWeights(Cat(strength2, phase2, brightness(v)))))
  val current3 = RegNext(current2.light)
  val previous3 = RegNext(previous2.light)
  val maskWeight3 = RegNext(maskWeight2)

  // Stage 4: the beams (a row's weights add up to 256 at most).
  val beams4 = (0 until 3).map { i =>
    RegNext(((current3(i) * currentWeight3(i) +& previous3(i) * previousWeight3(i)) >> 8)(LinearBits - 1, 0))
  }
  val maskWeight4 = RegNext(maskWeight3)

  // Stage 5: the mask, clipped at full light.
  val masked5 = beams4.zip(maskWeight4).map { case (l, w) =>
    val x = (l * w) >> 8
    RegNext(Mux(x > LinearMax.U, LinearMax.U, x(LinearBits - 1, 0)))
  }

  // Stage 6: back to 8-bit values (a ROM, from the registers above).
  val toGamma = VecInit(ToGamma.map(_.U(8.W)))
  val out6 = masked5.map(l => RegNext(toGamma(l)))
  io.out.r := out6(0)
  io.out.g := out6(1)
  io.out.b := out6(2)
}
