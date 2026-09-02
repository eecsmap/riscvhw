package riscvhw

import chisel3._

/** Machine parameters.
  *
  * RV64 is deliberate: the existing `riscvm` Python emulator is RV64 and already
  * runs the xv6 kernel, so it can serve as the golden model for co-simulation.
  * Keeping the hardware on the same ISA preserves that reference.
  */
case class RiscvhwConfig(
  xlen:        Int     = 64,
  // Where the core starts fetching after reset.
  resetVector: BigInt  = BigInt("80000000", 16),
  // Scratchpad size for stages 0-2; replaced by a real memory port at stage 3.
  memBytes:    Int     = 1 << 20,
  memBase:     BigInt  = BigInt("80000000", 16),
  // Emit a commit trace for co-simulation against riscvm.
  trace:       Boolean = true,
  // Where riscv-tests write their result. The suite has no other way to report:
  // it signals completion by storing to this address, 1 for pass and
  // (n << 1) | 1 for a failure in test n. Watching for that store is the whole
  // host interface a bare test needs.
  tohostAddr:  BigInt = BigInt("80001000", 16),
) {
  require(xlen == 64, "only RV64 is implemented")
}
