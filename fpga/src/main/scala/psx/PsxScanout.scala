package psx

import chisel3._
import chisel3.util._
import lib.video.ColorRGB
import xilinx.XpmCdcHandshake

object PsxScanout {
  /**
   * The picture: 4:3, the full height of the screen, centered: 640 wide on
   * the LCD (800 x 480, square pixels), all of HDMI's 720 x 480, which a TV
   * shows at 4:3 (CEA-861 format 2: its pixels are narrower).
   */
  val PictureWidth = 640
  val HdmiPictureWidth = 720
  val PictureHeight = 480
  /** Display clock cycles from a request to its color: reading the strip buffer, then the CRT effect. */
  val Latency = 3 + PsxCrt.Latency
  /**
   * Screen columns before the picture at which a frame's display state is
   * taken and its first two strips are fetched (a column is about 21 us on
   * the LCD; two strips of 480 rows take about 150 us).
   */
  val LatchColumns = 16
  /** Halfwords of strip buffer per strip (two strips): 256 rows of 18, or 512 of 10. */
  val StripHalfwords = 5120
  /**
   * Row-major (HDMI), a strip is a line: halfwords of strip buffer per line
   * (a line takes 962 at most: 640 24-bit pixels, from anywhere in a word),
   * and lines in it (while the scan is in one, the next three are fetched).
   */
  val LineHalfwords = 1024
  val LineSlots = 4

  /** The core's display state (clk1x). */
  class Params extends Bundle {
    /** Chip 1 byte address of the frame the display area is in: VRAM (0) or a copy of it (see [[PsxFrameGrab]]), laid out alike. */
    val base = UInt(25.W)
    /** Position of the display area in that frame (halfwords, lines). */
    val offsetX = UInt(10.W)
    val offsetY = UInt(9.W)
    /** Display area width: 256 / 320 / 368 / 512 / 640. */
    val areaWidth = UInt(11.W)
    /**
     * Lines of the TV picture (the screen's height): 240 / 288 (NTSC / PAL),
     * twice that in 480-line modes. Of these, `topRows` are black, then
     * `rows` come from VRAM (from `offsetY`), then black again: the
     * vertical display range.
     */
    val areaHeight = UInt(10.W)
    val topRows = UInt(10.W)
    val rows = UInt(10.W)
    val depth24 = Bool()
    val disabled = Bool()
    /**
     * 480-line modes (see [[PsxFrameGrab]]): only the rows of parity
     * `field` (the field the TV shows), each in its own place and the other
     * field's, as the TV shows them.
     */
    val bob = Bool()
    val field = UInt(1.W)
  }

  /** What the fetcher (clk3x) needs to know, from the display side. */
  class FetchRequest extends Bundle {
    /** Counts frames: a change starts the strips over. */
    val frame = UInt(2.W)
    /** Fetch strips up to this one. */
    val wanted = UInt(10.W)
    val base = UInt(25.W)
    val offsetY = UInt(9.W)
    /** Lines of the display area (column-major, of each strip). */
    val rows = UInt(10.W)
    /**
     * A strip is the display area's `stripBytes` from `startByte` on, the
     * next strip's after it, of every line, in slot `strip(0)` (column-major);
     * or (row-major) one line from `startByte`, `words` words, in slot
     * `strip` mod [[LineSlots]].
     */
    val rowMajor = Bool()
    /** Byte offset of the display area in its VRAM line. */
    val startByte = UInt(11.W)
    /** Bytes of the display area per strip (consecutive strips follow each other). */
    val stripBytes = UInt(6.W)
    /** Only the rows of parity `field` (one buffer row each). */
    val bob = Bool()
    val field = UInt(1.W)
    /** 32-bit words fetched per strip row (covering the strip's bytes wherever they start). */
    val words = UInt(9.W)
    /** Halfwords per strip buffer row. */
    val pitch = UInt(5.W)
  }
}

