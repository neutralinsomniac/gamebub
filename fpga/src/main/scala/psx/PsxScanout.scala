package psx

import chisel3._
import chisel3.util._
import lib.video.ColorRGB
import xilinx.XpmCdcHandshake

object PsxScanout {
  /** The picture: 4:3, the full height of the screen, centered. */
  val PictureWidth = 640
  val PictureHeight = 480
  /** Display clock cycles from a request to its color. */
  val Latency = 4
  /**
   * Screen columns before the picture at which a frame's display state is
   * taken and its first two strips are fetched (a column is about 21 us on
   * the LCD; two strips of 480 rows take about 150 us).
   */
  val LatchColumns = 16
  /** Halfwords of strip buffer per strip (two strips): 256 rows of 18, or 512 of 10. */
  val StripHalfwords = 5120

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
  }

  /** What the fetcher (clk3x) needs to know, from the display side. */
  class FetchRequest extends Bundle {
    /** Counts frames: a change starts the strips over. */
    val frame = UInt(2.W)
    /** Fetch strips up to this one (they go to slot `strip(0)`). */
    val wanted = UInt(8.W)
    val base = UInt(25.W)
    val offsetY = UInt(9.W)
    /** VRAM lines per strip. */
    val rows = UInt(10.W)
    /** Byte offset of the display area in its VRAM line. */
    val startByte = UInt(11.W)
    /** Bytes of the display area per strip (consecutive strips follow each other). */
    val stripBytes = UInt(6.W)
    /** 32-bit words fetched per strip row (covering the strip's bytes wherever they start). */
    val words = UInt(4.W)
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
 * LCD, centered), scaled from the display area's width and height by
 * nearest neighbor (a DDA in each direction). The LCD scans the screen one
 * column at a time, so a column of the picture is a column of VRAM, one
 * pixel from each of up to 480 lines: VRAM is read in strips, a few pixels
 * wide (16 in 15-bit color, 10 in 24-bit; half that for 480-line modes) and
 * the display area's height, into a buffer of two strips: while the scan
 * is in one, the next is fetched (in clk3x, through
 * [[PsxMemory]]'s display port). The first two are fetched in the screen's
 * left border, `LatchColumns` before the picture, where the display state
 * is taken for the whole frame. It scans a frame that does not change
 * while it does: a copy of the display, made line by line as a TV shows it
 * ([[PsxFrameGrab]]), the last one completed when the display's frame
 * started (with the core's vertical blanking, see `VideoPullV0`). Reading
 * VRAM itself column by column, the left strips (all their rows read at the
 * start of the frame) showed what the game was still drawing there.
 * (`frameStart` is not used.)
 *
 * Vertically, the picture is what a TV shows (see `Params.areaHeight`):
 * the lines of the game's vertical display range, black around them.
 *
 * Not yet: HDMI (row-major scanning; black for now), interlaced fields.
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

    /** The core's display state, in clk1x. */
    val clk1x = Input(Clock())
    val params = Input(new Params)

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
  // The display state is taken for the frame a few columns before the
  // picture (where its first strips are fetched), not at the start of the
  // frame: the display's frame starts as the core's vertical blanking does,
  // and games flip their buffers in the blanking, after its start (taken
  // there, the picture was the buffer being drawn).
  val x0 = (io.screenWidth - PictureWidth.U) >> 1
  val newLine = io.x =/= RegNext(io.x)
  val latch = newLine && io.x === x0 - LatchColumns.U
  val frame = RegInit(0.U.asTypeOf(new Params))
  val frameCount = RegInit(0.U(2.W))
  when (latch) {
    frame := params
    frameCount := frameCount + 1.U
  }

  val tall = frame.rows > 256.U
  val bytesPerPixel = Mux(frame.depth24, 3.U(2.W), 2.U(2.W))
  val pixelsPerStrip = Mux(tall, Mux(frame.depth24, 5.U(5.W), 8.U(5.W)), Mux(frame.depth24, 10.U(5.W), 16.U(5.W)))
  val stripBytes = Mux(tall, Mux(frame.depth24, 15.U(6.W), 16.U(6.W)), Mux(frame.depth24, 30.U(6.W), 32.U(6.W)))
  val pitch = Mux(tall, 10.U(5.W), 18.U(5.W))
  val startByte = Cat(frame.offsetX, 0.U(1.W))

  // Columns: the source column advances by width / 640 per screen column.
  val inPicture = RegInit(false.B)
  val columnAcc = Reg(UInt(10.W))
  val strip = RegInit(0.U(8.W))
  val stripColumn = Reg(UInt(5.W))
  /** Byte offset (in its VRAM line) of the current strip's first pixel. */
  val stripStart = Reg(UInt(16.W))
  /** Byte offset of the current pixel from the start of its strip's fetch (a word boundary). */
  val pixelByte = Reg(UInt(6.W))
  val wanted = RegInit(1.U(8.W))
  when (latch) {
    wanted := 1.U
  }
  when (newLine) {
    when (io.x === x0) {
      inPicture := true.B
      columnAcc := 0.U
      strip := 0.U
      stripColumn := 0.U
      stripStart := startByte
      pixelByte := startByte(1, 0)
      wanted := 1.U
    } .elsewhen (io.x > x0 && io.x < x0 + PictureWidth.U) {
      val acc = columnAcc +& frame.areaWidth
      when (acc >= PictureWidth.U) {
        columnAcc := acc - PictureWidth.U
        when (stripColumn === pixelsPerStrip - 1.U) {
          val start = stripStart + stripBytes
          stripColumn := 0.U
          strip := strip + 1.U
          stripStart := start
          pixelByte := start(1, 0)
          wanted := strip + 2.U
        } .otherwise {
          stripColumn := stripColumn + 1.U
          pixelByte := pixelByte + bytesPerPixel
        }
      } .otherwise {
        columnAcc := acc
      }
    } .otherwise {
      inPicture := false.B
    }
  }

  // Rows: the source row advances by height / 480 per screen row (up to
  // two: 576 PAL lines; the request's y counts up by one per cycle within a
  // scan line).
  val rowAcc = Reg(UInt(10.W))
  val sourceRow = Reg(UInt(10.W))
  val firstRow = io.y === 0.U
  val rowAccNow = Mux(firstRow, 0.U, rowAcc)
  val sourceRowNow = Mux(firstRow, 0.U, sourceRow)
  val rowAccNext = rowAccNow +& frame.areaHeight
  when (rowAccNext >= (2 * PictureHeight).U) {
    rowAcc := rowAccNext - (2 * PictureHeight).U
    sourceRow := sourceRowNow + 2.U
  } .elsewhen (rowAccNext >= PictureHeight.U) {
    rowAcc := rowAccNext - PictureHeight.U
    sourceRow := sourceRowNow + 1.U
  } .otherwise {
    rowAcc := rowAccNext
    sourceRow := sourceRowNow
  }
  val pictureRow = sourceRowNow - frame.topRows
  val inRows = sourceRowNow >= frame.topRows && pictureRow < frame.rows
  val rowBaseNow = Mux(tall, pictureRow * 10.U, pictureRow * 18.U)(12, 0)

  // Stage 1: the buffer address (halfwords) of the pixel's first halfword.
  val visible = io.columnMajor && inPicture && inRows && io.y < PictureHeight.U && !frame.disabled
  val address = Mux(strip(0), StripHalfwords.U(14.W), 0.U(14.W)) + rowBaseNow + (pixelByte >> 1)
  val address1 = RegNext(address)
  val visible1 = RegNext(visible, false.B)
  val depth24_1 = RegNext(frame.depth24)
  val byteOdd1 = RegNext(pixelByte(0))
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
  // Stage 4: out.
  io.data := RegNext(Mux(visible3, color, 0.U.asTypeOf(color)))

  // Underruns: the fetcher's progress (its frame, strips fetched) against
  // the strip on screen, once per strip.
  val fetchProgress = Wire(UInt(10.W))
  val progress = cdc(io.clk3x, fetchProgress)
  val stripReady = progress(9, 8) === frameCount && progress(7, 0) > strip
  val underruns = RegInit(0.U(32.W))
  val underrunCounted = RegInit(0.U(10.W))
  when (newLine && inPicture && !stripReady && underrunCounted =/= Cat(frameCount, strip)) {
    underruns := underruns + 1.U
    underrunCounted := Cat(frameCount, strip)
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
  request.startByte := startByte
  request.stripBytes := stripBytes
  request.words := Mux(tall, 5.U, 9.U)
  request.pitch := pitch
  val displayClock = clock
  withClockAndReset(io.clk3x, io.reset3x) {
    val req = cdc(displayClock, request)
    val frameSeen = RegInit(0.U(2.W))
    val nextStrip = RegInit(0.U(8.W))
    fetchProgress := Cat(frameSeen, nextStrip)
    val active = RegInit(false.B)
    /** Byte offset in the VRAM line of the strip's first pixel (unwrapped). */
    val stripStart = Reg(UInt(16.W))
    val row = Reg(UInt(10.W))
    val rowBuffer = Reg(UInt(14.W))
    /** Byte offset in the VRAM line of the next word to fetch, and words left in the row. */
    val byte = Reg(UInt(11.W))
    val left = Reg(UInt(4.W))

    // Fetches in flight: where their words go in the buffer (halfwords).
    val inFlight = Module(new Queue(UInt(14.W), 4))
    inFlight.io.deq.ready := io.word.valid && io.word.bits.last

    val rowStartByte = Cat(stripStart(10, 2), 0.U(2.W))
    val line = (req.offsetY + row)(8, 0)
    val toRowEnd = (1024.U(11.W) - byte(9, 0)) >> 2
    val run = left.min(toRowEnd)
    io.fetch.valid := active && inFlight.io.enq.ready
    io.fetch.bits.address := req.base + Cat(line, byte)
    io.fetch.bits.words := run - 1.U
    inFlight.io.enq.valid := io.fetch.fire
    inFlight.io.enq.bits := rowBuffer + ((req.words - left) << 1)

    when (req.frame =/= frameSeen) {
      // A new frame: start over (fetches in flight still land, before the new ones).
      frameSeen := req.frame
      nextStrip := 0.U
      stripStart := req.startByte
      active := false.B
    } .elsewhen (!active) {
      when (nextStrip <= req.wanted && req.rows =/= 0.U) {
        active := true.B
        row := 0.U
        rowBuffer := Mux(nextStrip(0), StripHalfwords.U(14.W), 0.U(14.W))
        byte := Cat(stripStart(10, 2), 0.U(2.W))
        left := req.words
      }
    } .elsewhen (io.fetch.fire) {
      val nextByte = byte + (run << 2)
      byte := nextByte
      left := left - run
      when (left === run) {
        // Row done
        row := row + 1.U
        rowBuffer := rowBuffer + req.pitch
        byte := rowStartByte
        left := req.words
        when (row === req.rows - 1.U) {
          active := false.B
          nextStrip := nextStrip + 1.U
          stripStart := stripStart + req.stripBytes
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
