package net.gamebub.core.snes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import HandheldSnes.BytePortBridge

/**
 * Drives the bridge like the SNES core drives its WRAM port and checks the
 * data and the strobe-to-data latency against a model of the
 * [[AsyncSramController]] (request accepted when idle, done two cycles
 * later, one dead cycle after done).
 */
class BytePortBridgeSpec extends AnyFreeSpec {
  val WordBase = 0x20000

  class Target {
    val memory = scala.collection.mutable.Map[Int, Int]().withDefaultValue(0xFFFF)
    var state = 0 // 0 idle, 1 access, 2 done
    var address = 0
    var write = false
    var data = 0
    var strobe = 0
    var accesses = 0

    /** Drive the target for one cycle: call before stepping the clock. */
    def service(dut: BytePortBridge): Unit = {
      dut.io.mem.done.poke((state == 2).B)
      dut.io.mem.dataRead.poke(memory(address).U)
      state match {
        case 0 =>
          if (dut.io.mem.enable.peek().litValue == 1) {
            address = dut.io.mem.address.peek().litValue.toInt
            write = dut.io.mem.write.peek().litValue == 1
            data = dut.io.mem.dataWrite.peek().litValue.toInt
            strobe = dut.io.mem.writeStrobe.peek().litValue.toInt
            accesses += 1
            state = 1
          }
        case 1 =>
          if (write) {
            val old = memory(address)
            val lo = if ((strobe & 1) != 0) data & 0xFF else old & 0xFF
            val hi = if ((strobe & 2) != 0) data & 0xFF00 else old & 0xFF00
            memory(address) = hi | lo
          }
          state = 2
        case 2 =>
          state = 3 // dead cycle (regDone still high in the controller)
        case 3 =>
          state = 0
      }
    }
  }

  def idlePort(dut: BytePortBridge): Unit = {
    dut.io.ceN.poke(true.B); dut.io.oeN.poke(true.B); dut.io.weN.poke(true.B)
  }

  def withBridge(bufferWords: Int = 0)(body: (BytePortBridge, Target) => Unit): Unit = {
    simulate(new BytePortBridge(addressWidth = 17, wordBase = WordBase, bufferWords = bufferWords)) { dut =>
      val t = new Target
      dut.reset.poke(true.B); dut.clock.step(); dut.reset.poke(false.B)
      dut.io.sample.poke(true.B)
      dut.io.invalidate.poke(false.B)
      dut.io.bufferOff.poke(false.B)
      dut.io.oneWay.poke(false.B)
      dut.io.address.poke(0.U)
      dut.io.dataWrite.poke(0.U)
      idlePort(dut)
      for (_ <- 0 until 4) { t.service(dut); dut.clock.step() }
      body(dut, t)
    }
  }