/**
 * Scans the PSX display area out of VRAM for the framework's on-demand video
 * ([[net.gamebub.framework.interface.VideoPullV0]]), in the display clock
 * domain (this module's clock).
 *
 * The picture fills the screen's height at 4:3 (640 x 480 on the 800 x 480
 * LCD, centered; all of HDMI's 720 x 480), scaled from the display area's
 * width and height by nearest neighbor (a DDA in each direction, stepping
 * as the scan moves to another screen column or row). VRAM is read ahead of
 * the scan into a strip buffer, in the order the screen is scanned (in
 * clk3x, through [[PsxMemory]]'s display port):
 *
 * The LCD scans the screen one column at a time, so a column of the picture
 * is a column of VRAM, one pixel from each of up to 480 lines: VRAM is read
 * in strips, a few pixels wide (16 in 15-bit color, 10 in 24-bit; half that
 * for more than 256 rows) and the display area's height, two at a time:
 * while the scan is in one, the next is fetched. The first two are fetched
 * in the screen's left border, `LatchColumns` before the picture, where
 * the display state is taken for the whole frame.
 *
 * HDMI scans it row by row: a strip is a line of the display area, read
 * while the lines before it are shown (three ahead of the scan). The first
 * ones are fetched in the line before the picture, at `frameStart`, where
 * the display state is taken.
 *
 * It scans a frame that does not change while it does: a copy of the
 * display, made line by line as a TV shows it ([[PsxFrameGrab]]), the last
 * one completed when the display state was taken. Reading VRAM itself
 * column by column, the left strips (all their rows read at the start of
 * the frame) showed what the game was still drawing there. In 480-line
 * modes it reads VRAM, the field the TV shows only (`bob`), which holds
 * still while it is shown (the LCD's frames start with the core's, see
 * `VideoPullV0`; on HDMI, whose do not, each line is read from the field
 * shown when it is fetched).
 *
 * Vertically, the picture is what a TV shows (see `Params.areaHeight`):
 * the lines of the game's vertical display range, black around them.
 *
 * The scaled picture goes through [[PsxCrt]] (scanlines and a mask, as
 * set), which is told where each pixel lies in its source line and column.
 */
class PsxScanout(simulation: Boolean = false) extends Module {
  import PsxScanout._

  /** Multi-bit state into the current clock domain (plain registers in simulation, where XPM is not available). */
  private def cdc[T <: Data](sourceClock: Clock, x: T): T =
    if (simulation) RegNext(RegNext(x)) else XpmCdcHandshake.continuous(sourceClock, x)

  val io = IO(new Bundle {
    val x = Input(UInt(10.W))
    val y = Input(UInt(10.W))
    val screenWidth = Input(UInt(10.W))
    val columnMajor = Input(Bool())
    val frameStart = Input(Bool())
    val data = Output(ColorRGB(8, 8, 8))
    /** Debug: strips the scan reached before their fetch was done (since reset). */
    val underruns = Output(UInt(32.W))

    /** The core's display state and the CRT effect's settings, in clk1x. */
    val clk1x = Input(Clock())
    val params = Input(new Params)
    val crt = Input(new PsxCrt.Settings)

    /** The fetcher, in clk3x. */
    val clk3x = Input(Clock())
    val reset3x = Input(Bool())
    val fetch = Decoupled(new PsxMemory.DisplayFetch)
    val word = Flipped(Valid(new PsxMemory.DisplayWord))
  })

  // The strip buffer: halfwords in two banks (even / odd addresses), so that
  // a 24-bit pixel's two halfwords come out together. Written by the
  // fetcher a 32-bit word (one halfword per bank) at a time.
  val banks = Seq.fill(2)(SRAM(
    StripHalfwords, UInt(16.W),
    readPortClocks = Seq(clock), writePortClocks = Seq(io.clk3x), readwritePortClocks = Seq(),
  ))

  //////////////////////////////////
  // Display side
  //////////////////////////////////
  val params = cdc(io.clk1x, io.params)
  val crtSettings = cdc(io.clk1x, io.crt)
  val pictureWidth = Mux(io.columnMajor, PictureWidth.U(10.W), HdmiPictureWidth.U(10.W))
  val x0 = (io.screenWidth - pictureWidth) >> 1
  /** The scan moved to another screen column / row (column-major, a new scan line; row-major, every cycle / a new one). */
  val newColumn = io.x =/= RegNext(io.x)
  val newRow = io.y =/= RegNext(io.y)
  // The display state is taken for the frame shortly before the picture,
  // where its first strips are fetched. On the LCD, a few columns before
  // it, not at the start of the frame: the display's frame starts as the
  // core's vertical blanking does, and games flip their buffers in the
  // blanking, after its start (taken there, the picture was the buffer
  // being drawn). On HDMI, whose frames do not follow the core's, at
  // `frameStart`, a line before the picture: the scan has to stay ahead of
  // PsxFrameGrab, which overwrites the copy taken (the last one completed)
  // once the next is complete, from the end of the core's vertical
  // blanking (a millisecond or more later) on, no faster than the scan
  // reads it.
  val latch = Mux(io.columnMajor, newColumn && io.x === x0 - LatchColumns.U, io.frameStart)
  val frame = RegInit(0.U.asTypeOf(new Params))
  val frameCount = RegInit(0.U(2.W))
  when (latch) {
    frame := params
    frameCount := frameCount + 1.U
  }

