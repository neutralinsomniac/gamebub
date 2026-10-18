package xilinx

import chisel3._

/**
 * D flip-flop with clock enable and synchronous reset (the primitive), for
 * registers whose exact form matters, e.g. the pair in an I/O block, which
 * must share their control signals: synthesis is free to fold logic into an
 * inferred register's reset or enable.
 */
class FDRE(init: Int) extends ExtModule(Map("INIT" -> init)) {
  val io = FlatIO(new Bundle {
    val Q = Output(Bool())
    val C = Input(Clock())
    val CE = Input(Bool())
    val R = Input(Bool())
    val D = Input(Bool())
  })
}

object FDRE {
  /** A plain register of `d` on `clock`: no reset, always enabled, `init` at configuration. */
  def apply(clock: Clock, d: Bool, init: Boolean): FDRE = {
    val r = Module(new FDRE(if (init) 1 else 0))
    r.io.C := clock
    r.io.CE := true.B
    r.io.R := false.B
    r.io.D := d
    r
  }
}
