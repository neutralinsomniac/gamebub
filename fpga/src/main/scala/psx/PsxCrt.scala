package psx

import chisel3._
import chisel3.util._
import lib.video.ColorRGB

object PsxCrt {
  /** Cycles from a pixel in to its color out. */
  val Latency = 8
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
  /**
   * The unit of `Pixel.columnPhase`: the scanout's horizontal DDA counts in
   * 1/(the picture's width) source columns: 640ths on the LCD (scanned
   * column by column), 720ths on HDMI (row by row).
   */
  val ColumnPhases = PsxScanout.PictureWidth
  val RowMajorColumnPhases = PsxScanout.HdmiPictureWidth
  /** The blur's weight of a pixel's own source column, in 256ths: `columnPhase * BlurScale >> 9` (`RowMajorBlurScale` on HDMI). */
  val BlurScale = math.round(256.0 * 512 / ColumnPhases).toInt
  val RowMajorBlurScale = math.round(256.0 * 512 / RowMajorColumnPhases).toInt
  /** Screen rows (column-major, the blur's buffer holds a pixel pair for each). */
  val Rows = 480
  /** Entries of the buffer across the scan: a pixel pair per screen row (column-major), or a pixel per screen column for each of two lines (row-major). */
  val BufferEntries = 2048

  /** The user's settings (register 0x0020). N.B. the last field is bit 0. */
  class Settings extends Bundle {
    /** Bit 5: the blur: 0 sharp (off), 1 soft. */
    val blur = UInt(1.W)
    /** Bit 4: the mask's pattern: 0 red / green / blue stripes, 1 magenta / green. */
    val maskPattern = UInt(1.W)
    /** Bits 3-2: the mask's strength: 0 off, 1-3 [[MaskStrengths]]. */
    val mask = UInt(2.W)
    /** Bits 1-0: the scanlines' strength: 0 off, 1-3 [[ScanlineStrengths]]. */
    val scanlines = UInt(2.W)
  }

  /** A screen row's pixels in the blur's buffer: the source pixel of the last screen column, and of the source column before it. */
  class RowPixels extends Bundle {
    val previous = ColorRGB(8, 8, 8)
    val current = ColorRGB(8, 8, 8)
  }

  /** A source line's pixel, in light (see [[ToLinear]]) and value. */
  class LinePixel extends Bundle {
    val light = Vec(3, UInt(LinearBits.W))
    val value = Vec(3, UInt(8.W))
  }

  /** A source line's pixel as its beam needs it: its light, and its brightness (for the beam's width, see `beamWeights`). */
  class BeamPixel extends Bundle {
    val light = Vec(3, UInt(LinearBits.W))
    val brightness = Vec(3, UInt(BrightnessBits.W))
  }

  /**
   * A pixel of the scaled picture, in the order the screen is scanned: down
   * each screen column (column-major: the LCD), or along each row (HDMI).
   */
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
    /** Column-major: the first pixel of its source line in the column: the one before it showed the previous line (or none). */
    val lineStart = Bool()
    /** Row-major: the parity of its source line (the half of the line buffer the line goes to). */
    val lineParity = UInt(1.W)
    /** The screen row (from [[Rows]] on, the blanking). */
    val row = UInt(10.W)
    /** The pixel's screen column starts a source column: the screen column before it showed the previous one (or none). */
    val columnStart = Bool()
    /** Where the screen column's left edge is in its source column, in 1/[[ColumnPhases]] (or [[RowMajorColumnPhases]]) columns. */
    val columnPhase = UInt(10.W)
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
 * previous one. A beam is wider the brighter its line is, in each color
 * (see [[PsxCrt.BrightFill]]). In 240-line modes, the second row of each
 * line is its beam, at full brightness, and the first lies halfway between
 * two: the two lines blended, darker by the strength for black lines, less
 * for bright ones. With 288 lines (PAL), the rows fall at other distances
 * from the beams; the next line's beam is left out, which makes the rows
 * past a beam a little darker. Not in 480-line modes (`scanlinesAllowed`
 * low), where each line is a row of its own.
 *
 * Mask: the phosphor stripes of an aperture grille, by screen column:
 * red, green and blue, or magenta and green; the other colors in a column
 * are darker by the strength, and its own colors brighter, keeping the
 * picture's light (see `MaskWeights`).
 *
 * Blur (soft): before the scanlines, each screen column blends its source
 * column with the one to its left, by where its left edge is in its own (a
 * linear blend, which moves the picture right by about a screen column),
 * as a TV blurs its composite signal; source pixels of uneven width (256
 * or 368 of them across the picture) come out even.
 *
 * The neighbors these need come from the scan, or from a buffer across it.
 * Column-major (the LCD scans down each screen column), the previous line's
 * pixel is the one before in the scan, and the source column to the left
 * was scanned a screen column or more ago: the buffer keeps, for each
 * screen row, the last screen column's source pixel and the one of the
 * source column before it. Row-major (HDMI), the source column to the left
 * is the one before in the scan, and the buffer keeps the previous line's
 * pixels (blurred, as its beam needs them), for each screen column: it
 * holds two lines, one in each half by their parity, so that every row of
 * a line finds the line before it in the other half.
 */
class PsxCrt extends Module {
  import PsxCrt._

