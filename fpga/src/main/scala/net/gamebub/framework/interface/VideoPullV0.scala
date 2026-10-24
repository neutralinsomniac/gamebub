package net.gamebub.framework.interface

import chisel3._
import lib.video.ColorRGB

/**
 * Video from a core that produces the output pixels itself, on demand,
 * instead of handing the framework a frame to buffer ([[VideoV0]]): for
 * cores whose frames do not fit in block RAM (the framework double-buffers a
 * [[VideoV0]] frame there), e.g. because they keep their frame buffer in
 * external memory. The core scales and positions its picture itself; the
 * framework still draws its overlay (the menus) on top.
 *
 * The framework scans the screen at the display clock (`clock`) and asks for
 * one pixel per cycle: `x`, `y` name the screen pixel that the color given
 * `latency` cycles later is shown at (the request is made that far ahead).
 * Coordinates are in the screen's landscape orientation, 0 <= x <
 * `screenWidth`, 0 <= y < `screenHeight`; outside the visible area (the
 * blanking) they run outside that range, wrapping around (they are 10-bit
 * counters). A core centering its picture on the `screenWidth` x
 * `screenHeight` screen gets the placement the framework gives a
 * [[VideoV0]] frame (the screen offset of the display revision is already
 * applied). The LCD's pixels are square; HDMI's 720 x 480 is shown at 4:3
 * (CEA-861 format 2), its pixels narrower.
 *
 * The scan order depends on the output: with `columnMajor` (the handheld's
 * LCD, which is mounted rotated) the screen is scanned column by column
 * (`y` counts up within a scan line, `x` advances from one line to the
 * next); without it (HDMI) row by row. A core that has to fetch its pixels
 * ahead of time uses it to know what comes next. `frameStart` pulses once
 * in a frame's vertical blanking, before its first visible line (on the
 * LCD with its vertical sync, on HDMI a line before the picture).
 *
 * Frame pacing: `vblank` (in the core's system clock domain) is the core's
 * vertical blanking, as for [[VideoV0]]; its rising edge counts as a frame
 * completed, and the display starts its next frame shortly after (within a
 * display line, once its minimum frame time has passed), so a core that
 * scans out its own frame buffer starts on a fresh frame. `framePeriod` is
 * the core's nominal frame period, which the display timing is derived
 * from.
 */
class VideoPullV0(
  /** Target frame period, in seconds. */
  val framePeriod: Double,
  /** Display clock cycles from a request (`x`, `y`) to its color (`data`). */
  val latency: Int,
) extends Bundle {
  /** The display clock: everything below except `vblank` is in its domain. */
  val clock = Input(Clock())
  /** Display-domain reset. */
  val reset = Input(Bool())

  val x = Input(UInt(10.W))
  val y = Input(UInt(10.W))
  val screenWidth = Input(UInt(10.W))
  val screenHeight = Input(UInt(10.W))
  val columnMajor = Input(Bool())
  val frameStart = Input(Bool())

  /** The color for the pixel requested `latency` cycles earlier. */
  val data = Output(ColorRGB(8, 8, 8))

  /** Vertical blanking, in the system clock domain. */
  val vblank = Output(Bool())
}