  // (With `bob`, a strip holds the field's rows only.)
  val tall = frame.rows > 256.U && !frame.bob
  val bytesPerPixel = Mux(frame.depth24, 3.U(2.W), 2.U(2.W))
  val pixelsPerStrip = Mux(tall, Mux(frame.depth24, 5.U(5.W), 8.U(5.W)), Mux(frame.depth24, 10.U(5.W), 16.U(5.W)))
  val stripBytes = Mux(tall, Mux(frame.depth24, 15.U(6.W), 16.U(6.W)), Mux(frame.depth24, 30.U(6.W), 32.U(6.W)))
  val pitch = Mux(tall, 10.U(5.W), 18.U(5.W))
  val startByte = Cat(frame.offsetX, 0.U(1.W))
  /** Row-major: words per line (covering its bytes from the start of the word they start in). */
  val lineWords = ((startByte(1, 0) +& frame.areaWidth * bytesPerPixel) +& 3.U) >> 2

  // Columns: the source column advances by width / (the picture's width)
  // per screen column. The register holds the last cycle's state, `column`
  // is this one's.
  class ColumnState extends Bundle {
    val inPicture = Bool()
    val acc = UInt(10.W)
    /** For the CRT effect's blur: the screen column starts a source column (every column outside the picture does). */
    val start = Bool()
    /** Column-major: the strip, the pixel's column in it, and the byte offset (in its VRAM line) of its first pixel. */
    val strip = UInt(10.W)
    val stripColumn = UInt(5.W)
    val stripStart = UInt(16.W)
    /** Byte offset of the pixel from the start of its strip's fetch (column-major) or its line's (a word boundary). */
    val pixelByte = UInt(11.W)
  }
  val lastColumn = RegInit({
    val c = WireInit(0.U.asTypeOf(new ColumnState))
    c.start := true.B
    c
  })
  val column = WireInit(lastColumn)
  lastColumn := column
  when (newColumn) {
    when (io.x === x0) {
      column.inPicture := true.B
      column.acc := 0.U
      column.start := true.B
      column.strip := 0.U
      column.stripColumn := 0.U
      column.stripStart := startByte
      column.pixelByte := startByte(1, 0)
    } .elsewhen (io.x > x0 && io.x < x0 + pictureWidth) {
      val acc = lastColumn.acc +& frame.areaWidth
      column.start := acc >= pictureWidth
      when (acc >= pictureWidth) {
        column.acc := acc - pictureWidth
        when (io.columnMajor && lastColumn.stripColumn === pixelsPerStrip - 1.U) {
          val start = lastColumn.stripStart + stripBytes
          column.stripColumn := 0.U
          column.strip := lastColumn.strip + 1.U
          column.stripStart := start
          column.pixelByte := start(1, 0)
        } .otherwise {
          column.stripColumn := lastColumn.stripColumn + 1.U
          column.pixelByte := lastColumn.pixelByte + bytesPerPixel
        }
      } .otherwise {
        column.acc := acc
      }
    } .otherwise {
      column.inPicture := false.B
      column.start := true.B
    }
  }

  // Rows: the source row advances by height / 480 per screen row (up to
  // two: 576 PAL lines). As for columns, `rowAcc` and `sourceRow` hold the
  // last cycle's, `...Now` this one's.
  val rowAcc = Reg(UInt(10.W))
  val sourceRow = Reg(UInt(10.W))
  val firstRow = io.y === 0.U
  val rowAccNow = WireInit(rowAcc)
  val sourceRowNow = WireInit(sourceRow)
  val rowAccNext = rowAcc +& frame.areaHeight
  when (firstRow) {
    rowAccNow := 0.U
    sourceRowNow := 0.U
  } .elsewhen (newRow) {
    when (rowAccNext >= (2 * PictureHeight).U) {
      rowAccNow := rowAccNext - (2 * PictureHeight).U
      sourceRowNow := sourceRow + 2.U
    } .elsewhen (rowAccNext >= PictureHeight.U) {
      rowAccNow := rowAccNext - PictureHeight.U
      sourceRowNow := sourceRow + 1.U
    } .otherwise {
      rowAccNow := rowAccNext
    }
  }
  rowAcc := rowAccNow
  sourceRow := sourceRowNow
  val pictureRow = sourceRowNow - frame.topRows
  val inRows = sourceRowNow >= frame.topRows && pictureRow < frame.rows
  val bufferRow = Mux(frame.bob, pictureRow >> 1, pictureRow)
  // For the CRT effect: the center of the screen row in its source line
  // (in 960ths of a line; 30ths, PsxCrt.PhasesPerLine, below), and whether
  // the row above showed another line.
  val rowCenter = (rowAccNow << 1) +& frame.areaHeight
  val lineStart = firstRow || sourceRowNow =/= RegNext(sourceRowNow)
  val rowBaseNow = Mux(tall, bufferRow * 10.U, bufferRow * 18.U)(12, 0)

