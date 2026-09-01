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
  val wb_sel  = UInt(3.W)
  val rf_wen  = Bool()
  val mem_en  = Bool()
  val mem_wr  = Bool()
  val mem_size= UInt(2.W)
  val mem_sgn = Bool()       // sign-extend sub-word loads
  val csr_cmd = UInt(3.W)    // CsrCmd; N for everything that is not a CSR access
  val ecall   = Bool()
  val ebreak  = Bool()
  val eret    = Bool()
}

object Decode {
  import riscvhw.mem.MemSize._

  //                 legal | br_type| op1_sel | op2_sel | imm_sel| alu_fun  | wb_sel| rf_wen| mem_en| mem_wr| size| signed| csr_cmd | ecall | ebreak | eret
  private val X = List(N, BR_N,  OP1_ZERO, OP2_ZERO, IMM_X, ALU_X,    WB_X,   N,      N,      N,      D,    N,     CsrCmd.N, N, N, N)

  val table: Array[(BitPat, List[UInt])] = Array(
    // ---- loads ----
    LB    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      B,    Y, CsrCmd.N, N, N, N),
    LH    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      H,    Y, CsrCmd.N, N, N, N),
    LW    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      W,    Y, CsrCmd.N, N, N, N),
    LD    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      D,    Y, CsrCmd.N, N, N, N),
    LBU   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      B,    N, CsrCmd.N, N, N, N),
    LHU   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      H,    N, CsrCmd.N, N, N, N),
    LWU   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_MEM, Y,      Y,      N,      W,    N, CsrCmd.N, N, N, N),
    // ---- stores ----
    SB    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      B,    N, CsrCmd.N, N, N, N),
    SH    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      H,    N, CsrCmd.N, N, N, N),
    SW    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      W,    N, CsrCmd.N, N, N, N),
    SD    -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_S, ALU_ADD,  WB_X,   N,      Y,      Y,      D,    N, CsrCmd.N, N, N, N),
    // ---- upper immediates ----
    // LUI is 0 + imm, not a dedicated copy op: OP1_ZERO exists precisely so the
    // ALU needs no pass-through function.
    LUI   -> List(Y, BR_N,  OP1_ZERO, OP2_IMM,  IMM_U, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    AUIPC -> List(Y, BR_N,  OP1_PC,   OP2_IMM,  IMM_U, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    // ---- register-immediate ----
    ADDI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLTI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLT,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLTIU -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLTU, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    XORI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_XOR,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    ORI   -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_OR,   WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    ANDI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_AND,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLLI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLL,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRLI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRL,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRAI  -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRA,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    // ---- register-register ----
    ADD   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_ADD,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SUB   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SUB,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLL   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLL,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLT   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLT,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLTU  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLTU, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    XOR   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_XOR,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRL   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRL,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRA   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRA,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    OR    -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_OR,   WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    AND   -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_AND,  WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    // ---- RV64 word forms ----
    ADDIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADDW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLLIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SLLW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRLIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRLW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRAIW -> List(Y, BR_N,  OP1_RS1,  OP2_IMM,  IMM_I, ALU_SRAW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    ADDW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_ADDW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SUBW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SUBW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SLLW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SLLW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRLW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRLW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    SRAW  -> List(Y, BR_N,  OP1_RS1,  OP2_RS2,  IMM_X, ALU_SRAW, WB_ALU, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    // ---- control transfer ----
    // JAL/JALR compute their target in a dedicated adder, so the ALU is free to
    // produce the link value's source (pc+4 comes from the fetch adder via WB_PC4).
    JAL   -> List(Y, BR_J,  OP1_PC,   OP2_IMM,  IMM_J, ALU_ADD,  WB_PC4, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    JALR  -> List(Y, BR_JR, OP1_RS1,  OP2_IMM,  IMM_I, ALU_ADD,  WB_PC4, Y,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    BEQ   -> List(Y, BR_EQ, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    BNE   -> List(Y, BR_NE, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    BLT   -> List(Y, BR_LT, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    BGE   -> List(Y, BR_GE, OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    BLTU  -> List(Y, BR_LTU,OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    BGEU  -> List(Y, BR_GEU,OP1_PC,   OP2_IMM,  IMM_B, ALU_ADD,  WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    // ---- CSR access ----
    // rd receives the OLD value, so wb_sel is WB_CSR; the new value comes from
    // the ALU, which just passes op1 through by adding zero.
    CSRRW  -> List(Y, BR_N, OP1_RS1,  OP2_ZERO, IMM_X, ALU_ADD, WB_CSR, Y, N, N, D, N, CsrCmd.W, N, N, N),
    CSRRS  -> List(Y, BR_N, OP1_RS1,  OP2_ZERO, IMM_X, ALU_ADD, WB_CSR, Y, N, N, D, N, CsrCmd.S, N, N, N),
    CSRRC  -> List(Y, BR_N, OP1_RS1,  OP2_ZERO, IMM_X, ALU_ADD, WB_CSR, Y, N, N, D, N, CsrCmd.C, N, N, N),
    CSRRWI -> List(Y, BR_N, OP1_ZIMM, OP2_ZERO, IMM_X, ALU_ADD, WB_CSR, Y, N, N, D, N, CsrCmd.W, N, N, N),
    CSRRSI -> List(Y, BR_N, OP1_ZIMM, OP2_ZERO, IMM_X, ALU_ADD, WB_CSR, Y, N, N, D, N, CsrCmd.S, N, N, N),
    CSRRCI -> List(Y, BR_N, OP1_ZIMM, OP2_ZERO, IMM_X, ALU_ADD, WB_CSR, Y, N, N, D, N, CsrCmd.C, N, N, N),
    // ---- environment calls: legal instructions whose only effect is a trap ----
    ECALL  -> List(Y, BR_N, OP1_ZERO, OP2_ZERO, IMM_X, ALU_ADD, WB_X, N, N, N, D, N, CsrCmd.N, Y, N, N),
    EBREAK -> List(Y, BR_N, OP1_ZERO, OP2_ZERO, IMM_X, ALU_ADD, WB_X, N, N, N, D, N, CsrCmd.N, N, Y, N),
    // MRET restores the pc from mepc and pops the interrupt-enable stack. It is
    // not a branch: its target is architectural state, not a computed address.
    MRET   -> List(Y, BR_N, OP1_ZERO, OP2_ZERO, IMM_X, ALU_ADD, WB_X, N, N, N, D, N, CsrCmd.N, N, N, Y),
    // ---- fences: no-ops in an in-order core with one outstanding access ----
    FENCE   -> List(Y, BR_N, OP1_ZERO, OP2_ZERO, IMM_X, ALU_ADD, WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
    FENCE_I -> List(Y, BR_N, OP1_ZERO, OP2_ZERO, IMM_X, ALU_ADD, WB_X,   N,      N,      N,      D,    N, CsrCmd.N, N, N, N),
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
    cs.csr_cmd  := d(12)
    cs.ecall    := d(13).asBool
    cs.ebreak   := d(14).asBool
    cs.eret     := d(15).asBool
    cs
  }
}
