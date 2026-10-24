package net.gamebub.framework.interface

import chisel3._
import lib.video.ColorRGB

/**
 * A video filter that knows where each pixel lies on the screen, for effects
 * drawn across the scaled frame (scanlines, a CRT's mask, a blur): as with
 * [[VideoFilterBasicV0]], the framework passes the frame pixel of every
 * screen pixel through it, one per display clock cycle, in the order the
 * screen is scanned, and shows its color `latency` cycles later. With each,
 * it gives the screen pixel's position (`x`, `y`) relative to the top-left
 * corner of the frame on the screen, in screen pixels: the frame pixel is
 * (x / `scale`, y / `scale`).
 *
 * Outside the frame (its border, the blanking) the position lies outside
 * 0 <= x < width * `scale`, 0 <= y < height * `scale` (10-bit counters that
 * wrap: left of or above the frame is a large value), `dataIn` is not a
 * frame pixel, and the framework shows black whatever the filter makes of
 * it. Inside, each screen pixel comes once a frame.
 *
 * The scan order: with `columnMajor` (the handheld's LCD, which is mounted
 * rotated) column by column (`y` counts up within a scan line, `x` advances
 * from one line to the next); without it (HDMI) row by row.
 *
 * The filter is drawn for one `scale` (screen pixels per frame pixel, each
 * way): the framework's scaling of the frame must come out at it on every
 * output (the build fails otherwise).
 */
class VideoFilterSpatialV0(
  /// Color depth of the input video (red channel), must match Video interface
  val colorInDepthR: Int,
  /// Color depth of the input video (green channel), must match Video interface
  val colorInDepthG: Int,
  /// Color depth of the input video (blue channel), must match Video interface
  val colorInDepthB: Int,
  /// Latency (in cycles) from filter input to output (pipeline depth)
  val latency: Int,
  /// Screen pixels per frame pixel, each way
  val scale: Int,
) extends Bundle {
  val clock = Input(Clock())
  val reset = Input(Reset())

  val dataIn = Input(ColorRGB(colorInDepthR, colorInDepthG, colorInDepthB))
  val x = Input(UInt(10.W))
  val y = Input(UInt(10.W))
  val columnMajor = Input(Bool())

  val dataOut = Output(ColorRGB(8))
}
