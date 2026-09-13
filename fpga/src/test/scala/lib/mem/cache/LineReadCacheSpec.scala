package lib.mem.cache

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.util.Random

/**
 * Drives the cache with a model SDRAM target of fixed latency and checks
 * data correctness and the effect of prefetching on sequential access.
 */
class LineReadCacheSpec extends AnyFreeSpec {
  val AddressWidth = 16
  val Latency = 4 // target cycles per word

  /** Expected memory contents: a function of the address. */
  def memory(address: Int): BigInt = (BigInt(address) * 2654435761L + 12345) & 0xFFFFFFFFL

  class Harness(prefetch: Boolean) {
    var pendingAddress: Option[Int] = None
    var countdown = 0

    /** Model the target for one cycle: call before stepping the clock. */
    var prefetches = 0
    var misses = 0
    var fillCycles = 0
    /** Addresses issued to the target, in order. */
    val issued = scala.collection.mutable.ArrayBuffer[Int]()
    /** (address, data) shown on fillWord*, in order. */
    val early = scala.collection.mutable.ArrayBuffer[(Int, BigInt)]()
    def serviceTarget(dut: LineReadCache): Unit = {
      if (dut.io.prefetch.peek().litValue == 1) prefetches += 1
      if (dut.io.miss.peek().litValue == 1) misses += 1
      if (dut.io.filling.peek().litValue == 1) fillCycles += 1
      // ready when no request is in flight or its latency has elapsed
      val ready = pendingAddress.isEmpty || countdown == 0
      dut.io.out.ready.poke(ready.B)
      if (ready) {
        pendingAddress.foreach(a => dut.io.out.dataRead.poke(memory(a).U))
        if (dut.io.fillWordValid.peek().litValue == 1) {
          early += ((dut.io.fillWordAddress.peek().litValue.toInt, dut.io.fillWordData.peek().litValue))
        }
        val enable = dut.io.out.enable.peek().litValue == 1
        pendingAddress = if (enable) Some(dut.io.out.address.peek().litValue.toInt) else None
        pendingAddress.foreach(issued += _)
        countdown = Latency
      } else {
        countdown -= 1
      }
    }
  }

  /** Issue a read and return (data, cycles waited). */
  def read(dut: LineReadCache, h: Harness, address: Int): (BigInt, Int) = {
    dut.io.in.enable.poke(true.B)
    dut.io.in.address.poke(address.U)
    dut.io.in.isWrite.poke(false.B)
    var waited = 0
    // request phase: hold until accepted
    while (dut.io.in.ready.peek().litValue == 0) {
      h.serviceTarget(dut); dut.clock.step(); waited += 1
      assert(waited < 1000, s"request at $address never accepted")
    }
    h.serviceTarget(dut); dut.clock.step()
    dut.io.in.enable.poke(false.B)
    // data phase
    while (dut.io.in.ready.peek().litValue == 0) {
      h.serviceTarget(dut); dut.clock.step(); waited += 1
      assert(waited < 1000, s"read at $address never completed")
    }
    val data = dut.io.in.dataRead.peek().litValue
    (data, waited)
  }

  def idle(dut: LineReadCache, h: Harness, cycles: Int): Unit = {
    dut.io.in.enable.poke(false.B)
    for (_ <- 0 until cycles) { h.serviceTarget(dut); dut.clock.step() }
  }

  def withCache(prefetch: Boolean, ways: Int = 1)(body: (LineReadCache, Harness) => Unit): Unit = {
    simulate(new LineReadCache(addressWidth = AddressWidth, dataWidth = 32, numLines = 16, wordsPerLine = 4, prefetch = prefetch, ways = ways)) { dut =>
      val h = new Harness(prefetch)
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.in.enable.poke(false.B)
      dut.io.out.ready.poke(false.B)
      dut.io.eagerIssue.poke(true.B)
      idle(dut, h, 40) // init
      body(dut, h)
    }
  }

  "returns correct data for random reads, with and without prefetch, 1 and 2 ways" in {
    for (prefetch <- Seq(false, true); ways <- Seq(1, 2)) withCache(prefetch, ways) { (dut, h) =>
      val rnd = new Random(1)
      for (_ <- 0 until 400) {
        val address = rnd.nextInt(1 << 10) & ~3
        val (data, _) = read(dut, h, address)
        assert(data == memory(address), s"prefetch=$prefetch address $address: got $data expected ${memory(address)}")
        if (rnd.nextInt(4) == 0) idle(dut, h, rnd.nextInt(12))
      }
    }
  }