  val io = IO(new Bundle {
    val settings = Input(new Settings)
    /** Scanlines may be drawn: a source line spans more than one screen row. */
    val scanlinesAllowed = Input(Bool())
    /** The scan order: down each screen column (the LCD), or (low) along each row (HDMI). */
    val columnMajor = Input(Bool())
    val in = Input(new Pixel)
    /** The pixel's color, [[PsxCrt.Latency]] cycles later. */
    val out = Output(ColorRGB(8, 8, 8))
  })

  def channels(c: ColorRGB): Seq[UInt] = Seq(c.r, c.g, c.b)
  /** `x` from the input, delayed to stage `n`. */
  def delayed[T <: Data](x: T, n: Int): T = ShiftRegister(x, n)
  val strength = Mux(io.scanlinesAllowed, io.settings.scanlines, 0.U)
  val maskColumn = Mux(io.settings.maskPattern === 1.U, io.in.column(0), io.in.column % 3.U)
  val maskWeights = VecInit(MaskWeights.flatten.flatten.map(ws => VecInit(ws.map(_.U(10.W)))))
  val maskWeight = maskWeights(Cat(io.settings.maskPattern, maskColumn(1, 0), io.settings.mask))

  // The buffer across the scan: column-major, a row's pixel pair (written
  // and read in stage 1); row-major, a line's pixel in a column (stage 3,
  // read a stage before).
  val bufferBits = (new RowPixels).getWidth.max((new BeamPixel).getWidth)
  val buffer = SRAM(BufferEntries, UInt(bufferBits.W), numReadPorts = 1, numWritePorts = 1, numReadwritePorts = 0)
  buffer.readPorts(0).enable := true.B

  // Stage 1: the source column to the left (the stored current one, if a
  // source column starts here): column-major, the row's pixels from the
  // buffer; row-major, the last pixel's.
  val color1 = RegNext(io.in.color)
  val row1 = RegNext(io.in.row)
  val columnStart1 = RegNext(io.in.columnStart)
  val blurScale = Mux(io.columnMajor, BlurScale.U, RowMajorBlurScale.U)
  val blurWeight1 = RegNext(Mux(io.settings.blur === 1.U, (io.in.columnPhase * blurScale) >> 9, 256.U(9.W)))
  val lastPixels = Reg(new RowPixels)
  val stored = Mux(io.columnMajor, buffer.readPorts(0).data.asTypeOf(new RowPixels), lastPixels)
  // (Black in the blanking, as the pixels there are: column-major, the
  // buffer's address is the row's low bits.)
  val left1 = Mux(row1 >= Rows.U, 0.U.asTypeOf(stored.current), Mux(columnStart1, stored.current, stored.previous))
  val pixels1 = Wire(new RowPixels)
  pixels1.previous := left1
  pixels1.current := color1
  lastPixels := pixels1

  // Stage 2: into linear light.
  val toLinear = VecInit(ToLinear.map(_.U(LinearBits.W)))
  def linePixel(c: ColorRGB): LinePixel = {
    val p = Wire(new LinePixel)
    p.light := VecInit(channels(c).map(toLinear(_)))
    p.value := VecInit(channels(c))
    p
  }
  val current2 = RegNext(linePixel(color1))
  val left2 = RegNext(linePixel(left1))
  val blurWeight2 = RegNext(blurWeight1)

