package net.gamebub.core.nes

import chisel3._
import chisel3.util._
import lib.video.{ColorRGB, Crt}
import net.gamebub.framework.interface.VideoScalerV0

object NesVideoFilter {
  /** Full NES frame: 256 pixels across, 240 rows. */
  val Width = 256
  val Height = 240
  /** The screen's rows, which the picture fills: the rev 3 and 4 LCDs' and HDMI's. */
  val ScreenRows = 480
  /** Fractional bits of a position in the frame (in its pixels and rows). */
  val FractionBits = 16
  val One = 1 << FractionBits

  /** Aspect ratio setting values (same layout as SNES). */
  val Aspect43 = 0
  val Aspect87 = 1
  val Aspect87Integer = 2
  val Aspect43Integer = 3

  /** The scaling's settings (register 0x002C). N.B. last field is bit 0. */
  class Settings extends Bundle {
    /** Bit 2: 0 = crop overscan (224 lines: rows 8..231), 1 = full 240 lines (rows 0..239). */
    val fullOverscan = Bool()
    /** Bits 1:0: aspect ratio (`Aspect43`, `Aspect87`, `Aspect87Integer`, `Aspect43Integer`). */
    val aspect = UInt(2.W)

    /** The picture is two screen rows to a line. */
    def integer: Bool = aspect(1)
  }

  case class Scaling(v224: Boolean, aspect: Int, hdmi: Boolean) {
    val integer = aspect == Aspect87Integer || aspect == Aspect43Integer
    val (top, bottom) = if (v224) (8, 232) else (0, 240)
    val stepY = if (integer) 0.5 else (bottom - top).toDouble / ScreenRows
    val squareWidth = if (aspect == Aspect43 || aspect == Aspect43Integer) (bottom - top) / stepY * 4 / 3 else Width / stepY
    val width = if (hdmi) squareWidth * 9 / 8 else squareWidth
    val stepX = Width / width
    val halfStepX = math.round(stepX / 2 * One).toInt
    val halfStepY = math.round(stepY / 2 * One).toInt
    val middleX = Width / 2 * One
    val middleY = (top + bottom) / 2 * One +
      (if (v224 && !integer) math.round(One / (2.0 * Crt.PhasesPerLine)).toInt else 0)
    def u(x: Int, screenWidth: Int): Long = middleX + (2L * x + 1 - screenWidth) * halfStepX
    def v(y: Int): Long = middleY + (2L * y + 1 - ScreenRows) * halfStepY
  }

  /** Every scaling, by `Cat(hdmi, aspect, v224)`. */
  val Scalings: Seq[Scaling] = (0 until 16).map { i =>
    Scaling(v224 = (i & 1) != 0, aspect = (i >> 1) & 3, hdmi = (i & 8) != 0)
  }

  def phaseOf(v: Long): Int = (((v & (One - 1)) * Crt.PhasesPerLine) >> FractionBits).toInt
  val Phases: Seq[Int] = Scalings.flatMap(s => (0 until ScreenRows).map(y => phaseOf(s.v(y)))).distinct.sorted

  val LookupLatency = 2
  val Latency = LookupLatency + VideoScalerV0.ReadLatency + Crt.Latency
}

/**
 * The NES video scaler and CRT filter ([[VideoScalerV0]]):
 * scales the 256x240 framebuffer (or cropped 256x224 overscan window) to
 * 4:3, 4:3 Integer, 8:7, or 8:7 Integer with optional bilinear interpolation
 * and linear-light CRT scanlines/mask ([[Crt]]).
 */
class NesVideoFilter(colorDepth: Int = 6) extends Module {
  import NesVideoFilter._

  val io = IO(new Bundle {
    val crt = Input(new Crt.Settings)
    val settings = Input(new Settings)
    val x = Input(UInt(10.W))
    val y = Input(UInt(10.W))
    val screenWidth = Input(UInt(10.W))
    val screenHeight = Input(UInt(10.W))
    val columnMajor = Input(Bool())
    val hdmi = Input(Bool())
    val frameX = Output(UInt(10.W))
    val frameY = Output(UInt(10.W))
    val dataIn = Input(ColorRGB(colorDepth, colorDepth, colorDepth))
    val dataOut = Output(ColorRGB(8, 8, 8))
  })

  val v224 = !io.settings.fullOverscan
  val mode = Cat(io.hdmi, io.settings.aspect, v224)
  def byMode(f: Scaling => Long, width: Int): UInt = VecInit(Scalings.map(s => f(s).U(width.W)))(mode)
  val halfStepX = byMode(_.halfStepX, FractionBits)
  val halfStepY = byMode(_.halfStepY, FractionBits)
  val middleY = byMode(_.middleY, 8 + FractionBits)
  val top = byMode(_.top, 8)
  val bottom = byMode(_.bottom, 9)
  val interpolateX = io.crt.blur === 1.U
  val interpolateY = interpolateX && io.crt.scanlines === 0.U && !io.settings.integer

  // Stage 1: position in the frame
  def offset(z: UInt, size: UInt): SInt = Cat(z, 1.U(1.W)).zext - size.zext
  val u1 = RegNext(Scalings.head.middleX.S + offset(io.x, io.screenWidth) * halfStepX.zext)
  val v1 = RegNext(middleY.zext + offset(io.y, io.screenHeight) * halfStepY.zext)
  val inColumns1 = RegNext(io.x < io.screenWidth)
  val inRows1 = RegNext(io.y < io.screenHeight)
  val x1 = RegNext(io.x)
  val y1 = RegNext(io.y)

  // Stage 2: frame pixel coordinates and CRT parameters
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
  pixel2.columnStart := !pictureColumn || fractionX < (halfStepX << 1)
  pixel2.columnWeight := Mux(pictureColumn && interpolateX, fractionX(FractionBits - 1, FractionBits - 8), 256.U)
  pixel2.lineStart := !inRows1 || y1 === 0.U || fractionY < (halfStepY << 1)
  pixel2.lineParity := Mux(inRows1, line(0), 1.U)
  pixel2.lineWeight := Mux(pictureRow && interpolateY, fractionY(FractionBits - 1, FractionBits - 8), 256.U)
  val phaseIndex = VecInit((0 until 32).map(p => Phases.indexOf(p).max(0).U))
  pixel2.phase := phaseIndex((fractionY * Crt.PhasesPerLine.U) >> FractionBits)

  // Stage 2 + ReadLatency: CRT effect
  val crt = Module(new Crt(phases = Phases, columns = 1024))
  crt.io.settings := io.crt
  crt.io.scanlinesAllowed := true.B
  crt.io.columnMajor := io.columnMajor
  val pixel = ShiftRegister(pixel2, VideoScalerV0.ReadLatency)
  val show = ShiftRegister(show2, VideoScalerV0.ReadLatency)
  def expand(c: UInt): UInt = {
    val expanded = if (colorDepth >= 8) c(7, 0) else Cat(c, c(colorDepth - 1, 2 * colorDepth - 8))
    Mux(show, expanded, 0.U)
  }
  crt.io.in := pixel
  crt.io.in.color.r := expand(io.dataIn.r)
  crt.io.in.color.g := expand(io.dataIn.g)
  crt.io.in.color.b := expand(io.dataIn.b)
  io.dataOut := crt.io.out
}
