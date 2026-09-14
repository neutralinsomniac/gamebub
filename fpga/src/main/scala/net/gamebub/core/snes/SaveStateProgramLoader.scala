package net.gamebub.core.snes

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface

object SaveStateProgramLoader {
  /** ROM address of the MiSTer save-state program (the core's `savestates_map` runs it from there). */
  val ProgramAddress = 0xFF0000
  /** The largest ROM size code that leaves the program's address free (8 MiB). */
  val MaxRomSizeCode = 13

  /** The program (`fpga/verilog/snes/savestates/savestates.bin`, linked into the resources), as little-endian words. */
  lazy val programWords: Seq[Long] = {
    val stream = getClass.getClassLoader.getResourceAsStream("snes_savestates.bin")
    require(stream != null, "snes_savestates.bin is missing from the resources")
    val bytes = try stream.readAllBytes() finally stream.close()
    bytes.map(_ & 0xFF).padTo((bytes.length + 3) / 4 * 4, 0).grouped(4).map { b =>
      b(0).toLong | (b(1).toLong << 8) | (b(2).toLong << 16) | (b(3).toLong << 24)
    }.toSeq
  }
}

/**
 * Writes the MiSTer 65816 save-state program to the SDRAM above the ROM,
 * at ROM address 0xFF0000, where the core runs it from. The program
 * (3.5 KiB) is a ROM in the glue, written one word at a
 * time through `mem` (the pipelined SDRAM port's protocol: address phase,
 * then the data phase with the write data) on `start`; `busy` is high
 * until the last word's data phase. The caller must not start it when the
 * padded ROM reaches the program's address (size code above
 * `MaxRomSizeCode`): save states are unavailable then.
 */
class SaveStateProgramLoader extends Module {
  import SaveStateProgramLoader._

  val io = IO(new Bundle {
    val start = Input(Bool())
    val busy = Output(Bool())
    val mem = Flipped(new PipelineMemoryInterface(addressWidth = 25, dataWidth = 32))
  })

  val Words = programWords.length
  val program = VecInit(programWords.map(_.U(32.W)))

  val busy = RegInit(false.B)
  val index = Reg(UInt(log2Ceil(Words + 1).W))
  val dataPhase = RegInit(false.B)

  io.busy := busy
  io.mem.enable := busy && !dataPhase
  io.mem.address := ProgramAddress.U(25.W) + (index << 2)
  io.mem.isWrite := true.B
  io.mem.writeStrobe := "b1111".U
  io.mem.dataWrite := program(index)

  when (!busy) {
    when (io.start) {
      busy := true.B
      index := 0.U
      dataPhase := false.B
    }
  } .elsewhen (io.mem.ready) {
    when (!dataPhase) {
      dataPhase := true.B
    } .otherwise {
      dataPhase := false.B
      index := index + 1.U
      when (index === (Words - 1).U) {
        busy := false.B
      }
    }
  }
}
