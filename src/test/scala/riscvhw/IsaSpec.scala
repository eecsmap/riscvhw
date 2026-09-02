package riscvhw

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.file.{Files, Paths}

/** The standard riscv-tests suite.
  *
  * These are the first tests here that check themselves. Every earlier program
  * relies on co-simulation, which needs no pass/fail protocol -- but that also
  * means it can only measure agreement with riscvm, not conformance to the
  * specification. riscv-tests is written against the specification and is
  * maintained by the people who wrote it, so it measures something the rest of
  * the suite structurally cannot.
  *
  * The result arrives as a store to `tohost`: 1 means pass, and (n << 1) | 1
  * means the test numbered n failed.
  */
object IsaTest {
  sealed trait Result
  case object Pass extends Result
  case class Fail(testNum: Int) extends Result
  case object Timeout extends Result

  def run(binPath: String, maxCycles: Int = 4000000): Result = {
    implicit val cfg: RiscvhwConfig = RiscvhwConfig()
    var result: Result = Timeout
    RawTester.test(new System()) { dut =>
      dut.clock.setTimeout(0)
      val bytes = Files.readAllBytes(Paths.get(binPath))

      dut.io.load.valid.poke(true.B)
      var off = 0
      while (off < bytes.length) {
        var word = BigInt(0)
        for (b <- 0 until 8) {
          val v = if (off + b < bytes.length) bytes(off + b) & 0xff else 0
          word |= BigInt(v) << (8 * b)
        }
        dut.io.load.bits.addr.poke((cfg.memBase + off).U)
        dut.io.load.bits.data.poke(word.U)
        dut.clock.step(1)
        off += 8
      }
      dut.io.load.valid.poke(false.B)
      dut.reset.poke(true.B); dut.clock.step(3); dut.reset.poke(false.B)

      var cycles = 0
      var done = false
      while (!done && cycles < maxCycles) {
        if (dut.io.tohost.valid.peek().litToBoolean) {
          val v = dut.io.tohost.bits.peek().litValue
          result = if (v == 1) Pass else Fail((v >> 1).toInt)
          done = true
        }
        dut.clock.step(1)
        cycles += 1
      }
    }
    result
  }
}

class IsaSpec extends AnyFlatSpec with ChiselScalatestTester {
  private val riscv = sys.env.getOrElse("RISCV", "")
  private val isaDir = s"$riscv/riscv64-unknown-elf/share/riscv-tests/isa"

  // rv64ui exercises the base integer instructions; rv64mi exercises machine
  // mode, which is what stage 2 added. Both are listed so a regression in the
  // datapath shows up here too, not only in co-simulation.
  private val ui = Seq("simple", "add", "addi", "addiw", "addw", "and", "andi",
    "auipc", "beq", "bge", "bgeu", "blt", "bltu", "bne", "jal", "jalr", "lb",
    "lbu", "ld", "lh", "lhu", "lui", "lw", "lwu", "or", "ori", "sb", "sd", "sh",
    "sll", "slli", "slliw", "sllw", "slt", "slti", "sltiu", "sltu", "sra",
    "srai", "sraiw", "sraw", "srl", "srli", "srliw", "srlw", "sub", "subw",
    "sw", "xor", "xori")
  private val mi = Seq("csr", "illegal", "ld-misaligned", "lh-misaligned",
    "lw-misaligned", "ma_addr", "ma_fetch", "sbreak", "scall",
    "sd-misaligned", "sh-misaligned", "sw-misaligned")

  // Tests that need features this core does not have yet. Listed rather than
  // deleted: this is the work list for the stages that follow, and a test that
  // starts passing here is how the feature announces it is done.
  private val notYetImplemented = Map(
    "rv64mi-p-mcsr"       -> "the read-only ID registers mvendorid, marchid and mimpid",
    "rv64mi-p-zicntr"     -> "the cycle and instret counters (Zicntr)",
    "rv64mi-p-breakpoint" -> "the debug trigger module: tselect, tdata1/2, tcontrol",
    "rv64mi-p-access"     -> "physical memory protection (pmpcfg/pmpaddr) and access faults",
  )

  behavior of "riscv-tests"

  for ((name, missing) <- notYetImplemented) {
    it should s"not yet pass $name" in {
      val bin = s"tests/isa/$name.bin"
      assume(Files.exists(Paths.get(bin)), s"$bin not built")
      // Asserted as still failing on purpose. If one of these starts passing,
      // the feature is done and it should move into the list above -- and the
      // failure here is the reminder to do that.
      assert(IsaTest.run(bin) != IsaTest.Pass,
             s"$name now passes: $missing must be implemented, move it to the passing list")
    }
  }

  for ((suite, names) <- Seq("rv64ui-p" -> ui, "rv64mi-p" -> mi); n <- names) {
    val name = s"$suite-$n"
    it should s"pass $name" in {
      val bin = s"tests/isa/$name.bin"
      assume(Files.exists(Paths.get(bin)), s"$bin not built")
      IsaTest.run(bin) match {
        case IsaTest.Pass       => succeed
        case IsaTest.Fail(num)  => fail(s"$name failed at test $num")
        case IsaTest.Timeout    => fail(s"$name did not finish")
      }
    }
  }
}
