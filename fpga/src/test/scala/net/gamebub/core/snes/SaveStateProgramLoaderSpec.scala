package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Runs the loader against a model of the pipelined SDRAM port with random
 * wait states and checks that exactly the program's words land at
 * 0xFF0000, in order, with the write data presented in the data phase.
 */
class SaveStateProgramLoaderSpec extends AnyFreeSpec {
  import SaveStateProgramLoader._

  "writes the program above the ROM" in {
    val random = new Random(2)
    val memory = scala.collection.mutable.Map[Long, Long]()
    var writes = 0
    simulate(new SaveStateProgramLoader) { dut =>
      dut.reset.poke(true.B)
      dut.clock.step()
      dut.reset.poke(false.B)
      dut.io.start.poke(false.B)
      dut.io.mem.ready.poke(true.B)
      dut.io.mem.dataRead.poke(0.U)
      dut.clock.step()
      dut.io.busy.expect(false.B)
      dut.io.mem.enable.expect(false.B)

      dut.io.start.poke(true.B)
      dut.clock.step()
      dut.io.start.poke(false.B)
      dut.io.busy.expect(true.B)

      // The request accepted last cycle, awaiting its data phase.
      var pending: Option[Long] = None
      var cycles = 0
      while (dut.io.busy.peek().litValue == 1 || pending.isDefined) {
        val ready = random.nextInt(3) != 0
        dut.io.mem.ready.poke(ready.B)
        if (ready) {
          // The data phase of the pending request completes with this cycle's write data.
          pending.foreach { address =>
            memory(address) = dut.io.mem.dataWrite.peek().litValue.toLong
            writes += 1
          }
          pending = if (dut.io.mem.enable.peek().litValue == 1) {
            dut.io.mem.isWrite.expect(true.B)
            dut.io.mem.writeStrobe.expect("b1111".U)
            Some(dut.io.mem.address.peek().litValue.toLong)
          } else None
        }
        dut.clock.step()
        cycles += 1
        assert(cycles < programWords.length * 8 + 16, "the load takes too long")
      }
      for (_ <- 0 until 4) {
        dut.io.busy.expect(false.B)
        dut.io.mem.enable.expect(false.B)
        dut.clock.step()
      }
    }
    assert(writes == programWords.length)
    val expected = programWords.zipWithIndex.map { case (w, i) => (ProgramAddress.toLong + 4 * i) -> w }.toMap
    assert(memory == expected)
    // Sanity: the program starts with the MiSTer stub's JML.
    assert((programWords.head & 0xFF) == 0x5C)
  }
}
