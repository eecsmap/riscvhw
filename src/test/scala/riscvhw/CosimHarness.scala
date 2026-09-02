package riscvhw

import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.io.{File, PrintWriter}
import java.nio.file.{Files, Paths}

/** Runs a raw binary on the core and writes a commit trace in the same format
  * `riscvm` emits, so the two can be compared line by line.
  *
  * There is no pass/fail protocol in the program itself. That is the point of
  * co-simulation: any program is a test, because correctness is defined by
  * agreeing with the reference model rather than by the program checking
  * itself. It also means stage 0 needs no CSRs, which the riscv-tests `p`
  * environment would otherwise require before a single instruction ran.
  */
object Cosim {
  def run(binPath: String, tracePath: String, maxInstrs: Int,
          memLatency: Int = 0, timing: Option[riscvhw.mem.MemTiming] = None): Int = {
    implicit val cfg: RiscvhwConfig = RiscvhwConfig()
    var emitted = 0
    RawTester.test(new System(latency = memLatency, timing = timing)) { dut =>
      // chiseltest watchdogs the clock at 1000 steps by default; a slow memory
      // pushes an 90-instruction program well past that, so run untimed and
      // rely on the explicit cycle budget below instead.
      dut.clock.setTimeout(0)

      val bytes = Files.readAllBytes(Paths.get(binPath))
      val pw = new PrintWriter(new File(tracePath))

      // preload the program through the scratchpad backdoor, 8 bytes at a time
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

      // Stop at the program's parking loop rather than padding the trace with
      // thousands of identical `j halt` lines: a trace that is 98% spin makes a
      // real divergence hard to see and slows every comparison down.
      var lastPc  = BigInt(-1)
      var selfJmp = 0
      var cycles = 0
      val cycleBudget = maxInstrs * 50 + 1000
      while (emitted < maxInstrs && cycles < cycleBudget && selfJmp < 3) {
        if (dut.io.trace.valid.peek().litToBoolean) {
          val pc    = dut.io.trace.pc.peek().litValue
          val inst  = dut.io.trace.inst.peek().litValue
          val wen   = dut.io.trace.wen.peek().litToBoolean
          val waddr = dut.io.trace.waddr.peek().litValue
          val wdata = dut.io.trace.wdata.peek().litValue
          val sb = new StringBuilder
          sb ++= f"$pc%016x $inst%08x"
          if (wen) sb ++= f" x$waddr=$wdata%016x"
          pw.println(sb.toString)
          emitted += 1
          selfJmp = if (pc == lastPc) selfJmp + 1 else 0
          lastPc  = pc
        }
        dut.clock.step(1)
        cycles += 1
      }
      pw.close()
      if (cycles >= cycleBudget)
        println(s"[cosim] cycle budget exhausted after $emitted instructions -- core may be stuck")

      // Read the trace back and check it holds what was just written. One run
      // reported 15 retired instructions and left a zero-byte file behind, and
      // the test passed: the count lives in memory, the comparison reads the
      // file, and nothing tied the two together. Whatever the cause -- and it
      // did not reproduce in isolation -- a silent empty trace turns the whole
      // co-simulation into a test that cannot fail, so it has to be loud.
      val written = scala.io.Source.fromFile(tracePath).getLines().size
      require(written == emitted,
              s"trace file $tracePath holds $written lines but $emitted instructions retired")
    }
    emitted
  }
}

class CosimSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "riscvhw stage-0 core"

  // Every program in tests/ gets both a normal run and a slow-memory run. The
  // second is not redundant: it is the standing check that memory latency
  // changes only how long the core takes, never what it computes.
  // Programs compared against riscvm instruction for instruction.
  private val programs = Seq("rv64i_basic", "rv64i_edge", "rv64_csr", "rv64_ecall", "rv64_trap", "rv64_mret")

  // Programs run on the hardware only, whose results are checked against the
  // specification instead. Co-simulation is the wrong instrument for WARL
  // fields and for anything that depends on machine configuration, because two
  // conformant implementations are allowed to differ there.
  private val specOnly = Seq("rv64_csr_warl")

  for (p <- specOnly) {
    it should s"produce a trace for $p to check against the specification" in {
      val n = Cosim.run(s"tests/$p.bin", s"tests/$p.hw.trace", maxInstrs = 4000)
      println(s"[cosim] $p: $n instructions (spec-checked)")
      assert(n > 0, s"$p retired no instructions")
    }
  }

  for (p <- programs) {
    it should s"produce a commit trace for $p" in {
      val n = Cosim.run(s"tests/$p.bin", s"tests/$p.hw.trace", maxInstrs = 4000)
      println(s"[cosim] $p: $n instructions")
      assert(n > 0, s"$p retired no instructions")
    }

    it should s"produce an identical trace for $p when memory is slow" in {
      val n = Cosim.run(s"tests/$p.bin", s"tests/$p.slow.trace", maxInstrs = 4000, memLatency = 7)
      println(s"[cosim] $p (slow memory): $n instructions")
      assert(n > 0, s"$p retired no instructions")
    }

    // Latency that changes from one access to the next, which a fixed delay
    // cannot catch: a core that latches something at the wrong moment can be
    // correct at every constant latency and wrong when it varies.
    it should s"produce an identical trace for $p when memory latency varies" in {
      val n = Cosim.run(s"tests/$p.bin", s"tests/$p.vary.trace", maxInstrs = 4000,
                        timing = Some(riscvhw.mem.Variable(1, 13)))
      println(s"[cosim] $p (variable latency): $n instructions")
      assert(n > 0, s"$p retired no instructions")
    }
  }

}
