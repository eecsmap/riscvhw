package chipyard

import org.chipsalliance.cde.config.{Config, Field}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tile._
import riscvhw.chipyard.{RiscvhwTileAttachParams, RiscvhwTileParams, RiscvhwCoreParams}

class WithRiscvhwCore extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) =>
    val prev = up(TilesLocated(InSubsystem))
    val idOffset = up(NumTiles)
    Seq(RiscvhwTileAttachParams(
      tileParams = RiscvhwTileParams(
        tileId = idOffset,
        core = RiscvhwCoreParams()),
      crossingParams = RocketCrossingParams()
    )) ++ prev
  case NumTiles => up(NumTiles) + 1
})

/** A whole SoC around the hand-written core: real DRAM, a serial link to load
  * a program into it, and the usual peripherals. Unlike SodorConfig this keeps
  * the memory port, because the point of stage 3 is that the core reaches real
  * memory rather than a tile-local scratchpad. */
class RiscvhwConfig extends Config(
  new WithRiscvhwCore ++
  new chipyard.config.AbstractConfig)
