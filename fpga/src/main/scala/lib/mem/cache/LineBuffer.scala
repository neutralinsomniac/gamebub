package lib.mem.cache

import chisel3._
import chisel3.util._
import lib.mem.PipelineMemoryInterface

/**
 * A few whole lines held in registers in front of a line cache, with a
 * combinational lookup: a request that hits answers in the same cycle.
 *
 * The cache behind it ([[LineReadCache]]) needs a cycle even for a hit,
 * which a core that expects single-cycle ROM has to be stalled for. Every
 * response from the cache installs the whole line here (`outLine`), so a
 * sequential stream only pays that cycle once per line; and whenever a line
 * is used, the following line is pulled from the cache in the background
 * (if the cache has it, it arrives before the stream gets there), so a
 * sequential stream does not stall at all.
 *
 * Demand requests are combinational (`request` / `address` in, `hit` /
 * `dataRead` out in the same cycle); a miss raises `pending` until the data
 * arrives with a `respValid` pulse: either with the cache's response (the
 * whole line, which is installed here) or earlier, when the cache shows the
 * missed word on `early*` as it arrives from memory during a line fill. A
 * request for a line or word arriving in that very cycle is a hit. A new
 * request supersedes an older miss: `hit` and `respValid` never coincide,
 * and a miss still outstanding when the next request comes is dropped
 * (its line is still installed when it arrives). One request at a time on
 * `out`.
 *
 * Addresses are byte addresses of 32-bit words.
 */
class LineBuffer(addressWidth: Int, numLines: Int, wordsPerLine: Int, prefetch: Boolean = true) extends Module {
  require(isPow2(wordsPerLine))
  val io = IO(new Bundle {
    val request = Input(Bool())
    val address = Input(UInt(addressWidth.W))
    /** Same cycle as `request`: the data is in `dataRead` now. */
    val hit = Output(Bool())
    val dataRead = Output(UInt(32.W))
    /** A missed request is outstanding. */
    val pending = Output(Bool())
    /** Pulse: the missed request's data is in `dataRead` this cycle. */
    val respValid = Output(Bool())

    val out = Flipped(new PipelineMemoryInterface(addressWidth, 32))
    /** The whole line of `out.dataRead`, from the cache. */
    val outLine = Input(Vec(wordsPerLine, UInt(32.W)))
    /** A word arriving in the cache from memory this cycle (`LineReadCache.fillWord*`). */
    val earlyValid = Input(Bool())
    val earlyAddress = Input(UInt(addressWidth.W))
    val earlyData = Input(UInt(32.W))
    /** Pulses when a background line pull is issued (statistics). */
    val prefetch = Output(Bool())
  })

  val wordSelBits = log2Ceil(wordsPerLine)
  val lineBits = 2 + wordSelBits
  val lineBytes = wordsPerLine * 4
  private def lineOf(address: UInt): UInt = address(addressWidth - 1, lineBits)
  private def wordOf(address: UInt): UInt = address(lineBits - 1, 2)

  val valid = RegInit(VecInit(Seq.fill(numLines)(false.B)))
  val tags = Reg(Vec(numLines, UInt((addressWidth - lineBits).W)))
  val lines = Reg(Vec(numLines, Vec(wordsPerLine, UInt(32.W))))
  val replace = RegInit(0.U(log2Ceil(numLines).max(1).W))

  private def lookup(address: UInt): (Bool, UInt) = {
    val hits = VecInit((0 until numLines).map(i => valid(i) && tags(i) === lineOf(address)))
    (hits.asUInt.orR, Mux1H(hits, lines.map(_(wordOf(address)))))
  }
  private def present(line: UInt): Bool =
    VecInit((0 until numLines).map(i => valid(i) && tags(i) === line)).asUInt.orR

  // Demand side
  val pending = RegInit(false.B)
  val missAddress = Reg(UInt(addressWidth.W))
  val demandIssued = RegInit(false.B) // the miss is the request in flight on `out`
  // One request in flight on `out`
  val outBusy = RegInit(false.B)
  val outIsDemand = Reg(Bool())
  val outAddress = Reg(UInt(addressWidth.W))
  val respNow = outBusy && io.out.ready
  val canIssue = !outBusy || respNow

