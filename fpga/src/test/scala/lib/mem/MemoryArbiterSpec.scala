package lib.mem

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

/**
 * Drives the arbiter with a model of [[lib.mem.sram.AsyncSramController]]:
 * accept when idle, done two cycles later, and accept a *different* request
 * in the done cycle right away.
 */
class MemoryArbiterSpec extends AnyFreeSpec {
  class Target {
    var state = 0 // 0 idle, 1 access, 2 done
    var last: Option[(Int, Boolean, Int)] = None
    var accesses = List[(Int, Boolean, Int)]()
    /** Phase 1 (start of cycle): drive done / read data. */
    def drive(dut: MemoryArbiter): Unit = {
      dut.io.target.done.poke((state == 2).B)
      dut.io.target.dataRead.poke((last.map(_._1).getOrElse(0) * 3 + 1).U)
    }
    /** Phase 2 (end of cycle, after the initiators settled): sample the request and advance. */
    def update(dut: MemoryArbiter): Unit = {
      val req = (dut.io.target.address.peek().litValue.toInt, dut.io.target.write.peek().litValue == 1,
        dut.io.target.dataWrite.peek().litValue.toInt)
      val enable = dut.io.target.enable.peek().litValue == 1
      state match {
        case 0 =>
          if (enable) { accesses ::= req; last = Some(req); state = 1 }
        case 1 => state = 2
        case 2 =>
          if (enable && last.exists(l => l != req)) { accesses ::= req; last = Some(req); state = 1 }
          else state = 0
      }
    }
    def service(dut: MemoryArbiter): Unit = { drive(dut); update(dut) }
  }

  def withArbiter(fair: Boolean = false)(body: (MemoryArbiter, Target) => Unit): Unit =
    simulate(new MemoryArbiter(addressWidth = 8, dataWidth = 16, n = 2, fair = fair)) { dut =>
      val t = new Target
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      for (i <- 0 until 2) {
        dut.io.initiator(i).enable.poke(false.B)
        dut.io.initiator(i).write.poke(false.B)
        dut.io.initiator(i).address.poke(0.U)
        dut.io.initiator(i).dataWrite.poke(0.U)
        dut.io.initiator(i).writeStrobe.poke(3.U)
      }
      body(dut, t)
    }

  def request(dut: MemoryArbiter, i: Int, address: Int, write: Boolean, data: Int = 0): Unit = {
    val p = dut.io.initiator(i)
    p.enable.poke(true.B); p.address.poke(address.U); p.write.poke(write.B); p.dataWrite.poke(data.U)
  }

  "back-to-back different requests from one initiator run every two cycles" in withArbiter() { (dut, t) =>
    // Present request k; as soon as its done shows, present request k+1 in that same cycle.
    var k = 0
    var dones = List[Int]()
    request(dut, 0, 0x10, write = true, data = 0x100)
    var cycles = 0
    while (k < 6 && cycles < 60) {
      t.drive(dut)
      if (dut.io.initiator(0).done.peek().litValue == 1) {
        dones ::= cycles
        k += 1
        if (k < 6) request(dut, 0, 0x10 + k, write = true, data = 0x100 + k)
        else dut.io.initiator(0).enable.poke(false.B)
      }
      t.update(dut)
      dut.clock.step(); cycles += 1
    }
    assert(k == 6, s"only $k of 6 requests completed")
    assert(t.accesses.reverse.map(_._1) == (0 until 6).map(0x10 + _).toList, s"accesses ${t.accesses.reverse}")
    val gaps = dones.reverse.sliding(2).map(p => p(1) - p(0)).toList
    assert(gaps.forall(_ == 2), s"done spacing $gaps")
  }

