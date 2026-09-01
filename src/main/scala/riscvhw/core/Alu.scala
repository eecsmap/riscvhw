package riscvhw.core

import chisel3._
import chisel3.util._
import Consts._
import riscvhw.RiscvhwConfig

/** The ALU.
  *
  * RV64 adds the "W" forms, which compute on the low 32 bits and sign-extend
  * the 32-bit result back to 64. They are separate ALU functions rather than a
  * width flag so the decode table stays a flat lookup with no side conditions.
  */
class Alu(implicit c: RiscvhwConfig) extends Module {
  val io = IO(new Bundle {
    val fn   = Input(UInt(5.W))
    val op1  = Input(UInt(c.xlen.W))
    val op2  = Input(UInt(c.xlen.W))
    val out  = Output(UInt(c.xlen.W))
  })

  // RV64 shifts use the low 6 bits; the W forms use the low 5.
  val shamt  = io.op2(5, 0)
  val shamtw = io.op2(4, 0)

  val op1w = io.op1(31, 0)
  val op2w = io.op2(31, 0)
  def sext32(x: UInt): UInt = Cat(Fill(32, x(31)), x(31, 0))

  io.out := MuxLookup(io.fn, 0.U)(Seq(
    ALU_ADD  -> (io.op1 + io.op2),
    ALU_SUB  -> (io.op1 - io.op2),
    ALU_SLL  -> (io.op1 << shamt)(c.xlen - 1, 0),
    ALU_SLT  -> (io.op1.asSInt < io.op2.asSInt).asUInt,
    ALU_SLTU -> (io.op1 < io.op2).asUInt,
    ALU_XOR  -> (io.op1 ^ io.op2),
    ALU_SRL  -> (io.op1 >> shamt),
    ALU_SRA  -> (io.op1.asSInt >> shamt).asUInt,
    ALU_OR   -> (io.op1 | io.op2),
    ALU_AND  -> (io.op1 & io.op2),
    // word forms
    ALU_ADDW -> sext32(op1w + op2w),
    ALU_SUBW -> sext32(op1w - op2w),
    ALU_SLLW -> sext32((op1w << shamtw)(31, 0)),
    ALU_SRLW -> sext32(op1w >> shamtw),
    ALU_SRAW -> sext32((op1w.asSInt >> shamtw).asUInt),
  ))
}

/** Immediate generation. Each format rearranges instruction bits differently;
  * RISC-V keeps each immediate bit at a fixed instruction position across
  * formats, which is why the concatenations look scrambled but cost no logic. */
object ImmGen {
  def apply(inst: UInt, sel: UInt, xlen: Int): UInt = {
    def sext(x: UInt, w: Int): UInt = Cat(Fill(xlen - w, x(w - 1)), x)
    val i = sext(inst(31, 20), 12)
    val s = sext(Cat(inst(31, 25), inst(11, 7)), 12)
    val b = sext(Cat(inst(31), inst(7), inst(30, 25), inst(11, 8), 0.U(1.W)), 13)
    val u = sext(Cat(inst(31, 12), 0.U(12.W)), 32)
    val j = sext(Cat(inst(31), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W)), 21)
    MuxLookup(sel, 0.U(xlen.W))(Seq(
      IMM_I -> i, IMM_S -> s, IMM_B -> b, IMM_U -> u, IMM_J -> j,
    ))
  }
}
