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


def main(path):
    seen, bad = {}, []
    for line in open(path):
        parts = line.split()
        if len(parts) < 3:
            continue
        inst, wr = parts[1], parts[2]
        if inst in EXPECTED and inst not in seen:
            seen[inst] = wr.split('=', 1)[1]

    for inst, want in EXPECTED.items():
        got = seen.get(inst)
        if got is None:
            bad.append(f'  {inst}: never executed')
        elif got != want:
            bad.append(f'  {inst}: expected {want}, got {got}')

    if bad:
        print(f'KNOWN-ANSWER FAILURES in {path}:')
        print('\n'.join(bad))
        return 1
    print(f'OK: {len(EXPECTED)} known-answer checks passed')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
