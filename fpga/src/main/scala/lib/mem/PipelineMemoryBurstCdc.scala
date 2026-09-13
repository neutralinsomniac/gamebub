package lib.mem

import chisel3._
import chisel3.util.Cat
import lib.fifo.SimpleFwftFifo
import lib.log.Logger

/**
 * Utility to allow a memory initiator in a slow clock domain to
 * access a target in a fast clock domain.
 * e.g. a system interfacing with a faster SDRAM controller.
 *
 * Additionally, it optimizes for burst accesses, by continuing
 * reads from the next address in the fast clock domain so that the next
 * word is ready in the slow domain.
 *
 * Must be instantiated with the faster clock, and the fast clock must be
 * an integer multiple of the slow clock.
 *
 * Note: doesn't use or propagate write byte strobe
 */
class PipelineMemoryBurstCdc(
  addressWidth: Int,
  dataWidth: Int,
  addressBurstIncrement: Int = 1,
  /// Set to false to disable read prefetch (helps for highly random accesses)
  enablePrefetch: Boolean = true,
) extends Module {
  val io = IO(new Bundle {
    val slowClock = Input(Clock())
    val initiator = new PipelineMemoryInterface(addressWidth, dataWidth)
    val target = Flipped(new PipelineMemoryInterface(addressWidth, dataWidth))
  })
  private val logger = Logger("memcdc")

  val sync = Wire(Bool())

  // Slow -> Fast request fifo
  private val requestFifo = withClock (io.slowClock) {
    Module(new SimpleFwftFifo(new RequestEntry, 4))
  }
  requestFifo.io.read.pop := false.B
  requestFifo.io.write.push := false.B
  requestFifo.io.write.data := DontCare

  // Skid buffer with response
  // Carries the completion signal (and data) from the fast -> slow clock domain
  val regSkidComplete = RegInit(false.B)
  val regSkidAddress = Reg(UInt(addressWidth.W))
  val regSkidDataRead = Reg(UInt(dataWidth.W))
  val regSkidIsWrite = Reg(Bool())
  val regSkidIsPrefetch = Reg(Bool())
  // Whether a request is completing in the fast domain
  val skidComplete = WireDefault(regSkidComplete)
  val skidAddress = WireDefault(regSkidAddress)
  val skidDataRead = WireDefault(regSkidDataRead)
  val skidIsWrite = WireDefault(regSkidIsWrite)
  val skidIsPrefetch = WireDefault(regSkidIsPrefetch)

  // Whether there's space in the response fifo at the end of this cycle.
  val responseCanAccept = Wire(Bool())
  val responseBufferGood = Wire(Bool())
  /** Slow-domain state for tests: busy, busyIsWrite, responsePresent, fifoEmpty, fifoFull; and the busy / response addresses. */
  val slowDebug = Wire(UInt(5.W))
  val slowDebugBusyAddress = Wire(UInt(addressWidth.W))
  val slowDebugResponseAddress = Wire(UInt(addressWidth.W))

  // Portion in the slow clock domain
  withClock (io.slowClock) {
    logger.info("======= slow")
    // Sync signal in the slow domain so that the fast can tell when a clock has happened.
    val regSync = RegInit(false.B)
    regSync := !regSync
    sync := regSync

    // Prefetched response (fast -> slow read response fifo, size 1)
    val regResponsePresent = RegInit(false.B)
    val regResponseAddress = Reg(UInt(addressWidth.W))
    val regResponseDataRead = Reg(UInt(dataWidth.W))
    responseCanAccept := !regResponsePresent

    // Whether we're busy with a request
    val regBusy = RegInit(false.B)
    val regBusyIsWrite = Reg(Bool())
    // Address we're busy with:
    //   for a write, used to hold the address until the data is available
    //   for a read, used to compare with the response fifo
    val regBusyAddress = Reg(UInt(addressWidth.W))
    val regBusyFifoDelayed = Reg(Bool())
    responseBufferGood := regResponsePresent && (regResponseAddress === regBusyAddress)
    slowDebug := Cat(regBusy, regBusyIsWrite, regResponsePresent, requestFifo.io.read.empty, requestFifo.io.write.full)
    slowDebugBusyAddress := regBusyAddress
    slowDebugResponseAddress := regResponseAddress

    /// Whether the request we're about to push should be suppressed, because
    /// we're getting the result back at the end of this cycle.
    val suppressRequestPush = WireDefault(false.B)
    /// A write has been pushed while a prefetch may be in flight ahead of
    /// it: the prefetch reads the memory before the write reaches it, so
    /// its response is stale (it may be the written word) and is dropped.
    val regDiscardPrefetch = RegInit(false.B)
    val skidStale = skidComplete && !skidIsWrite && skidIsPrefetch && regDiscardPrefetch
    when (skidComplete) {
      regDiscardPrefetch := false.B
    }
    when (skidStale) {
      logger.info(cf"slow: dropping stale prefetch: addr=0x${skidAddress}%x")
    }

    // A request pushed while the fifo is full is lost (and a read would
    // then wait for its response forever), so hold the initiator off. The
    // fifo only fills with writes, which complete for the initiator as soon
    // as they are pushed.
    io.initiator.ready := !requestFifo.io.write.full
    io.initiator.dataRead := regResponseDataRead

    // Response receiver
    when (skidComplete && !skidStale) {
      logger.info(cf"slow: got response: addr=0x${skidAddress}%x isWrite=${skidIsWrite} (data=0x${skidDataRead}%x)")

      when (skidIsWrite) {
        logger.info("slow: popping write request")
        requestFifo.io.read.pop := true.B
      } .otherwise {
        when (regBusy && !regBusyIsWrite && regBusyAddress === skidAddress) {
          // This is completing the current request.
          logger.info("slow: popping busy read request")
          requestFifo.io.read.pop := true.B
          suppressRequestPush := true.B
        }
        when (io.initiator.ready && io.initiator.enable && !io.initiator.isWrite && io.initiator.address === skidAddress) {
          logger.info("slow: prefetch completing the next read request")
          suppressRequestPush := true.B
        }
      }


      // Only fill the response FIFO if it's a read
      when (!skidIsWrite) {
        regResponsePresent := true.B
        regResponseAddress := skidAddress
        regResponseDataRead := skidDataRead
      }
    }

    when (regBusy) {
      when (regBusyIsWrite) {
        when (requestFifo.io.write.full) {
          logger.info(cf"slow: write busy... (fifo full)")
          // Waiting for space in the request fifo
          io.initiator.ready := false.B
        } .otherwise {
          // Write is done when the write data can be moved into the request fifo.
          logger.info(cf"slow: write pushed! addr=0x${regBusyAddress}%x data=0x${io.initiator.dataWrite}%x")
          regBusy := false.B

          requestFifo.io.write.push := true.B
          requestFifo.io.write.data.address := regBusyAddress
          requestFifo.io.write.data.isWrite := true.B
          requestFifo.io.write.data.writeData := io.initiator.dataWrite

          // Invalidate the read response buffer, and the prefetch that may
          // be in flight ahead of the write.
          regResponsePresent := false.B
          regDiscardPrefetch := true.B

          when (io.initiator.enable && !io.initiator.isWrite) {
            // If we're placing data in the fifo, and there's read
            // that's also starting now, the read will overwrite what we place into
            // the request FIFO.
            // Avoid that by keeping ready low for one cycle. Write goes first,
            // next cycle read goes.
            logger.info("slow: blocking read right after write")
          }
        }
      } .otherwise {
        when (responseBufferGood) {
          // Read is done when the pending request matches what's in the response buffer.
          logger.info("slow: read completed!")
          responseCanAccept := true.B
          regBusy := false.B
          // Note: we aren't clearing out regResponsePresent,
          // this allows a repeated read from the same address to keep happening.
        } .otherwise {
          logger.info("slow: read busy...")
          io.initiator.ready := false.B

          when (regBusyFifoDelayed) {
            when (suppressRequestPush) {
              regBusyFifoDelayed := false.B
            } .elsewhen (!requestFifo.io.write.full) {
              // (Waits while the fifo is full: the write ahead of this read
              // may have filled it.)
              logger.info(cf"slow: pushing delayed read to fifo")
              regBusyFifoDelayed := false.B
              requestFifo.io.write.push := true.B
              requestFifo.io.write.data.address := regBusyAddress
              requestFifo.io.write.data.isWrite := false.B
            }
          }
        }
      }
    }

    // Request receiver
    when (io.initiator.enable && io.initiator.ready) {
      regBusy := true.B
      regBusyIsWrite := io.initiator.isWrite
      regBusyAddress := io.initiator.address
      regBusyFifoDelayed := false.B

      when (io.initiator.isWrite) {
        logger.info(cf"slow: write request: addr=0x${io.initiator.address}%x")

        // Latch the write address (above) until we get the data next cycle.
      } .otherwise {
        logger.info(cf"slow: read request : addr=0x${io.initiator.address}%x")

        when (regBusy && regBusyIsWrite) {
          logger.info("slow: read can't start immediately!")
          regBusyFifoDelayed := true.B
        } .elsewhen (
          // The response is already in the buffer...
          (regResponsePresent && regResponseAddress === io.initiator.address) &&
          // and it's not about to be overwritten next cycle
          !(skidComplete && !skidIsWrite)
        ){
          logger.info("slow:   not pushing | already prefetched")
        } .elsewhen (suppressRequestPush) {
          logger.info("slow:   not pushing | about to complete")
        }  .otherwise {
          // Put the request in the request FIFO and mark ourselves busy.
          requestFifo.io.write.push := true.B
          requestFifo.io.write.data.address := io.initiator.address
          requestFifo.io.write.data.isWrite := false.B
        }
      }
    }
  }

  io.target.enable := false.B
  io.target.address := DontCare
  io.target.isWrite := DontCare
  io.target.writeStrobe := DontCare
  io.target.dataWrite := requestFifo.io.read.data.writeData

  val regBusy = RegInit(false.B)
  val regBusyAddress = Reg(UInt(addressWidth.W))
  val regBusyWrite = Reg(Bool())
  val regBusyPrefetch = Reg(Bool())
  val regReadBurst = RegInit(false.B)

  val slowTick = sync =/= RegNext(sync)

  when (slowTick) {
    logger.debug("=========== slow tick")

    // Slow has seen the completion signal.
    // Do this before it's set to true below (when an access completes).
    regSkidComplete := false.B
  }

  when (regBusy) {
    when (io.target.ready) {
      logger.info("fast: request complete!")
      regBusy := false.B
      // We could continue the burst here (if needed) but it's simpler to wait until the next slowTick.
      regReadBurst := !regBusyWrite && enablePrefetch.B

      regSkidComplete := true.B
      regSkidDataRead := io.target.dataRead
      regSkidAddress := regBusyAddress
      regSkidIsWrite := regBusyWrite
      regSkidIsPrefetch := regBusyPrefetch
      skidComplete := true.B
      skidDataRead := io.target.dataRead
      skidAddress := regBusyAddress
      skidIsWrite := regBusyWrite
      skidIsPrefetch := regBusyPrefetch
    } .otherwise {
      logger.info(cf"fast: busy (write=${regBusyWrite})")
    }
  }

  when (slowTick) {
    val canStartAccess = !regBusy || (io.target.ready && regBusyPrefetch)
    when (canStartAccess) {
      // We could start a new request! Should we?

      // Check if there's a valid request waiting for us.
      val haveNewRequest = WireDefault(!requestFifo.io.read.empty)
      when (regBusy && requestFifo.io.read.data.address === regBusyAddress) {
        // We're completing this request right now, so don't start it again.
        haveNewRequest := false.B
      }

      when (haveNewRequest) {
        // There is a request, so handle it.
        logger.info(cf"fast: got request: addr=0x${requestFifo.io.read.data.address}%x isWrite=${requestFifo.io.read.data.isWrite}")
        regBusy := true.B
        regBusyAddress := requestFifo.io.read.data.address
        regBusyWrite := requestFifo.io.read.data.isWrite
        regBusyPrefetch := false.B

        io.target.enable := true.B
        io.target.address := requestFifo.io.read.data.address
        io.target.isWrite := requestFifo.io.read.data.isWrite
      } .elsewhen (regReadBurst) {
        // Continue the read burst.

        when (responseCanAccept) {
          // There is space in response fifo next cycle.
          val nextAddress = regBusyAddress + addressBurstIncrement.U
          logger.info(cf"fast: continue read burst: addr=0x${nextAddress}%x")
          regBusy := true.B
          regBusyAddress := nextAddress
          regBusyWrite := false.B
          regBusyPrefetch := true.B

          io.target.enable := true.B
          io.target.address := nextAddress
          io.target.isWrite := false.B
        }
      }
    }
  }

  logger.debug(cf"* fast: busy=${regBusy} slowtick=${slowTick}")

  class RequestEntry extends Bundle {
    val address = UInt(addressWidth.W)
    val isWrite = Bool()
    val writeData = UInt(dataWidth.W)
  }
}
