package lib.video

import chisel3._
import lib.log.Logger
import lib.mem.HandshakeMemoryCdc
import net.gamebub.framework.interface.VideoFilterBasicV0
import lib.mem.MemoryInterface
import chisel3.util.{Fill, MemoryReadPort, SRAM}

/**
 * Color correction matrix calculator
 *
 * Applies a gamma correction to transform to linear space,
 * then a color transformation matrix, then a transform back to non-linear.
 */
class ColorCorrection(
  /// Per-channel input color depth
  val inputDepth: Int = 5,
  /// Per-channel output depth
  val outputDepth: Int = 6,

  /// Internal representation color depth
  val internalDepth: Int = 10,
  /// Internal matrix depth
  val matrixDepth: Int = 10,
  /// Internal output table depth
  val outputTableDepth: Int = 6,
) extends Module {
  val io = IO(new Bundle {
    /// Enable corrections, or false to pass colors through unchanged.
    val enable = Input(Bool())

    val in = Input(ColorRGB(inputDepth, inputDepth, inputDepth))
    val out = Output(ColorRGB(outputDepth, outputDepth, outputDepth))

    val matrixR = Input(Vec(3, SInt((matrixDepth + 2).W)))
    val matrixG = Input(Vec(3, SInt((matrixDepth + 2).W)))
    val matrixB = Input(Vec(3, SInt((matrixDepth + 2).W)))
    val inputTable = Input(Vec(1 << inputDepth, SInt((internalDepth + 1).W)))
    val outputTable = Vec(3, Flipped(new MemoryReadPort(UInt(outputDepth.W), 1 << outputTableDepth)))
  })
  val logger = Logger("color")

  // Convert to linear space
  val inputR = RegNext(io.inputTable(io.in.r))
  val inputG = RegNext(io.inputTable(io.in.g))
  val inputB = RegNext(io.inputTable(io.in.b))

  // Do matrix multiplication
  val sumR = (inputR * io.matrixR(0)) + (inputG * io.matrixR(1)) + (inputB * io.matrixR(2))
  val sumG = (inputR * io.matrixG(0)) + (inputG * io.matrixG(1)) + (inputB * io.matrixG(2))
  val sumB = (inputR * io.matrixB(0)) + (inputG * io.matrixB(1)) + (inputB * io.matrixB(2))

  // And divide (fixed point)
  val correctR = RegNext(sumR >> matrixDepth).asSInt
  val correctG = RegNext(sumG >> matrixDepth).asSInt
  val correctB = RegNext(sumB >> matrixDepth).asSInt

  // Clamp at (0.0 and 1.0), and then convert from internal depth to output table depth
  val indexR = clamp(correctR, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)
  val indexG = clamp(correctG, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)
  val indexB = clamp(correctB, 0.S, ((1 << internalDepth) - 1).S).asUInt >> (internalDepth - outputTableDepth)

  // Read from output table
  for ((i, channel, addr) <- Seq((0, io.out.r, indexR), (1, io.out.g, indexG), (2, io.out.b, indexB))) {
    io.outputTable(i).enable := true.B
    io.outputTable(i).address := addr.asUInt
    channel := io.outputTable(i).data
  }

  // Original input colors, delayed for the same number of cycles (if corrections are disabled)
  val delayInput = RegNext(RegNext(RegNext(io.in)))
  when (!io.enable) {
    if (outputDepth > inputDepth) {
      // Expand by bit replication, so that full scale maps to full scale.
      def expand(channel: UInt): UInt = {
        val repeated = Fill((outputDepth + inputDepth - 1) / inputDepth, channel)
        repeated(repeated.getWidth - 1, repeated.getWidth - outputDepth)
      }
      io.out.r := expand(delayInput.r)
      io.out.g := expand(delayInput.g)
      io.out.b := expand(delayInput.b)
    } else {
      io.out.r := delayInput.r >> (inputDepth - outputDepth)
      io.out.g := delayInput.g >> (inputDepth - outputDepth)
      io.out.b := delayInput.b >> (inputDepth - outputDepth)
    }
  }

  def clamp(value: SInt, min: SInt, max: SInt): SInt = {
    val output = WireDefault(value)
    when (value < min) {
      output := min
    } .elsewhen (value > max) {
      output := max
    }
    output
  }
}

object ColorCorrection {
  /**
   * Instantiates the corrector behind the video filter interface, with its
   * matrix and tables written through `memInterface` (matrix at 0x0000,
   * input table at 0x4000, output table at 0x8000; 16-bit words).
   *
   * The tables have no reset value, so until the host has written them the
   * output is undefined. With `passThroughUntilLoaded`, colors pass through
   * unchanged (5-bit channels expanded to 8 by bit replication) until the
   * first write: for a core that may run without a driver loading a table.
   */
  def setup(
    clock: Clock,
    reset: Reset,
    videoFilter: VideoFilterBasicV0,
    memInterface: MemoryInterface,
    passThroughUntilLoaded: Boolean = false,
  ): Unit = {
    withClockAndReset (videoFilter.clock, videoFilter.reset) {
      val colorCorrector = Module(new ColorCorrection(
        inputDepth = 5,
        outputDepth = 8,
        internalDepth = 12,
        matrixDepth = 12,
        outputTableDepth = 10,
      ))
      colorCorrector.io.in := videoFilter.dataIn
      videoFilter.dataOut := colorCorrector.io.out.convertTo(videoFilter.dataOut)

      {
        val cdc = Module(new HandshakeMemoryCdc(addressWidth = 16, dataWidth = 16))
        cdc.io.sourceClock := clock
        cdc.io.sourceReset := reset
        cdc.io.initiator <> memInterface
        val mem = cdc.io.target
        mem.done := true.B
        mem.dataRead := DontCare
        val loaded = RegInit(false.B)
        when (mem.enable && mem.write) {
          loaded := true.B
        }
        colorCorrector.io.enable := (!passThroughUntilLoaded).B || loaded
        val matrix = Reg(Vec(9, SInt((colorCorrector.matrixDepth + 2).W)))
        val inputTable = Reg(Vec(1 << colorCorrector.inputDepth, SInt((colorCorrector.internalDepth + 1).W)))
        val outputTable = SRAM(
          1 << colorCorrector.outputTableDepth,
          UInt(colorCorrector.outputDepth.W),
          numReadPorts = 3,
          numWritePorts = 1,
          numReadwritePorts = 0,
        )
        outputTable.writePorts(0) := DontCare
        outputTable.writePorts(0).enable := false.B

        when (mem.enable && mem.write) {
          when (mem.address(15, 14) === 0.U) {
            matrix(mem.address(4, 1)) := mem.dataWrite.asSInt
          }
          when (mem.address(15, 14) === 1.U) {
            inputTable(mem.address(colorCorrector.inputDepth, 1)) := mem.dataWrite.asSInt
          }
          when (mem.address(15, 14) === 2.U) {
            outputTable.writePorts(0).enable := true.B
            outputTable.writePorts(0).address := mem.address(colorCorrector.outputTableDepth, 1)
            outputTable.writePorts(0).data := mem.dataWrite
          }
        }
        colorCorrector.io.matrixR := VecInit(matrix(0), matrix(1), matrix(2))
        colorCorrector.io.matrixG := VecInit(matrix(3), matrix(4), matrix(5))
        colorCorrector.io.matrixB := VecInit(matrix(6), matrix(7), matrix(8))
        colorCorrector.io.inputTable := inputTable
        colorCorrector.io.outputTable <> outputTable.readPorts
      }
    }
  }
}