  // Stage 3: the two source columns blended, in light, and in value (for
  // the beams' widths).
  def blend(left: UInt, current: UInt, width: Int): UInt =
    ((left * (256.U - blurWeight2) +& current * blurWeight2) >> 8)(width - 1, 0)
  val pixel3 = Reg(new LinePixel)
  for (i <- 0 until 3) {
    pixel3.light(i) := blend(left2.light(i), current2.light(i), LinearBits)
    pixel3.value(i) := blend(left2.value(i), current2.value(i), 8)
  }
  def brightness(value: UInt): UInt = value(7, 8 - BrightnessBits)
  val beam3 = Wire(new BeamPixel)
  beam3.light := pixel3.light
  beam3.brightness := VecInit(pixel3.value.map(brightness))
  val lineStart3 = delayed(io.in.lineStart, 3)
  // Row-major: the previous line's pixel in this column, from the other
  // half of the buffer.
  val lineParity2 = delayed(io.in.lineParity, 2)
  val above3 = buffer.readPorts(0).data.asTypeOf(new BeamPixel)

  // The buffer's port, by scan order.
  val lineParity3 = delayed(io.in.lineParity, 3)
  val column3 = delayed(io.in.column, 3)
  when (io.columnMajor) {
    buffer.readPorts(0).address := io.in.row(8, 0)
    buffer.writePorts(0).enable := row1 < Rows.U
    buffer.writePorts(0).address := row1(8, 0)
    buffer.writePorts(0).data := pixels1.asUInt
  } .otherwise {
    buffer.readPorts(0).address := Cat(~lineParity2, delayed(io.in.column, 2))
    buffer.writePorts(0).enable := true.B
    buffer.writePorts(0).address := Cat(lineParity3, column3)
    buffer.writePorts(0).data := beam3.asUInt
  }

  // Stage 4: the previous source line's pixel: column-major, the last one
  // when a line starts, kept for the rest of it; row-major, from the
  // buffer.
  val last = RegNext(beam3)
  val previousLine = Reg(new BeamPixel)
  when (lineStart3) {
    previousLine := last
  }
  val current4 = RegNext(beam3)
  val previous4 = RegNext(Mux(io.columnMajor, Mux(lineStart3, last, previousLine), above3))
  val phase4 = delayed(io.in.phase, 4)
  val strength4 = delayed(strength, 4)

  // Stage 5: the beams' weights, by their lines' brightness (ROMs, from the
  // registers above).
  val currentWeights = VecInit(CurrentWeights.flatten.flatten.map(_.U(9.W)))
  val previousWeights = VecInit(PreviousWeights.flatten.flatten.map(_.U(9.W)))
  val currentWeight5 = current4.brightness.map(b => RegNext(currentWeights(Cat(strength4, phase4, b))))
  val previousWeight5 = previous4.brightness.map(b => RegNext(previousWeights(Cat(strength4, phase4, b))))
  val current5 = RegNext(current4.light)
  val previous5 = RegNext(previous4.light)

  // Stage 6: the beams (a row's weights add up to 256 at most).
  val beams6 = (0 until 3).map { i =>
    RegNext(((current5(i) * currentWeight5(i) +& previous5(i) * previousWeight5(i)) >> 8)(LinearBits - 1, 0))
  }
  val maskWeight6 = delayed(maskWeight, 6)

  // Stage 7: the mask, clipped at full light.
  val masked7 = beams6.zip(maskWeight6).map { case (l, w) =>
    val x = (l * w) >> 8
    RegNext(Mux(x > LinearMax.U, LinearMax.U, x(LinearBits - 1, 0)))
  }

  // Stage 8: back to 8-bit values (a ROM, from the registers above).
  val toGamma = VecInit(ToGamma.map(_.U(8.W)))
  val out8 = masked7.map(l => RegNext(toGamma(l)))
  io.out.r := out8(0)
  io.out.g := out8(1)
  io.out.b := out8(2)
}
