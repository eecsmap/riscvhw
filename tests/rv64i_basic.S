# Stage-0 co-simulation program: exercise RV64I without any CSR or trap.
#
# There is no pass/fail protocol on purpose. Correctness is established by
# comparing the commit trace against riscvm instruction by instruction, which
# means the program does not have to check itself -- it only has to be varied.

  .section .text
  .globl _start
_start:
  # --- immediates and upper immediates ---
  lui   x1, 0x12345
  addi  x1, x1, 0x678
  auipc x2, 0x1
  addi  x3, x0, -1
  addi  x4, x0, 2047

  # --- register-register arithmetic ---
  add   x5, x1, x4
  sub   x6, x1, x4
  and   x7, x1, x3
  or    x8, x1, x4
  xor   x9, x1, x3
  slt   x10, x3, x4          # -1 < 2047 signed -> 1
  sltu  x11, x3, x4          # huge unsigned    -> 0
  sltiu x12, x4, 2047

  # --- shifts, including the RV64 6-bit shamt ---
  slli  x13, x4, 40
  srli  x14, x13, 20
  srai  x15, x3, 3           # arithmetic: stays -1
  sll   x16, x4, x4          # shamt uses low 6 bits of x4
  srl   x17, x13, x4
  sra   x18, x3, x4

  # --- RV64 word forms: compute on 32 bits, sign-extend to 64 ---
  lui   x19, 0x80000         # 0xffffffff80000000 after sext
  addiw x20, x19, -1         # word add, sign-extended
  addw  x21, x19, x4
  subw  x22, x19, x4
  slliw x23, x3, 20          # x3 = -1 -> 0xfff00000, negative as a word
  sraiw x24, x23, 4          # arithmetic: keeps the sign
  srliw x25, x23, 4          # logical: does not
  sllw  x26, x4, x4
  subw  x27, x0, x4

  # --- loads and stores at every width ---
  # PC-relative, because LUI sign-extends in RV64: `lui x28, 0x80002` yields
  # 0xffffffff80002000, not 0x80002000.
  la    x28, scratch
  sd    x1, 0(x28)
  ld    x29, 0(x28)
  sw    x3, 8(x28)
  lw    x30, 8(x28)           # sign-extended
  lwu   x31, 8(x28)           # zero-extended
  sh    x4, 16(x28)
  lh    x5, 16(x28)
  lhu   x6, 16(x28)
  sb    x3, 24(x28)
  lb    x7, 24(x28)
  lbu   x8, 24(x28)

  # --- branches: each taken and not-taken at least once ---
  addi  x9, x0, 5
  addi  x10, x0, 5
  beq   x9, x10, 1f
  addi  x11, x0, 111          # skipped
1:
  bne   x9, x10, 2f
  addi  x12, x0, 222          # executed
2:
  blt   x3, x9, 3f            # -1 < 5 -> taken
  addi  x13, x0, 333
3:
  bge   x9, x10, 4f           # 5 >= 5 -> taken
  addi  x14, x0, 444
4:
  bltu  x9, x3, 5f            # 5 <u huge -> taken
  addi  x15, x0, 555
5:
  bgeu  x9, x10, 6f
  addi  x16, x0, 666
6:

  # --- jumps and the link register ---
  jal   x17, target
  addi  x18, x0, 777          # return lands here
  j     done

target:
  addi  x19, x0, 888
  jalr  x0, 0(x17)

done:
  # --- x0 stays zero however hard we try ---
  addi  x0, x0, 42
  add   x0, x1, x1

  # Park. The testbench stops on instruction count, so a tight loop is enough.
halt:
  j     halt

  .align 3
scratch:
  .space 64
