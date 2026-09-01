# CSR access, verified against riscvm by co-simulation.
#
# Only registers with no side effects are touched here, and no trap is taken:
# trap sequencing arrives in the next commit. What is under test is the access
# semantics -- which operand writes, which value is returned, and the cases
# where an operand of x0 changes the meaning of the instruction.

#define mscratch 0x340
#define mtvec    0x305
#define mepc     0x341
#define mcause   0x342
#define mstatus  0x300
#define mhartid  0xf14

  .section .text
  .globl _start
_start:

  # ---- CSRRW: rd gets the OLD value, the CSR gets rs1 ----
  li    x1, 0x1234
  csrrw x2, mscratch, x1        # x2 = old mscratch (0), mscratch = 0x1234
  li    x3, 0x5678
  csrrw x4, mscratch, x3        # x4 = 0x1234, mscratch = 0x5678

  # ---- plain read: CSRRS with rs1 = x0, which must not write ----
  csrr  x5, mscratch            # x5 = 0x5678, mscratch unchanged

  # ---- CSRRS / CSRRC set and clear without disturbing other bits ----
  li    x6, 0xff
  csrrs x7, mscratch, x6        # x7 = old, mscratch |= 0xff
  csrr  x8, mscratch
  li    x9, 0x0f
  csrrc x10, mscratch, x9       # mscratch &= ~0x0f
  csrr  x11, mscratch

  # ---- immediate forms take a 5-bit zero-extended value, not a register ----
  csrrwi x12, mscratch, 31      # mscratch = 31, not the contents of x31
  csrr   x13, mscratch
  csrrsi x14, mscratch, 1
  csrr   x15, mscratch
  csrrci x16, mscratch, 16
  csrr   x17, mscratch

  # ---- rs1 = x0 means "do not write" for S and C, even with a nonzero rd ----
  li    x18, 0x4321
  csrrw x0, mscratch, x18       # rd = x0: still writes
  csrrs x19, mscratch, x0       # must NOT write
  csrr  x20, mscratch           # still 0x4321
  csrrc x21, mscratch, x0       # must NOT write
  csrr  x22, mscratch

  # zero as an immediate means the same thing
  csrrsi x23, mscratch, 0
  csrr   x24, mscratch

  # ---- mepc keeps an aligned value unchanged ----
  # Only an ALIGNED value is checked here. How many low bits mepc discards
  # depends on IALIGN, which depends on whether the C extension exists: this
  # core has no C, so mepc[1:0] read as zero, while riscvm does implement C and
  # keeps bit 1. Both are correct for their own machine, so an unaligned write
  # would be comparing two different machines rather than testing either.
  # tools/check_known.py covers this core's own behaviour instead.
  li     x25, 0x80001004
  csrrw  x26, mepc, x25
  csrr   x27, mepc

  # WARL fields are deliberately NOT co-simulated. "Write Any, Read Legal"
  # lets each implementation pick which legal value an illegal write folds to,
  # so two conformant cores can disagree and both be right. mtvec's reserved
  # modes and mstatus's reserved bits are therefore checked against the
  # specification in tools/check_known.py instead of against riscvm.

  # ---- a read-only CSR reads fine ----
  csrr   x6, mhartid

halt:
  j     halt
