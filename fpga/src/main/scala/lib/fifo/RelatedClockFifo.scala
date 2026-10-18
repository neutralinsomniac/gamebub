package lib.fifo

import chisel3._
import chisel3.util._

/**
 * A first-word-fall-through FIFO between two clocks derived from the same
 * source (e.g. two outputs of one MMCM), in distributed RAM.
 *
 * The pointers cross in Gray code through two registers on the other side,
 * as in an asynchronous FIFO, but no constraints are needed: Vivado times
 * the crossing paths like any other between related clocks (at the
 * closest pair of edges), and they are short (register to register, and
 * the RAM's write clock to the read side's registers). Not for unrelated
 * clocks: those paths would need maximum-delay constraints.
 *
 * Unlike the XPM FIFOs, it simulates with Verilator.
 */
class RelatedClockFifo[T <: Data](gen: T, depth: Int) extends RawModule {
  require(isPow2(depth) && depth >= 4)
  val io = IO(new Bundle {
    val writeClock = Input(Clock())
    val writeReset = Input(Bool())
    val enq = Flipped(Decoupled(gen))

    val readClock = Input(Clock())
    val readReset = Input(Bool())
    val deq = Decoupled(gen)
  })

  val bits = log2Ceil(depth)
  def toGray(x: UInt): UInt = x ^ (x >> 1)

  val mem = Mem(depth, gen)

  val writeGray = Wire(UInt((bits + 1).W))
  val readGray = Wire(UInt((bits + 1).W))

  withClockAndReset(io.writeClock, io.writeReset) {
    val pointer = RegInit(0.U((bits + 1).W))
    val gray = RegInit(0.U((bits + 1).W))
    val readGraySync = RegNext(RegNext(readGray, 0.U), 0.U)
    // Full: the read pointer is one lap behind (Gray code: top two bits inverted).
    val full = gray === Cat(~readGraySync(bits, bits - 1), readGraySync(bits - 2, 0))
    io.enq.ready := !full
    when (io.enq.fire) {
      mem(pointer(bits - 1, 0)) := io.enq.bits
      pointer := pointer + 1.U
      gray := toGray(pointer + 1.U)
    }
    writeGray := gray
  }

  withClockAndReset(io.readClock, io.readReset) {
    val pointer = RegInit(0.U((bits + 1).W))
    val gray = RegInit(0.U((bits + 1).W))
    val writeGraySync = RegNext(RegNext(writeGray, 0.U), 0.U)
    io.deq.valid := gray =/= writeGraySync
    io.deq.bits := mem(pointer(bits - 1, 0))
    when (io.deq.fire) {
      pointer := pointer + 1.U
      gray := toGray(pointer + 1.U)
    }
    readGray := gray
  }
}
