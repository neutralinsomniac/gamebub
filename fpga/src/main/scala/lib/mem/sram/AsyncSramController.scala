package lib.mem.sram

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface
import AsyncSramController.State
import xilinx.ODDRWrapper

object AsyncSramController {
  object State extends ChiselEnum {
    val idle, write, read = Value
  }

  class Signals(addressWidth: Int, dataWidth: Int) extends Bundle {
    val address = Output(UInt(addressWidth.W))
    val dataIn = Input(UInt(dataWidth.W))
    val dataOut = Output(UInt(dataWidth.W))
    val dataDir = Output(Bool())
    val oeN = Output(Bool())
    val weN = Output(Bool())
    val writeMaskN = Output(UInt((dataWidth / 8).W))
  }
}

/**
 * A controller for a single asynchronous SRAM chip.
 *
 * nCE is assumed to always be low.
 * Memory interface is word addressed and has byte strobe.
 *
 * An access takes two cycles (accept, access) and `done` pulses in the
 * third. A request presented in that `done` cycle is accepted right away if
 * it differs from the access just completed (address, direction, or write
 * data / strobe), so back-to-back different accesses run every two cycles;
 * an identical one is assumed to be the completed request still being held
 * by its initiator and waits a cycle. A read right after a write also waits:
 * WE rises at the start of the `done` cycle (the ODDR outputs D2 in the
 * second half of the write cycle and the next D1 in the first half of the
 * following one), so the data bus has to be driven through the `done` cycle
 * for the SRAM's data hold time, and a read accepted then would have the
 * SRAM driving the bus while we still do.
 */
class AsyncSramController(addressWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val signals = new AsyncSramController.Signals(addressWidth, dataWidth)

    /** Standard memory interface to consumers. */
    val mem = new MemoryInterface(addressWidth, dataWidth)
  })
  val maskWidth = dataWidth / 8

  val state = RegInit(State.idle)
  val regDone = RegInit(false.B)
  val regAddress = Reg(UInt(addressWidth.W))
  val regDataOut = Reg(UInt(dataWidth.W))
  val regDataIn = Reg(UInt(dataWidth.W))
  val regDataDir = RegInit(false.B)
  val regWriteMaskN = RegInit(0.U(maskWidth.W))
  val regOeN = RegInit(true.B)
  val regWrite = Reg(Bool())
  /** Strobe of the request being / last served (regWriteMaskN is deasserted after the access). */
  val regStrobe = Reg(UInt(maskWidth.W))

  val oddrWeN = Module(new ODDRWrapper(initial = true))
  oddrWeN.io.D1 := true.B
  oddrWeN.io.D2 := true.B

  io.signals.address := regAddress
  io.signals.dataOut := regDataOut
  io.signals.writeMaskN := regWriteMaskN
  io.signals.dataDir := regDataDir
  io.signals.oeN := regOeN
  io.signals.weN := oddrWeN.io.Q
  io.mem.dataRead := regDataIn
  io.mem.done := regDone

  switch (state) {
    is (State.idle) {
      regDone := false.B
      regDataDir := false.B

      val differs = io.mem.address =/= regAddress || io.mem.write =/= regWrite ||
        (io.mem.write && (io.mem.dataWrite =/= regDataOut || io.mem.writeStrobe =/= regStrobe))
      val turnaround = regWrite && !io.mem.write
      when (io.mem.enable && (!regDone || (differs && !turnaround))) {
        regAddress := io.mem.address
        regWrite := io.mem.write
        regStrobe := io.mem.writeStrobe

        when (io.mem.write) {
          state := State.write
          regDataOut := io.mem.dataWrite
          regWriteMaskN := ~io.mem.writeStrobe
          regDataDir := true.B

          // Next cycle: first weN high, then weN low
          oddrWeN.io.D1 := true.B
          oddrWeN.io.D2 := false.B
        } .otherwise {
          state := State.read
          regOeN := false.B
          regWriteMaskN := 0.U
        }
      }
    }
    is (State.read) {
      state := State.idle
      regDone := true.B
      regOeN := true.B
      regDataIn := io.signals.dataIn
      regWriteMaskN := Fill(maskWidth, true.B)
    }
    is (State.write) {
      state := State.idle
      regDone := true.B
      regWriteMaskN := Fill(maskWidth, true.B)

      // WE rises at the start of the next cycle; keep driving the data bus
      // through it (dataDir turns off in idle, i.e. one cycle later).
    }
  }
}
