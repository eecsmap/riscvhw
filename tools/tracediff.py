#!/usr/bin/env python3
"""Compare two commit traces and report the first divergence.

    tracediff.py <reference.trace> <dut.trace> [-C context]

Line format (produced by both riscvm and the hardware testbench):

    <pc:016x> <inst:08x> [x<rd>=<value:016x>]

Reporting only the first divergence is deliberate. Once two implementations
disagree their architectural state differs, so everything after that point is
noise -- the first differing line is the bug, and the rest is its shadow.
"""
import sys, argparse


def parse(path):
    out = []
    with open(path) as f:
        for lineno, raw in enumerate(f, 1):
            line = raw.strip()
            if not line:
                continue
            parts = line.split()
            pc, inst = parts[0], parts[1]
            wr = parts[2] if len(parts) > 2 else None
            out.append((pc, inst, wr, lineno, line))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('ref')
    ap.add_argument('dut')
    ap.add_argument('-C', '--context', type=int, default=5)
    args = ap.parse_args()

    ref, dut = parse(args.ref), parse(args.dut)
    n = min(len(ref), len(dut))

    for i in range(n):
        r, d = ref[i], dut[i]
        if (r[0], r[1], r[2]) != (d[0], d[1], d[2]):
            lo = max(0, i - args.context)
            print(f'DIVERGE at retired instruction {i} '
                  f'(ref line {r[3]}, dut line {d[3]})\n')
            print(f'{"":>6}  {"reference (riscvm)":<44}  {"dut (hardware)":<44}')
            for j in range(lo, min(n, i + args.context + 1)):
                mark = '>>' if j == i else '  '
                print(f'{mark} {j:>4}  {ref[j][4]:<44}  {dut[j][4]:<44}')
            print()
            # name the field that differs, so the failure is actionable
            if r[0] != d[0]:
                print(f'  pc differs:        ref={r[0]}  dut={d[0]}')
            elif r[1] != d[1]:
                print(f'  instruction differs: ref={r[1]}  dut={d[1]}')
            else:
                print(f'  register write differs: ref={r[2]}  dut={d[2]}')
            return 1

    if len(ref) != len(dut):
        # A length difference is only meaningful if the traces were still going
        # somewhere. Both sides stop at the program's parking loop, but they can
        # stop after a different number of spins, which says nothing.
        tail = ref[n:] or dut[n:]
        if all(t[0] == tail[0][0] for t in tail):
            print(f'OK: {n} instructions identical '
                  f'(then {abs(len(ref) - len(dut))} extra spins at {tail[0][0]})')
            return 0
        print(f'traces agree on all {n} common instructions, but lengths differ: '
              f'ref={len(ref)} dut={len(dut)}')
        return 1

    print(f'OK: {n} instructions identical')
    return 0


if __name__ == '__main__':
    sys.exit(main())
