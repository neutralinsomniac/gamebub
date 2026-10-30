package net.gamebub.framework.interface

import chisel3._
import lib.video.ColorRGB

object VideoScalerV0 {
  /** Display clock cycles from a frame position (`frameX`, `frameY`) to that pixel in `dataIn`: the frame buffer's read. */
  val ReadLatency = 3
}

/**
 * A video filter that scales the frame itself, for pictures the framework's
 * whole-number scaling does not make (another aspect ratio, interpolation)
 * and effects drawn across the scaled picture (scanlines, a CRT's mask): the
 * framework keeps the core's frame ([[VideoV0]]) and scans the screen, one
 * pixel per display clock cycle, and for each screen pixel the core names
 * the frame pixel to read, then turns that pixel into the color shown.
 *
 * The scan (`x`, `y`) is as for [[VideoPullV0]]: screen coordinates in its
 * landscape orientation, 0 <= x < `screenWidth`, 0 <= y < `screenHeight`,
 * outside that range in the blanking (10-bit counters that wrap), and a
 * picture centered on the screen lands where the framework would center a
 * frame (the display revision's screen offset is already applied). The
 * screen pixel's color is shown `latency` cycles after its position: the
 * core gives the frame position (`frameX`, `frameY`) some cycles after it,
 * the framework reads that pixel and gives it in `dataIn`
 * [[VideoScalerV0.ReadLatency]] cycles later, and the core's color
 * (`dataOut`) is shown the rest of the `latency` later. A position outside
 * the frame reads some other pixel of it.
 *
 * The core draws all of the screen, its border too: the framework shows
 * `dataOut` everywhere (and its overlay, the menus, on top).
 *
 * The scan order: with `columnMajor` (the rev 1, 2 and 4 LCDs, which are
 * mounted rotated) column by column (`y` counts up within a scan line, `x`
 * advances from one line to the next); without it (the rev 3 LCD, HDMI)
 * row by row. The LCDs' pixels are square; with `hdmi`, the screen is
 * HDMI's 720 x 480, which a TV shows at 4:3 (CEA-861 format 2), its pixels
 * 8/9 as wide as they are tall.
 */
class VideoScalerV0(
  /// Color depth of the input video (red channel), must match Video interface
  val colorInDepthR: Int,
  /// Color depth of the input video (green channel), must match Video interface
  val colorInDepthG: Int,
  /// Color depth of the input video (blue channel), must match Video interface
  val colorInDepthB: Int,
  /// Display clock cycles from a screen position (`x`, `y`) to its color (`dataOut`)
  val latency: Int,
) extends Bundle {
  /** The display clock: everything here is in its domain. */
  val clock = Input(Clock())
  val reset = Input(Reset())

  val x = Input(UInt(10.W))
  val y = Input(UInt(10.W))
  val screenWidth = Input(UInt(10.W))
  val screenHeight = Input(UInt(10.W))
  val columnMajor = Input(Bool())
  val hdmi = Input(Bool())

  /** The frame pixel to show at a screen position. */
  val frameX = Output(UInt(10.W))
  val frameY = Output(UInt(10.W))
  /** The frame pixel at (`frameX`, `frameY`), [[VideoScalerV0.ReadLatency]] cycles after it. */
  val dataIn = Input(ColorRGB(colorInDepthR, colorInDepthG, colorInDepthB))

  val dataOut = Output(ColorRGB(8))
}
