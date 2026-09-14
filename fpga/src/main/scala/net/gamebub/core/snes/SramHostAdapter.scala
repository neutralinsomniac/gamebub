package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import lib.mem.MemoryInterface

/**
 * Presents the 16-bit SRAM as a 32-bit host window: each host access (a
 * 32-bit word at a 4-byte-aligned byte address) becomes two consecutive
 * 16-bit word accesses, the low half first (little-endian, as the bytes
 * of a 32-bit host transfer land in the SDRAM window too).
 *
 * The firmware transfers an external core's files as 32-bit words (the
 * word size is not part of the file descriptor), so the save file could
 * not land in a 16-bit window; the built-in driver uses 32-bit words for
 * the SRAM too.
 *
 * Host protocol as with the [[lib.mem.sram.AsyncSramController]]: the
 * request is held until `done` pulses (with the read data); the request
 * still held in the done cycle is the completed one and is not taken
 * again. SRAM side: the request is held until its `done`.
 */
class SramHostAdapter extends Module {
  val io = IO(new Bundle {
    val host = new MemoryInterface(addressWidth = 19, dataWidth = 32)
    val sram = Flipped(new MemoryInterface(addressWidth = 18, dataWidth = 16))
  })

  object State extends ChiselEnum {
    val idle, low, high, done = Value
  }
  val state = RegInit(State.idle)
  val address = Reg(UInt(18.W))
  val write = Reg(Bool())
  val dataWrite = Reg(UInt(32.W))
  val strobe = Reg(UInt(4.W))
  val dataLow = Reg(UInt(16.W))
  val dataHigh = Reg(UInt(16.W))

  val active = state === State.low || state === State.high
  io.sram.enable := active
  io.sram.address := address + Mux(state === State.high, 1.U, 0.U)
  io.sram.write := write
  io.sram.dataWrite := Mux(state === State.high, dataWrite(31, 16), dataWrite(15, 0))
  io.sram.writeStrobe := Mux(state === State.high, strobe(3, 2), strobe(1, 0))

  io.host.done := state === State.done
  io.host.dataRead := Cat(dataHigh, dataLow)

  switch (state) {
    is (State.idle) {
      when (io.host.enable) {
        address := io.host.address(18, 1) // the word of the low half
        write := io.host.write
        dataWrite := io.host.dataWrite
        strobe := io.host.writeStrobe
        state := State.low
      }
    }
    is (State.low) {
      when (io.sram.done) {
        dataLow := io.sram.dataRead
        state := State.high
      }
    }
    is (State.high) {
      when (io.sram.done) {
        dataHigh := io.sram.dataRead
        state := State.done
      }
    }
    is (State.done) {
      state := State.idle
    }
  }
}
