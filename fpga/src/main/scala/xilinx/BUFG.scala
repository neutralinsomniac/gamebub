package xilinx

import chisel3._

/** Global clock buffer. */
class BUFG extends ExtModule {
  val io = FlatIO(new Bundle {
    val I = Input(Clock())
    val O = Output(Clock())
  })
}

object BUFG {
  def apply(clock: Clock): Clock = {
    val buf = Module(new BUFG)
    buf.io.I := clock
    buf.io.O
  }
}
