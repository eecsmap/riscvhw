# ECALL, co-simulated against riscvm.
#
# Only ECALL is compared here. riscvm raises a Python error for an illegal
# instruction rather than trapping, and does not check access alignment at all,
# so the other exception causes have no reference to compare against yet --
# they are covered by rv64_trap.S against the specification instead.

#define mtvec   0x305
#define mepc    0x341
#define mcause  0x342
#define mtval   0x343

  .section .text
  .globl _start
_start:
  la    x1, handler
  csrw  mtvec, x1

  li    x10, 0x1111           # marker: set before the call
  ecall
  # execution continues in the handler; there is no MRET yet, so it parks there

  li    x11, 0x2222           # must never execute
halt:
  j     halt

  .align 2
handler:
  csrr  x20, mcause           # 11: environment call from M-mode
  csrr  x21, mepc             # address of the ecall itself, not the next one
  csrr  x22, mtval            # 0 for an environment call
  li    x23, 0x3333           # marker: the handler ran
hhalt:
  j     hhalt
