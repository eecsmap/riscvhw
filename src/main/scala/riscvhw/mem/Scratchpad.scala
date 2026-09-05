package riscvhw.mem

import chisel3._
import chisel3.util._
import riscvhw.RiscvhwConfig

/** Stage-0 memory: a byte-addressable scratchpad with two independent ports.
  *
  * SIMULATION ONLY -- this does not synthesise. `Mem` gives an asynchronous
  * read, and 7-series block RAM has no asynchronous read mode, so it can only
  * map to LUTRAM; at 1 MB it fits neither LUTRAM (~1.1 Mbit) nor all the block
  * RAM on an xc7z020 (~4.9 Mbit). That is fine, because from stage 3 the core
  * reaches real DDR over TileLink and this model only carries the fast
  * iteration loop. See docs/002-scratchpad-is-simulation-only.md.
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
    // One outstanding access per port, held in an explicit little state
    // machine. Writing this as a state machine even for latency == 0 keeps a
    // single code path: the response must be latched (the address may change
    // while the core waits) and must stay valid until the core takes it.
    val sIdle :: sWait :: sResp :: Nil = Enum(3)
    val st = RegInit(sIdle)

    val cnt      = RegInit(0.U(8.W))
    val dataReg  = Reg(UInt(c.xlen.W))

    val addr    = p.req.bits.addr
    val off     = addr(2, 0)
    val bytes   = (1.U << p.req.bits.size)              // 1, 2, 4 or 8
    val rawVec  = mem.read(wordIdx(addr))
    val raw     = Cat(rawVec.reverse)                   // little-endian assembly
    val shifted = raw >> (off << 3)

    // Mask to the requested width, then sign- or zero-extend.
    val maskBits = (bytes << 3)(6, 0)
    val allOnes  = ((1.U << maskBits) - 1.U)(c.xlen - 1, 0)
    val masked   = shifted & allOnes
    val signBit  = (shifted >> (maskBits - 1.U))(0)
    val extended = Mux(p.req.bits.signed && signBit && (maskBits < c.xlen.U),
                       masked | (~allOnes).asUInt, masked)

    p.req.ready := st === sIdle

    when (p.req.fire) {
      when (p.req.bits.write) {
        val wvec = VecInit(Seq.tabulate(8) { i =>
          val byteEn = (i.U >= off) && (i.U < (off +& bytes))
          Mux(byteEn, (p.req.bits.wdata >> ((i.U - off) << 3))(7, 0), rawVec(i))
        })
        mem.write(wordIdx(addr), wvec)
      }
      // Latch the read data now: by the time the response is delivered the
      // request bits may already be showing a different address.
      dataReg := extended
      cnt     := latency.U
      st      := Mux(latency.U === 0.U, sResp, sWait)
    }

    when (st === sWait) {
      cnt := cnt - 1.U
      when (cnt === 1.U) { st := sResp }
    }

    // Hold the response until the core actually takes it.
    when (st === sResp && p.resp.fire) { st := sIdle }

    p.resp.valid      := st === sResp
    p.resp.bits.rdata := dataReg
    p.resp.bits.fault := false.B
  }
}
