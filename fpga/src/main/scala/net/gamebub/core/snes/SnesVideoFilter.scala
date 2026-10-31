package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import lib.video.{ColorRGB, Crt}
import net.gamebub.framework.interface.VideoScalerV0

object SnesVideoFilter {
  /** The frame: 256 pixels across, `HandheldSnes.OutputLines` rows. */
  val Width = 256
  val Height = HandheldSnes.OutputLines
  /** The screen's rows, which the picture fills: the rev 3 and 4 LCDs' and HDMI's. */
  val ScreenRows = 480
  /** Fractional bits of a position in the frame (in its pixels and rows). */
  val FractionBits = 16
  val One = 1 << FractionBits

  /**
   * The aspect ratio setting's values: 4:3, 8:7 (square pixels), and
   * either at two screen rows to a line (Integer, bit 1 set).
   */
  val Aspect43 = 0
  val Aspect87 = 1
  val Aspect87Integer = 2
  val Aspect43Integer = 3

  /** The scaling's settings (register 0x002C). */
  class Settings extends Bundle {
    /** Bits 1:0: the aspect ratio: `Aspect43`, `Aspect87`, `Aspect87Integer`, `Aspect43Integer`. */
    val aspect = UInt(2.W)

    /** The picture is two screen rows to a line. */
    def integer: Bool = aspect(1)
  }

  /**
   * How the frame is scaled: with 224 lines (`v224`, else 239), by
   * `aspect`, on the LCD (square pixels) or HDMI. The picture is the
   * frame's rows `top` until `bottom` (the PPU's lines), as tall as the
   * screen (Integer: two screen rows to a line), and as wide as its aspect
   * ratio makes it, centered: on the LCD with 224 lines, 640 x 480 (4:3),
   * 549 x 480 (8:7), 597 x 448 (4:3 Integer), 512 x 448 (8:7 Integer); with
   * 239, 640 or 512 across, all twice the lines tall (8:7 is the rows' scale
   * both ways).
   *
   * A screen pixel's position in the frame (`u`, `v`, in frame pixels and
   * rows, `FractionBits` fixed point) is that of its middle: `step` frame
   * pixels (rows) per screen pixel (row) from the screen's middle at the
   * picture's. With 224 lines to the screen's 480 rows (15 screen rows to 7
   * of them), the rows' middles would fall on whole 30ths of a line, the
   * edges of the scanlines' phases (see [[Crt.Pixel]]), where the rounding
   * of the step tips them either way: the picture is half a phase (1/60
   * line) lower, so that they fall in the phases' middles.
   */
  case class Scaling(v224: Boolean, aspect: Int, hdmi: Boolean) {
    val integer = aspect == Aspect87Integer || aspect == Aspect43Integer
    val (top, bottom) = if (v224) (8, 232) else (0, 240)
    val stepY = if (integer) 0.5 else (bottom - top).toDouble / ScreenRows
    /** The picture's width in square pixels. */
    val squareWidth = if (aspect == Aspect43 || aspect == Aspect43Integer) (bottom - top) / stepY * 4 / 3 else Width / stepY
    /** And in screen pixels: HDMI's are 8/9 as wide as they are tall. */
    val width = if (hdmi) squareWidth * 9 / 8 else squareWidth
    val stepX = Width / width
    /** Half the steps, in fixed point (a screen pixel's middle is half a step from its edge). */
    val halfStepX = math.round(stepX / 2 * One).toInt
    val halfStepY = math.round(stepY / 2 * One).toInt
    /** The frame position at the screen's middle. */
    val middleX = Width / 2 * One
    val middleY = (top + bottom) / 2 * One +
      (if (v224 && !integer) math.round(One / (2.0 * Crt.PhasesPerLine)).toInt else 0)
    /** The position of the middle of screen column `x` of a screen `screenWidth` across, and of screen row `y`. */
    def u(x: Int, screenWidth: Int): Long = middleX + (2L * x + 1 - screenWidth) * halfStepX
    def v(y: Int): Long = middleY + (2L * y + 1 - ScreenRows) * halfStepY
  }
  /** Every scaling, by `Cat(hdmi, aspect, v224)`. */
  val Scalings: Seq[Scaling] = (0 until 16).map { i =>
    Scaling(v224 = (i & 1) != 0, aspect = (i >> 1) & 3, hdmi = (i & 8) != 0)
  }

