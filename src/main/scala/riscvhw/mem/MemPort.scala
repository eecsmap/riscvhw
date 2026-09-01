package riscvhw.mem

import chisel3._
import chisel3.util._
import riscvhw.RiscvhwConfig

/** Memory access size, encoded as log2(bytes). */
object MemSize {
  val B = 0.U(2.W)   // 1 byte
  val H = 1.U(2.W)   // 2 bytes
  val W = 2.U(2.W)   // 4 bytes
  val D = 3.U(2.W)   // 8 bytes
}

class MemReq(implicit c: RiscvhwConfig) extends Bundle {
  val addr   = UInt(c.xlen.W)
  val wdata  = UInt(c.xlen.W)
  val size   = UInt(2.W)      // MemSize
  val signed = Bool()         // sign-extend loads narrower than xlen
  val write  = Bool()
}

class MemResp(implicit c: RiscvhwConfig) extends Bundle {
  val rdata = UInt(c.xlen.W)
  /** Access faulted (unmapped / misaligned at the memory, not the core). Wired
    * through from stage 0 so the fault path exists before it is needed. */
  val fault = Bool()
}

/** Core-side memory port.
  *
  * Both directions carry a handshake from the very first stage, even though the
  * stage-0 scratchpad always answers immediately. This is on purpose: Sodor's
  * `MemPortIo` gives `resp` a valid but no ready, so the core cannot refuse a
  * response, and the 1-stage core is forced to carry an instruction buffer to
  * catch replies that arrive while it is busy. Designing the handshake in now
  * costs nothing and means the core does not change when the scratchpad is
  * replaced by a bus that takes tens of cycles (stage 3).
  */
class MemPort(implicit c: RiscvhwConfig) extends Bundle {
  val req  = Decoupled(new MemReq)
  val resp = Flipped(Decoupled(new MemResp))
}
