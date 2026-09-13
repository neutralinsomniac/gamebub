package lib.mem

import chisel3._
import lib.util.EphemeralSimulator._
import org.scalatest.funsuite.AnyFunSuite


class PipelineMemoryLowPriorityMuxSpec extends AnyFunSuite {
  private class Harness(dut: PipelineMemoryLowPriorityMux) {
    val io = dut.io
    def step(n: Int = 1) = dut.clock.step(n)

    def request(port: PipelineMemoryInterface, address: BigInt, write: Boolean = false): Unit = {
      port.enable.poke(true)
      port.address.poke(address)
      port.isWrite.poke(write)
    }
    def idle(port: PipelineMemoryInterface): Unit = port.enable.poke(false)
    def ready(port: PipelineMemoryInterface): Boolean = port.ready.peek().litToBoolean
    def targetRequest: Option[BigInt] =
      if (io.target.enable.peek().litToBoolean) Some(io.target.address.peek().litValue) else None

    io.main.enable.poke(false)
    io.side.enable.poke(false)
    io.main.dataWrite.poke(0)
    io.side.dataWrite.poke(0)
    io.main.writeStrobe.poke(0xF)
    io.side.writeStrobe.poke(0xF)
    io.target.ready.poke(true)
    io.target.dataRead.poke(0)
    dut.reset.poke(true)
    dut.clock.step()
    dut.reset.poke(false)
  }

  private def go()(body: Harness => Unit): Unit = {
    simulate(new PipelineMemoryLowPriorityMux(addressWidth = 25, dataWidth = 32)) { dut =>
      body(new Harness(dut))
    }
  }

  test("main passes through with no added latency, pipelined") {
    go() { dut =>
      dut.request(dut.io.main, 0x100)
      assert(dut.ready(dut.io.main))
      assert(dut.targetRequest.contains(0x100))
      dut.step()
      // Data phase of the first request overlaps the address phase of the next.
      dut.request(dut.io.main, 0x104)
      dut.io.target.dataRead.poke(0xAAAA)
      assert(dut.ready(dut.io.main))
      assert(dut.io.main.dataRead.peek().litValue == 0xAAAA)
      assert(dut.targetRequest.contains(0x104))
      dut.step()
      dut.idle(dut.io.main)
      dut.io.target.dataRead.poke(0xBBBB)
      assert(dut.ready(dut.io.main))
      assert(dut.io.main.dataRead.peek().litValue == 0xBBBB)
    }
  }

  test("main sees a slow target's ready as is") {
    go() { dut =>
      dut.io.target.ready.poke(false)
      dut.request(dut.io.main, 0x100)
      assert(!dut.ready(dut.io.main))
      dut.step()
      dut.io.target.ready.poke(true)
      assert(dut.ready(dut.io.main))
      assert(dut.targetRequest.contains(0x100))
    }
  }

  test("side waits for main's data phase, then owns the bus for the whole transaction") {
    go() { dut =>
      dut.request(dut.io.main, 0x100)
      assert(dut.ready(dut.io.main))
      assert(dut.targetRequest.contains(0x100))
      dut.step()
      // Main's data phase is outstanding: a side request waits.
      dut.idle(dut.io.main)
      dut.request(dut.io.side, 0x2000, write = true)
      dut.io.side.dataWrite.poke(0x1234)
      assert(!dut.ready(dut.io.side))
      assert(dut.targetRequest.isEmpty)
      dut.step()
      // Main idle: side's address phase goes out.
      assert(dut.ready(dut.io.side))
      assert(dut.targetRequest.contains(0x2000))
      assert(dut.io.target.isWrite.peek().litToBoolean)
      dut.step()
      // Side's data phase: a main request arriving now is held off, side's
      // write data is on the bus.
      dut.idle(dut.io.side)
      dut.request(dut.io.main, 0x108)
      assert(dut.io.target.dataWrite.peek().litValue == 0x1234)
      assert(dut.ready(dut.io.side))
      assert(!dut.ready(dut.io.main))
      assert(dut.targetRequest.isEmpty)
      dut.step()
      // Main gets the bus back.
      assert(dut.ready(dut.io.main))
      assert(dut.targetRequest.contains(0x108))
    }
  }

  test("simultaneous requests: side issues, main waits a cycle") {
    go() { dut =>
      dut.request(dut.io.main, 0x100)
      dut.request(dut.io.side, 0x2000)
      assert(dut.ready(dut.io.side))
      assert(!dut.ready(dut.io.main))
      assert(dut.targetRequest.contains(0x2000))
      dut.step()
      dut.idle(dut.io.side)
      assert(!dut.ready(dut.io.main))
      dut.step()
      assert(dut.ready(dut.io.main))
      assert(dut.targetRequest.contains(0x100))
    }
  }

  test("side read data") {
    go() { dut =>
      dut.request(dut.io.side, 0x2000)
      assert(dut.ready(dut.io.side))
      dut.step()
      dut.idle(dut.io.side)
      dut.io.target.ready.poke(false)
      assert(!dut.ready(dut.io.side))
      dut.step()
      dut.io.target.ready.poke(true)
      dut.io.target.dataRead.poke(0xCAFE)
      assert(dut.ready(dut.io.side))
      assert(dut.io.side.dataRead.peek().litValue == 0xCAFE)
      dut.step()
      assert(dut.ready(dut.io.main))
    }
  }
}
