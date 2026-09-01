# Trap return: the handler runs and control resumes where the trap left off.
#
# This is the first program where a trap is not the end of the story. It takes
# several traps in a row from the same handler, which is the pattern every real
# trap handler follows and the one that catches a core whose mepc or mstatus
# stacking is subtly wrong -- a single trap can look fine while the second one
# clobbers the first's saved state.

#define mtvec    0x305
#define mepc     0x341
#define mcause   0x342
#define mscratch 0x340
#define mstatus  0x300

  .section .text
  .globl _start
_start:
  la    x1, handler
  csrw  mtvec, x1
  li    x30, 0                 # count of traps taken

  # --- three ecalls in a row, all returning to the instruction after ---
  li    x5, 0x11
  ecall
  li    x6, 0x22               # must run after the first return
  ecall
  li    x7, 0x33
  ecall
  li    x8, 0x44

  # --- a trap from an illegal instruction, returning past it ---
  .word 0xffffffff
  li    x9, 0x55               # must run after the handler skips the fault

  # Reading mstatus is deliberately NOT done here. Any read of the whole
  # register diverges after an MRET, because MPP goes to the least-privileged
  # mode the machine implements -- U on riscvm, M on this core, which has no U
  # mode yet. Masking afterwards does not help: the csrr itself is the traced
  # instruction. tests/rv64_csr_warl.S checks the interrupt-enable stacking and
  # MPP against the specification instead, and this becomes comparable at
  # stage 7.

halt:
  j     halt

  .align 2
handler:
  addi  x30, x30, 1            # count this trap
  csrr  x31, mepc
  addi  x31, x31, 4            # resume at the instruction after the faulting one
  csrw  mepc, x31
  mret
