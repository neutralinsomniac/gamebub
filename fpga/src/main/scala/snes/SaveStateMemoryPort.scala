package snes

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface

/**
 * Adapter from the MiSTer SNES core's save-state memory port (a 64-bit
 * request/acknowledge toggle interface meant for the MiSTer's DDR3) to a
 * 32-bit PipelineMemoryInterface, i.e. the Game Bub SDRAM.
 *
 * The core toggles `req` with `address`, `write`, `byteEnable` and
 * `dataWrite` valid; the transfer is complete when `ack` matches `req`
 * again, at which point `dataRead` holds the data of a read. Each 64-bit
 * word becomes two 32-bit accesses, little-endian (the low word first), at
 * byte address `baseAddress + (address << 3)`.
 *
 * `busy` is meant to stall the core: the MiSTer's 65816 save-state program
 * assumes the DDR3 answers before its next access (it polls the busy flag
 * only once, before the first read of a load), and the core's clock is
 * stopped here instead. With the core stopped, its side of the port is
 * stable for the whole transfer, but it is latched anyway.
 *
 * `headerWritten` pulses when a write to the first word of a slot completes:
 * the core writes the header (size and save counter) last, so this marks a
 * finished save.
 *
 * Byte enables are not forwarded (the SDRAM controller has no byte strobes);
 * the core only uses partial enables to skip the counter when `SS_TOSD` is
 * low, and the glue keeps it high.
 */
class SaveStateMemoryPort(baseAddress: BigInt, addressWidth: Int = 25) extends Module {
  val io = IO(new Bundle {
    val req = Input(Bool())
    val ack = Output(Bool())
    /** 64-bit word address: {slot(1:0), offset(19:3)}. */
    val address = Input(UInt(19.W))
    val write = Input(Bool())
    val byteEnable = Input(UInt(8.W))
    val dataWrite = Input(UInt(64.W))
    val dataRead = Output(UInt(64.W))
    /** A transfer is outstanding (from the request toggle to the acknowledge). */
    val busy = Output(Bool())
    /** A write to word 0 of a slot completed this cycle. */
    val headerWritten = Output(Bool())
    val mem = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth = 32))
  })

  object State extends ChiselEnum {
    val idle, issue, data = Value
  }

  val regAck = RegInit(false.B)
  val regState = RegInit(State.idle)
  /** Which 32-bit half of the 64-bit word is in flight. */
  val regHigh = RegInit(false.B)
  val regAddress = Reg(UInt(19.W))
  val regWrite = Reg(Bool())
  val regDataWrite = Reg(UInt(64.W))
  val regDataRead = RegInit(0.U(64.W))

  io.ack := regAck
  io.busy := io.req =/= regAck
  io.dataRead := regDataRead
  io.headerWritten := false.B

  val wordAddress = Cat(regAddress, regHigh, 0.U(2.W))
  io.mem.enable := false.B
  io.mem.address := baseAddress.U(addressWidth.W) | wordAddress
  io.mem.isWrite := regWrite
  io.mem.writeStrobe := Fill(4, 1.U(1.W))
  io.mem.dataWrite := Mux(regHigh, regDataWrite(63, 32), regDataWrite(31, 0))

  switch (regState) {
    is (State.idle) {
      when (io.busy) {
        regAddress := io.address
        regWrite := io.write
        regDataWrite := io.dataWrite
        regHigh := false.B
        regState := State.issue
      }
    }
    is (State.issue) {
      io.mem.enable := true.B
      when (io.mem.ready) {
        regState := State.data
      }
    }
    is (State.data) {
      when (io.mem.ready) {
        when (!regWrite) {
          when (regHigh) {
            regDataRead := Cat(io.mem.dataRead, regDataRead(31, 0))
          } .otherwise {
            regDataRead := Cat(regDataRead(63, 32), io.mem.dataRead)
          }
        }
        when (regHigh) {
          regAck := io.req
          regState := State.idle
          io.headerWritten := regWrite && regAddress(16, 0) === 0.U
        } .otherwise {
          regHigh := true.B
          regState := State.issue
        }
      }
    }
  }
}
