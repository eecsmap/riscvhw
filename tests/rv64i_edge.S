# Boundary cases for RV64I.
#
# rv64i_basic.S establishes that the core runs; this one goes after the places
# implementations actually get wrong. Every case here is a bug that has been
# shipped in a real emulator or core at some point -- the SRL shift-amount width
# below is one that was live in riscvm until co-simulation caught it.

  .section .text
  .globl _start
_start:

  # ============ shift amounts: the RV64 6-bit vs W-form 5-bit split ============
  # RV64 register shifts take rs2[5:0]; RV32 takes rs2[4:0]. An implementation
  # using 5 bits is correct for every shift under 32 and wrong above it, so the
  # bug hides until something shifts far.
  addi  x1, x0, 1
  addi  x2, x0, 63
  sll   x3, x1, x2            # 1 << 63 -> 0x8000000000000000
  addi  x4, x0, 32
  sll   x5, x1, x4            # 1 << 32  -- wrong if the mask is 5 bits
  srl   x6, x3, x2            # back to 1
  sra   x7, x3, x2            # arithmetic: 0x8..0 >> 63 -> -1

  # shift amounts are masked, not saturated: 64 masks to 0, 65 masks to 1
  addi  x8, x0, 64
  sll   x9, x1, x8            # -> 1  (no shift at all)
  addi  x10, x0, 65
  sll   x11, x1, x10          # -> 2
  addi  x12, x0, -1           # 0xffff...ff, low 6 bits = 63
  sll   x13, x1, x12          # -> 1 << 63

  # immediate shifts use the 6-bit shamt field
  slli  x14, x1, 63
  srli  x15, x14, 63
  srai  x16, x14, 63          # -> -1

  # W-form shifts mask to 5 bits and sign-extend the 32-bit result
  addi  x17, x0, 1
  addi  x18, x0, 31
  sllw  x19, x17, x18         # 1 << 31 as a word -> 0xffffffff80000000
  addi  x20, x0, 32
  sllw  x21, x17, x20         # masks to 0 -> 1
  srlw  x22, x19, x18         # logical on the word -> 1
  sraw  x23, x19, x18         # arithmetic on the word -> -1

  # ============ W forms must sign-extend, always ============
  lui   x24, 0x40000          # 0x40000000
  addw  x25, x24, x24         # 0x80000000 as a word -> sign-extends to negative
  addiw x26, x24, 0           # positive, stays positive
  # overflow wraps within 32 bits before extension
  lui   x27, 0x7ffff
  addi  x27, x27, 0x7ff       # near INT32_MAX
  addiw x28, x27, 1

  # ============ signed vs unsigned comparison ============
  addi  x1, x0, -1
  addi  x2, x0, 1
  slt   x3, x1, x2            # -1 < 1 signed   -> 1
  sltu  x4, x1, x2            # huge < 1 unsign -> 0
  slti  x5, x1, 0             # -1 < 0          -> 1
  sltiu x6, x1, 0             # huge < 0        -> 0
  sltiu x7, x2, -1            # 1 < 0xffff..ff  -> 1  (imm is sign-extended
                              #                        then compared unsigned)

  # ============ loads: sign vs zero extension at every width ============
  la    x8, scratch
  li    x9, -1
  sd    x9, 0(x8)
  lb    x10, 0(x8)            # -1
  lbu   x11, 0(x8)            # 255
  lh    x12, 0(x8)            # -1
  lhu   x13, 0(x8)            # 65535
  lw    x14, 0(x8)            # -1
  lwu   x15, 0(x8)            # 0xffffffff
  ld    x16, 0(x8)            # -1

  # byte-granular addressing: each byte of a word individually
  li    x17, 0x8877665544332211
  sd    x17, 8(x8)
  lbu   x18, 8(x8)            # 0x11 (little-endian: lowest byte first)
  lbu   x19, 9(x8)            # 0x22
  lbu   x20, 15(x8)           # 0x88
  lhu   x21, 14(x8)           # 0x8877
  lwu   x22, 12(x8)           # 0x88776655

  # partial stores must not disturb neighbouring bytes
  li    x23, 0
  sd    x23, 16(x8)
  li    x24, 0xff
  sb    x24, 17(x8)
  ld    x25, 16(x8)           # -> 0x000000000000ff00

  # ============ x0 is immovable ============
  li    x0, 12345
  add   x0, x1, x1
  ld    x0, 0(x8)
  slt   x0, x1, x2
  add   x26, x0, x0           # still 0

  # ============ branch boundaries ============
  # signed vs unsigned comparison at the sign boundary
  li    x27, -1
  li    x28, 1
  blt   x27, x28, 1f          # signed: taken
  add   x29, x0, x0
1:
  bltu  x28, x27, 2f          # unsigned: taken
  add   x30, x0, x0
2:
  beq   x0, x0, 3f            # x0 == x0 always
  add   x31, x0, x0
3:

  # ============ JALR clears the low bit ============
  la    x1, jtarget
  addi  x1, x1, 1             # deliberately misaligned target
  jalr  x2, 0(x1)             # must land on jtarget, not jtarget+1
  j     done
jtarget:
  addi  x3, x0, 99
  jalr  x0, 0(x2)

done:
  # JAL with x0 as link discards the return address
  jal   x0, after
  addi  x4, x0, 1             # skipped
after:

halt:
  j     halt

  .align 3
scratch:
  .space 128
