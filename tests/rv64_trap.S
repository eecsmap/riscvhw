# Exception causes other than ECALL, checked against the specification.
#
# Not co-simulated: riscvm raises a Python error for an illegal instruction
# instead of trapping, and never checks access alignment, so there is no
# reference behaviour to compare against. Until riscvm grows those traps, the
# specification is the only reference.
#
# Each case jumps to its own handler slot so the cause and mtval can be read
# before the next fault is triggered.

#define mtvec   0x305
#define mepc    0x341
#define mcause  0x342
#define mtval   0x343

  .section .text
  .globl _start
_start:
  la    x1, h_illegal
  csrw  mtvec, x1
  .word 0xffffffff            # not a valid encoding -> illegal instruction

  .align 2
h_illegal:
  csrr  x5, mcause            # 2
  csrr  x6, mtval             # the offending encoding, 0xffffffff

  la    x1, h_load
  csrw  mtvec, x1
  la    x7, scratch
  addi  x7, x7, 1             # deliberately misaligned
  ld    x8, 0(x7)             # -> load address misaligned

  .align 2
h_load:
  csrr  x9, mcause            # 4
  csrr  x10, mtval            # the effective address

  la    x1, h_store
  csrw  mtvec, x1
  la    x11, scratch
  addi  x11, x11, 2
  sw    x12, 0(x11)           # -> store address misaligned

  .align 2
h_store:
  csrr  x13, mcause           # 6
  csrr  x14, mtval

  la    x1, h_break
  csrw  mtvec, x1
  ebreak                      # -> breakpoint

  .align 2
h_break:
  csrr  x15, mcause           # 3
  csrr  x16, mepc             # address of the ebreak

halt:
  j     halt

  .align 3
scratch:
  .space 32
