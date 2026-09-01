package riscvhw.mem

import chisel3._
import chisel3.util._
import riscvhw.RiscvhwConfig

/** Stage-0 memory: a byte-addressable scratchpad with two independent ports.
  *
  * Answers in the same cycle, but still speaks the handshake protocol, so
  * replacing it with a bus adapter at stage 3 is a drop-in change. The
  * `latency` parameter injects artificial delay, which is how "memory wait"
  * gets tested long before real DRAM is attached.
  */
class Scratchpad(nPorts: Int = 2, latency: Int = 0)(implicit c: RiscvhwConfig) extends Module {
  val io = IO(new Bundle {
    val ports = Vec(nPorts, Flipped(new MemPort))
    /** Backdoor for the testbench to preload a program. */
    val load  = Input(Valid(new Bundle {
      val addr = UInt(c.xlen.W)
      val data = UInt(64.W)
    }))
  })

  val words = c.memBytes / 8
  val mem   = Mem(words, Vec(8, UInt(8.W)))

  private def wordIdx(addr: UInt) = ((addr - c.memBase.U) >> 3)(log2Ceil(words) - 1, 0)

  when (io.load.valid) {
    mem.write(wordIdx(io.load.bits.addr),
              VecInit(Seq.tabulate(8)(i => io.load.bits.data(8 * i + 7, 8 * i))))
  }

  io.ports.foreach { p =>
    val addr   = p.req.bits.addr
    val off    = addr(2, 0)
    val bytes  = (1.U << p.req.bits.size)               // 1,2,4,8
    val rawVec = mem.read(wordIdx(addr))
    val raw    = Cat(rawVec.reverse)                    // little-endian assembly
    val shifted = raw >> (off << 3)

    // Mask to the requested width, then sign- or zero-extend.
    val maskBits = (bytes << 3)(6, 0)
    val masked   = shifted & ((1.U << maskBits) - 1.U)
    val signBit  = (shifted >> (maskBits - 1.U))(0)
    val extended = Mux(p.req.bits.signed && signBit && maskBits < c.xlen.U,
                       masked | (~((1.U << maskBits) - 1.U)).asUInt,
                       masked)

    when (p.req.fire && p.req.bits.write) {
      val wvec = VecInit(Seq.tabulate(8) { i =>
        val byteEn = (i.U >= off) && (i.U < (off +& bytes))
        Mux(byteEn, (p.req.bits.wdata >> ((i.U - off) << 3))(7, 0), rawVec(i))
      })
      mem.write(wordIdx(addr), wvec)
    }

    // latency == 0: combinational answer, always ready.
    val delay = if (latency == 0) 0 else latency
    val respValid = if (delay == 0) RegNext(p.req.fire, false.B) else {
      val cnt = RegInit(0.U(8.W))
      val busy = RegInit(false.B)
      when (p.req.fire) { busy := true.B; cnt := delay.U }
      when (busy && cnt =/= 0.U) { cnt := cnt - 1.U }
      when (busy && cnt === 0.U) { busy := false.B }
      busy && cnt === 0.U
    }

    p.req.ready       := true.B
    p.resp.valid      := respValid
    p.resp.bits.rdata := RegNext(extended)
    p.resp.bits.fault := false.B
  }
}
