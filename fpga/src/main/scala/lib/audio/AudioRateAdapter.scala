package lib.audio

import chisel3._
import chisel3.util._

/**
 * Re-times an audio stream whose samples arrive with jitter (e.g. from a core
 * whose clock is gated while it waits for memory) onto a smooth time base.
 *
 * Samples are pushed into a FIFO as they arrive. A phase accumulator running
 * on the (ungated) module clock pops them at a smooth rate and linearly
 * interpolates between the last two, so the output is a continuous signal
 * that can be sampled at any rate downstream. The pop rate is servo'd to the
 * FIFO fill level, so it tracks the long-term average input rate while
 * smoothing out short-term jitter with a time constant of tens of
 * milliseconds. Latency is `fifoDepth / 2` samples.
 *
 * On underrun the output holds the last sample; on overrun the newest sample
 * is dropped.
 *
 * @param nominalPeriod nominal input sample period in module clocks
 * @param fifoDepth     FIFO depth in samples (power of two); the servo targets
 *                      half full
 * @param gainShift     servo gain: the phase increment is corrected by
 *                      `(fill - target) << gainShift` per clock. Larger is a
 *                      faster, less smooth loop.
 */
class AudioRateAdapter(
  nominalPeriod: Double,
  fifoDepth: Int = 256,
  gainShift: Int = 12,
  width: Int = 16,
) extends Module {
  val io = IO(new Bundle {
    val inValid = Input(Bool())
    val inLeft = Input(SInt(width.W))
    val inRight = Input(SInt(width.W))
    val outLeft = Output(SInt(width.W))
    val outRight = Output(SInt(width.W))
    /** Statistics */
    val fill = Output(UInt(log2Ceil(fifoDepth + 1).W))
    val underrun = Output(Bool())
    val overrun = Output(Bool())
  })
  require(isPow2(fifoDepth) && fifoDepth >= 4)
  require(nominalPeriod > 2.0)

  private val phaseBits = 32
  private val nominalIncrement = math.round(math.pow(2, phaseBits) / nominalPeriod)
  private val target = fifoDepth / 2

  class Sample extends Bundle {
    val left = SInt(width.W)
    val right = SInt(width.W)
  }
  val fifo = Module(new Queue(new Sample, fifoDepth, pipe = false, flow = false))
  fifo.io.enq.valid := io.inValid
  fifo.io.enq.bits.left := io.inLeft
  fifo.io.enq.bits.right := io.inRight
  io.overrun := io.inValid && !fifo.io.enq.ready
  io.fill := fifo.io.count

  // Servo: phase increment corrected by the fill error.
  val fillError = (fifo.io.count.zext - target.S).asSInt
  val increment = (nominalIncrement.S((phaseBits + 2).W) + (fillError << gainShift)).asUInt(phaseBits - 1, 0)

  val phase = RegInit(0.U(phaseBits.W))
  val prev = RegInit(0.U.asTypeOf(new Sample))
  val cur = RegInit(0.U.asTypeOf(new Sample))
  // Don't start consuming until the FIFO has reached the target level, so
  // the loop starts near its operating point.
  val started = RegInit(false.B)
  when (fifo.io.count >= target.U) { started := true.B }

  val next = phase +& increment
  val wrap = started && next(phaseBits)
  fifo.io.deq.ready := wrap
  io.underrun := wrap && !fifo.io.deq.valid
  when (started) {
    phase := next(phaseBits - 1, 0)
  }
  when (wrap) {
    prev := cur
    when (fifo.io.deq.valid) {
      cur := fifo.io.deq.bits
    }
  }

  private def interpolate(a: SInt, b: SInt): SInt = {
    val frac = phase(phaseBits - 1, phaseBits - 16) // 0.16 fixed point
    val diff = b -& a
    val scaled = (diff * Cat(0.U(1.W), frac).asSInt) >> 16
    (a +& scaled.asSInt)(width - 1, 0).asSInt
  }
  io.outLeft := interpolate(prev.left, cur.left)
  io.outRight := interpolate(prev.right, cur.right)
}
