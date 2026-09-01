#!/usr/bin/env python3
"""Known-answer checks, to complement co-simulation.

Co-simulation catches *disagreement* between two implementations. It cannot
catch a *shared* misunderstanding: if the hardware and riscvm both masked shift
amounts to five bits, the traces would agree and both would be wrong.

So a handful of values are checked here against the ISA specification directly,
by hand. These are the cases where an implementation can be self-consistently
wrong -- mostly widths, sign extension, and masking rules.

    check_known.py tests/rv64i_edge.hw.trace
"""
import sys

# (instruction encoding, expected write) -- keyed by encoding so the check does
# not depend on where in the program the instruction sits.
EXPECTED = {
    # sll x3, x1(=1), x2(=63)  -- RV64 takes six bits of rs2
    '002091b3': '8000000000000000',
    # sll x5, x1(=1), x4(=32)  -- the case a 5-bit mask gets wrong (would give 1)
    '004092b3': '0000000100000000',
    # srl x6, x3, x2(=63)
    '0021d333': '0000000000000001',
    # sra x7, x3(=0x8..0), x2(=63) -- arithmetic, keeps the sign
    '4021d3b3': 'ffffffffffffffff',
    # sll x9, x1(=1), x8(=64)  -- masks to 0: no shift, not a zero result
    '008094b3': '0000000000000001',
    # sll x11, x1(=1), x10(=65) -- masks to 1
    '00a095b3': '0000000000000002',
    # srai x16, x14(=0x8..0), 63
    '43f75813': 'ffffffffffffffff',
    # sllw x19, x17(=1), x18(=31) -- word result sign-extends to 64 bits
    '012899bb': 'ffffffff80000000',
}

# WARL fields, checked against the specification rather than against riscvm.
#
# "Write Any values, Read Legal values" lets an implementation choose WHICH
# legal value an illegal write folds to, so two conformant cores can disagree
# here and both be right -- riscvm stores mtvec's reserved mode bits verbatim.
# The same applies to anything that depends on machine configuration: riscvm
# implements the C extension and this core does not, so IALIGN differs and the
# two legitimately disagree about how many low bits mepc keeps.
#
# Co-simulation is the wrong instrument for both categories. These are the
# right one.
WARL = {
    # csrr sp, mtvec after writing 0x80002002 -- mode 2 is reserved, folds away
    '30502173': '0000000080002000',
    # csrr tp, mtvec after writing 0x80002001 -- mode 1 (vectored) is legal
    '30502273': '0000000080002001',
    # csrr t1, mepc after writing 0x80001006 -- IALIGN=32, so mepc[1:0] read zero
    '34102373': '0000000080001004',
    # csrr s0, mstatus after writing all ones -- only MIE, MPIE and MPP exist
    '30002473': '0000000000001888',
    # csrr a0, mstatus after MRET -- MPP goes to the least-privileged mode the
    # machine implements, which is M here because there is no U mode yet.
    # riscvm implements U and sets 0; both follow the specification.
    '30002573': '0000000000001888',
    # The interrupt-enable stack, observed at both ends of a trap.
    # csrr a3, mstatus inside the handler: MIE cleared, MPIE holding the old MIE
    '300026f3': '0000000000001880',
    # csrr a5, mstatus after the MRET: MIE restored from MPIE
    '300027f3': '0000000000001888',
}


# Exception causes and mtval contents, checked against the specification.
#
# Not co-simulated: riscvm raises a Python error for an illegal instruction
# rather than trapping, and never checks access alignment, so there is nothing
# to compare against. Once riscvm grows those traps these can move to
# co-simulation.
TRAP = {
    # csrr t0, mcause after `.word 0xffffffff`
    '342022f3': '0000000000000002',   # illegal instruction
    # csrr t1, mtval -- the offending encoding itself
    '34302373': '00000000ffffffff',
    # csrr s1, mcause after `ld` from scratch+1
    '342024f3': '0000000000000004',   # load address misaligned
    # csrr a0, mtval -- the effective address, not the base
    '34302573': '0000000080000081',
    # csrr a3, mcause after `sw` to scratch+2
    '342026f3': '0000000000000006',   # store address misaligned
    '34302773': '0000000080000082',
    # csrr a5, mcause after ebreak
    '342027f3': '0000000000000003',   # breakpoint
    # csrr a6, mepc -- the address of the ebreak, not the instruction after it
    '34102873': '000000008000006c',
}


def main(path):
    table = (WARL if 'warl' in path else
             TRAP if 'trap' in path else EXPECTED)
    seen, bad = {}, []
    for line in open(path):
        parts = line.split()
        if len(parts) < 3:
            continue
        inst, wr = parts[1], parts[2]
        if inst in table and inst not in seen:
            seen[inst] = wr.split('=', 1)[1]

    for inst, want in table.items():
        got = seen.get(inst)
        if got is None:
            bad.append(f'  {inst}: never executed')
        elif got != want:
            bad.append(f'  {inst}: expected {want}, got {got}')

    if bad:
        print(f'KNOWN-ANSWER FAILURES in {path}:')
        print('\n'.join(bad))
        return 1
    print(f'OK: {len(table)} known-answer checks passed')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
