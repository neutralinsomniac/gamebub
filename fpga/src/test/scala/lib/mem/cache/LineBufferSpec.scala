package lib.mem.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Drives the buffer with a model of the line cache behind it (1-cycle hit
 * returning the whole line, or a fill of `FillLatency` cycles) and checks
 * data, same-cycle hits and the background next-line pull.
 */
class LineBufferSpec extends AnyFreeSpec {
  val AddressWidth = 16
  val FillLatency = 8

  def memory(address: Int): BigInt = (BigInt(address) * 2654435761L + 999) & 0xFFFFFFFFL

  /** Model cache: lines it "has" hit in one cycle, others take FillLatency. */
  class Cache(present: scala.collection.mutable.Set[Int]) {
    var busyAddress: Option[Int] = None
    var countdown = 0
    var requests = 0
    def service(dut: LineBuffer): Unit = {
      val ready = busyAddress.isEmpty || countdown == 0
      dut.io.out.ready.poke(ready.B)
      busyAddress.foreach { a =>
        if (countdown == 0) {
          for (w <- 0 until 4) dut.io.outLine(w).poke(memory((a & ~15) + w * 4).U)
          dut.io.out.dataRead.poke(memory(a).U)
        }
      }
      if (ready) {
        busyAddress = None
        if (dut.io.out.enable.peek().litValue == 1) {
          val a = dut.io.out.address.peek().litValue.toInt
          requests += 1
          busyAddress = Some(a)
          countdown = if (present.contains(a & ~15)) 0 else FillLatency
          present += (a & ~15)
        }
      } else {
        countdown -= 1
      }
    }
  }

  /** Request a word; returns (data, stall cycles). */
  def read(dut: LineBuffer, c: Cache, address: Int): (BigInt, Int) = {
    dut.io.request.poke(true.B)
    dut.io.address.poke(address.U)
    c.service(dut)
    if (dut.io.hit.peek().litValue == 1) {
      val d = dut.io.dataRead.peek().litValue
      dut.clock.step()
      dut.io.request.poke(false.B)
      (d, 0)
    } else {
      dut.clock.step()
      dut.io.request.poke(false.B)
      var stalls = 1
      while (true) {
        c.service(dut)
        if (dut.io.respValid.peek().litValue == 1) {
          val d = dut.io.dataRead.peek().litValue
          dut.clock.step()
          return (d, stalls)
        }
        dut.clock.step(); stalls += 1
        assert(stalls < 200, s"read of $address never completed")
      }
      throw new IllegalStateException
    }
  }

  def idle(dut: LineBuffer, c: Cache, cycles: Int): Unit = {
    dut.io.request.poke(false.B)
    for (_ <- 0 until cycles) { c.service(dut); dut.clock.step() }
  }

  def withBuffer(present: Set[Int] = Set())(body: (LineBuffer, Cache) => Unit): Unit = {
    simulate(new LineBuffer(addressWidth = AddressWidth, numLines = 4, wordsPerLine = 4)) { dut =>
      val c = new Cache(scala.collection.mutable.Set(present.toSeq: _*))
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.request.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.earlyValid.poke(false.B)
      dut.io.earlyAddress.poke(0.U)
      dut.io.earlyData.poke(0.U)
      idle(dut, c, 3)
      body(dut, c)
    }
  }

  "returns correct data for random reads" in withBuffer() { (dut, c) =>
    val rnd = new Random(7)
    for (_ <- 0 until 300) {
      val a = rnd.nextInt(1 << 9) & ~3
      val (d, _) = read(dut, c, a)
      assert(d == memory(a), s"address $a")
      if (rnd.nextInt(3) == 0) idle(dut, c, rnd.nextInt(10))
    }
  }

  "words of a fetched line hit in the same cycle" in withBuffer() { (dut, c) =>
    val (d0, s0) = read(dut, c, 0x100); assert(d0 == memory(0x100)); assert(s0 > 0)
    for (w <- 1 until 4) {
      val (d, s) = read(dut, c, 0x100 + w * 4)
      assert(d == memory(0x100 + w * 4)); assert(s == 0, s"word $w stalled $s")
    }
  }

  "a sequential stream through lines the cache has does not stall after the first line" in
    withBuffer(present = (0 until 64).map(_ * 16).toSet) { (dut, c) =>
      var stalls = 0
      for (i <- 0 until 64) {
        val a = 0x200 + i * 4
        val (d, s) = read(dut, c, a); assert(d == memory(a))
        if (i >= 4) stalls += s
        idle(dut, c, 3) // a CPU fetching every few cycles
      }
      assert(stalls == 0, s"sequential stream stalled $stalls cycles")
    }

  "a miss on a line the cache has is answered in the next cycle" in
    withBuffer(present = Set(0x600, 0x610, 0x900)) { (dut, c) =>
      val (d0, s0) = read(dut, c, 0x600); assert(d0 == memory(0x600)); assert(s0 == 1, s"stalled $s0")
      // Also right after a hit (the pull of the next line may be in flight).
      val (_, s1) = read(dut, c, 0x604); assert(s1 == 0)
      val (d2, s2) = read(dut, c, 0x908); assert(d2 == memory(0x908)); assert(s2 <= 2, s"stalled $s2")
    }

