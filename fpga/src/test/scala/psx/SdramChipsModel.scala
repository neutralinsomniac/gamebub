package psx

import scala.collection.mutable

/**
 * A cycle-level model of SDR SDRAM chips sharing a bus, for tests of
 * [[SdramEngine]]: call `cycle` once per controller clock cycle with the pins
 * as the engine's output registers show them (commands are visible in the
 * cycle after the engine decides them); it returns the data bus value for
 * that cycle (read beats two cycles after the READ, so that the engine's
 * input register takes the first in the fourth cycle after its decision, as
 * with `captureLatency` 4 on the board). It records violations of the
 * command timing (tRCD, tRP, tRAS, tRRD, tRC, tWR, tRFC, the refresh
 * interval, bursts cut short) and of the data bus (the chips and the FPGA
 * driving it at once) in `errors`, and keeps the memory contents.
 */
/** The chips, seen at the pins: commands are visible in the cycle after the engine decides them. */
class SdramChipsModel(config: SdramEngine.Config, chips: Int) {
  val Never = -1000000L
  val errors = mutable.ArrayBuffer[String]()
  def error(cycle: Long, s: String): Unit = if (errors.size < 20) errors += s"cycle $cycle: $s"

  val memory = mutable.Map[(Int, Int, Int, Int), Int]()
  val bankOpen = Array.fill(chips, 4)(false)
  val bankRow = Array.fill(chips, 4)(0)
  val lastActive = Array.fill(chips, 4)(Never)
  val lastPrecharge = Array.fill(chips, 4)(Never)
  val lastWriteBeat = Array.fill(chips, 4)(Never)
  val lastChipActive = Array.fill(chips)(Never)
  val lastRefresh = Array.fill(chips)(Never)
  val lastBurst = Array.fill(chips)(Never)
  var refreshes = Array.fill(chips)(0)
  var modeSet = Array.fill(chips)(false)
  var initRefreshes = Array.fill(chips)(0)
  var maxRefreshGap = 0L

  /** Read beats to drive: cycle -> data. */
  val readBeats = mutable.Map[Long, Int]()
  /** Cycles in which a chip drives the bus (beats plus a turnaround cycle). */
  val chipDrives = mutable.Set[Long]()
  /** A write burst in progress: (chip, bank, row, column of the second beat, cycle of the second beat). */
  var pendingSecondBeat: Option[(Int, Int, Int, Int, Long)] = None

  def writeBeat(chip: Int, bank: Int, row: Int, column: Int, data: Int, dqm: Int): Unit = {
    val key = (chip, bank, row, column)
    val old = memory.getOrElse(key, 0)
    val lo = if ((dqm & 1) == 0) data & 0xFF else old & 0xFF
    val hi = if ((dqm & 2) == 0) data & 0xFF00 else old & 0xFF00
    memory(key) = hi | lo
  }

