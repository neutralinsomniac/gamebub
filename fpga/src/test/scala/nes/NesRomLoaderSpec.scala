package nes

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec

import scala.collection.mutable

/**
 * Verifies NesRomLoader: RAM clear, iNES/NSF/FDS magic auto-detection,
 * GameLoader byte streaming, FDS BIOS copy, and 2 KiB EEPROM load/save.
 */
class NesRomLoaderSpec extends AnyFreeSpec {
  private def runWithMemory(initWords: Map[Long, Long] = Map.empty)(body: (NesRomLoader, mutable.Map[Long, Long], Array[Int]) => Unit): Unit = {
    val sdram = mutable.Map[Long, Long]() ++ initWords
    val eeprom = Array.fill(2048)(0)

    simulate(new NesRomLoader) { dut =>
      dut.reset.poke(true.B)
      dut.io.start.poke(false.B)
      dut.io.op.poke(NesRomLoader.Op.none)
      dut.io.romSize.poke(0.U)
      dut.io.biosLoaded.poke(false.B)
      dut.io.mem.done.poke(false.B)
      dut.io.mem.dataRead.poke(0.U)
      dut.io.loaderMemAddr.poke(0.U)
      dut.io.loaderMemWrite.poke(false.B)
      dut.io.loaderDone.poke(false.B)
      dut.io.loaderError.poke(false.B)
      dut.io.ch0Busy.poke(false.B)
      dut.io.eepromDout.poke(0.U)
      dut.clock.step(2)
      dut.reset.poke(false.B)

      body(dut, sdram, eeprom)
    }
  }

  private def serviceCycle(
    dut: NesRomLoader,
    sdram: mutable.Map[Long, Long],
    eeprom: Array[Int],
    streamed: mutable.ArrayBuffer[Int],
  ): Unit = {
    // 1-cycle memory response
    if (dut.io.mem.enable.peek().litToBoolean) {
      val addr = dut.io.mem.address.peek().litValue.toLong & ~3L
      if (dut.io.mem.write.peek().litToBoolean) {
        sdram(addr) = dut.io.mem.dataWrite.peek().litValue.toLong & 0xFFFFFFFFL
      } else {
        dut.io.mem.dataRead.poke(sdram.getOrElse(addr, 0L).U)
      }
      dut.io.mem.done.poke(true.B)
    } else {
      dut.io.mem.done.poke(false.B)
    }

    // EEPROM BRAM model (synchronous 1-cycle read, write on rising edge)
    val eAddr = dut.io.eepromAddr.peek().litValue.toInt
    val eWr = dut.io.eepromWren.peek().litToBoolean
    val eDin = dut.io.eepromDin.peek().litValue.toInt

    // Capture GameLoader bytes
    if (dut.io.loaderIndataClk.peek().litToBoolean) {
      streamed += dut.io.loaderIndata.peek().litValue.toInt
    }

    dut.clock.step()
    if (eWr) {
      eeprom(eAddr) = eDin
    }
    dut.io.eepromDout.poke(eeprom(eAddr).U)
  }