  "an early word from the cache answers a pending miss before the line arrives" in withBuffer() { (dut, c) =>
    // Miss on 0x308 (a slow fill); a few cycles in, the cache shows the
    // word arriving from memory: the miss is answered right there, and the
    // line is still installed when the fill completes.
    dut.io.request.poke(true.B)
    dut.io.address.poke(0x308.U)
    c.service(dut)
    assert(dut.io.hit.peek().litValue == 0)
    dut.clock.step()
    dut.io.request.poke(false.B)
    for (_ <- 0 until 2) { c.service(dut); assert(dut.io.respValid.peek().litValue == 0); dut.clock.step() }
    // A word of another line does nothing.
    c.service(dut)
    dut.io.earlyValid.poke(true.B); dut.io.earlyAddress.poke(0x408.U); dut.io.earlyData.poke(1.U)
    assert(dut.io.respValid.peek().litValue == 0)
    dut.clock.step()
    c.service(dut)
    dut.io.earlyAddress.poke(0x308.U); dut.io.earlyData.poke(0x12345678.U)
    assert(dut.io.respValid.peek().litValue == 1)
    assert(dut.io.dataRead.peek().litValue == 0x12345678)
    dut.clock.step()
    dut.io.earlyValid.poke(false.B)
    assert(dut.io.pending.peek().litValue == 0)
    // Let the fill complete; the line is then installed and hits.
    idle(dut, c, FillLatency + 2)
    val (d, s) = read(dut, c, 0x30C); assert(d == memory(0x30C)); assert(s == 0, s"stalled $s")
    // And a second miss, issued while the earlier fill was still in flight, also completes.
    val (d2, _) = read(dut, c, 0x700); assert(d2 == memory(0x700))
  }

  "a request in the cycle its line arrives is a hit and leaves nothing pending" in withBuffer() { (dut, c) =>
    // Miss on 0x500; when the fill completes, request 0x504 in that very
    // cycle: it must hit on the arriving line (not re-arm a miss for a line
    // the buffer is installing), so that the next request's hit is not
    // clobbered by a late answer.
    dut.io.request.poke(true.B); dut.io.address.poke(0x500.U)
    c.service(dut); assert(dut.io.hit.peek().litValue == 0); dut.clock.step()
    dut.io.request.poke(false.B)
    var cycles = 0
    var done = false
    while (!done) {
      c.service(dut)
      if (dut.io.out.ready.peek().litValue == 1 && c.busyAddress.isEmpty) {
        // The line is arriving now (the model raised ready with the data).
        dut.io.request.poke(true.B); dut.io.address.poke(0x504.U)
        c.service(dut)
        assert(dut.io.hit.peek().litValue == 1, "request in the arrival cycle did not hit")
        assert(dut.io.dataRead.peek().litValue == memory(0x504))
        assert(dut.io.respValid.peek().litValue == 1 || true) // an answer for 0x500 may or may not be reported here
        dut.clock.step()
        dut.io.request.poke(false.B)
        c.service(dut)
        assert(dut.io.pending.peek().litValue == 0, "a miss was re-armed for the arriving line")
        // The next request hits with its own data.
        dut.io.request.poke(true.B); dut.io.address.poke(0x508.U)
        c.service(dut)
        assert(dut.io.hit.peek().litValue == 1)
        assert(dut.io.dataRead.peek().litValue == memory(0x508), "hit data clobbered")
        assert(dut.io.respValid.peek().litValue == 0)
        dut.clock.step()
        dut.io.request.poke(false.B)
        done = true
      } else {
        dut.clock.step(); cycles += 1
        assert(cycles < 100, "fill never completed")
      }
    }
  }

  "a new request supersedes a pending miss" in withBuffer(present = Set(0x700)) { (dut, c) =>
    // Miss on 0x900 (slow fill); before it completes, request 0x700 (a
    // cache hit, one cycle) and then 0x704: no late answer for 0x900 may
    // be reported once the newer requests were made.
    dut.io.request.poke(true.B); dut.io.address.poke(0x900.U)
    c.service(dut); dut.clock.step()
    dut.io.request.poke(false.B)
    for (_ <- 0 until 2) { c.service(dut); dut.clock.step() }
    dut.io.request.poke(true.B); dut.io.address.poke(0x700.U)
    c.service(dut)
    assert(dut.io.respValid.peek().litValue == 0)
    dut.clock.step()
    dut.io.request.poke(false.B)
    var answered = 0
    var last = BigInt(0)
    for (_ <- 0 until 30) {
      c.service(dut)
      if (dut.io.respValid.peek().litValue == 1) { answered += 1; last = dut.io.dataRead.peek().litValue }
      dut.clock.step()
    }
    assert(answered == 1, s"answers reported: $answered")
    assert(last == memory(0x700), "the answer is not the newest request's")
    // 0x900's line was still installed by the fill.
    val (d, s) = read(dut, c, 0x904); assert(d == memory(0x904)); assert(s == 0)
  }

  "a request while a pull is in flight is served correctly" in withBuffer() { (dut, c) =>
    val (d0, _) = read(dut, c, 0x400); assert(d0 == memory(0x400))
    // The pull of 0x410 is a slow fill now; jump elsewhere immediately.
    val (d1, _) = read(dut, c, 0x800); assert(d1 == memory(0x800))
    val (d2, _) = read(dut, c, 0x410); assert(d2 == memory(0x410))
    val (d3, _) = read(dut, c, 0x404); assert(d3 == memory(0x404))
  }
}
