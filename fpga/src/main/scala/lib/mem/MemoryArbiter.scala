package lib.mem

import chisel3._
import chisel3.util._

/**
 * Allows multiple initiator MemoryInterfaces to map to one target MemoryInterface.
 * Priority is given to the lowest initiator.
 *
 * The owner keeps the target in the cycle its access completes (`done`) if
 * it presents a different request in that cycle (a target such as
 * [[lib.mem.sram.AsyncSramController]] accepts that request right
 * away); an identical request is taken to be the completed one still held,
 * and the target is released.
 *
 * With `fair`, initiators are served round-robin (the one after the last
 * owner first), and in a `done` cycle any initiator's request that differs
 * from the completed access may take the target right away, without an idle
 * cycle in between. An identical one has to wait for that cycle, since the
 * target would take it for the completed access still held. For initiators
 * that need bounded latency: with fixed priority and the idle cycle, the
 * SNES S-CPU's WRAM traffic held up every BW-RAM read of the SA-1 on the
 * same SRAM.
 */
class MemoryArbiter(addressWidth: Int, dataWidth: Int, n: Int, fair: Boolean = false) extends Module {
  val io = IO(new Bundle {
    val target = Flipped(new MemoryInterface(addressWidth, dataWidth))
    val initiator = Vec(n, new MemoryInterface(addressWidth, dataWidth))
  })

  io.target.enable := false.B
  io.target.address := DontCare
  io.target.write := DontCare
  io.target.dataWrite := DontCare
  io.target.writeStrobe := DontCare

  /// Whether we're currently waiting for an access to complete.
  val busy = RegInit(false.B)
  /// If busy, the initiator who initiated the access.
  val busyOwner = Reg(Vec(n, Bool()))
  /// The request that was granted (to tell a new back-to-back request from the old one held).
  val busyAddress = Reg(UInt(addressWidth.W))
  val busyWrite = Reg(Bool())
  val busyDataWrite = Reg(UInt(dataWidth.W))
  val busyStrobe = Reg(UInt((dataWidth / 8).W))
  /// Index of the last initiator granted (round-robin origin).
  val lastOwner = RegInit(0.U(log2Ceil(n).max(1).W))

  /// Vector of which input ports have a memory request.
  val requestsVec = Wire(Vec(n, Bool()))
  /// Which input port is chosen for the next request.
  val requestChosen = Wire(Vec(n, Bool()))
  /// Which initiator's request is driven to the target this cycle.
  val driver = Wire(Vec(n, Bool()))

  for ((initiator, i) <- io.initiator.zipWithIndex) {
    initiator.done := false.B
    initiator.dataRead := io.target.dataRead
    requestsVec(i) := initiator.enable

    when (driver(i)) {
      io.target.enable := initiator.enable
      io.target.address := initiator.address
      io.target.write := initiator.write
      io.target.dataWrite := initiator.dataWrite
      io.target.writeStrobe := initiator.writeStrobe
    }
    when (busy && busyOwner(i)) {
      initiator.done := io.target.done
    }
  }

  /** Pick the first candidate after `lastOwner`, cyclically. */
  def roundRobin(candidates: Seq[Bool]): Vec[Bool] = {
    val chosen = Wire(Vec(n, Bool()))
    chosen.foreach(_ := false.B)
    var taken = false.B
    for (d <- 1 to n) {
      val sum = lastOwner +& d.U
      val idx = Mux(sum >= n.U, sum - n.U, sum)(log2Ceil(n).max(1) - 1, 0)
      val hit = candidates.zipWithIndex.map { case (c, i) => c && idx === i.U }.reduce(_ || _)
      when (!taken && hit) { for (i <- 0 until n) chosen(i) := idx === i.U }
      taken = taken || hit
    }
    chosen
  }

  // Choose the next request.
  val isRequesting = WireDefault(requestsVec.asUInt.orR)
  requestChosen := (if (fair) roundRobin(requestsVec) else PriorityEncoderOH(requestsVec))

  /** Whether `request` is an access other than the completed one still held. */
  def differs(request: MemoryInterface): Bool = request.enable && (
    request.address =/= busyAddress || request.write =/= busyWrite ||
    (request.write && (request.dataWrite =/= busyDataWrite || request.writeStrobe =/= busyStrobe)))
  val ownerRequest = Mux1H(busyOwner, io.initiator)
  val ownerDiffers = differs(ownerRequest)
  /// In the done cycle: who may take the target right away.
  val doneCandidates = VecInit(io.initiator.map(differs))
  val doneChosen: Vec[Bool] = if (fair) roundRobin(doneCandidates) else VecInit(busyOwner.map(_ && ownerDiffers))
  val doneAny = doneChosen.asUInt.orR
  driver := Mux(busy && io.target.done, doneChosen, Mux(busy, busyOwner, requestChosen))

  def grant(chosen: Vec[Bool]): Unit = {
    val request = Mux1H(chosen, io.initiator)
    busyOwner := chosen
    lastOwner := OHToUInt(chosen)
    busyAddress := request.address
    busyWrite := request.write
    busyDataWrite := request.dataWrite
    busyStrobe := request.writeStrobe
  }

  when (busy) {
    when (io.target.done) {
      when (doneAny) {
        grant(doneChosen)
      } .otherwise {
        busy := false.B
      }
    }
  } .elsewhen (isRequesting) {
    busy := true.B
    grant(requestChosen)
  }
}
