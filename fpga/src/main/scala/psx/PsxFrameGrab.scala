package psx

import chisel3._
import chisel3.util._

object PsxFrameGrab {
  /** Chip 1 byte addresses of the two copies (laid out as VRAM: 2 KiB lines). */
  val CopyBase = 0x80_0000L
  val CopyStride = 0x10_0000L
}

/**
 * Copies the PSX's display, line by line as a TV shows it, into one of two
 * frames in chip 1 (in the clk1x domain, the module's clock), for
 * [[PsxScanout]], which scans the LCD column by column and so cannot read
 * VRAM itself while the game updates it: reading the left strips (all their
 * rows) at the start of a frame, it showed what the game was still drawing
 * there (a game that draws or copies its picture into the displayed buffer
 * in the vertical blanking, as Final Fantasy IX's battles and fades do,
 * staying ahead of the TV's beam).
 *
 * At the start of each line of the picture (`hblank` falling, outside
 * `vblank`), the display area's line goes to the copy being made: its row
 * from the display's position as it is then, as the TV takes it. When
 * `vblank` rises the copy is complete and becomes `frame`, for the scanout
 * (whose display frame starts there), and the next goes to the other one.
 * One frame of latency, as a framebuffer has.
 *
 * `display` is the core's display state in the scanout's terms (VRAM; its
 * `base` is ignored); its format is taken for the frame when `vblank` falls.
 * `skipLines` are the display range's lines above the TV picture (not
 * shown).
 *
 * In 480-line modes (`doubled`, each line of the picture two rows of it)
 * nothing is copied: two 640-pixel rows per line, on top of the scanout's
 * own reads of them, is more than the memory has to spare (the copy fell
 * behind into the frame being shown). `frame` is then the display itself,
 * in VRAM, as it is (the scanout takes it once per frame).
 */
class PsxFrameGrab extends Module {
  import PsxFrameGrab._

  val io = IO(new Bundle {
    val vblank = Input(Bool())
    val hblank = Input(Bool())
    val display = Input(new PsxScanout.Params)
    val skipLines = Input(UInt(10.W))
    val doubled = Input(Bool())

    val copy = Decoupled(new PsxMemory.LineCopy)
    /** The last complete copy. */
    val frame = Output(new PsxScanout.Params)
  })

  val vblankLast = RegNext(io.vblank, true.B)
  val hblankLast = RegNext(io.hblank, true.B)
  val frameStart = !io.vblank && vblankLast
  val frameEnd = io.vblank && !vblankLast
  val lineStart = !io.hblank && hblankLast && !io.vblank

  val writing = RegInit(0.U(1.W))
  val copying = RegInit(false.B)
  /** The frame being copied: format, where it goes. */
  val format = Reg(new PsxScanout.Params)
  val skip = Reg(UInt(10.W))
  val doubled = Reg(Bool())
  /** Lines of the picture so far. */
  val line = Reg(UInt(10.W))

  val done = RegInit({
    val p = Wire(new PsxScanout.Params)
    p := 0.U.asTypeOf(p)
    p.disabled := true.B
    p
  })
  val direct = Wire(new PsxScanout.Params)
  direct := io.display
  direct.base := 0.U
  io.frame := Mux(io.doubled, direct, done)

  when (frameStart) {
    copying := !io.doubled
    line := 0.U
    format := io.display
    format.base := CopyBase.U + Mux(writing.asBool, CopyStride.U, 0.U)
    skip := io.skipLines
    doubled := io.doubled
  }
  when (frameEnd) {
    when (copying) {
      // The copy has each line from its start word: only the position of
      // the first pixel within its word is left.
      done := format
      done.offsetX := format.offsetX(0)
      done.offsetY := 0.U
      done.disabled := format.disabled || format.rows === 0.U
      writing := !writing
    } .otherwise {
      // (A frame not copied: what was is stale.)
      done.disabled := true.B
    }
    copying := false.B
  }

  // The rows of this line still to queue (two in 480-line modes).
  val pending = RegInit(0.U(2.W))
  val row = Reg(UInt(10.W))
  val pictureLines = Mux(doubled, format.rows >> 1, format.rows)

  // A line whose first pixel would sit elsewhere in its word than the
  // frame's (the display moved by an odd number of pixels mid-frame) keeps
  // the frame's position.
  val offsetX = Mux(io.display.offsetX(0) === format.offsetX(0), io.display.offsetX, format.offsetX)
  val bytes = format.areaWidth * Mux(format.depth24, 3.U, 2.U) +& Cat(offsetX(0), 0.U(1.W))
  val queue = Module(new Queue(new PsxMemory.LineCopy, 4))
  io.copy <> queue.io.deq
  queue.io.enq.valid := pending =/= 0.U
  queue.io.enq.bits.fromLine := (io.display.offsetY + row)(8, 0)
  queue.io.enq.bits.fromWord := offsetX(9, 1)
  queue.io.enq.bits.words := ((bytes + 3.U) >> 2)(9, 0)
  queue.io.enq.bits.to := format.base + (row << 11)
  when (queue.io.enq.fire) {
    pending := pending - 1.U
    row := row + 1.U
  }
  when (lineStart && copying) {
    line := line + 1.U
    val k = line - skip
    when (line >= skip && k < pictureLines) {
      row := Mux(doubled, k << 1, k)
      pending := Mux(doubled, 2.U, 1.U)
    }
  }
}
