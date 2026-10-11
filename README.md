# logic-simulator
Simple app for simulating binary logic

## Building blocks

Gates (`AndGate`, `OrGate`, `XorGate`, `NandGate`, `NorGate`, `InverterLogicUnit`, wired
with `LogicSignal` or the `Gates` shorthand), `FlipFlop`/`Register` (optionally with a
write enable), `TriStateBuffer`, `BusSelector8By8Bit`, `HalfAdder`/`FullAdder`/
`RippleCarryAdder`, and Intel 8080 blocks: `RegisterFile` and `Alu`. Every component
documents its input/output bus layout in its javadoc and rejects a wrongly-sized input
bus at construction (`UnexpectedBusWidthException`).

## Simulation semantics

The gate-level library (`com.darchan.physical`) is an event-driven simulator with
VHDL/Verilog-style **delta cycles**.

- **Simulator context.** Every signal and component is created with an explicit
  `com.darchan.physical.sim.Simulator`, which owns the event queue. There is no global state, so
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
- **Four-valued signals, tri-state and shared buses.** Every signal has a `LogicValue`:
  `ZERO`, `ONE`, `Z` (high impedance: nothing drives it) or `X` (unknown, e.g. two drivers
  fighting). `getValue()` returns it. `isOn()` is `true` only for `ONE`; **Z and X read as
  `false`**, so boolean code and all gates (`ILogicUnit`) stay simple — but gates do not
  propagate X (a Z/X input counts as 0). A `Wire` may have any number of drivers and
  resolves them: no driver (or all drivers Z) → `Z`, one driver or agreeing drivers → that
  value, conflicting drivers → `X`. `TriStateBuffer` (data bus + enable) drives its data
  when enabled and `Z` otherwise; connect several to the same wires to share a bus
  (`Wire.createBus`, `Wire.connect(wires, drivers)`). A flip-flop's rising edge is any
  change to `ONE` from a value that is not `ONE`, and it captures Z/X data as-is.
- **Listeners** are notified on every change of the four-valued level (a change between
  0, Z and X reaches boolean-only listeners as `(false, false)`; override
  `ISignalChangeListener.valueChanged` to see the levels). They may add or remove listeners while being notified; each event is delivered
  to the listeners registered when delivery started.
