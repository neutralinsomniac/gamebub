package lib.audio

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

class AudioRateAdapterSpec extends AnyFreeSpec {
  /**
   * Feed a ramp with bursty jitter (samples arrive every `period` clocks, but
   * every `burstEvery` samples one arrives `burstDelay` clocks late) and check
   * that the output advances smoothly (no burst-shaped steps) and that the
   * FIFO stays near its target without under/overruns.
   */
  "smooths bursty input jitter" in {
    val period = 20
    val depth = 64
    simulate(new AudioRateAdapter(nominalPeriod = period, fifoDepth = depth, gainShift = 19)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.inValid.poke(false.B)
      var value = 0
      var outputs = Vector.empty[Int]
      var underruns = 0
      var overruns = 0
      var maxFill = 0
      var minFill = depth
      def step(): Unit = {
        if (dut.io.underrun.peek().litValue == 1) underruns += 1
        if (dut.io.overrun.peek().litValue == 1) overruns += 1
        dut.clock.step()
      }
      def push(): Unit = {
        value += 5
        dut.io.inLeft.poke(value.S)
        dut.io.inRight.poke((-value).S)
        dut.io.inValid.poke(true.B)
        step()
        dut.io.inValid.poke(false.B)
      }
      var n = 0
      for (_ <- 0 until 4000) {
        // Every 16th sample arrives 6 clocks late (a burst of stalls), so
        // the average period is 20.375 clocks: the servo has to track that.
        val gap = if (n % 16 == 15) period + 6 else period
        for (_ <- 0 until gap - 1) {
          step()
          if (n > 200) {
            outputs :+= dut.io.outLeft.peek().litValue.toInt
            val fill = dut.io.fill.peek().litValue.toInt
            maxFill = maxFill max fill
            minFill = minFill min fill
          }
        }
        push()
        n += 1
      }
      assert(underruns == 0, s"underruns: $underruns")
      assert(overruns == 0, s"overruns: $overruns")
      assert(minFill > depth / 8 && maxFill < depth * 7 / 8, s"fill range $minFill..$maxFill")
      // Output must be monotonic (ramp input) and its per-clock step must be
      // small and steady: no bursts of 6-period-worth of catch-up.
      val steps = outputs.sliding(2).map(p => p(1) - p(0)).toVector
      assert(steps.forall(_ >= 0), "output not monotonic")
      val avg = 5.0 / period
      val maxStep = steps.max
      assert(maxStep <= 3, s"max per-clock step $maxStep (average ${avg}); jitter not smoothed")
    }
  }

  "holds on underrun" in {
    simulate(new AudioRateAdapter(nominalPeriod = 8, fifoDepth = 8, gainShift = 4)) { dut =>
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.inValid.poke(false.B)
      for (i <- 1 to 6) {
        dut.io.inLeft.poke((i * 100).S); dut.io.inRight.poke(0.S); dut.io.inValid.poke(true.B)
        dut.clock.step()
      }
      dut.io.inValid.poke(false.B)
      for (_ <- 0 until 200) dut.clock.step()
      val v = dut.io.outLeft.peek().litValue
      assert(v == 600, s"expected to hold last sample 600, got $v")
    }
  }
}
