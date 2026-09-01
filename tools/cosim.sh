#!/usr/bin/env bash
# Run a program on both riscvm and the hardware, then compare commit traces.
#
#   tools/cosim.sh tests/rv64i_basic
#
# Any program is a test: correctness is defined by agreeing with the reference
# model, so the program needs no self-checking protocol -- and stage 0 therefore
# needs no CSRs, which the riscv-tests `p` environment would demand before a
# single instruction ran.
set -e

BASE=${1:?usage: cosim.sh <test-basename-without-extension>}
LIMIT=${2:-2000}
RISCVM=${RISCVM:-$HOME/github/riscvm}
LAB=$(cd "$(dirname "$0")/.." && pwd)

cd "$LAB"
[ -f "$BASE.bin" ] || { echo "missing $BASE.bin -- run make in tests/"; exit 1; }

echo "== reference (riscvm) =="
(cd "$RISCVM" && python3 -m riscvm.emulator --plain --address 0x80000000 \
    --trace "$LAB/$BASE.ref.trace" --limit "$LIMIT" "$LAB/$BASE.bin" >/dev/null 2>&1) || true

echo "== dut (hardware) =="
sbt -batch "testOnly riscvhw.CosimSpec" > /dev/null

echo "== compare =="
python3 tools/tracediff.py "$BASE.ref.trace" "$BASE.hw.trace"
