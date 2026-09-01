package riscvhw.core

import chisel3._
import chisel3.util._
import riscvhw.RiscvhwConfig

/** Privilege levels. Stage 2 only ever runs in M; U and S are defined now
  * because MSTATUS.MPP has to hold one of them from the very first trap. */
object Priv {
  val U = 0.U(2.W)
  val S = 1.U(2.W)
  val M = 3.U(2.W)
}

/** CSR addresses, matching the set riscvm implements so the two can be
  * compared. Stage 2 needs only the M-mode block; the S-mode block is listed
  * for stage 7 but not yet backed by storage. */
object CsrAddr {
  val mstatus  = 0x300
  val misa     = 0x301
  val medeleg  = 0x302
  val mideleg  = 0x303
  val mie      = 0x304
  val mtvec    = 0x305
  val mscratch = 0x340
  val mepc     = 0x341
  val mcause   = 0x342
  val mtval    = 0x343
  val mip      = 0x344
  val mhartid  = 0xf14

  /** Every address stage 2 answers to. Anything else raises illegal-instruction,
    * which is what the specification requires and what riscv-tests checks. */
  val implemented = Seq(mstatus, misa, medeleg, mideleg, mie, mtvec,
                        mscratch, mepc, mcause, mtval, mip, mhartid)
}

/** CSR access command. The read-only form exists because CSRRS/CSRRC with
  * rs1 = x0 must not write at all -- not even a no-op write, since a write can
  * have side effects. `csrr rd, csr` is exactly that encoding, so getting this
  * wrong breaks every plain CSR read. */
object CsrCmd {
  val N = 0.U(3.W)   // no access
  val W = 1.U(3.W)   // write
  val S = 2.U(3.W)   // set bits
  val C = 3.U(3.W)   // clear bits
  val R = 4.U(3.W)   // read only, no write side effects
}

/** Trap causes, from the privileged specification. */
object Cause {
  val InstAddrMisaligned = 0
  val IllegalInstruction = 2
  val Breakpoint         = 3
  val LoadAddrMisaligned = 4
  val StoreAddrMisaligned= 6
  val EcallU             = 8
  val EcallS             = 9
  val EcallM             = 11
}

/** MSTATUS, held as named fields rather than one opaque 64-bit register.
  *
  * The fields are the whole point: a trap pushes MIE onto MPIE and records the
  * previous privilege in MPP, and MRET pops them back. Storing the register raw
  * would make that shuffle a set of bit-slicing expressions scattered across the
  * core; naming the fields keeps the trap/return protocol readable and keeps
  * the reserved bits from ever being writable by accident.
  */
class MStatus extends Bundle {
  val mpp  = UInt(2.W)   // privilege the trap came from
  val mpie = Bool()      // interrupt-enable saved across the trap
  val mie  = Bool()      // interrupt enable
}

class CsrPort(implicit c: RiscvhwConfig) extends Bundle {
  val addr  = Input(UInt(12.W))
  val cmd   = Input(UInt(3.W))
  val wdata = Input(UInt(c.xlen.W))
  val rdata = Output(UInt(c.xlen.W))
  /** Access to an unimplemented CSR, or a write to a read-only one. */
  val illegal = Output(Bool())
}

/** The M-mode control and status registers.
  *
  * Deliberately holds only architectural state and its access rules. Trap
  * sequencing -- deciding when to take a trap, what to record, where to jump --
  * lives in the core, so that this module stays readable as "what the registers
  * are" rather than "what happens on a trap".
  */
class CsrFile(implicit c: RiscvhwConfig) extends Module {
  val io = IO(new Bundle {
    val rw   = new CsrPort
    val priv = Output(UInt(2.W))

    /** Trap entry, driven by the core. */
    val trap = Input(new Bundle {
      val valid = Bool()
      val cause = UInt(c.xlen.W)
      val epc   = UInt(c.xlen.W)
      val tval  = UInt(c.xlen.W)
    })
    /** MRET. */
    val eret = Input(Bool())

    /** Where to jump on a trap, and where to return to on MRET. */
    val trapVector = Output(UInt(c.xlen.W))
    val epc        = Output(UInt(c.xlen.W))

    val hartid = Input(UInt(c.xlen.W))
  })

  val priv = RegInit(Priv.M)          // reset into machine mode

  val mstatus  = RegInit(0.U.asTypeOf(new MStatus))
  val mtvec    = RegInit(0.U(c.xlen.W))
  val mepc     = RegInit(0.U(c.xlen.W))
  val mcause   = RegInit(0.U(c.xlen.W))
  val mtval    = RegInit(0.U(c.xlen.W))
  val mscratch = RegInit(0.U(c.xlen.W))
  val mie      = RegInit(0.U(c.xlen.W))
  val mip      = RegInit(0.U(c.xlen.W))
  val medeleg  = RegInit(0.U(c.xlen.W))
  val mideleg  = RegInit(0.U(c.xlen.W))

  // MISA: RV64 (MXL=2 in the top two bits) with the I extension present.
  val misa = ((2.U(2.W) << (c.xlen - 2)) | (1.U << 8)).asUInt