  // Strips to fetch: column-major, up to the one after the strip on
  // screen; row-major, up to three lines after the line on screen.
  val wanted = RegInit(1.U(10.W))
  when (latch) {
    wanted := Mux(io.columnMajor, 1.U, (LineSlots - 1).U)
  } .elsewhen (io.columnMajor && newColumn && column.inPicture) {
    wanted := column.strip + 1.U
  } .elsewhen (!io.columnMajor && newRow && inRows) {
    wanted := bufferRow + (LineSlots - 1).U
  }

  // Stage 1: the buffer address (halfwords) of the pixel's first halfword.
  val visible = column.inPicture && inRows && io.y < PictureHeight.U && !frame.disabled
  val address = Mux(io.columnMajor,
    Mux(column.strip(0), StripHalfwords.U(14.W), 0.U(14.W)) + rowBaseNow,
    bufferRow(1, 0) * LineHalfwords.U) + (column.pixelByte >> 1)
  val address1 = RegNext(address)
  val visible1 = RegNext(visible, false.B)
  val depth24_1 = RegNext(frame.depth24)
  val byteOdd1 = RegNext(column.pixelByte(0))
  // Even bank: the even halfword at or after the address; odd bank: at or before.
  banks(0).readPorts(0).enable := true.B
  banks(0).readPorts(0).address := (address1 + 1.U) >> 1
  banks(1).readPorts(0).enable := true.B
  banks(1).readPorts(0).address := address1 >> 1
  // Stage 2: read; stage 3: the RAMs' output register.
  val even = RegNext(banks(0).readPorts(0).data)
  val odd = RegNext(banks(1).readPorts(0).data)
  val addressEven3 = ShiftRegister(!address1(0), 2)
  val visible3 = ShiftRegister(visible1, 2, false.B, true.B)
  val depth24_3 = ShiftRegister(depth24_1, 2)
  val byteOdd3 = ShiftRegister(byteOdd1, 2)
  val lo = Mux(addressEven3, even, odd)
  val hi = Mux(addressEven3, odd, even)
  def expand(c: UInt): UInt = Cat(c, c(4, 2))
  val color = Wire(ColorRGB(8, 8, 8))
  when (!depth24_3) {
    color.r := expand(lo(4, 0))
    color.g := expand(lo(9, 5))
    color.b := expand(lo(14, 10))
  } .elsewhen (!byteOdd3) {
    color.r := lo(7, 0)
    color.g := lo(15, 8)
    color.b := hi(7, 0)
  } .otherwise {
    color.r := lo(15, 8)
    color.g := hi(7, 0)
    color.b := hi(15, 8)
  }
  // Stages 4-6: the CRT effect.
  val crt = Module(new PsxCrt)
  crt.io.settings := crtSettings
  crt.io.scanlinesAllowed := !frame.bob && frame.areaHeight < PictureHeight.U
  crt.io.columnMajor := io.columnMajor
  crt.io.in.color := Mux(visible3, color, 0.U.asTypeOf(color))
  crt.io.in.column := ShiftRegister(io.x, 3)
  crt.io.in.phase := ShiftRegister((rowCenter >> 5)(5, 0), 3)
  crt.io.in.lineStart := ShiftRegister(lineStart, 3)
  crt.io.in.lineParity := ShiftRegister(sourceRowNow(0), 3)
  crt.io.in.row := ShiftRegister(io.y, 3)
  crt.io.in.columnStart := ShiftRegister(column.start, 3)
  crt.io.in.columnPhase := ShiftRegister(Mux(column.inPicture, column.acc, 0.U), 3)
  io.data := crt.io.out

  // Underruns: the fetcher's progress (its frame, strips fetched) against
  // the strip on screen, once per strip, where the scan reaches it:
  // column-major, at each screen column; row-major, at the start of each
  // row.
  val fetchProgress = Wire(UInt(12.W))
  val progress = cdc(io.clk3x, fetchProgress)
  val shownStrip = Mux(io.columnMajor, column.strip, bufferRow)
  val stripReached = Mux(io.columnMajor,
    newColumn && column.inPicture,
    newColumn && io.x === x0 && inRows && io.y < PictureHeight.U)
  val stripReady = progress(11, 10) === frameCount && progress(9, 0) > shownStrip
  val underruns = RegInit(0.U(32.W))
  val underrunCounted = RegInit(0.U(12.W))
  when (stripReached && !stripReady && underrunCounted =/= Cat(frameCount, shownStrip)) {
    underruns := underruns + 1.U
    underrunCounted := Cat(frameCount, shownStrip)
  }
  io.underruns := underruns

