package snes

import chisel3._
import chisel3.util._
import net.gamebub.framework.interface.InputV0

/**
 * A standard SNES controller on the serial joypad port.
 *
 * Port of the joypad half of MiSTer's `ioport.sv`: the button state is
 * (inverted) latched while `latch` is high and shifted out one bit per
 * rising edge of `clk`, B first. Must be clocked by the core clock.
 */
class SnesJoypad extends Module {
  val io = IO(new Bundle {
    /** Button state, true = pressed */
    val buttons = Input(new InputV0.Buttons)
    /** JOY_STRB from the core */
    val latch = Input(Bool())
    /** JOY1_CLK from the core */
    val clk = Input(Bool())
    /** Serial data (D0) to the core */
    val data = Output(Bool())
  })

  // Serial order: B, Y, Select, Start, Up, Down, Left, Right, A, X, L, R, then 4 zero bits.
  val b = io.buttons
  val state = Cat(
    b.b, b.y, b.select, b.start, b.up, b.down, b.left, b.right,
    b.a, b.x, b.l, b.r, 0.U(4.W),
  )

  val shift = RegInit(0.U(16.W))
  val oldClk = RegNext(io.clk, false.B)
  when (io.latch) {
    shift := ~state
  } .elsewhen (io.clk && !oldClk) {
    shift := shift << 1
  }
  io.data := shift(15)
}
