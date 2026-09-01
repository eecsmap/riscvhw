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
) {
  require(xlen == 64, "only RV64 is implemented")
}
