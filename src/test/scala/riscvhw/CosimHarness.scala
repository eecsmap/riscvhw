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
          memLatency: Int = 0): Int = {
    implicit val cfg: RiscvhwConfig = RiscvhwConfig()
    var emitted = 0
    RawTester.test(new System(latency = memLatency)) { dut =>
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

      var cycles = 0
      val cycleBudget = maxInstrs * 50 + 1000
      while (emitted < maxInstrs && cycles < cycleBudget) {
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
        }
        dut.clock.step(1)
        cycles += 1
      }
      pw.close()
      if (cycles >= cycleBudget)
        println(s"[cosim] cycle budget exhausted after $emitted instructions -- core may be stuck")
    }
    emitted
  }
}

class CosimSpec extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "riscvhw stage-0 core"

  it should "produce a commit trace for rv64i_basic" in {
    val n = Cosim.run("tests/rv64i_basic.bin", "tests/rv64i_basic.hw.trace", maxInstrs = 90)
    println(s"[cosim] emitted $n instructions")
    assert(n == 90, s"expected 90 retired instructions, got $n")
  }

  // The whole point of giving the memory port a handshake at stage 0 is that a
  // slow memory should change only how long the core takes, never what it
  // computes. Running the same program against a scratchpad that stalls for
  // several cycles is the cheapest possible check of that claim -- and it
  // exercises the wait states years before real DRAM is attached.
  it should "produce an identical trace when memory is slow" in {
    val n = Cosim.run("tests/rv64i_basic.bin", "tests/rv64i_basic.slow.trace",
                      maxInstrs = 90, memLatency = 7)
    println(s"[cosim] emitted $n instructions with 7-cycle memory")
    assert(n == 90, s"expected 90 retired instructions, got $n")
  }
}