  /** MSTATUS packed into its architectural bit positions for a CSR read. */
  def mstatusValue: UInt = Cat(
    0.U((c.xlen - 13).W),
    mstatus.mpp,          // 12:11
    0.U(3.W),             // 10:8
    mstatus.mpie,         // 7
    0.U(3.W),             // 6:4
    mstatus.mie,          // 3
    0.U(3.W))             // 2:0

  // ---------------- read ----------------
  val rdata = WireDefault(0.U(c.xlen.W))
  switch (io.rw.addr) {
    is (CsrAddr.mstatus.U)  { rdata := mstatusValue }
    is (CsrAddr.misa.U)     { rdata := misa }
    is (CsrAddr.medeleg.U)  { rdata := medeleg }
    is (CsrAddr.mideleg.U)  { rdata := mideleg }
    is (CsrAddr.mie.U)      { rdata := mie }
    is (CsrAddr.mtvec.U)    { rdata := mtvec }
    is (CsrAddr.mscratch.U) { rdata := mscratch }
    is (CsrAddr.mepc.U)     { rdata := mepc }
    is (CsrAddr.mcause.U)   { rdata := mcause }
    is (CsrAddr.mtval.U)    { rdata := mtval }
    is (CsrAddr.mip.U)      { rdata := mip }
    is (CsrAddr.mhartid.U)  { rdata := io.hartid }
  }
  io.rw.rdata := rdata

  // ---------------- legality ----------------
  val exists = CsrAddr.implemented.map(a => io.rw.addr === a.U).reduce(_ || _)
  // Bits 11:10 of a CSR address encode read/write permission: 11 means the
  // register is read-only, so any writing command to it is illegal.
  val readOnlyAddr = io.rw.addr(11, 10) === "b11".U
  val isWrite = io.rw.cmd === CsrCmd.W || io.rw.cmd === CsrCmd.S || io.rw.cmd === CsrCmd.C
  io.rw.illegal := (io.rw.cmd =/= CsrCmd.N) && (!exists || (isWrite && readOnlyAddr))

  // ---------------- write ----------------
  val wdata = MuxLookup(io.rw.cmd, rdata)(Seq(
    CsrCmd.W -> io.rw.wdata,
    CsrCmd.S -> (rdata | io.rw.wdata),
    CsrCmd.C -> (rdata & (~io.rw.wdata).asUInt)))

  val doWrite = isWrite && exists && !readOnlyAddr

  when (doWrite) {
    switch (io.rw.addr) {
      is (CsrAddr.mstatus.U) {
        // Only the fields that exist are writable; everything else reads as
        // zero and stays zero. MPP is WARL: with no U or S mode implemented
        // yet, M is the only legal value, so anything else folds to M.
        mstatus.mie  := wdata(3)
        mstatus.mpie := wdata(7)
        mstatus.mpp  := Mux(wdata(12, 11) === Priv.M, Priv.M, Priv.M)
      }
      // MTVEC's low two bits are the mode field: 0 = direct, 1 = vectored.
      // Values 2 and 3 are reserved, and WARL means an illegal write is folded
      // to a legal value rather than raising an exception.
      is (CsrAddr.mtvec.U)    { mtvec := Cat(wdata(c.xlen - 1, 2),
                                             Mux(wdata(1, 0) < 2.U, wdata(1, 0), 0.U(2.W))) }
      // MEPC always reads as a multiple of 4 without the C extension: the low
      // two bits are hardwired to zero rather than rejected.
      is (CsrAddr.mepc.U)     { mepc := Cat(wdata(c.xlen - 1, 2), 0.U(2.W)) }
      is (CsrAddr.mcause.U)   { mcause := wdata }
      is (CsrAddr.mtval.U)    { mtval := wdata }
      is (CsrAddr.mscratch.U) { mscratch := wdata }
      is (CsrAddr.mie.U)      { mie := wdata }
      is (CsrAddr.mip.U)      { mip := wdata }
      is (CsrAddr.medeleg.U)  { medeleg := wdata }
      is (CsrAddr.mideleg.U)  { mideleg := wdata }
    }
  }

  // ---------------- trap entry and return ----------------
  // These take priority over an explicit CSR write in the same cycle: a trap
  // and a CSR instruction cannot both retire, and if they could, the trap wins.
  when (io.trap.valid) {
    mepc         := Cat(io.trap.epc(c.xlen - 1, 2), 0.U(2.W))
    mcause       := io.trap.cause
    mtval        := io.trap.tval
    mstatus.mpie := mstatus.mie
    mstatus.mie  := false.B
    mstatus.mpp  := priv
    priv         := Priv.M
  } .elsewhen (io.eret) {
    mstatus.mie  := mstatus.mpie
    mstatus.mpie := true.B
    priv         := mstatus.mpp
    // MPP returns to the least-privileged supported mode, which is M while
    // neither U nor S exists yet.
    mstatus.mpp  := Priv.M
  }

  // Direct mode only for now: vectored mode changes the target for interrupts,
  // and stage 2 has no interrupts to vector.
  io.trapVector := Cat(mtvec(c.xlen - 1, 2), 0.U(2.W))
  io.epc        := mepc
  io.priv       := priv
}
