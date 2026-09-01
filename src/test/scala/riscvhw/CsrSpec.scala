package riscvhw

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import riscvhw.core._

/** Unit tests for the CSR file.
  *
  * This module is not wired into the core yet, so co-simulation cannot reach
  * it. These tests stand in until it is: they cover the access rules that are
  * easy to get subtly wrong and that riscv-tests will later check -- read-only
  * enforcement, the read-without-write command, and the WARL fields that fold
  * an illegal write to a legal value instead of raising an exception.
  */
class CsrSpec extends AnyFlatSpec with ChiselScalatestTester {
  implicit val cfg: RiscvhwConfig = RiscvhwConfig()

  private def idle(dut: CsrFile): Unit = {
    dut.io.rw.cmd.poke(CsrCmd.N)
    dut.io.trap.valid.poke(false.B)
    dut.io.eret.poke(false.B)
  }

  private def write(dut: CsrFile, addr: Int, value: BigInt): Unit = {
    dut.io.rw.addr.poke(addr.U)
    dut.io.rw.cmd.poke(CsrCmd.W)
    dut.io.rw.wdata.poke(value.U)
    dut.clock.step(1)
    idle(dut)
  }

  private def read(dut: CsrFile, addr: Int): BigInt = {
    dut.io.rw.addr.poke(addr.U)
    dut.io.rw.cmd.poke(CsrCmd.R)
    val v = dut.io.rw.rdata.peek().litValue
    idle(dut)
    v
  }

  behavior of "CsrFile"

  it should "read back what was written to a plain register" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      write(dut, CsrAddr.mscratch, BigInt("0123456789abcdef", 16))
      assert(read(dut, CsrAddr.mscratch) == BigInt("0123456789abcdef", 16))
    }
  }

  it should "reject access to an unimplemented CSR" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      dut.io.rw.addr.poke(0x3ff.U)     // not in the implemented set
      dut.io.rw.cmd.poke(CsrCmd.R)
      dut.io.rw.illegal.expect(true.B)
    }
  }

  it should "reject a write to a read-only CSR but allow reading it" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(7.U)
      dut.io.rw.addr.poke(CsrAddr.mhartid.U)
      dut.io.rw.cmd.poke(CsrCmd.R)
      dut.io.rw.illegal.expect(false.B)
      dut.io.rw.rdata.expect(7.U)
      dut.io.rw.cmd.poke(CsrCmd.W)     // address bits 11:10 == 11 -> read-only
      dut.io.rw.illegal.expect(true.B)
    }
  }

  it should "set and clear bits without disturbing the rest" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      write(dut, CsrAddr.mscratch, BigInt("f0", 16))
      dut.io.rw.addr.poke(CsrAddr.mscratch.U)
      dut.io.rw.cmd.poke(CsrCmd.S); dut.io.rw.wdata.poke(BigInt("0f", 16).U)
      dut.clock.step(1); idle(dut)
      assert(read(dut, CsrAddr.mscratch) == BigInt("ff", 16))
      dut.io.rw.addr.poke(CsrAddr.mscratch.U)
      dut.io.rw.cmd.poke(CsrCmd.C); dut.io.rw.wdata.poke(BigInt("f0", 16).U)
      dut.clock.step(1); idle(dut)
      assert(read(dut, CsrAddr.mscratch) == BigInt("0f", 16))
    }
  }

  it should "not write at all under the read-only command" in {
    // `csrr rd, csr` is CSRRS with rs1 = x0, and must have no write side
    // effects -- not even a write of the value that is already there.
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      write(dut, CsrAddr.mscratch, 0x55.U.litValue)
      dut.io.rw.addr.poke(CsrAddr.mscratch.U)
      dut.io.rw.cmd.poke(CsrCmd.R); dut.io.rw.wdata.poke(BigInt("ffffffffffffffff", 16).U)
      dut.clock.step(1); idle(dut)
      assert(read(dut, CsrAddr.mscratch) == 0x55)
    }
  }

  it should "hardwire the low two bits of mepc to zero" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      write(dut, CsrAddr.mepc, BigInt("80000006", 16))
      assert(read(dut, CsrAddr.mepc) == BigInt("80000004", 16))
    }
  }

  it should "fold an illegal mtvec mode to a legal one rather than trapping" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      // mode 2 is reserved; WARL means it becomes a legal value silently
      write(dut, CsrAddr.mtvec, BigInt("80000002", 16))
      assert(read(dut, CsrAddr.mtvec) == BigInt("80000000", 16))
      // mode 1 (vectored) is legal and must survive
      write(dut, CsrAddr.mtvec, BigInt("80000001", 16))
      assert(read(dut, CsrAddr.mtvec) == BigInt("80000001", 16))
    }
  }

  it should "read mstatus back in its architectural bit positions" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      write(dut, CsrAddr.mstatus, BigInt("8", 16))          // MIE = bit 3
      assert((read(dut, CsrAddr.mstatus) & 0x8) == 0x8)
      // reserved bits are not storage: writing them changes nothing
      write(dut, CsrAddr.mstatus, BigInt("ffffffffffffffff", 16))
      val v = read(dut, CsrAddr.mstatus)
      assert((v & ~BigInt("1888", 16)) == 0, f"reserved bits became writable: $v%x")
    }
  }

  it should "stack the interrupt-enable bit across trap and return" in {
    test(new CsrFile) { dut =>
      idle(dut); dut.io.hartid.poke(0.U)
      write(dut, CsrAddr.mstatus, BigInt("8", 16))           // MIE = 1

      dut.io.trap.valid.poke(true.B)
      dut.io.trap.cause.poke(2.U)
      dut.io.trap.epc.poke(BigInt("80001000", 16).U)
      dut.io.trap.tval.poke(BigInt("deadbeef", 16).U)
      dut.clock.step(1); idle(dut)

      val afterTrap = read(dut, CsrAddr.mstatus)
      assert((afterTrap & 0x8) == 0,    "MIE must be cleared on trap entry")
      assert((afterTrap & 0x80) == 0x80, "MPIE must hold the old MIE")
      assert(read(dut, CsrAddr.mepc) == BigInt("80001000", 16))
      assert(read(dut, CsrAddr.mcause) == 2)
      assert(read(dut, CsrAddr.mtval) == BigInt("deadbeef", 16))

      dut.io.eret.poke(true.B)
      dut.clock.step(1); idle(dut)
      val afterRet = read(dut, CsrAddr.mstatus)
      assert((afterRet & 0x8) == 0x8,    "MIE must be restored from MPIE on MRET")
      assert((afterRet & 0x80) == 0x80,  "MPIE must be set to 1 on MRET")
    }
  }
}
