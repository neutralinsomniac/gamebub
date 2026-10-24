package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import lib.video.{ColorRGB, Crt}

object SnesVideoFilter {
  /** The frame: 256 pixels across, `HandheldSnes.OutputLines` rows. */
  val Width = 256
  val Height = HandheldSnes.OutputLines
  /** Screen pixels per frame pixel, each way: 512 x 480 on the rev 3 and 4 LCDs and HDMI. */
  val Scale = 2
  /** Where the centers of a frame row's screen rows lie in it, for the scanlines (in `Crt.PhasesPerLine`ths). */
  val Phases = (0 until Scale).map(s => ((s + 0.5) / Scale * Crt.PhasesPerLine).toInt)
  /** The blur's weight of a screen column's own source column, by which of its source column's screen columns it is. */
  val ColumnWeights = (0 until Scale).map(s => (s + 1) * 256 / Scale)
  val Latency = Crt.Latency
}

/**
 * The SNES's video filter: the CRT effect ([[Crt]]) on its frame as the
 * framework scales it and scans it out
 * ([[net.gamebub.framework.interface.VideoFilterSpatialV0]]: `x`, `y` are
 * the screen pixel's position from the frame's top-left corner), in the
 * display clock domain.
 *
 * A frame row's two screen rows are the scanlines' two phases. The blur
 * weighs each screen column's own source column by where the column's right
 * edge lies in it (the PSX's goes by the left edge): the left one of a
 * source column's two screen columns blends it with the one to its left,
 * the right one is its own. By the left edge, the picture would move right
 * by a screen column, and the framework cuts off what falls past the frame.
 */
class SnesVideoFilter extends Module {
  import SnesVideoFilter._

  val io = IO(new Bundle {
    val settings = Input(new Crt.Settings)
    val dataIn = Input(ColorRGB(5, 5, 5))
    val x = Input(UInt(10.W))
    val y = Input(UInt(10.W))
    val columnMajor = Input(Bool())
    /** The pixel's color, [[SnesVideoFilter.Latency]] cycles later. */
    val dataOut = Output(ColorRGB(8, 8, 8))
  })

  val crt = Module(new Crt(phases = Phases, columns = 512))
  crt.io.settings := io.settings
  crt.io.scanlinesAllowed := true.B
  crt.io.columnMajor := io.columnMajor
  val inColumns = io.x < (Width * Scale).U
  val inFrame = inColumns && io.y < (Height * Scale).U
  /** Which of its frame pixel's screen columns / rows the pixel is. */
  val subBits = log2Ceil(Scale).max(1)
  val subX = (io.x % Scale.U)(subBits - 1, 0)
  val subY = (io.y % Scale.U)(subBits - 1, 0)
  // (5-bit channels expanded by bit replication; black outside the frame.)
  def expand(c: UInt): UInt = Mux(inFrame, Cat(c, c(4, 2)), 0.U)
  crt.io.in.color.r := expand(io.dataIn.r)
  crt.io.in.color.g := expand(io.dataIn.g)
  crt.io.in.color.b := expand(io.dataIn.b)
  crt.io.in.column := io.x
  crt.io.in.phase := subY
  crt.io.in.lineStart := subY === 0.U
  crt.io.in.lineParity := (io.y / Scale.U)(0)
  crt.io.in.row := io.y
  crt.io.in.columnStart := !inColumns || subX === 0.U
  val columnWeights = VecInit(ColumnWeights.map(_.U(9.W)))
  crt.io.in.columnWeight := Mux(inColumns, columnWeights(subX), 256.U)
  io.dataOut := crt.io.out
}