  // A request hits in the buffer, in the line arriving from the cache this
  // cycle, or on the word arriving from memory this cycle.
  val (bufHit, bufData) = lookup(io.address)
  val respHit = respNow && lineOf(outAddress) === lineOf(io.address)
  val earlyHit = io.earlyValid && io.earlyAddress(addressWidth - 1, 2) === io.address(addressWidth - 1, 2)
  val reqHit = bufHit || respHit || earlyHit
  val reqData = Mux(bufHit, bufData, Mux(respHit, io.outLine(wordOf(io.address)), io.earlyData))
  io.hit := io.request && reqHit
  io.dataRead := reqData
  io.pending := pending
  io.respValid := false.B
  io.prefetch := false.B

  io.out.enable := false.B
  io.out.address := DontCare
  io.out.isWrite := false.B
  io.out.writeStrobe := DontCare
  io.out.dataWrite := DontCare

  // Next-line pull
  val pullWanted = RegInit(false.B)
  val pullIssued = WireDefault(false.B) // a pull was issued this cycle (a demand may override it)
  val pullLine = Reg(UInt((addressWidth - lineBits).W))
  private def touch(line: UInt): Unit = if (prefetch) {
    pullWanted := true.B
    pullLine := line + 1.U
  }

  // Install the line that arrives from the cache.
  when (respNow) {
    outBusy := false.B
    when (!present(lineOf(outAddress))) {
      valid(replace) := true.B
      tags(replace) := lineOf(outAddress)
      lines(replace) := io.outLine
      replace := replace + 1.U
    }
  }

  // A pending miss: answered by an arriving line, by the buffer (a pull
  // that landed), or issued to the cache.
  val (pendHit, pendData) = lookup(missAddress)
  val respIsMiss = respNow && lineOf(outAddress) === lineOf(missAddress)
  val earlyIsMiss = io.earlyValid && io.earlyAddress(addressWidth - 1, 2) === missAddress(addressWidth - 1, 2)
  // A new request supersedes the pending miss (see below): its answer, if it
  // came this cycle, is not reported.
  when (pending && !io.request) {
    when (respIsMiss) {
      io.respValid := true.B
      io.dataRead := io.outLine(wordOf(missAddress))
      pending := false.B
      demandIssued := false.B
      touch(lineOf(missAddress))
    } .elsewhen (earlyIsMiss) {
      // The missed word arrives from memory now; the cache's response (and
      // the line's installation) follows once the whole line is in.
      io.respValid := true.B
      io.dataRead := io.earlyData
      pending := false.B
      demandIssued := false.B
      touch(lineOf(missAddress))
    } .elsewhen (pendHit && !demandIssued) {
      io.respValid := true.B
      io.dataRead := pendData
      pending := false.B
      touch(lineOf(missAddress))
    } .elsewhen (!demandIssued && canIssue) {
      io.out.enable := true.B
      io.out.address := Cat(lineOf(missAddress), 0.U(lineBits.W))
      demandIssued := true.B
      outBusy := true.B
      outIsDemand := true.B
      outAddress := missAddress
    }
  } .elsewhen (pullWanted && canIssue) {
    pullWanted := false.B
    pullIssued := true.B
    when (!present(pullLine)) {
      io.prefetch := true.B
      io.out.enable := true.B
      io.out.address := Cat(pullLine, 0.U(lineBits.W))
      outBusy := true.B
      outIsDemand := false.B
      outAddress := Cat(pullLine, 0.U(lineBits.W))
    }
  }

  // New request (takes priority for the state it sets); an older miss is
  // superseded whether this one hits or misses (a demand issued for it
  // still completes and installs its line). A miss is issued to the cache
  // in the request cycle itself when the port is free (a cache hit then
  // answers in the next cycle, which is all the slack some cores have);
  // otherwise it is issued from `pending` above.
  when (io.request) {
    when (reqHit) {
      pending := false.B
      touch(lineOf(io.address))
    } .otherwise {
      pending := true.B
      missAddress := io.address
      when (canIssue) {
        io.out.enable := true.B
        io.out.address := Cat(lineOf(io.address), 0.U(lineBits.W))
        io.prefetch := false.B
        when (pullIssued) { pullWanted := true.B } // the overridden pull stays wanted
        outBusy := true.B
        outIsDemand := true.B
        outAddress := io.address
        demandIssued := true.B
      } .otherwise {
        demandIssued := false.B
      }
    }
  }
}
