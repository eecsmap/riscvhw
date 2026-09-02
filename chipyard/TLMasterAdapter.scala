package riscvhw.chipyard

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.rocket.{LoadGen, StoreGen}
import riscvhw.RiscvhwConfig
import riscvhw.mem.MemPort

/** Turns one core memory port into a TileLink master.
  *
  * One request in flight, which is why the client node declares a single source
  * id: the core is not pipelined and cannot issue a second access before the
  * first returns. That is also what makes this adapter a plain three-state
  * machine rather than a queue.
  *
  * The core's port already carries a full handshake in both directions, so
  * nothing here has to invent back-pressure -- a TileLink transaction that takes
  * fifty cycles simply keeps the core waiting, exactly as the scratchpad's
  * injected latency does in the standalone tests.
  */
class TLMasterAdapter(name: String)(implicit p: Parameters, c: RiscvhwConfig) extends LazyModule {
  val node = TLClientNode(Seq(TLMasterPortParameters.v1(
    clients = Seq(TLMasterParameters.v1(name = name, sourceId = IdRange(0, 1))))))

  lazy val module = new Impl
  class Impl extends LazyModuleImp(this) {
    val io = IO(new Bundle { val mem = Flipped(new MemPort) })
    val (tl, edge) = node.out(0)

    val sIdle :: sSend :: sWait :: sResp :: Nil = Enum(4)
    val state = RegInit(sIdle)

    // Latched at accept time: the core is free to change its request bits once
    // the handshake completes, and the reply may be many cycles away.
    val addrReg   = Reg(UInt(c.xlen.W))
    val sizeReg   = Reg(UInt(2.W))
    val signedReg = Reg(Bool())
    val writeReg  = Reg(Bool())
    val dataReg   = Reg(UInt(c.xlen.W))
    val respReg   = Reg(UInt(c.xlen.W))
    val faultReg  = Reg(Bool())

    io.mem.req.ready := state === sIdle
    when (io.mem.req.fire) {
      addrReg   := io.mem.req.bits.addr
      sizeReg   := io.mem.req.bits.size
      signedReg := io.mem.req.bits.signed
      writeReg  := io.mem.req.bits.write
      dataReg   := io.mem.req.bits.wdata
      state     := sSend
    }

    // A sub-beat store has to be positioned in the right byte lanes and given a
    // matching mask; StoreGen does both from the address and size.
    val sg = new StoreGen(sizeReg, addrReg, dataReg, c.xlen / 8)
    val (legalGet, getBits) = edge.Get(0.U, addrReg, sizeReg)
    val (legalPut, putBits) = edge.Put(0.U, addrReg, sizeReg, sg.data, sg.mask)

    tl.a.valid := state === sSend
    tl.a.bits  := Mux(writeReg, putBits, getBits)
    when (tl.a.fire) { state := sWait }

    tl.d.ready := state === sWait
    when (tl.d.fire) {
      // LoadGen performs the sub-word extraction and sign extension that the
      // core's port asked for, from whichever byte lanes the reply arrived in.
      respReg  := new LoadGen(sizeReg, signedReg, addrReg, tl.d.bits.data,
                              false.B, c.xlen / 8).data
      faultReg := tl.d.bits.denied || tl.d.bits.corrupt
      state    := sResp
    }

    io.mem.resp.valid      := state === sResp
    io.mem.resp.bits.rdata := respReg
    io.mem.resp.bits.fault := faultReg
    when (io.mem.resp.fire) { state := sIdle }

    assert(!tl.a.valid || Mux(writeReg, legalPut, legalGet),
           s"$name issued an illegal TileLink request")

    // Unused channels: this client is not coherent.
    tl.b.ready := true.B
    tl.c.valid := false.B
    tl.e.valid := false.B
  }
}
