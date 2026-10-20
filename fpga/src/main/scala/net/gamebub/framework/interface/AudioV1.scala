package net.gamebub.framework.interface

import chisel3._
import chisel3.util._

/**
 * Audio from a core that runs the DAC at its own sample rate.
 *
 * With [[AudioV0]] the DAC runs at 48 kHz and takes whatever sample the core
 * shows at the start of each frame, without filtering: at any other core
 * rate, samples are repeated or skipped in a regular pattern, which adds
 * spurious tones (at 44.1 kHz, every tone gets one 3.9 kHz above it and one
 * mirrored below, 26 dB down for a 2 kHz tone, 13 dB for 8 kHz). Here the
 * core supplies the DAC's clock instead, and hands over exactly one sample
 * per frame.
 *
 * `clock` runs at 512 times the sample rate: twice the DAC's master clock,
 * which the DAC derives its sample rate from (with the firmware's DAC
 * settings, rates from about 22 to 48 kHz work). Every 512 cycles the
 * framework takes a sample
 * from `sample` (in `clock`'s domain; `ready` is high for that one cycle);
 * when none is `valid` then, it repeats the last one. A core whose samples
 * come at exactly its rate (from a clock related to `clock`, e.g. another
 * output of the same MMCM) through a FIFO only runs dry when it stops or
 * falls behind; the FIFO then keeps the extra samples it has caught up with,
 * so it does not run dry again until it falls further behind.
 *
 * HDMI audio still runs at 48 kHz, as with [[AudioV0]].
 */
class AudioV1 extends Bundle {
  val clock = Output(Clock())
  val sample = Decoupled(new AudioV1.Sample)
}

object AudioV1 {
  class Sample extends Bundle {
    val left = SInt(16.W)
    val right = SInt(16.W)
  }
}
