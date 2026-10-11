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

## Behavioral 8080 and diagnostics

`com.darchan.behavioral.i8080.Cpu8080` is an instruction-level Intel 8080: all 256 opcodes (the undocumented
duplicates behave like their twins), data-sheet clock states (conditional `CALL` 17/11, conditional `RET`
11/5), `EI` taking effect after the following instruction, `DI`, `HLT`, and interrupts that inject a
single-byte instruction (normally `RST n`) at the next instruction boundary. Every flag comes from `Alu8080`,
the same golden model the gate-level `Alu` is tested against. Memory and I/O are the `MemoryBus` / `IoBus`
interfaces in `com.darchan.behavioral.bus` (`Ram` is 64 KiB of plain RAM). It passes the classic CP/M
diagnostics 8080PRE, TST8080, CPUTEST and 8080EXM with cycle counts identical to a reference emulator
(`./gradlew build` runs the first three; the ~24-billion-cycle 8080EXM is tagged `slow` and runs with
`./gradlew slowTest`).

The CPU-agnostic diagnostics live in `com.darchan.diag` (the 8080 implements them; the SM83 and the gate-level
layer are meant to):

- `Snapshot` — ordered named `Field`s (name, width, value, display format) plus optional `MemoryRegion`s;
  `Snapshottable` (`snapshot()` / `restore()`); `SnapshotDiff` (field-by-field and byte-by-byte report);
  `SnapshotJson` (save/load, one field per line so files diff well); `RegisterDump`; `HexDump`.
- `diag.trace` — `TraceListener` gets one reusable `TraceEvent` per instruction (state *before* it executes);
  `TraceFormatter` writes the fixed line format below; sinks `TextTraceWriter` (file/stream), `InMemoryTrace`
  and `TraceRingBuffer` (last N instructions, formatted only when dumped). With no listener the CPU does no
  tracing work at all.
- `diag.run` — `DebugTarget` (step, counters, PC, snapshots, trace hook) and `Debugger`: step / step N /
  step N cycles / run / run-until, breakpoints on PC and cycle count, watchpoints on memory read/write/fetch and
  I/O ports, dumps on stop / every N cycles / on demand, and an `ExecutionFailure` carrying recent trace +
  registers + memory dumps if the model throws. Watchpoints come from bus decorators
  (`ObservedMemoryBus`/`ObservedIoBus`), so the CPU's fast path is untouched without a debugger.
- Ordinary log messages use `java.lang.System.Logger`.

### Trace format

One line per instruction, before it executes, designed for plain `diff`:

```
000000004924 000000000651 0100: 31 00 07  LXI SP,0700H   A=00 F=02 B=00 C=00 D=00 E=00 H=00 L=00 SP=0000 SZAPC=-----
```

cycles before the instruction (decimal, 12 digits) · instructions before it (decimal, 12 digits) · PC (hex) and
`:` · instruction bytes (padded to 8 columns) · two spaces · disassembly (`INT ` prefix if injected by an
interrupt; padded to 14) · registers `NAME=hex` · flags (`S Z AC P CY` as letters, `-` when clear). No trailing
spaces. Disassembly uses Intel mnemonics; undocumented opcodes are prefixed `*` (`*NOP`, `*JMP`, `*CALL`,
`*RET`). The full rules are in `TraceFormatter`'s javadoc.

### Examples

Run a CP/M `.COM` with a trace file (from the command line):

```
./gradlew installDist
build/install/logic-simulator/bin/logic-simulator run-com src/test/resources/cpm/TST8080.COM --trace tst.trace
```

The same in code, plus a breakpoint and a memory dump:

```java
CpmMachine m = CpmMachine.load(Path.of("TST8080.COM"));     // RAM + Cpu8080 + BDOS stub + Debugger
Debugger dbg = m.debugger();
try (TextTraceWriter trace = TextTraceWriter.toFile(Path.of("tst.trace"))) {
    dbg.addTraceListener(trace);
    dbg.addBreakpoint(0x0005);                               // stop before every BDOS call
    StopReason stop = m.run(1_000_000);                      // BREAKPOINT at 0005
    System.out.println(stop + "\n" + dbg.dump());            // registers
    System.out.println(dbg.hexDump(0x0100, 64));
    // 0100: C3 B2 01 4D 49 43 52 4F  43 4F 53 4D 20 41 53 53  |...MICROCOSM ASS|
    // ...
}
```

Any 8080 system, watchpoints and periodic dumps:

```java
Ram ram = new Ram();
Cpu8080 cpu = new Cpu8080(ram, myIoBus);
Debugger dbg = Debug8080.attach(cpu);                        // wraps the buses for watchpoints
dbg.addWatchpoint(Watchpoint.memoryWrite(0x2400, 0x3FFF));   // e.g. VRAM
dbg.addWatchpoint(Watchpoint.ioOut(0x03));
dbg.addDumpRange(0x2000, 0x100);
dbg.setDumpEvery(1_000_000);                                 // registers + range every 1M cycles (System.Logger)
StopReason stop = dbg.runUntil(() -> cpu.a() == 0x42, 50_000_000);
```

Snapshots, JSON and diffs:

```java
Snapshot before = cpu.snapshot().withRegion("ram", ram::peek, 0x0000, 0x10000);
SnapshotJson.save(before, Path.of("before.json"));
dbg.step(1000);
Snapshot after = cpu.snapshot().withRegion("ram", ram::peek, 0x0000, 0x10000);
System.out.println(SnapshotJson.load(Path.of("before.json")).diff(after).report());
// e.g.  4 field(s) and 2 memory byte(s) differ (expected vs actual)
//         PC          expected=0100  actual=01C4
//         ...
//         ram[2001]   expected=00  actual=3E
cpu.restore(before);                                         // registers, flags, counters
before.region("ram").ifPresent(ram::restore);                // and memory
```

### How the next pieces plug in

- **Lockstep** (live vs live, vs golden trace logs, vs snapshot files): drive two `DebugTarget`s with
  `Debugger.step()`, compare `snapshot()`s with `SnapshotDiff` (ignoring fields such as `CYCLES` if needed), or
  compare `TraceFormatter` lines against a recorded log; on divergence print the diff plus each side's
  `TraceRingBuffer.dump()`.
- **VCD** for the gate-level layer: a gate-level machine implements `DebugTarget`/`Snapshottable` with signal
  values as `Field`s; a VCD writer is another listener next to the trace sinks.
- **REPL**: a thin command loop over `Debugger` (step/continue/break/watch/dump/hexDump), `Snapshot` and
  `SnapshotJson`, and `Disassembler8080.listing`.
