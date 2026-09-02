package riscvhw

import chisel3._
import circt.stage.ChiselStage
import riscvhw.core.Core
import riscvhw.mem.Scratchpad

/** Stage-0 system: core + scratchpad. Replaced by a Chipyard tile at stage 3. */
class System(latency: Int = 0)(implicit c: RiscvhwConfig) extends Module {
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
  val mem  = Module(new Scratchpad(nPorts = 2, latency = latency))

  mem.io.ports(0) <> core.io.imem
  mem.io.ports(1) <> core.io.dmem
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
