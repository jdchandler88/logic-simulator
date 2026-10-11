# logic-simulator
Simple app for simulating binary logic

## Simulation semantics

The gate-level library (`com.darchan.elements`) is an event-driven simulator with
VHDL/Verilog-style **delta cycles**.

- **Simulator context.** Every signal and component is created with an explicit
  `com.darchan.sim.Simulator`, which owns the event queue. There is no global state, so
  independent circuits (and tests) never interfere.
- **Scheduled writes.** Writing a signal (`SimpleSignal.setOn`, a gate re-evaluating, a
  flip-flop loading) never changes it immediately. The write is scheduled and becomes
  visible in the next delta cycle; if a signal is written several times in one delta the
  last write wins.
- **Delta cycles.** Each delta first commits all scheduled writes (update phase), then
  notifies the listeners of every signal that actually changed (evaluate phase). All
  listeners in a delta read the same values, and whatever they write lands in the next
  delta. A flip-flop therefore samples its *pre-edge* input: in a shift register every
  stage moves exactly one position per rising edge.
- **`settle()`.** `sim.settle()` runs delta cycles (and any delayed events) until nothing
  is scheduled. Testbench pattern: drive inputs, `settle()`, read outputs.
  `Clock.tick()` toggles the clock and settles, so after a tick every flip-flop has loaded
  and all combinational logic is stable.
- **Delays.** Gates have zero delay by default: one level of logic costs one delta cycle
  but no simulated time. `new LogicSignal(sim, bus, unit, delay)` adds a transport delay
  in simulated time units; `sim.runUntil(t)` runs up to time `t` (useful for circuits that
  never become quiescent, such as a ring oscillator with delays).
- **Feedback and oscillation.** Feedback is wired with a `Wire`: create it, build the logic
  that reads it, then `wire.connect(driver)`. Stable feedback (e.g. a cross-coupled NOR SR
  latch) settles normally. A zero-delay loop that never stabilises (an odd inverter ring,
  or releasing S and R of a NOR latch at the same instant) makes `settle()` throw
  `OscillationException` after `Simulator.DEFAULT_MAX_DELTA_CYCLES` (10,000) delta cycles,
  configurable with `new Simulator(maxDeltaCycles)`.
- **Clocking rule.** Keep logic off the clock path. A clock that passes through a gate
  arrives one delta later than the raw clock and races with flip-flops on the raw clock,
  exactly as in an HDL simulator. Use write-enables instead of gated clocks.
- **Listeners** may add or remove listeners while being notified; each event is delivered
  to the listeners registered when delivery started.
