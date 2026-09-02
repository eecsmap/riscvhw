package riscvhw

import chisel3._
import circt.stage.ChiselStage
import riscvhw.core.Core
import riscvhw.mem.{Scratchpad, MemDelay, MemTiming}

/** Stage-0 system: core + scratchpad. Replaced by a Chipyard tile at stage 3. */
class System(latency: Int = 0, timing: Option[MemTiming] = None)(implicit c: RiscvhwConfig) extends Module {
  val io = IO(new Bundle {
    val trace   = Output(new riscvhw.core.TraceIo)
    val illegal = Output(Bool())
    /** riscv-tests report their result by storing to a fixed address; this
      * mirrors that store out so the testbench can see it. It is a host
      * interface, not core logic -- the equivalent of HTIF on a real chip. */
    val tohost  = Output(chisel3.util.Valid(UInt(c.xlen.W)))
    val load    = Input(chisel3.util.Valid(new Bundle {
      val addr = UInt(c.xlen.W)
      val data = UInt(64.W)
    }))
  })

  val core = Module(new Core)
  core.io.resetVector := c.resetVector.U   // no boot ROM in the standalone tests
  val mem  = Module(new Scratchpad(nPorts = 2, latency = latency))

  // `timing` inserts a delay model between the core and the memory. It is
  // separate from the scratchpad's own `latency` so the model can later front
  // the bus adapter instead, without the memory knowing.
  timing match {
    case None =>
      mem.io.ports(0) <> core.io.imem
      mem.io.ports(1) <> core.io.dmem
    case Some(t) =>
      val di = Module(new MemDelay(t)); val dd = Module(new MemDelay(t))
      di.io.in <> core.io.imem; mem.io.ports(0) <> di.io.out
      dd.io.in <> core.io.dmem; mem.io.ports(1) <> dd.io.out
  }
  mem.io.load     := io.load

  io.trace   := core.io.trace
  io.illegal := core.io.illegal

  io.tohost.valid := core.io.dmem.req.fire && core.io.dmem.req.bits.write &&
                     (core.io.dmem.req.bits.addr === c.tohostAddr.U)
  io.tohost.bits  := core.io.dmem.req.bits.wdata
}

object Elaborate extends App {
  implicit val cfg: RiscvhwConfig = RiscvhwConfig()
  val dir = if (args.nonEmpty) args(0) else "generated"
  println(ChiselStage.emitSystemVerilogFile(
    new System(),
    firtoolOpts = Array("-disable-all-randomization", "-strip-debug-info",
                        "--split-verilog", "-o", dir)))
}