  "prefetch removes misses from a sequential stream" in {
    var waitsWithout = 0
    var waitsWith = 0
    for (prefetch <- Seq(false, true)) withCache(prefetch) { (dut, h) =>
      var total = 0
      // A CPU-like stream: one word every 8 cycles, sequential.
      for (i <- 0 until 64) {
        val address = 0x100 + i * 4
        val (data, waited) = read(dut, h, address)
        assert(data == memory(address))
        total += waited
        idle(dut, h, 7)
      }
      println(s"prefetch=$prefetch: waits $total, misses ${h.misses}, prefetches ${h.prefetches}, fill cycles ${h.fillCycles}")
      if (prefetch) waitsWith = total else waitsWithout = total
    }
    println(s"sequential stream wait cycles: without prefetch $waitsWithout, with prefetch $waitsWith")
    assert(waitsWithout > 0)
    // Only the very first line can miss; everything after it is prefetched.
    assert(waitsWith <= waitsWithout / 8, s"prefetch not effective: $waitsWith vs $waitsWithout")
  }

  "a demand miss during a prefetch fill still returns correct data" in {
    withCache(true) { (dut, h) =>
      // Touch line 0 (triggers a prefetch of line 1), then immediately jump
      // to line 5 while line 1 is filling, then to line 1 itself.
      val (d0, _) = read(dut, h, 0x000); assert(d0 == memory(0x000))
      val (d5, _) = read(dut, h, 0x050); assert(d5 == memory(0x050))
      val (d1, _) = read(dut, h, 0x014); assert(d1 == memory(0x014))
      val (d6, _) = read(dut, h, 0x064); assert(d6 == memory(0x064))
      // Aliases (same index, different tag) evict correctly.
      val alias = 0x000 + 16 * 16
      val (da, _) = read(dut, h, alias); assert(da == memory(alias))
      val (d0b, _) = read(dut, h, 0x000); assert(d0b == memory(0x000))
    }
  }

  "a miss fetches the requested word first and shows every word as it arrives" in {
    withCache(prefetch = false) { (dut, h) =>
      for (word <- Seq(2, 0, 3, 1)) {
        val line = 0x200 + word * 0x40 // a fresh line each time
        val address = line + word * 4
        h.issued.clear(); h.early.clear()
        val (data, waited) = read(dut, h, address)
        assert(data == memory(address))
        val expected = (0 until 4).map(i => line + ((word + i) % 4) * 4)
        assert(h.issued.toSeq == expected, s"word $word: issued ${h.issued} expected $expected")
        assert(h.early.toSeq == expected.map(a => (a, memory(a))), s"word $word: early words ${h.early}")
        // The first word was issued in the lookup cycle and completed with
        // the target's latency; the rest of the line took no extra wait.
        assert(waited <= Latency + 2 + 3 * (Latency + 1), s"word $word waited $waited")
      }
      // The lines are complete and correct.
      for (word <- 0 until 4; w <- 0 until 4) {
        val address = 0x200 + word * 0x40 + w * 4
        val (data, waited) = read(dut, h, address)
        assert(data == memory(address)); assert(waited <= 1, s"$address waited $waited")
      }
    }
  }

  "two aliasing streams only miss once each with 2 ways" in {
    val setBytes = 16 * 16 // lines x line bytes: addresses this far apart share a set
    var missesByWays = Map[Int, Int]()
    for (ways <- Seq(1, 2)) withCache(prefetch = false, ways) { (dut, h) =>
      // Alternate between two lines that map to the same set.
      for (i <- 0 until 20) {
        val address = (if (i % 2 == 0) 0x40 else 0x40 + setBytes) + (i % 4) * 4 // stay inside the line
        val (data, _) = read(dut, h, address)
        assert(data == memory(address))
      }
      missesByWays += ways -> h.misses
      // A third alias evicts the least recently used line (the first one).
      val third = 0x40 + 2 * setBytes
      val before = h.misses
      val (d, _) = read(dut, h, third); assert(d == memory(third))
      assert(h.misses == before + 1)
      if (ways == 2) {
        val (d1, _) = read(dut, h, 0x40 + setBytes); assert(d1 == memory(0x40 + setBytes))
        assert(h.misses == before + 1, "the most recently used alias was evicted")
        val (d0, _) = read(dut, h, 0x40); assert(d0 == memory(0x40))
        assert(h.misses == before + 2, "the least recently used alias survived")
      }
    }
    println(s"aliasing streams: misses by ways $missesByWays")
    assert(missesByWays(1) == 20)
    assert(missesByWays(2) == 2)
  }
}
