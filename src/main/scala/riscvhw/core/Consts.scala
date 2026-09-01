package riscvhw.core

import chisel3._

/** Control signal encodings.
  *
  * Note on don't-care values: Sodor encodes `X` as the same bit pattern as the
  * first real case (OP1_X == OP1_RS1), which saves mux width but means a
  * "don't care" silently selects rs1 -- easy to misread. Here every mux has an
  * explicit zero/none encoding so that reading the decode table means what it
  * says. The cost is at most one extra mux input, which is the right trade for
  * a core whose purpose is to be understood.
  */
object Consts {
  // PC select
  val PC_4    = 0.U(3.W)   // sequential
  val PC_BRJMP= 1.U(3.W)   // pc + imm      (branches and JAL)
  val PC_JALR = 2.U(3.W)   // (rs1 + imm) & ~1
  val PC_TRAP = 3.U(3.W)   // trap vector   (stage 2)
  val PC_EPC  = 4.U(3.W)   // trap return   (stage 2)

  // Branch type
  val BR_N    = 0.U(4.W)   // not a branch
  val BR_EQ   = 1.U(4.W)
  val BR_NE   = 2.U(4.W)
  val BR_LT   = 3.U(4.W)
  val BR_GE   = 4.U(4.W)
  val BR_LTU  = 5.U(4.W)
  val BR_GEU  = 6.U(4.W)
  val BR_J    = 7.U(4.W)   // unconditional, pc-relative
  val BR_JR   = 8.U(4.W)   // unconditional, register-relative

  // ALU operand 1
  val OP1_ZERO = 0.U(2.W)  // explicit zero, so LUI is a plain add
  val OP1_RS1  = 1.U(2.W)
  val OP1_PC   = 2.U(2.W)
  // 5-bit zero-extended rs1 field, the source for the CSRRxI forms. Routing it
  // through op1 with op2 = 0 and ALU_ADD means the CSR write data reuses the
  // ALU and needs no pass-through function of its own -- Sodor needs ALU_COPY1
  // only because its op1 mux has no explicit zero.
  val OP1_ZIMM = 3.U(2.W)

  // ALU operand 2
  val OP2_ZERO = 0.U(2.W)
  val OP2_RS2  = 1.U(2.W)
  val OP2_IMM  = 2.U(2.W)

  // Immediate format
  val IMM_X = 0.U(3.W)
  val IMM_I = 1.U(3.W)
  val IMM_S = 2.U(3.W)
  val IMM_B = 3.U(3.W)
  val IMM_U = 4.U(3.W)
  val IMM_J = 5.U(3.W)

  // ALU function
  val ALU_ADD  =  0.U(5.W)
  val ALU_SUB  =  1.U(5.W)
  val ALU_SLL  =  2.U(5.W)
  val ALU_SLT  =  3.U(5.W)
  val ALU_SLTU =  4.U(5.W)
  val ALU_XOR  =  5.U(5.W)
  val ALU_SRL  =  6.U(5.W)
  val ALU_SRA  =  7.U(5.W)
  val ALU_OR   =  8.U(5.W)
  val ALU_AND  =  9.U(5.W)
  // RV64 word forms: compute on 32 bits, sign-extend the result to 64
  val ALU_ADDW = 10.U(5.W)
  val ALU_SUBW = 11.U(5.W)
  val ALU_SLLW = 12.U(5.W)
  val ALU_SRLW = 13.U(5.W)
  val ALU_SRAW = 14.U(5.W)
  val ALU_X    =  0.U(5.W)

  // Writeback select
  val WB_X    = 0.U(3.W)
  val WB_ALU  = 1.U(3.W)
  val WB_MEM  = 2.U(3.W)
  val WB_PC4  = 3.U(3.W)   // link register for JAL/JALR
  val WB_CSR  = 4.U(3.W)   // old CSR value

  val Y = true.B
  val N = false.B
}