  /** The phase of a screen row at frame position `v`: where its middle is in its line (rounded down, see [[Crt.Pixel]]). */
  def phaseOf(v: Long): Int = (((v & (One - 1)) * Crt.PhasesPerLine) >> FractionBits).toInt
  /** The phases the rows have (15 with 224 lines to the screen's rows, 2 at two rows to a line). */
  val Phases: Seq[Int] = Scalings.flatMap(s => (0 until ScreenRows).map(y => phaseOf(s.v(y)))).distinct.sorted

  /** Display clock cycles from a screen position to its frame position (`frameX`, `frameY`). */
  val LookupLatency = 2
  /** Display clock cycles from a screen position to its color. */
  val Latency = LookupLatency + VideoScalerV0.ReadLatency + Crt.Latency
}

/**
 * The SNES's video ([[net.gamebub.framework.interface.VideoScalerV0]]):
 * scales the frame to the screen's height at 4:3 or 8:7, or by 2 down at
 * 4:3 or 8:7 (see [[SnesVideoFilter.Scaling]]), and draws the CRT effect
 * ([[Crt]]) on it, in the display clock domain.
 *
 * Interpolation: with the effect's blur (Bilinear in the menu, its Soft),
 * each screen pixel is a linear interpolation of the frame pixels around
 * its middle: across, the source columns whose middles are on either side
 * of it (the Crt's blur, by `columnWeight`); down, without scanlines and
 * unless Integer, the two lines likewise (by `lineWeight`): at two rows to
 * a line, each row is its own line's, the lines as sharp as the framework's
 * whole-number scaling made them. With scanlines, the lines' beams
 * are the vertical filter instead: a row shows its own line's and the
 * previous one's, by where its middle is in its line (`phase`). Without the
 * blur, each screen pixel is the frame pixel its middle is in (nearest
 * neighbor). Past the frame's edges, black: the picture's outermost half
 * pixels blend into the border.
 *
 * The own source column (line) is the one to the right of (below) the
 * screen pixel's middle when interpolating (the one it is in plus a half,
 * rounded down), so that the Crt's neighbor to the left (above) is the
 * other one.
 */
class SnesVideoFilter extends Module {
  import SnesVideoFilter._

  val io = IO(new Bundle {
    val crt = Input(new Crt.Settings)
    val settings = Input(new Settings)
    /** The frame shown has 224 lines (else 239). */
    val v224 = Input(Bool())
    // The scan (see VideoScalerV0).
    val x = Input(UInt(10.W))
    val y = Input(UInt(10.W))
    val screenWidth = Input(UInt(10.W))
    val screenHeight = Input(UInt(10.W))
    val columnMajor = Input(Bool())
    val hdmi = Input(Bool())
    /** The frame pixel to read, [[SnesVideoFilter.LookupLatency]] cycles after its screen position. */
    val frameX = Output(UInt(10.W))
    val frameY = Output(UInt(10.W))
    /** The frame pixel read, [[VideoScalerV0.ReadLatency]] cycles after its position. */
    val dataIn = Input(ColorRGB(5, 5, 5))
    /** The screen pixel's color, [[SnesVideoFilter.Latency]] cycles after its position. */
    val dataOut = Output(ColorRGB(8, 8, 8))
  })

