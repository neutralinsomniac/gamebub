package snes

import chisel3._
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite


class SaveStateMemoryPortSpec extends AnyFunSuite {
  private val Base = BigInt(0x1000000)

  private class Harness(dut: SaveStateMemoryPort) {
    val io = dut.io
    def step(n: Int = 1) = dut.clock.step(n)
    var req = false

    def busy: Boolean = io.busy.peek().litToBoolean
    def memRequest: Option[(BigInt, Boolean)] =
      if (io.mem.enable.peek().litToBoolean) Some((io.mem.address.peek().litValue, io.mem.isWrite.peek().litToBoolean)) else None

    /** Toggle the request like the core does (its outputs change with the toggle). */
    def request(address: BigInt, write: Boolean, data: BigInt = 0): Unit = {
      req = !req
      io.req.poke(req)
      io.address.poke(address)
      io.write.poke(write)
      io.dataWrite.poke(data)
    }

    /** Serve one 32-bit access: accept the address phase now, answer the data phase next cycle. */
    def serve(expectedAddress: BigInt, expectedWrite: Boolean, readData: BigInt = 0): BigInt = {
      // The port may take a cycle to notice the toggle.
      var waited = 0
      while (memRequest.isEmpty && waited < 4) { step(); waited += 1 }
      assert(memRequest.contains((expectedAddress, expectedWrite)), s"request ${memRequest} != ${expectedAddress.toString(16)}")
      io.mem.ready.poke(true)
      step()
      // Data phase: hold it off for a cycle, then complete.
      io.mem.ready.poke(false)
      assert(memRequest.isEmpty)
      val written = io.mem.dataWrite.peek().litValue
      step()
      io.mem.ready.poke(true)
      io.mem.dataRead.poke(readData)
      assert(io.mem.dataWrite.peek().litValue == written)
      step()
      written
    }

    io.req.poke(false)
    io.address.poke(0)
    io.write.poke(false)
    io.byteEnable.poke(0xFF)
    io.dataWrite.poke(0)
    io.mem.ready.poke(true)
    io.mem.dataRead.poke(0)
    dut.reset.poke(true)
    dut.clock.step()
    dut.reset.poke(false)
  }

  private def go()(body: Harness => Unit): Unit = {
    simulate(new SaveStateMemoryPort(Base)) { dut =>
      body(new Harness(dut))
    }
  }

  test("idle") {
    go() { dut =>
      for (_ <- 0 until 5) {
        assert(!dut.busy)
        assert(dut.memRequest.isEmpty)
        dut.step()
      }
    }
  }

  test("write: two little-endian words, busy until acknowledged") {
    go() { dut =>
      // Slot 1, offset 0x10 (64-bit word 2).
      val address = (BigInt(1) << 17) | 2
      dut.request(address, write = true, data = BigInt("1122334455667788", 16))
      assert(dut.busy)
      val lo = dut.serve(Base + 0x100000 + 0x10, expectedWrite = true)
      assert(lo == BigInt("55667788", 16))
      assert(dut.busy)
      assert(!dut.io.headerWritten.peek().litToBoolean)
      val hi = dut.serve(Base + 0x100000 + 0x14, expectedWrite = true)
      assert(hi == BigInt("11223344", 16))
      assert(!dut.busy)
      assert(dut.io.ack.peek().litToBoolean == dut.req)
      assert(dut.memRequest.isEmpty)
    }
  }

  test("read: assembles the 64-bit word") {
    go() { dut =>
      dut.request(3, write = false)
      dut.serve(Base + 0x18, expectedWrite = false, readData = BigInt("AABBCCDD", 16))
      dut.serve(Base + 0x1C, expectedWrite = false, readData = BigInt("00112233", 16))
      assert(!dut.busy)
      assert(dut.io.dataRead.peek().litValue == BigInt("00112233AABBCCDD", 16))
    }
  }

  test("header write pulses headerWritten, a data write does not") {
    go() { dut =>
      dut.request((BigInt(2) << 17) | 1, write = true, data = 1)
      dut.serve(Base + 0x200000 + 8, expectedWrite = true)
      dut.serve(Base + 0x200000 + 12, expectedWrite = true)
      assert(!dut.io.headerWritten.peek().litToBoolean)
      dut.request(BigInt(2) << 17, write = true, data = 2)
      dut.serve(Base + 0x200000, expectedWrite = true)
      // The pulse is in the completion cycle of the second word's data phase.
      while (dut.memRequest.isEmpty) dut.step()
      dut.io.mem.ready.poke(true)
      assert(!dut.io.headerWritten.peek().litToBoolean)
      dut.step()
      assert(dut.io.headerWritten.peek().litToBoolean)
      dut.step()
      assert(!dut.io.headerWritten.peek().litToBoolean)
      assert(!dut.busy)
    }
  }

  test("back-to-back transfers") {
    go() { dut =>
      for (i <- 0 until 3) {
        dut.request(i, write = true, data = i)
        dut.serve(Base + i * 8, expectedWrite = true)
        dut.serve(Base + i * 8 + 4, expectedWrite = true)
        assert(!dut.busy)
      }
    }
  }
}