  "an identical request held through done is not re-accepted, and another initiator then gets the target" in
    withArbiter() { (dut, t) =>
      request(dut, 0, 0x20, write = true, data = 0x55)
      request(dut, 1, 0x30, write = false)
      var done0 = 0; var done1 = 0
      for (c <- 0 until 12) {
        t.service(dut)
        if (dut.io.initiator(0).done.peek().litValue == 1) { done0 += 1; doneAt0 = c }
        if (dut.io.initiator(1).done.peek().litValue == 1) { done1 += 1 }
        dut.clock.step()
        // initiator 0 drops enable the cycle after its done (like SramBackup)
        if (done0 > 0) dut.io.initiator(0).enable.poke(false.B)
        if (done1 > 0) dut.io.initiator(1).enable.poke(false.B)
      }
      assert(done0 == 1, s"initiator 0 got $done0 dones")
      assert(done1 == 1, s"initiator 1 got $done1 dones")
      assert(t.accesses.reverse == List((0x20, true, 0x55), (0x30, false, 0)), s"accesses ${t.accesses.reverse}")
    }
  var doneAt0 = -1

  "fair: a waiting initiator takes the done cycle ahead of the owner's next request, and they alternate" in
    withArbiter(fair = true) { (dut, t) =>
      // Both initiators always have a fresh request ready: the target must
      // alternate between them every two cycles, with no idle cycle.
      var k0 = 0; var k1 = 0
      request(dut, 0, 0x40, write = false)
      request(dut, 1, 0x80, write = false)
      var dones = List[(Int, Int)]() // (cycle, initiator)
      for (c <- 0 until 40) {
        t.drive(dut)
        if (dut.io.initiator(0).done.peek().litValue == 1) { k0 += 1; dones ::= (c, 0); request(dut, 0, 0x40 + k0, write = false) }
        if (dut.io.initiator(1).done.peek().litValue == 1) { k1 += 1; dones ::= (c, 1); request(dut, 1, 0x80 + k1, write = false) }
        t.update(dut)
        dut.clock.step()
      }
      val seq = dones.reverse
      assert(seq.map(_._2).sliding(2).forall(p => p(0) != p(1)), s"not alternating: ${seq.map(_._2)}")
      val gaps = seq.map(_._1).sliding(2).map(p => p(1) - p(0)).toList
      assert(gaps.forall(_ == 2), s"done spacing $gaps (an idle cycle crept in)")
      assert(k0 >= 8 && k1 >= 8, s"$k0 / $k1 accesses")
    }

  "fair: another initiator's identical request in the done cycle waits a cycle and is not lost" in
    withArbiter(fair = true) { (dut, t) =>
      request(dut, 0, 0x33, write = false)
      var done0 = false; var done1At = -1
      for (c <- 0 until 12) {
        t.drive(dut)
        if (dut.io.initiator(0).done.peek().litValue == 1) {
          done0 = true
          dut.io.initiator(0).enable.poke(false.B)
          request(dut, 1, 0x33, write = false) // the same access from the other port, in the done cycle
        }
        if (dut.io.initiator(1).done.peek().litValue == 1) { done1At = c; dut.io.initiator(1).enable.poke(false.B) }
        t.update(dut)
        dut.clock.step()
      }
      assert(done0 && done1At > 0, s"initiator 1 never completed")
      assert(t.accesses.length == 2, s"accesses ${t.accesses.reverse}")
    }

  "a new request in the done cycle keeps the target ahead of a waiting initiator" in withArbiter() { (dut, t) =>
    request(dut, 0, 0x40, write = false)
    request(dut, 1, 0x50, write = false)
    var dones0 = 0; var done1At = -1; var lastDone0At = -1
    for (c <- 0 until 20) {
      t.drive(dut)
      if (dut.io.initiator(0).done.peek().litValue == 1) {
        dones0 += 1; lastDone0At = c
        if (dones0 < 3) request(dut, 0, 0x40 + dones0, write = false) else dut.io.initiator(0).enable.poke(false.B)
      }
      if (dut.io.initiator(1).done.peek().litValue == 1) { done1At = c; dut.io.initiator(1).enable.poke(false.B) }
      t.update(dut)
      dut.clock.step()
    }
    assert(dones0 == 3)
    assert(t.accesses.reverse.map(_._1) == List(0x40, 0x41, 0x42, 0x50), s"accesses ${t.accesses.reverse}")
    assert(done1At > lastDone0At)
  }
}