  val mode = Cat(io.hdmi, io.settings.aspect, io.v224)
  def byMode(f: Scaling => Long, width: Int): UInt = VecInit(Scalings.map(s => f(s).U(width.W)))(mode)
  val halfStepX = byMode(_.halfStepX, FractionBits)
  val halfStepY = byMode(_.halfStepY, FractionBits)
  val middleY = byMode(_.middleY, 8 + FractionBits)
  val top = byMode(_.top, 8)
  val bottom = byMode(_.bottom, 9)
  val interpolateX = io.crt.blur === 1.U
  val interpolateY = interpolateX && io.crt.scanlines === 0.U && !io.settings.integer

  // Stage 1: the position in the frame.
  def offset(z: UInt, size: UInt): SInt = Cat(z, 1.U(1.W)).zext - size.zext
  val u1 = RegNext(Scalings.head.middleX.S + offset(io.x, io.screenWidth) * halfStepX.zext)
  val v1 = RegNext(middleY.zext + offset(io.y, io.screenHeight) * halfStepY.zext)
  val inColumns1 = RegNext(io.x < io.screenWidth)
  val inRows1 = RegNext(io.y < io.screenHeight)
  val x1 = RegNext(io.x)
  val y1 = RegNext(io.y)

  // Stage 2: the frame pixel, and how the Crt takes it.
  val half = (One / 2).S
  val ownX = u1 + Mux(interpolateX, half, 0.S)
  val ownY = v1 + Mux(interpolateY, half, 0.S)
  val column = ownX >> FractionBits
  val line = ownY >> FractionBits
  val fractionX = ownX(FractionBits - 1, 0)
  val fractionY = ownY(FractionBits - 1, 0)
  val pictureColumn = inColumns1 && u1 >= 0.S && u1 < (Width * One).S
  val pictureRow = inRows1 && v1 >= (top << FractionBits).zext && v1 < (bottom << FractionBits).zext
  val inFrame = column >= 0.S && column < Width.S && line >= top.zext && line < bottom.zext
  val pixel2 = Reg(new Crt.Pixel(log2Ceil(Phases.length).max(1)))
  val show2 = RegNext(pictureColumn && pictureRow && inFrame)
  io.frameX := RegNext(column(7, 0))
  io.frameY := RegNext(line(7, 0))
  pixel2.color := DontCare
  pixel2.column := x1
  pixel2.row := y1
  // (A screen column (row) starts a source column (line) when its position
  // is less than a step into it: the one before was in the one before.)
  pixel2.columnStart := !pictureColumn || fractionX < (halfStepX << 1)
  pixel2.columnWeight := Mux(pictureColumn && interpolateX, fractionX(FractionBits - 1, FractionBits - 8), 256.U)
  pixel2.lineStart := !inRows1 || y1 === 0.U || fractionY < (halfStepY << 1)
  // (Rows past the screen's are the blanking before its first line, which
  // is even: they fill the other half of the row-major line buffer.)
  pixel2.lineParity := Mux(inRows1, line(0), 1.U)
  pixel2.lineWeight := Mux(pictureRow && interpolateY, fractionY(FractionBits - 1, FractionBits - 8), 256.U)
  val phaseIndex = VecInit((0 until 32).map(p => Phases.indexOf(p).max(0).U))
  pixel2.phase := phaseIndex((fractionY * Crt.PhasesPerLine.U) >> FractionBits)

  // Stage 2 + ReadLatency: the frame pixel; black outside the picture and
  // the frame.
  val crt = Module(new Crt(phases = Phases, columns = 1024))
  crt.io.settings := io.crt
  crt.io.scanlinesAllowed := true.B
  crt.io.columnMajor := io.columnMajor
  val pixel = ShiftRegister(pixel2, VideoScalerV0.ReadLatency)
  val show = ShiftRegister(show2, VideoScalerV0.ReadLatency)
  // (5-bit channels expanded by bit replication.)
  def expand(c: UInt): UInt = Mux(show, Cat(c, c(4, 2)), 0.U)
  crt.io.in := pixel
  crt.io.in.color.r := expand(io.dataIn.r)
  crt.io.in.color.g := expand(io.dataIn.g)
  crt.io.in.color.b := expand(io.dataIn.b)
  io.dataOut := crt.io.out
}