  /** One cycle: the pins as the engine's output registers show them. Returns dqIn for this cycle. */
  def cycle(n: Long, csN: Int, ras: Int, cas: Int, we: Int, bank: Int, address: Int,
            dqm: Int, dqOut: Int, dqOe: Int): Int = {
    if (dqOe != 0 && dqOe != 0xFFFF) error(n, f"partial output enable $dqOe%04x")
    if (dqOe != 0 && chipDrives.contains(n)) error(n, "bus conflict (FPGA and chip drive)")

    pendingSecondBeat.foreach { case (c, b, row, col, when) =>
      if (when == n) {
        if (dqOe == 0) error(n, "second write beat not driven")
        writeBeat(c, b, row, col, dqOut, dqm)
        lastWriteBeat(c)(b) = n
        pendingSecondBeat = None
      }
    }

    val cmd = (ras << 2) | (cas << 1) | we
    for (c <- 0 until chips if ((csN >> c) & 1) == 0) {
      cmd match {
        case 0x7 => // NOP
        case 0x3 => // ACTIVE
          if (!modeSet(c)) error(n, s"chip $c: ACTIVE before mode set")
          if (bankOpen(c)(bank)) error(n, s"chip $c bank $bank: ACTIVE to open bank")
          if (n - lastPrecharge(c)(bank) < config.rp) error(n, s"chip $c bank $bank: tRP")
          if (n - lastActive(c)(bank) < config.ras + config.rp) error(n, s"chip $c bank $bank: tRC")
          if (n - lastChipActive(c) < config.rrd) error(n, s"chip $c: tRRD")
          if (n - lastRefresh(c) < config.rfc) error(n, s"chip $c: tRFC before ACTIVE")
          bankOpen(c)(bank) = true
          bankRow(c)(bank) = address
          lastActive(c)(bank) = n
          lastChipActive(c) = n
        case 0x5 | 0x4 => // READ / WRITE
          val write = cmd == 0x4
          if (!bankOpen(c)(bank)) error(n, s"chip $c bank $bank: READ/WRITE to closed bank")
          if (n - lastActive(c)(bank) < config.rcd) error(n, s"chip $c bank $bank: tRCD")
          if ((address & (1 << 10)) != 0) error(n, "auto precharge")
          val column = address & 0x1FF
          if ((column & 1) != 0) error(n, "odd column")
          if (n - lastBurst(c) < 2) error(n, s"chip $c: burst cut short")
          lastBurst(c) = n
          val row = bankRow(c)(bank)
          if (write) {
            if (dqOe == 0) error(n, "first write beat not driven")
            writeBeat(c, bank, row, column, dqOut, dqm)
            pendingSecondBeat = Some((c, bank, row, column + 1, n + 1))
          } else {
            readBeats(n + 2) = memory.getOrElse((c, bank, row, column), 0)
            readBeats(n + 3) = memory.getOrElse((c, bank, row, column + 1), 0)
            chipDrives ++= Seq(n + 2, n + 3, n + 4)
          }
        case 0x2 => // PRECHARGE
          val targets = if ((address & (1 << 10)) != 0) 0 until 4 else Seq(bank)
          for (b <- targets if bankOpen(c)(b)) {
            if (n - lastActive(c)(b) < config.ras) error(n, s"chip $c bank $b: tRAS")
            if (n - lastWriteBeat(c)(b) <= config.wr) error(n, s"chip $c bank $b: tWR")
            if (pendingSecondBeat.exists(p => p._1 == c && p._2 == b)) error(n, "precharge during write burst")
          }
          for (b <- targets) {
            bankOpen(c)(b) = false
            lastPrecharge(c)(b) = n
          }
        case 0x1 => // AUTO REFRESH
          if (bankOpen(c).exists(identity)) error(n, s"chip $c: refresh with open banks")
          if ((0 until 4).exists(b => n - lastPrecharge(c)(b) < config.rp)) error(n, s"chip $c: tRP before refresh")
          if (n - lastRefresh(c) < config.rfc) error(n, s"chip $c: tRFC")
          if (modeSet(c)) {
            if (lastRefresh(c) > 0) maxRefreshGap = maxRefreshGap.max(n - lastRefresh(c))
          } else {
            initRefreshes(c) += 1
          }
          lastRefresh(c) = n
          refreshes(c) += 1
        case 0x0 => // MODE
          if (bankOpen(c).exists(identity)) error(n, s"chip $c: mode with open banks")
          if (initRefreshes(c) < 8) error(n, s"chip $c: mode before 8 refreshes")
          if (address != config.mode) error(n, f"chip $c: mode $address%03x")
          if (n - lastRefresh(c) < config.rfc) error(n, s"chip $c: tRFC before mode")
          modeSet(c) = true
        case x => error(n, s"chip $c: unexpected command $x")
      }
    }
    if (modeSet.forall(identity)) {
      for (c <- 0 until chips if n - lastRefresh(c) > config.refi + 64) {
        error(n, s"chip $c: refresh overdue")
        lastRefresh(c) = n
      }
    }
    chipDrives -= n
    readBeats.remove(n).getOrElse(0xDEAD)
  }
}
