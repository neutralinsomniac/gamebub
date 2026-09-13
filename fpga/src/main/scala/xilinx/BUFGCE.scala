package xilinx

import chisel3._

/**
 * Global clock buffer with clock enable.
 *
 * When `CE` is low, the output clock is held low (glitch-free). `CE` is timed
 * against the rising edge of `I`: the value present before a rising edge
 * decides whether that edge is passed through.
 */
class BUFGCE extends ExtModule(Map(
  "CE_TYPE" -> "SYNC",
  "IS_CE_INVERTED" -> 0,
  "IS_I_INVERTED" -> 0,
)) {
  val io = FlatIO(new Bundle {
    val I = Input(Clock())
    val CE = Input(Bool())
    val O = Output(Clock())
  })
}

object BUFGCE {
  /** Gate `clock` with `enable`, returning the gated clock. */
  def apply(clock: Clock, enable: Bool): Clock = {
    val buf = Module(new BUFGCE)
    buf.io.I := clock
    buf.io.CE := enable
    buf.io.O
  }
}
