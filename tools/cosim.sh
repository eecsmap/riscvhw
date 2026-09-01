#!/usr/bin/env bash
# Run every program in tests/ on both riscvm and the hardware, and compare
# commit traces.
#
#   tools/cosim.sh            # all programs
#   tools/cosim.sh rv64i_edge # just one
#
# Any program is a test: correctness is defined by agreeing with the reference
# model, so no program needs a self-checking protocol -- which is why stage 0
# can be verified thoroughly without any CSR support.
set -u

RISCVM=${RISCVM:-$HOME/github/riscvm}
LAB=$(cd "$(dirname "$0")/.." && pwd)
LIMIT=${LIMIT:-4000}
cd "$LAB"

if [ $# -gt 0 ]; then PROGS=("$@"); else
  mapfile -t PROGS < <(cd tests && ls *.S | sed 's/\.S$//')
fi

echo "== building hardware traces =="
sbt -batch "testOnly riscvhw.CosimSpec" > /tmp/cosim-sbt.log 2>&1 || {
  echo "hardware run failed; see /tmp/cosim-sbt.log"; tail -20 /tmp/cosim-sbt.log; exit 1; }

fail=0
for p in "${PROGS[@]}"; do
  [ -f "tests/$p.bin" ] || { echo "!! tests/$p.bin missing (run make in tests/)"; fail=1; continue; }

  (cd "$RISCVM" && python3 -m riscvm.emulator --plain --address 0x80000000 \
      --trace "$LAB/tests/$p.ref.trace" --limit "$LIMIT" "$LAB/tests/$p.bin" \
      >/dev/null 2>&1) || true

  echo
  echo "== $p =="
  printf '  %-22s ' 'hardware vs riscvm:'
  python3 tools/tracediff.py "tests/$p.ref.trace" "tests/$p.hw.trace" || fail=1
  printf '  %-22s ' 'fast vs slow memory:'
  python3 tools/tracediff.py "tests/$p.hw.trace" "tests/$p.slow.trace" || fail=1
  if [ "$p" = "rv64i_edge" ]; then
    # Co-simulation proves the two implementations agree; it cannot prove they
    # are both right. Check the cases where an implementation can be
    # self-consistently wrong against the spec directly.
    printf '  %-22s ' 'known answers:'
    python3 tools/check_known.py "tests/$p.hw.trace" || fail=1
  fi
done

echo
[ $fail -eq 0 ] && echo "ALL AGREE" || echo "DIVERGENCE FOUND"
exit $fail
