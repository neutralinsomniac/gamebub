package net.gamebub.framework.interface

import chisel3._

object InputV1 {
  /**
   * A gamepad's buttons. The low 12 bits (`a` to `select`) are laid out as
   * [[InputV0.Buttons]]; `l1` / `r1` are its `l` / `r`.
   */
  class GamepadButtons extends Bundle {
    val l2 = Bool()
    val r2 = Bool()
    val l3 = Bool()
    val r3 = Bool()
    val a = Bool()
    val b = Bool()
    val x = Bool()
    val y = Bool()
    val up = Bool()
    val down = Bool()
    val left = Bool()
    val right = Bool()
    val l1 = Bool()
    val r1 = Bool()
    val start = Bool()
    val select = Bool()
  }

  class Gamepad extends Bundle {
    val connected = Bool()
    val buttons = new GamepadButtons
    /** The sticks: -128 (left, up) to 127 (right, down), 0 centered. */
    val leftX = SInt(8.W)
    val leftY = SInt(8.W)
    val rightX = SInt(8.W)
    val rightY = SInt(8.W)
  }

  /** The most gamepads the framework passes on. */
  val MaxGamepads = 4
}

/**
 * Input with the dock's gamepads kept apart: `buttons` are the handheld's
 * own (with [[InputV0]] the firmware merges the gamepads into them, their
 * left stick as the D-pad), `gamepads` the dock's, in the order they were
 * connected (one not connected reads as nothing pressed, sticks centered).
 * A gamepad's System button still opens the menu.
 */
class InputV1(
  /** The gamepads the core takes, up to [[InputV1.MaxGamepads]]. */
  val gamepadCount: Int,
) extends Bundle {
  require(gamepadCount <= InputV1.MaxGamepads)

  val buttons = Input(new InputV0.Buttons())
  val gamepads = Input(Vec(gamepadCount, new InputV1.Gamepad))
}
