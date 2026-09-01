package riscvhw.core

import chisel3._
import chisel3.util._
import Consts._
import riscvhw.RiscvhwConfig
import riscvhw.mem._

/** Commit trace, emitted for co-simulation against the `riscvm` emulator.
  * Only valid when `valid` is high. */
class TraceIo(implicit c: RiscvhwConfig) extends Bundle {
  val valid = Bool()
  val pc    = UInt(c.xlen.W)
  val inst  = UInt(32.W)
  val wen   = Bool()
  val waddr = UInt(5.W)
  val wdata = UInt(c.xlen.W)
}

class CoreIo(implicit c: RiscvhwConfig) extends Bundle {
  val imem  = new MemPort
  val dmem  = new MemPort
  val trace = Output(new TraceIo)
  /** Raised when decode fails. Becomes an exception at stage 2; until then it
    * is brought out so the testbench can stop rather than run off into weeds. */
  val illegal = Output(Bool())
}

/** Stage 0: a non-pipelined RV64I core.
  *
  * One instruction at a time, driven by a small state machine. It is written as
  * a state machine rather than as pure combinational logic even though the
  * stage-0 scratchpad always answers in one cycle, because every later stage
  * (real memory, page-table walks, MMIO) needs states where the core waits.
  * Starting here means those stages add states instead of restructuring.
  */
class Core(implicit c: RiscvhwConfig) extends Module {
  val io = IO(new CoreIo)

  val sFetch :: sExec :: sMem :: sWb :: Nil = Enum(4)
  val state = RegInit(sFetch)

  val pc      = RegInit(c.resetVector.U(c.xlen.W))
  val inst    = Reg(UInt(32.W))
  val memData = Reg(UInt(c.xlen.W))

  val regfile = Mem(32, UInt(c.xlen.W))

  // ---------------- decode ----------------
  val cs       = Decode(inst)
  val rs1_addr = inst(19, 15)
  val rs2_addr = inst(24, 20)
  val rd_addr  = inst(11, 7)

  // x0 reads as zero. Guarded on the read side rather than relying on the
  // storage holding zero, so a debug write can never break the invariant.
  val rs1_data = Mux(rs1_addr === 0.U, 0.U, regfile(rs1_addr))
  val rs2_data = Mux(rs2_addr === 0.U, 0.U, regfile(rs2_addr))

  val imm = ImmGen(inst, cs.imm_sel, c.xlen)

  val op1 = MuxLookup(cs.op1_sel, 0.U)(Seq(
    OP1_ZERO -> 0.U, OP1_RS1 -> rs1_data, OP1_PC -> pc))
  val op2 = MuxLookup(cs.op2_sel, 0.U)(Seq(
    OP2_ZERO -> 0.U, OP2_RS2 -> rs2_data, OP2_IMM -> imm))

  val alu = Module(new Alu)
  alu.io.fn  := cs.alu_fun
  alu.io.op1 := op1
  alu.io.op2 := op2
  val alu_out = alu.io.out

  // ---------------- branch resolution ----------------
  // Dedicated comparators, not the ALU: a branch needs the comparison and the
  // target in the same step, and one ALU cannot produce both.
  val br_eq  = rs1_data === rs2_data
  val br_lt  = rs1_data.asSInt < rs2_data.asSInt
  val br_ltu = rs1_data < rs2_data

  val br_taken = MuxLookup(cs.br_type, false.B)(Seq(
    BR_EQ -> br_eq, BR_NE -> !br_eq,
    BR_LT -> br_lt, BR_GE -> !br_lt,
    BR_LTU-> br_ltu, BR_GEU-> !br_ltu,
    BR_J  -> true.B, BR_JR -> true.B))

  val pc_plus4    = pc + 4.U
  val brjmp_target = pc + imm
  val jalr_target  = (rs1_data + imm) & (~1.U(c.xlen.W)).asUInt

  val pc_next = Mux(!br_taken, pc_plus4,
                Mux(cs.br_type === BR_JR, jalr_target, brjmp_target))

  // ---------------- writeback ----------------
  val wb_data = MuxLookup(cs.wb_sel, alu_out)(Seq(
    WB_ALU -> alu_out, WB_MEM -> memData, WB_PC4 -> pc_plus4))
  val wb_en = cs.rf_wen && rd_addr =/= 0.U

  // ---------------- memory ports ----------------
  io.imem.req.valid       := state === sFetch
  io.imem.req.bits.addr   := pc
  io.imem.req.bits.wdata  := 0.U
  io.imem.req.bits.size   := MemSize.W
  io.imem.req.bits.signed := false.B
  io.imem.req.bits.write  := false.B
  io.imem.resp.ready      := state === sFetch

  io.dmem.req.valid       := (state === sMem) && cs.mem_en
  io.dmem.req.bits.addr   := alu_out
  io.dmem.req.bits.wdata  := rs2_data
  io.dmem.req.bits.size   := cs.mem_size
  io.dmem.req.bits.signed := cs.mem_sgn
  io.dmem.req.bits.write  := cs.mem_wr
  io.dmem.resp.ready      := state === sMem

  io.illegal := (state === sExec) && !cs.legal

  // ---------------- state machine ----------------
  // Each state waits for its handshake, so a memory that takes many cycles
  // simply keeps the core in that state. Nothing else has to know.
  switch (state) {
    is (sFetch) {
      when (io.imem.req.fire) { /* request accepted */ }
      when (io.imem.resp.fire) {
        inst  := io.imem.resp.bits.rdata(31, 0)
        state := sExec
      }
    }
    is (sExec) {
      state := Mux(cs.mem_en, sMem, sWb)
    }
    is (sMem) {
      when (io.dmem.resp.fire) {
        memData := io.dmem.resp.bits.rdata
        state   := sWb
      }
    }
    is (sWb) {
      when (wb_en) { regfile(rd_addr) := wb_data }
      pc    := pc_next
      state := sFetch
    }
  }

  // ---------------- trace ----------------
  io.trace.valid := state === sWb
  io.trace.pc    := pc
  io.trace.inst  := inst
  io.trace.wen   := wb_en
  io.trace.waddr := rd_addr
  io.trace.wdata := wb_data
}
