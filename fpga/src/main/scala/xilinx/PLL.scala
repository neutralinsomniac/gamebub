package xilinx

import chisel3._

object PLL {
  case class ClockOut(divide: Int, dutyCycle: Double = 0.5, phase: Double = 0.0)
}

/**
 * PLLE2_BASE wrapper: integer multiplier and dividers only (unlike [[MMCM]]),
 * VCO 800-1600 MHz on speed grade -1.
 */
class PLL(
    clockInHz: Int,
    divide: Int,
    multiply: Int,
    clockOutConfig: Seq[PLL.ClockOut],
) extends RawModule {
  require(clockOutConfig.length <= 6)
  val io = IO(new Bundle {
    val clockIn = Input(Clock())
    val powerDown = Input(Bool())
    val reset = Input(Bool())
    val clockOuts = Output(Vec(clockOutConfig.length, Clock()))
    val locked = Output(Bool())
  })

  private val params = Map(
    "CLKIN1_PERIOD" -> DoubleParam(1_000_000_000.toDouble / clockInHz),
    "DIVCLK_DIVIDE" -> IntParam(divide),
    "CLKFBOUT_MULT" -> IntParam(multiply),
  ) ++ clockOutConfig.zipWithIndex.flatMap { case (x, i) =>
    Seq(
        s"CLKOUT${i}_DIVIDE" -> IntParam(x.divide),
        s"CLKOUT${i}_PHASE" -> DoubleParam(x.phase),
        s"CLKOUT${i}_DUTY_CYCLE" -> DoubleParam(x.dutyCycle),
    )
  }
  private val pll = Module(new PLLE2_BASE(params))
  pll.io.CLKFBIN := pll.io.CLKFBOUT
  pll.io.CLKIN1 := io.clockIn
  pll.io.PWRDWN := io.powerDown
  pll.io.RST := io.reset
  io.locked := pll.io.LOCKED

  private val pllClocks = Seq(
    pll.io.CLKOUT0,
    pll.io.CLKOUT1,
    pll.io.CLKOUT2,
    pll.io.CLKOUT3,
    pll.io.CLKOUT4,
    pll.io.CLKOUT5,
  )
  io.clockOuts := VecInit(pllClocks.take(clockOutConfig.length))
}

private class PLLE2_BASE(
    params: Map[String, Param],
) extends ExtModule(params) {
  val io = FlatIO(new Bundle {
    val CLKFBIN = Input(Clock())
    val CLKIN1 = Input(Clock())
    val PWRDWN = Input(Bool())
    val RST = Input(Bool())

    val CLKFBOUT = Output(Clock())
    val CLKOUT0 = Output(Clock())
    val CLKOUT1 = Output(Clock())
    val CLKOUT2 = Output(Clock())
    val CLKOUT3 = Output(Clock())
    val CLKOUT4 = Output(Clock())
    val CLKOUT5 = Output(Clock())

    val LOCKED = Output(Bool())
  })
}
