package riscvhw.mem

import chisel3._
import chisel3.util._
import riscvhw.RiscvhwConfig

/** How long a memory takes to answer.
  *
  * The point is not to model DDR accurately -- an accurate model would be a
  * project of its own and would not change whether the core is correct. The
  * point is to make the core meet, in a seconds-long test loop, the kinds of
  * timing it will meet on the bus: replies that take tens of cycles, replies
  * whose latency varies from one access to the next, and a memory that refuses
  * a request for a while before accepting it.
  *
  * A core that is correct under all of these is correct under a real bus, and
  * one that passes only at zero latency has a bug waiting on the FPGA.
  */
sealed trait MemTiming { def maxLatency: Int }

/** Answers in the cycle after the request. The fastest thing that still speaks
  * the handshake. */
case object Immediate extends MemTiming { val maxLatency = 1 }

/** Always the same number of cycles. Useful for arithmetic on cycle counts. */
case class Fixed(cycles: Int) extends MemTiming { val maxLatency = cycles }

/** Latency varies per access, cycling deterministically through a range.
  *
  * Deterministic rather than random on purpose: a failure has to be
  * reproducible, and a pseudo-random latency that only fails one run in twenty
  * is worse than no test at all. Cycling covers the same ground and always
  * covers it the same way.
  */
case class Variable(min: Int, max: Int) extends MemTiming { val maxLatency = max }

/** Adds latency and request back-pressure to a memory port.
  *
  * Sits between the core and the scratchpad rather than inside either, so the
  * memory model stays independent of what it is delaying -- the same wrapper
  * will front the TileLink adapter when the timing questions move to the bus.
  */
class MemDelay(timing: MemTiming)(implicit c: RiscvhwConfig) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(new MemPort)   // from the core
    val out = new MemPort            // to the memory
  })

  val latency = timing match {
    case Immediate      => 0.U
    case Fixed(n)       => n.U
    case Variable(_, _) => 0.U       // replaced below
  }

  // Five explicit states. An earlier version tried to fold the countdown into
  // the waiting state and deadlocked whenever the remaining latency was more
  // than one cycle: the transition out depended on a one-cycle pulse that had
  // already passed by the time the counter reached zero. Separating "waiting
  // for the memory" from "holding the reply back" removes the coupling.
  val sIdle :: sIssue :: sWait :: sHold :: sResp :: Nil = Enum(5)
  val state = RegInit(sIdle)
  val count = RegInit(0.U(16.W))
  val respReg = Reg(new MemResp)
  val reqReg  = Reg(new MemReq)

  // For Variable, walk the range one step per access so every latency in it is
  // exercised and the sequence repeats identically on every run.
  val target = timing match {
    case Variable(lo, hi) =>
      val cur = RegInit(lo.U(16.W))
      when (io.in.req.fire) { cur := Mux(cur === hi.U, lo.U, cur + 1.U) }
      cur
    case Fixed(n)  => n.U
    case Immediate => 0.U
  }

  io.in.req.ready := state === sIdle
  when (io.in.req.fire) {
    reqReg := io.in.req.bits
    count  := target
    state  := sIssue
  }

  io.out.req.valid := state === sIssue
  io.out.req.bits  := reqReg
  when (io.out.req.fire) { state := sWait }

  io.out.resp.ready := state === sWait
  when (io.out.resp.fire) {
    respReg := io.out.resp.bits
    // Latency is added on top of whatever the memory itself took, rather than
    // replacing it, so a slow memory behind the model stays slow.
    state   := Mux(count === 0.U, sResp, sHold)
  }

  when (state === sHold) {
    count := count - 1.U
    when (count === 1.U) { state := sResp }
  }

  io.in.resp.valid := state === sResp
  io.in.resp.bits  := respReg
  when (io.in.resp.fire) { state := sIdle }
}