  "detects iNES magic, clears RAM, and streams ROM bytes to GameLoader" in {
    // Put a 20-byte fake .nes file at StagingBase (0x040_0000)
    val romBytes = Seq(
      0x4E, 0x45, 0x53, 0x1A, // "NES\x1A"
      0x01, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00,
      0x00, 0x00, 0x00, 0x00,
      0xAA, 0xBB, 0xCC, 0xDD,
    )
    val init = mutable.Map[Long, Long]()
    // Dirty CPU-RAM and CHR-VRAM that must be cleared
    init(NesRomLoader.CpuRamBase) = 0xDEADBEEFL
    init(NesRomLoader.VramBase) = 0x12345678L
    for (i <- romBytes.indices by 4) {
      val w = romBytes(i).toLong | (romBytes(i + 1).toLong << 8) | (romBytes(i + 2).toLong << 16) | (romBytes(i + 3).toLong << 24)
      init(NesRomLoader.StagingBase + i) = w
    }

    runWithMemory(init.toMap) { (dut, sdram, eeprom) =>
      val streamed = mutable.ArrayBuffer[Int]()
      dut.io.start.poke(true.B)
      dut.io.op.poke(NesRomLoader.Op.loadRom)
      dut.io.romSize.poke(romBytes.length.U)
      dut.clock.step()
      dut.io.start.poke(false.B)

      var cycles = 0
      var sawReset = false
      while (dut.io.busy.peek().litToBoolean && cycles < 300000) {
        if (dut.io.loaderReset.peek().litToBoolean) {
          sawReset = true
          assert(dut.io.loaderFiletype.peek().litValue == 2, "expected type_nes (bit 1)")
        }
        // Once all bytes are streamed and downloading drops, assert loaderDone
        if (streamed.length == romBytes.length && !dut.io.loaderDownloading.peek().litToBoolean) {
          dut.io.loaderDone.poke(true.B)
        }
        serviceCycle(dut, sdram, eeprom, streamed)
        cycles += 1
      }
      assert(!dut.io.busy.peek().litToBoolean, "loader did not finish")
      assert(sawReset, "GameLoader was not reset")
      assert(streamed.toSeq == romBytes, s"streamed bytes mismatch: $streamed")
      assert(sdram(NesRomLoader.CpuRamBase) == 0L, "CPU-RAM was not cleared")
      assert(sdram(NesRomLoader.VramBase) == 0L, "CHR-VRAM was not cleared")
      assert(!dut.io.isFds.peek().litToBoolean)
      assert(!dut.io.isNsf.peek().litToBoolean)
    }
  }

  "copies FDS BIOS and round-trips 2 KiB EEPROM" in {
    val init = mutable.Map[Long, Long]()
    for (i <- 0 until NesRomLoader.FdsBiosSize by 4) {
      init(NesRomLoader.FdsBiosStageBase + i) = (0xA0000000L + i)
    }
    for (i <- 0 until NesRomLoader.EepromSize by 4) {
      init(NesRomLoader.CartRamBase + i) = (0x55000000L + i)
    }

    runWithMemory(init.toMap) { (dut, sdram, eeprom) =>
      val streamed = mutable.ArrayBuffer[Int]()

      // 1. Copy FDS BIOS
      dut.io.start.poke(true.B)
      dut.io.op.poke(NesRomLoader.Op.copyFdsBios)
      dut.clock.step()
      dut.io.start.poke(false.B)
      var c = 0
      while (dut.io.busy.peek().litToBoolean && c < 20000) {
        serviceCycle(dut, sdram, eeprom, streamed); c += 1
      }
      assert(!dut.io.busy.peek().litToBoolean)
      assert(sdram(NesRomLoader.FdsBiosDestBase) == 0xA0000000L)
      assert(sdram(NesRomLoader.FdsBiosDestBase + 0x1FFC) == 0xA0001FFCL)

      // 2. Load EEPROM from SDRAM -> BRAM
      dut.io.start.poke(true.B)
      dut.io.op.poke(NesRomLoader.Op.loadEeprom)
      dut.clock.step()
      dut.io.start.poke(false.B)
      c = 0
      while (dut.io.busy.peek().litToBoolean && c < 20000) {
        serviceCycle(dut, sdram, eeprom, streamed); c += 1
      }
      assert(!dut.io.busy.peek().litToBoolean)
      assert(eeprom(0) == 0x00 && eeprom(3) == 0x55)
      assert(eeprom(4) == 0x04 && eeprom(7) == 0x55)

      // Mutate EEPROM and save back to SDRAM
      eeprom(0) = 0x77
      eeprom(1) = 0x88
      eeprom(2) = 0x99
      eeprom(3) = 0xAA
      sdram(NesRomLoader.CartRamBase) = 0L

      dut.io.start.poke(true.B)
      dut.io.op.poke(NesRomLoader.Op.saveEeprom)
      dut.clock.step()
      dut.io.start.poke(false.B)
      c = 0
      while (dut.io.busy.peek().litToBoolean && c < 20000) {
        serviceCycle(dut, sdram, eeprom, streamed); c += 1
      }
      assert(!dut.io.busy.peek().litToBoolean)
      assert(sdram(NesRomLoader.CartRamBase) == 0xAA998877L)
    }
  }
}