  /** Assert the read strobe and return (data, cycles from strobe to data). */
  def read(dut: BytePortBridge, t: Target, address: Int, hold: Int = 6): (Int, Int) = {
    dut.io.address.poke(address.U)
    dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B)
    var cycles = 0
    var result: Option[(Int, Int)] = None
    for (_ <- 0 until hold) {
      t.service(dut)
      if (result.isEmpty && dut.io.pendingRead.peek().litValue == 0 && cycles > 0) {
        result = Some((dut.io.dataRead.peek().litValue.toInt, cycles))
      }
      dut.clock.step(); cycles += 1
    }
    idlePort(dut)
    result.getOrElse(fail(s"read at $address did not complete within $hold cycles"))
  }

  /** Assert the write strobe for `hold` cycles. */
  def write(dut: BytePortBridge, t: Target, address: Int, data: Int, hold: Int = 4): Unit = {
    dut.io.address.poke(address.U)
    dut.io.dataWrite.poke(data.U)
    dut.io.ceN.poke(false.B); dut.io.weN.poke(false.B)
    for (_ <- 0 until hold) { t.service(dut); dut.clock.step() }
    idlePort(dut)
  }

  def settle(dut: BytePortBridge, t: Target, cycles: Int = 6): Unit =
    for (_ <- 0 until cycles) { t.service(dut); dut.clock.step() }

  "reads are issued in the strobe cycle and complete two cycles later" in {
    withBridge() { (dut, t) =>
      t.memory(WordBase + 0x1234 / 2) = 0xBEEF
      val (lo, cyclesLo) = read(dut, t, 0x1234)
      assert(lo == 0xEF)
      assert(cyclesLo == 2, s"low byte took $cyclesLo cycles")
      settle(dut, t)
      val (hi, cyclesHi) = read(dut, t, 0x1235)
      assert(hi == 0xBE)
      assert(cyclesHi == 2, s"high byte took $cyclesHi cycles")
      assert(t.accesses == 2)
    }
  }

  "a held strobe with a changing address reads each address once" in {
    withBridge() { (dut, t) =>
      for (a <- 0 until 8) t.memory(WordBase + 0x100 + a) = 0x0100 * (2 * a + 1) | (2 * a)
      dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B)
      var seen = List[Int]()
      for (a <- 0 until 16) {
        dut.io.address.poke((0x200 + a).U)
        for (c <- 0 until 4) {
          t.service(dut)
          if (c > 0 && dut.io.pendingRead.peek().litValue == 0 && seen.size == a) seen ::= dut.io.dataRead.peek().litValue.toInt
          dut.clock.step()
        }
      }
      idlePort(dut)
      assert(seen.reverse == (0 until 16).toList)
      assert(t.accesses == 16)
    }
  }

  "writes use the byte strobe and are posted (neither pending nor pendingRead)" in {
    withBridge() { (dut, t) =>
      t.memory(WordBase + 0x40) = 0x1122
      dut.io.address.poke(0x81.U)
      dut.io.dataWrite.poke(0xAB.U)
      dut.io.ceN.poke(false.B); dut.io.weN.poke(false.B)
      t.service(dut)
      // A write arriving this cycle is held for one (it is posted anyway).
      assert(dut.io.mem.enable.peek().litValue == 0, "write issued in the strobe cycle")
      dut.clock.step()
      t.service(dut)
      assert(dut.io.mem.enable.peek().litValue == 1, "write not issued in the cycle after the strobe")
      assert(dut.io.pending.peek().litValue == 0)
      assert(dut.io.pendingRead.peek().litValue == 0)
      dut.clock.step()
      idlePort(dut)
      settle(dut, t)
      assert(t.memory(WordBase + 0x40) == 0xAB22)
      assert(t.accesses == 1)
      write(dut, t, 0x80, 0xCD)
      settle(dut, t)
      assert(t.memory(WordBase + 0x40) == 0xABCD)
      assert(t.accesses == 2)
    }
  }

  "back-to-back writes are queued and the queue stalls when nearly full" in {
    withBridge() { (dut, t) =>
      // One write per cycle: the SRAM takes 4 cycles each, so the queue fills.
      var sawPending = false
      var issued = 0
      dut.io.ceN.poke(false.B); dut.io.weN.poke(false.B)
      while (issued < 8) {
        dut.io.address.poke((0x100 + issued).U)
        dut.io.dataWrite.poke((0x10 + issued).U)
        t.service(dut)
        // Queued writes are not outstanding reads (a coprocessor sampling
        // its RAM port must not be stalled by a burst of its own stores).
        assert(dut.io.pendingRead.peek().litValue == 0, s"pendingRead with only writes queued (write $issued)")
        dut.clock.step()
        issued += 1
        // A stalled core holds its request; emulate by not advancing while pending.
        while (dut.io.pending.peek().litValue == 1) {
          sawPending = true
          t.service(dut)
          assert(dut.io.pendingRead.peek().litValue == 0, "pendingRead with only writes queued")
          dut.clock.step()
        }
      }
      idlePort(dut)
      settle(dut, t, 40)
      assert(sawPending, "queue never reported nearly full")
      for (i <- 0 until 8) {
        val word = t.memory(WordBase + (0x100 + i) / 2)
        val byte = if ((i & 1) == 0) word & 0xFF else word >> 8
        assert(byte == 0x10 + i, s"write $i lost: word ${word.toHexString}")
      }
      assert(t.accesses == 8)
    }
  }

  "a read arriving during a write is queued and returns the written data" in {
    withBridge() { (dut, t) =>
      t.memory(WordBase + 0x10) = 0x0000
      dut.io.address.poke(0x20.U)
      dut.io.dataWrite.poke(0x5A.U)
      dut.io.ceN.poke(false.B); dut.io.weN.poke(false.B)
      t.service(dut); dut.clock.step()
      dut.io.weN.poke(true.B); dut.io.oeN.poke(false.B) // read the same byte right away
      val (data, cycles) = read(dut, t, 0x20, hold = 10)
      assert(data == 0x5A)
      assert(cycles <= 6, s"queued read took $cycles cycles")
      assert(t.accesses == 2)
    }
  }

  /** Read with the buffer: returns (data, hit-in-strobe-cycle). */
  def readB(dut: BytePortBridge, t: Target, address: Int): (Int, Boolean) = {
    dut.io.address.poke(address.U)
    dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B)
    t.service(dut)
    val hit = dut.io.bufferHit.peek().litValue == 1
    val hitData = dut.io.dataRead.peek().litValue.toInt
    dut.clock.step()
    var data = hitData
    if (!hit) {
      var n = 0
      while (dut.io.pendingRead.peek().litValue == 1 || n == 0) {
        t.service(dut)
        if (dut.io.pendingRead.peek().litValue == 0) data = dut.io.dataRead.peek().litValue.toInt
        dut.clock.step(); n += 1
        assert(n < 20)
      }
    }
    idlePort(dut)
    settle(dut, t, 6) // let a prefetch complete
    (data, hit)
  }

  "the word buffer answers the sibling byte and the prefetched next word in the strobe cycle" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      for (w <- 0 until 8) t.memory(WordBase + 0x80 + w) = 0x1100 * (w + 1) + w
      val (d0, h0) = readB(dut, t, 0x100); assert(d0 == 0x00 && !h0)
      val (d1, h1) = readB(dut, t, 0x101); assert(d1 == 0x11 && h1, "sibling byte missed")
      // The first word of a run is not followed by a prefetch (nothing says
      // it is a run yet); the second is, and from then on every word is.
      val (d2, h2) = readB(dut, t, 0x102); assert(d2 == 0x01 && !h2, "prefetch after a lone access")
      val (d3, h3) = readB(dut, t, 0x103); assert(d3 == 0x22 && h3)
      // Sequential stream: each word was prefetched by the previous access.
      for (b <- 4 until 16) {
        val (d, h) = readB(dut, t, 0x100 + b)
        val expected = if (b % 2 == 0) b / 2 else 0x11 * (b / 2 + 1)
        assert(d == expected, s"byte $b: $d"); assert(h, s"byte $b missed")
      }
      // One memory read per word (the first demand read, then prefetches): no bogus demand reads on hits.
      assert(t.accesses == 9, s"${t.accesses} memory accesses for 16 sequential bytes (2 demand reads, 6 prefetches, a trailing prefetch)")
    }
  }

  "a hit never leaves a read outstanding, and a later sample still sees the hit data" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      t.memory(WordBase + 0x70) = 0xBBAA
      t.memory(WordBase + 0x71) = 0xDDCC
      val (_, _) = readB(dut, t, 0xDE) // word 0x6F, so that the next access continues a run
      val (_, _) = readB(dut, t, 0xE0) // miss: word 0x70 and prefetch 0x71 installed
      // Hit on 0xE1, then a hit on 0xE2; sample dataRead several cycles later (like the S-CPU at SYSCLKF_CE).
      dut.io.address.poke(0xE1.U); dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B)
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 1); dut.clock.step()
      dut.io.address.poke(0xE2.U)
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 1); dut.clock.step()
      for (_ <- 0 until 8) {
        t.service(dut)
        assert(dut.io.pendingRead.peek().litValue == 0, "read outstanding after a hit")
        assert(dut.io.dataRead.peek().litValue == 0xCC, s"dataRead ${dut.io.dataRead.peek().litValue.toInt.toHexString}")
        dut.clock.step()
      }
      idlePort(dut)
    }
  }

  "a demand read completing after a newer hit does not replace the hit data" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      t.memory(WordBase + 0x70) = 0xBBAA
      t.memory(WordBase + 0x71) = 0xDDCC
      t.memory(WordBase + 0x100) = 0x9988
      val (_, _) = readB(dut, t, 0xE0) // word 0x70 (and 0x71) installed
      // A speculative read of 0x200 (miss, in flight for a few cycles), then
      // the next cycle a hit on 0xE1 like a GSU LOAD right after a SAVE.
      dut.io.address.poke(0x200.U); dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B)
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 0); dut.clock.step()
      dut.io.address.poke(0xE1.U)
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 1)
      assert(dut.io.dataRead.peek().litValue == 0xBB); dut.clock.step()
      for (i <- 0 until 8) {
        t.service(dut)
        assert(dut.io.dataRead.peek().litValue == 0xBB, s"cycle $i: dataRead ${dut.io.dataRead.peek().litValue.toInt.toHexString}")
        dut.clock.step()
      }
      assert(dut.io.pendingRead.peek().litValue == 0)
      idlePort(dut)
    }
  }

  "another consumer's hit between a strobe and its sample does not replace the sampled data" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      // The S-CPU and the SA-1 share the BW-RAM port: the SA-1 strobes A
      // (a miss), the S-CPU reads B (a hit) in between, then the SA-1's
      // address is back on the port when it samples: it must see A's data.
      t.memory(WordBase + 0x30) = 0x2211 // B
      t.memory(WordBase + 0x90) = 0x44AA // A
      val (_, _) = readB(dut, t, 0x60) // B's word installed
      dut.io.address.poke(0x120.U); dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B) // A: miss
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 0); dut.clock.step()
      dut.io.address.poke(0x61.U) // the S-CPU's hit while A is in flight
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 1); assert(dut.io.dataRead.peek().litValue == 0x22); dut.clock.step()
      dut.io.address.poke(0x120.U) // A back on the port; the SA-1 samples once nothing is pending
      var n = 0
      while (dut.io.pendingRead.peek().litValue == 1) { t.service(dut); dut.clock.step(); n += 1; assert(n < 20) }
      t.service(dut)
      assert(dut.io.dataRead.peek().litValue == 0xAA, s"sampled ${dut.io.dataRead.peek().litValue.toInt.toHexString}, not A's data")
      dut.clock.step()
      for (_ <- 0 until 4) { t.service(dut); assert(dut.io.dataRead.peek().litValue == 0xAA); dut.clock.step() }
      idlePort(dut)
    }
  }

  "writes update the buffer and a read in flight cannot install stale data" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      t.memory(WordBase + 0x40) = 0x1234
      val (d0, _) = readB(dut, t, 0x80); assert(d0 == 0x34)
      write(dut, t, 0x80, 0xAB); settle(dut, t, 6)
      val (d1, h1) = readB(dut, t, 0x80); assert(d1 == 0xAB && h1, "write-through miss")
      assert(t.memory(WordBase + 0x40) == 0x12AB)
      // Read of a word not in the buffer, and a write to it while the read is in flight.
      t.memory(WordBase + 0x50) = 0x5566
      dut.io.address.poke(0xA0.U); dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B)
      t.service(dut); dut.clock.step()
      dut.io.oeN.poke(true.B); dut.io.weN.poke(false.B); dut.io.dataWrite.poke(0x77.U) // write while the read is busy
      t.service(dut); dut.clock.step()
      idlePort(dut); settle(dut, t, 12)
      val (d2, _) = readB(dut, t, 0xA0); assert(d2 == 0x77, s"stale data ${d2.toHexString}")
      assert(t.memory(WordBase + 0x50) == 0x5577)
    }
  }

  "writes allocate their byte, so reading back what was written hits" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      // A decompressor writes its output and reads it back for back-references.
      write(dut, t, 0x300, 0x5A)
      settle(dut, t)
      val (d0, h0) = readB(dut, t, 0x300); assert(d0 == 0x5A && h0, "written byte missed")
      val (d1, h1) = readB(dut, t, 0x301); assert(!h1, "unwritten sibling byte hit") // then installed by that read
      val (d2, h2) = readB(dut, t, 0x301); assert(h2 && d2 == d1)
      val (d3, h3) = readB(dut, t, 0x300); assert(h3 && d3 == 0x5A, "the written byte survived the install")
      assert((t.memory(WordBase + 0x180) & 0xFF) == 0x5A)
    }
  }

  "two words of one set stay cached together, a third evicts the least recently used" in {
    withBridge(bufferWords = 32) { (dut, t) => // 2 ways x 16 sets
      val setStride = 16 * 2 // bytes between words of the same set
      for (i <- 0 until 3) t.memory(WordBase + 0x200 + i * 16) = 0x1000 * (i + 1) + i
      val a = 0x400; val b = a + setStride; val c = b + setStride
      val (_, ha) = readB(dut, t, a); assert(!ha)
      val (_, hb) = readB(dut, t, b); assert(!hb)
      val (da, ha2) = readB(dut, t, a); assert(ha2 && da == 0x00, "a evicted by b")
      val (db, hb2) = readB(dut, t, b); assert(hb2 && db == 0x01, "b evicted by a")
      val (_, hc) = readB(dut, t, c); assert(!hc) // evicts a (b was used most recently)
      val (_, hb3) = readB(dut, t, b); assert(hb3, "b evicted although most recently used")
      val (_, ha3) = readB(dut, t, a); assert(!ha3, "a survived a third word in its set")
    }
  }

  "a prefetch and a demand read of the same word never leave it in both ways" in {
    withBridge(bufferWords = 32) { (dut, t) => // 2 ways x 16 sets; set = word & 15
      for (w <- Seq(0x10, 0x11, 0x12, 0x22, 0x32)) t.memory(WordBase + w) = 0xFFFF
      // Set 2: way 1 <- 0x22, way 0 <- 0x32 (most recently used).
      readB(dut, t, 0x44); readB(dut, t, 0x64)
      // A sequential run 0x10, 0x11 raises a prefetch of 0x12 (set 2, into the
      // LRU way 1) in 0x11's done cycle...
      readB(dut, t, 0x20)
      // Strobe 0x22 and leave in the very cycle its data arrives (the
      // prefetch of word 0x12 is issued in that cycle)...
      dut.io.address.poke(0x22.U); dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B)
      var m = 0
      t.service(dut); dut.clock.step()
      while ({ t.service(dut); dut.io.pendingRead.peek().litValue == 1 }) { dut.clock.step(); m += 1; assert(m < 20) }
      dut.clock.step()
      // ...then a hit on 0x22 (way 1) makes way 0 the victim, and a demand
      // read of 0x12 arrives before the prefetch has installed it.
      dut.io.address.poke(0x44.U)
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 1); dut.clock.step()
      dut.io.address.poke(0x24.U)
      t.service(dut); assert(dut.io.bufferHit.peek().litValue == 0, "0x12 already installed: the sequence did not overlap"); dut.clock.step()
      var n = 0
      while (dut.io.pendingRead.peek().litValue == 1) { t.service(dut); dut.clock.step(); n += 1; assert(n < 30) }
      idlePort(dut); settle(dut, t, 8)
      // Write the low byte of 0x12 and read it back: with the word in both
      // ways only one copy is updated and the read sees the copies OR-ed.
      write(dut, t, 0x24, 0x00)
      settle(dut, t)
      val (d, h) = readB(dut, t, 0x24)
      assert(h && d == 0x00, s"read back ${d.toHexString} after writing 0x00 (hit=$h)")
    }
  }

  "invalidate drops the buffer" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      t.memory(WordBase + 0x60) = 0x0102
      val (_, h0) = readB(dut, t, 0xC0); assert(!h0)
      val (_, h1) = readB(dut, t, 0xC1); assert(h1)
      t.memory(WordBase + 0x60) = 0x0304 // "the MCU" changes the memory
      dut.io.invalidate.poke(true.B); t.service(dut); dut.clock.step(); dut.io.invalidate.poke(false.B)
      val (d2, h2) = readB(dut, t, 0xC1); assert(!h2 && d2 == 0x03)
    }
  }

  "random traffic matches a reference model" in {
    withBridge(bufferWords = 32) { (dut, t) =>
      val rnd = new scala.util.Random(1)
      val ref = Array.fill(64)(0)
      for (w <- 0 until 32) {
        val v = rnd.nextInt(0x10000)
        t.memory(WordBase + w) = v; ref(2 * w) = v & 0xFF; ref(2 * w + 1) = v >> 8
      }
      for (op <- 0 until 4000) {
        val a = if (rnd.nextInt(4) == 0) rnd.nextInt(64) else (rnd.nextInt(8) + op % 8) % 64
        val hold = 1 + rnd.nextInt(4)
        if (rnd.nextInt(3) == 0) {
          val d = rnd.nextInt(256)
          dut.io.address.poke(a.U); dut.io.dataWrite.poke(d.U)
          dut.io.ceN.poke(false.B); dut.io.weN.poke(false.B); dut.io.oeN.poke(true.B)
          ref(a) = d
          for (_ <- 0 until hold) { t.service(dut); dut.clock.step() }
        } else {
          dut.io.address.poke(a.U)
          dut.io.ceN.poke(false.B); dut.io.oeN.poke(false.B); dut.io.weN.poke(true.B)
          var got: Option[Int] = None
          var c = 0
          while (c < hold + 16 && (got.isEmpty || c < hold)) {
            t.service(dut)
            if (c > 0 && dut.io.pendingRead.peek().litValue == 0) {
              val v = dut.io.dataRead.peek().litValue.toInt
              if (got.isEmpty) got = Some(v)
              else assert(v == got.get, s"op $op: data at $a changed from ${got.get} to $v while the strobe was held")
            }
            dut.clock.step(); c += 1
          }
          assert(got.isDefined, s"op $op: read at $a never completed")
          assert(got.get == ref(a), s"op $op: read at $a got 0x${got.get.toHexString}, expected 0x${ref(a).toHexString}")
        }
        val gap = rnd.nextInt(3)
        if (gap > 0) {
          idlePort(dut)
          for (_ <- 0 until gap) { t.service(dut); dut.clock.step() }
        }
      }
      idlePort(dut); settle(dut, t, 12)
      for (w <- 0 until 32) {
        val v = t.memory(WordBase + w)
        assert(v == (ref(2 * w) | (ref(2 * w + 1) << 8)), s"memory word $w is 0x${v.toHexString}, expected 0x${(ref(2 * w) | (ref(2 * w + 1) << 8)).toHexString}")
      }
    }
  }
}
