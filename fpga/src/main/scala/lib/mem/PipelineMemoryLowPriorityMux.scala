package lib.mem

import chisel3._

/**
 * Shares one PipelineMemoryInterface between a main initiator and a side
 * initiator without adding latency to the main one.
 *
 * `main` is wired straight through: its requests and data phases see the
 * target's `ready` unchanged whenever `side` does not own the bus. `side`
 * only gets the bus when `main` has no data phase outstanding, keeps it for
 * its whole transaction (address phase and data phase), and `main` is held
 * off (`ready` low) meanwhile. `main`'s `ready` must not depend on its own
 * `enable` (initiators such as LineReadCache issue when they see `ready`),
 * so a `main` request in the very cycle `side` issues waits one cycle.
 * Intended for an occasional background port next to a latency-critical
 * one, e.g. the SNES save-state port next to the ROM cache.
 *
 * The side initiator must not pipeline: it presents at most one transaction
 * at a time and waits for its data phase to complete before the next request.
 */
class PipelineMemoryLowPriorityMux(addressWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val target = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
    val main = new PipelineMemoryInterface(addressWidth, dataWidth)
    val side = new PipelineMemoryInterface(addressWidth, dataWidth)
  })

  /** `main` has an accepted request whose data phase has not completed. */
  val mainOutstanding = RegInit(false.B)
  /** `side` has an accepted request whose data phase has not completed. */
  val sideOutstanding = RegInit(false.B)

  /** `side` presents its address phase this cycle. */
  val sideIssue = io.side.enable && !mainOutstanding && !sideOutstanding
  val sideOwns = sideIssue || sideOutstanding

  io.target.enable := Mux(sideOwns, sideIssue, io.main.enable)
  io.target.address := Mux(sideOwns, io.side.address, io.main.address)
  io.target.isWrite := Mux(sideOwns, io.side.isWrite, io.main.isWrite)
  io.target.writeStrobe := Mux(sideOwns, io.side.writeStrobe, io.main.writeStrobe)
  io.target.dataWrite := Mux(sideOwns, io.side.dataWrite, io.main.dataWrite)

  io.main.ready := io.target.ready && !sideOwns
  io.main.dataRead := io.target.dataRead
  io.side.ready := io.target.ready && sideOwns
  io.side.dataRead := io.target.dataRead

  when (io.target.ready) {
    when (sideOwns) {
      // An accepted side request starts its data phase; a completing one ends it.
      sideOutstanding := sideIssue
    } .otherwise {
      // Likewise for main, which may pipeline a new request into the
      // completing data phase.
      mainOutstanding := io.main.enable
    }
  }
}
