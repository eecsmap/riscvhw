# WARL behaviour of this core's CSRs, checked against the specification.
#
# Not co-simulated: "Write Any values, Read Legal values" lets an implementation
# choose WHICH legal value an illegal write folds to, so two conformant cores
# can disagree here and both be correct. riscvm, for instance, stores mtvec's
# reserved mode bits verbatim. These are checked by tools/check_known.py against
# what the specification requires of this core's configuration.

#define mtvec    0x305
#define mepc     0x341
#define mstatus  0x300

  .section .text
  .globl _start
_start:
  # mtvec MODE: 0 (direct) and 1 (vectored) are legal; 2 and 3 are reserved
  li     x1, 0x80002002
  csrrw  x0, mtvec, x1
  csrr   x2, mtvec              # reserved mode folded away -> 0x80002000
  li     x3, 0x80002001
  csrrw  x0, mtvec, x3
  csrr   x4, mtvec              # vectored is legal -> unchanged

  # mepc: IALIGN is 32 without the C extension, so mepc[1:0] read as zero
  li     x5, 0x80001006
  csrrw  x0, mepc, x5
  csrr   x6, mepc               # -> 0x80001004

  # mstatus: reserved bits are not storage
  li     x7, -1
  csrrw  x0, mstatus, x7
  csrr   x8, mstatus            # only MIE, MPIE and MPP come back

  # MRET sets MPP to the least-privileged mode the machine implements. This
  # core has only M, so MPP stays M -- riscvm implements U and sets 0 there.
  # Both follow the specification; they just describe different machines.
  la     x9, after_mret
  csrw   mepc, x9
  mret
after_mret:
  csrr   x10, mstatus          # MPP == 3 (M), MPIE == 1, MIE popped from MPIE

  # MIE must survive a trap and return: set it, take a trap, come back.
  li     x11, 8                # MSTATUS.MIE
  csrw   mstatus, x11
  la     x12, h_ret
  csrw   mtvec, x12
  ecall
back:
  csrr   x15, mstatus          # after the return: MIE restored from MPIE

halt:
  j     halt

  # The handler must be out of line. Placing it directly after the ecall makes
  # mepc+4 land inside the handler itself, and the mret loops forever.
  .align 2
h_ret:
  csrr   x13, mstatus          # inside the handler: MIE cleared, MPIE holds it
  csrr   x14, mepc
  addi   x14, x14, 4
  csrw   mepc, x14
  mret
