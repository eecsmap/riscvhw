package riscvhw.chipyard

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.rocket._
import freechips.rocketchip.prci._
import riscvhw.{RiscvhwConfig => CoreCfg}
import riscvhw.core.Core

/** Core parameters as rocket-chip's tile infrastructure wants to see them.
  *
  * Most of these describe features this core does not have. They are stated
  * explicitly rather than inherited, because each `false` here is a promise the
  * surrounding SoC relies on -- and each one becomes a real decision at a later
  * stage: useVM at stage 7, useAtomics when xv6 needs them.
  */
case class RiscvhwCoreParams(
  bootFreqHz: BigInt = BigInt(100000000),
) extends CoreParams {
  val xLen = 64
  val pgLevels = 3                       // Sv39, once useVM turns on at stage 7
  val useVM: Boolean = false
  val useHypervisor: Boolean = false
  val useUser: Boolean = false
  val useSupervisor: Boolean = false
  val useDebug: Boolean = false
  val useAtomics: Boolean = false
  val useAtomicsOnlyForIO: Boolean = false
  val useCompressed: Boolean = false
  override val useVector: Boolean = false
  val useSCIE: Boolean = false
  val useRVE: Boolean = false
  val mulDiv: Option[MulDivParams] = None
  val fpu: Option[FPUParams] = None
  val nLocalInterrupts: Int = 0
  val useNMI: Boolean = false
  val nPMPs: Int = 0
  val pmpGranularity: Int = 4
  val nBreakpoints: Int = 0
  val useBPWatch: Boolean = false
  val mcontextWidth: Int = 0
  val scontextWidth: Int = 0
  val nPerfCounters: Int = 0
  val haveBasicCounters: Boolean = true
  val haveFSDirty: Boolean = false
  val misaWritable: Boolean = false
  val haveCFlush: Boolean = false
  val nL2TLBEntries: Int = 0
  val nL2TLBWays: Int = 0
  val mtvecInit: Option[BigInt] = Some(BigInt(0))
  val mtvecWritable: Boolean = true
  val instBits: Int = 32
  val lrscCycles: Int = 80
  val decodeWidth: Int = 1
  val fetchWidth: Int = 1
  val retireWidth: Int = 1
  val nPTECacheEntries: Int = 0
  val traceHasWdata: Boolean = false
  // Bit-manipulation and conditional-zero extensions: absent, and unlikely to
  // be added -- xv6 does not need them and they teach nothing this project is
  // about.
  val useZba: Boolean = false
  val useZbb: Boolean = false
  val useZbs: Boolean = false
  val useConditionalZero: Boolean = false
}

case class RiscvhwTileParams(
  name: Option[String] = Some("riscvhw_tile"),
  tileId: Int = 0,
  trace: Boolean = false,
  core: RiscvhwCoreParams = RiscvhwCoreParams(),
) extends InstantiableTileParams[RiscvhwTile] {
  val beuAddr: Option[BigInt] = None
  val blockerCtrlAddr: Option[BigInt] = None
  val btb: Option[BTBParams] = None
  val boundaryBuffers: Boolean = false
  // No caches and no scratchpad: the core goes straight to the bus, so a
  // program is loaded into DRAM over the serial link exactly as for Rocket.
  val dcache: Option[DCacheParams] = None
  val icache: Option[ICacheParams] = None
  val clockSinkParams: ClockSinkParameters = ClockSinkParameters()
  def instantiate(crossing: HierarchicalElementCrossingParamsLike, lookup: LookupByHartIdImpl)
                 (implicit p: Parameters): RiscvhwTile =
    new RiscvhwTile(this, crossing, lookup)
  val baseName = name.getOrElse("riscvhw_tile")
  val uniqueName = s"${baseName}_$tileId"
}

case class RiscvhwTileAttachParams(
  tileParams: RiscvhwTileParams,
  crossingParams: RocketCrossingParams
) extends CanAttachTile {
  type TileType = RiscvhwTile
  val lookup = PriorityMuxHartIdFromSeq(Seq(tileParams))
}

class RiscvhwTile(
  val riscvhwParams: RiscvhwTileParams,
  crossing: ClockCrossingType,
  lookup: LookupByHartIdImpl,
  q: Parameters
) extends BaseTile(riscvhwParams, crossing, lookup, q)
  with SinksExternalInterrupts
  with SourcesExternalNotifications {

  def this(params: RiscvhwTileParams, crossing: HierarchicalElementCrossingParamsLike,
           lookup: LookupByHartIdImpl)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p)

  val intOutwardNode = None
  val masterNode = visibilityNode
  val slaveNode = TLIdentityNode()

  tlOtherMastersNode := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  implicit val coreCfg: CoreCfg = CoreCfg()

  // Instruction and data get their own master, matching the core's two ports.
  // Sharing one would serialise every fetch behind every load for no benefit
  // while the core issues one access at a time anyway.
  // The device tree entry the SoC generates for this hart, so a kernel can find
  // it. The compatible string names the core, not the ISA -- software matches on
  // "riscv" for the generic case.
  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("eecsmap,riscvhw", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++ cpuProperties ++ nextLevelCacheProperty ++ tileProperties)
    }
  }

  ResourceBinding { Resource(cpuDevice, "reg").bind(ResourceAddress(tileId)) }

  val imemAdapter = LazyModule(new TLMasterAdapter("riscvhw-imem"))
  val dmemAdapter = LazyModule(new TLMasterAdapter("riscvhw-dmem"))
  tlMasterXbar.node := imemAdapter.node
  tlMasterXbar.node := dmemAdapter.node

  override lazy val module = new RiscvhwTileModuleImp(this)
}

class RiscvhwTileModuleImp(outer: RiscvhwTile) extends BaseTileModuleImp(outer) {
  implicit val coreCfg: CoreCfg = outer.coreCfg
  val core = Module(new Core)

  // The SoC decides where this hart starts: Chipyard points it at the boot ROM,
  // which waits for the program to arrive over the serial link before jumping
  // to DRAM.
  core.io.resetVector := outer.resetVectorSinkNode.bundle

  core.io.imem <> outer.imemAdapter.module.io.mem
  core.io.dmem <> outer.dmemAdapter.module.io.mem
}
