package riscvhw.core

import chisel3._
import chisel3.util._
import Consts._
import Instructions._

/** Decoded control signals for one instruction. */
class CtrlSignals extends Bundle {
  val legal   = Bool()       // decoded successfully; false raises illegal-instruction
  val br_type = UInt(4.W)
  val op1_sel = UInt(2.W)
  val op2_sel = UInt(2.W)
  val imm_sel = UInt(3.W)
  val alu_fun = UInt(5.W)
  val wb_sel  = UInt(2.W)
  val rf_wen  = Bool()
  val mem_en  = Bool()
  val mem_wr  = Bool()
  val mem_size= UInt(2.W)
  val mem_sgn = Bool()       // sign-extend sub-word loads
}

object Decode {
  import riscvhw.mem.MemSize._

  //                 legal | br_type| op1_sel | op2_sel | imm_sel| alu_fun  | wb_sel| rf_wen| mem_en| mem_wr| size| signed
  private val X = List(N, BR_N,  OP1_ZERO, OP2_ZERO, IMM_X, ALU_X,    WB_X,   N,      N,      N,      D,    N)

  val table: Array[(BitPat, List[UInt])] = Array(
    // ---- loads ----
    LB    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      B,    Y),
    LH    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      H,    Y),
    LW    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      W,    Y),
    LD    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      D,    Y),
    LBU   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      B,    N),
    LHU   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      H,    N),
    LWU   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      W,    N),
    // ---- stores ----
    SB    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      B,    N),
    SH    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      H,    N),
    SW    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      W,    N),
    SD    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      D,    N),
    // ---- upper immediates ----
    // LUI is 0 + imm, not a dedicated copy op: OP1_ZERO exists precisely so the
    // ALU needs no pass-through function.
    LUI   -> List(Y, BR_N,  OP1_ZERO, OP2_IMM,  IMM_U, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N),
    AUIPC -> List(Y, BR_N,  OP1_PC,   OP2_IMM,  IMM_U, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N),
    // ---- register-immediate ----
    ADDI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N),
    SLTI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLT,  WB_ALU, Y,      N,      N,      D,    N),
    SLTIU -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLTU, WB_ALU, Y,      N,      N,      D,    N),
    XORI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_XOR,  WB_ALU, Y,      N,      N,      D,    N),
    ORI   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_OR,   WB_ALU, Y,      N,      N,      D,    N),
    ANDI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_AND,  WB_ALU, Y,      N,      N,      D,    N),
    SLLI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLL,  WB_ALU, Y,      N,      N,      D,    N),
    SRLI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRL,  WB_ALU, Y,      N,      N,      D,    N),
    SRAI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRA,  WB_ALU, Y,      N,      N,      D,    N),
    // ---- register-register ----
    ADD   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N),
    SUB   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SUB,  WB_ALU, Y,      N,      N,      D,    N),
    SLL   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLL,  WB_ALU, Y,      N,      N,      D,    N),
    SLT   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLT,  WB_ALU, Y,      N,      N,      D,    N),
    SLTU  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLTU, WB_ALU, Y,      N,      N,      D,    N),
    XOR   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_XOR,  WB_ALU, Y,      N,      N,      D,    N),
    SRL   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRL,  WB_ALU, Y,      N,      N,      D,    N),
    SRA   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRA,  WB_ALU, Y,      N,      N,      D,    N),
    OR    -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_OR,   WB_ALU, Y,      N,      N,      D,    N),
    AND   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_AND,  WB_ALU, Y,      N,      N,      D,    N),
    // ---- RV64 word forms ----
    ADDIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADDW, WB_ALU, Y,      N,      N,      D,    N),
    SLLIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLLW, WB_ALU, Y,      N,      N,      D,    N),
    SRLIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRLW, WB_ALU, Y,      N,      N,      D,    N),
    SRAIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRAW, WB_ALU, Y,      N,      N,      D,    N),
    ADDW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_ADDW, WB_ALU, Y,      N,      N,      D,    N),
    SUBW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SUBW, WB_ALU, Y,      N,      N,      D,    N),
    SLLW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLLW, WB_ALU, Y,      N,      N,      D,    N),
    SRLW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRLW, WB_ALU, Y,      N,      N,      D,    N),
    SRAW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRAW, WB_ALU, Y,      N,      N,      D,    N),
    // ---- control transfer ----
    // JAL/JALR compute their target in a dedicated adder, so the ALU is free to
    // produce the link value's source (pc+4 comes from the fetch adder via WB_PC4).
    JAL   -> List(Y, BR_J,  OP1_PC,   OP2_IMM,  IMM_J, ALU_ADD,  WB_PC4, Y,      N,      N,      D,    N),
    JALR  -> List(Y, BR_JR, OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_PC4, Y,      N,      N,      D,    N),
    BEQ   -> List(Y, BR_EQ, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N),
    BNE   -> List(Y, BR_NE, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N),
    BLT   -> List(Y, BR_LT, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N),
    BGE   -> List(Y, BR_GE, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N),
    BLTU  -> List(Y, BR_LTU,OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N),
    BGEU  -> List(Y, BR_GEU,OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N),
    // ---- fences: no-ops in an in-order core with one outstanding access ----
    FENCE   -> List(Y, BR_N, OP1_ZERO, OP2_ZERO, IMM_X, ALU_ADD, WB_X,   N,      N,      N,      D,    N),
    FENCE_I -> List(Y, BR_N, OP1_ZERO, OP2_ZERO, IMM_X, ALU_ADD, WB_X,   N,      N,      N,      D,    N),
  )

  def apply(inst: UInt): CtrlSignals = {
    val d = ListLookup(inst, X, table)
    val cs = Wire(new CtrlSignals)
    cs.legal    := d(0).asBool
    cs.br_type  := d(1)
    cs.op1_sel  := d(2)
    cs.op2_sel  := d(3)
    cs.imm_sel  := d(4)
    cs.alu_fun  := d(5)
    cs.wb_sel   := d(6)
    cs.rf_wen   := d(7).asBool
    cs.mem_en   := d(8).asBool
    cs.mem_wr   := d(9).asBool
    cs.mem_size := d(10)
    cs.mem_sgn  := d(11).asBool
    cs
  }
}
