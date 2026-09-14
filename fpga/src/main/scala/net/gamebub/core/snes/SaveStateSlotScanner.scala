package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface

object SaveStateSlotScanner {
  val Slots = 4
  val SlotSize = 0x10_0000
  val SlotsBase = 0x100_0000
  /** "SNES" at byte 8 of a slot, as the little-endian word there. */
  val Magic = 0x53454E53L
  /** The whole slots region, in bytes: everything loaded. */
  val FullSize = Slots * SlotSize
}

/**
 * Finds which save-state slots in the SDRAM hold a state, so that the glue
 * can refuse a load of an empty one and tell the host how much of the
 * states file to write back (whole slots up to the last one in use).
 *
 * A slot is valid when its header (written by the MiSTer save-state
 * program) has the magic "SNES" at byte 8 and a sane size at bytes 4..7
 * (bits 17:0, in 32-bit words, at least 4). `start` scans all slots: those beyond
 * `loadedSize` (the bytes of the states file the host transferred; the
 * region is all loaded after a save) get their size and magic words
 * zeroed instead, so that a stale state from another game cannot pass the
 * core's own header check. Eight or so single-word accesses through `mem`,
 * `busy` meanwhile; `valid` and `usedSlots` (the last valid slot plus one)
 * hold afterwards.
 */
class SaveStateSlotScanner extends Module {
  import SaveStateSlotScanner._

  val io = IO(new Bundle {
    val start = Input(Bool())
    val loadedSize = Input(UInt(23.W))
    val busy = Output(Bool())
    val valid = Output(UInt(Slots.W))
    val usedSlots = Output(UInt(log2Ceil(Slots + 1).W))
    val mem = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
  })

  object State extends ChiselEnum {
    val idle, decide, clearSize, clearMagic, readSize, readMagic, next = Value
  }
  val state = RegInit(State.idle)
  val slot = Reg(UInt(log2Ceil(Slots).W))
  val loadedSize = Reg(UInt(23.W))
  val valid = RegInit(0.U(Slots.W))
  val sizeWord = Reg(UInt(32.W))
  val dataPhase = RegInit(false.B)

  io.busy := state =/= State.idle
  io.valid := valid
  io.usedSlots := (0 until Slots).map(i => Mux(valid(i), (i + 1).U(io.usedSlots.getWidth.W), 0.U)).reduce((a, b) => Mux(a > b, a, b))

  val slotBase = SlotsBase.U(25.W) + (slot << log2Ceil(SlotSize))
  val access = state === State.clearSize || state === State.clearMagic || state === State.readSize || state === State.readMagic
  val isWrite = state === State.clearSize || state === State.clearMagic
  val atMagic = state === State.clearMagic || state === State.readMagic
  io.mem.enable := access && !dataPhase
  io.mem.address := slotBase + Mux(atMagic, 8.U, 4.U)
  io.mem.isWrite := isWrite
  io.mem.writeStrobe := "b1111".U
  io.mem.dataWrite := 0.U
  /** The current access's data phase completes this cycle. */
  val done = access && dataPhase && io.mem.ready
  when (access && io.mem.ready) {
    dataPhase := !dataPhase
  }

  switch (state) {
    is (State.idle) {
      when (io.start) {
        loadedSize := io.loadedSize
        slot := 0.U
        state := State.decide
      }
    }
    is (State.decide) {
      // The slot is loaded when the file covered it entirely.
      val loaded = ((slot +& 1.U) << log2Ceil(SlotSize)) <= loadedSize
      state := Mux(loaded, State.readSize, State.clearSize)
    }
    is (State.clearSize) {
      when (done) { state := State.clearMagic }
    }
    is (State.clearMagic) {
      when (done) {
        valid := valid & ~UIntToOH(slot, Slots)
        state := State.next
      }
    }
    is (State.readSize) {
      when (done) {
        sizeWord := io.mem.dataRead
        state := State.readMagic
      }
    }
    is (State.readMagic) {
      when (done) {
        val sizeWords = sizeWord(17, 0)
        val ok = io.mem.dataRead === Magic.U && sizeWords >= 4.U
        valid := Mux(ok, valid | UIntToOH(slot, Slots), valid & ~UIntToOH(slot, Slots))
        state := State.next
      }
    }
    is (State.next) {
      when (slot === (Slots - 1).U) {
        state := State.idle
      } .otherwise {
        slot := slot + 1.U
        state := State.decide
      }
    }
  }
}