  //////////////////////////////////
  // Fetcher (clk3x)
  //////////////////////////////////
  val request = Wire(new FetchRequest)
  request.frame := frameCount
  request.wanted := wanted
  request.base := frame.base
  request.offsetY := frame.offsetY
  request.rows := frame.rows
  request.rowMajor := !io.columnMajor
  request.startByte := startByte
  request.stripBytes := stripBytes
  request.bob := frame.bob
  // (On HDMI, the field shown now, see above.)
  request.field := Mux(io.columnMajor, frame.field, params.field)
  request.words := Mux(io.columnMajor, Mux(tall, 5.U, 9.U), lineWords)
  request.pitch := pitch
  val displayClock = clock
  withClockAndReset(io.clk3x, io.reset3x) {
    val req = cdc(displayClock, request)
    val frameSeen = RegInit(0.U(2.W))
    val nextStrip = RegInit(0.U(10.W))
    fetchProgress := Cat(frameSeen, nextStrip)
    val active = RegInit(false.B)
    /** Byte offset in the VRAM line of the strip's first pixel (unwrapped). */
    val stripStart = Reg(UInt(16.W))
    val row = Reg(UInt(10.W))
    val rowBuffer = Reg(UInt(14.W))
    /** Byte offset in the VRAM line of the next word to fetch, and words left in the row. */
    val byte = Reg(UInt(11.W))
    val left = Reg(UInt(9.W))

    // Fetches in flight: where their words go in the buffer (halfwords).
    val inFlight = Module(new Queue(UInt(14.W), 4))
    inFlight.io.deq.ready := io.word.valid && io.word.bits.last

    val rowStartByte = Cat(stripStart(10, 2), 0.U(2.W))
    val line = (req.offsetY + row)(8, 0)
    val toRowEnd = (1024.U(11.W) - byte(9, 0)) >> 2
    // (16 words at most per fetch.)
    val run = left.min(toRowEnd).min(16.U)
    io.fetch.valid := active && inFlight.io.enq.ready
    io.fetch.bits.address := req.base + Cat(line, byte)
    io.fetch.bits.words := run - 1.U
    inFlight.io.enq.valid := io.fetch.fire
    inFlight.io.enq.bits := rowBuffer + ((req.words - left) << 1)

    // The strip's first row (with `bob`, of the field's rows: every other one).
    val startRow = Mux(req.rowMajor,
      Mux(req.bob, Cat(nextStrip, req.field), nextStrip),
      Mux(req.bob, req.field, 0.U))
    when (req.frame =/= frameSeen) {
      // A new frame: start over (fetches in flight still land, before the new ones).
      frameSeen := req.frame
      nextStrip := 0.U
      stripStart := req.startByte
      active := false.B
    } .elsewhen (!active) {
      when (nextStrip <= req.wanted && startRow < req.rows) {
        active := true.B
        row := startRow(9, 0)
        rowBuffer := Mux(req.rowMajor,
          nextStrip(1, 0) * LineHalfwords.U,
          Mux(nextStrip(0), StripHalfwords.U(14.W), 0.U(14.W)))
        byte := rowStartByte
        left := req.words
      }
    } .elsewhen (io.fetch.fire) {
      val nextByte = byte + (run << 2)
      byte := nextByte
      left := left - run
      when (left === run) {
        // Row done
        val nextRow = row + Mux(req.bob, 2.U, 1.U)
        row := nextRow
        rowBuffer := rowBuffer + req.pitch
        byte := rowStartByte
        left := req.words
        when (req.rowMajor || nextRow >= req.rows) {
          active := false.B
          nextStrip := nextStrip + 1.U
          when (!req.rowMajor) {
            stripStart := stripStart + req.stripBytes
          }
        }
      }
    }

    // Words into the buffer.
    val wordAddress = (inFlight.io.deq.bits >> 1) + io.word.bits.index
    for ((bank, i) <- banks.zipWithIndex) {
      bank.writePorts(0).enable := io.word.valid
      bank.writePorts(0).address := wordAddress
      bank.writePorts(0).data := io.word.bits.data(16 * i + 15, 16 * i)
    }
  }
}
