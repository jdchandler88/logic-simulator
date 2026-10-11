# logic-simulator
Simple app for simulating binary logic

## Building blocks

Gates (`AndGate`, `OrGate`, `XorGate`, `NandGate`, `NorGate`, `InverterLogicUnit`, wired
with `LogicSignal` or the `Gates` shorthand), `FlipFlop`/`Register` (optionally with a
write enable), `TriStateBuffer`, `BusSelector8By8Bit`, `HalfAdder`/`FullAdder`/
`RippleCarryAdder`, `Counter`, and Intel 8080 blocks: `RegisterFile`, `Alu` and `AluDatapath` (register file +
ALU + multiplexers + flags register executing MOV/MVI/ALU instructions one per clock). Every component
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

The CPU-agnostic diagnostics live in `com.darchan.diag` (implemented by the 8080 and, through probes and
`ClockedTarget`, by gate-level circuits; the SM83 will implement them too):

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
- `diag.compare` — lockstep comparison of two live models (`Lockstep`), of a live run against a recorded trace
  file (`GoldenTrace`) and against saved snapshot files at checkpoints (`SnapshotCheckpoints`); see below.
- `diag.repl` — `DebuggerRepl`, the interactive command-line debugger, over any `ReplTarget`.
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

### Lockstep comparison

Three ways to find the first point where a model goes wrong, all stopping at the first divergence with a
report that says where, what differs, and what the model(s) executed just before.

**Live against live** (`Lockstep`): step a trusted *reference* and a *candidate* side by side and compare
snapshots after every instruction, every N instructions, or at the first instruction boundary after every N
cycles (`Granularity`). Fields can be ignored, reduced to a common set for different machines
(`compareOnly`), and memory regions of both sides included.

```java
CpmMachine golden = CpmMachine.load(rom), candidate = CpmMachine.load(rom);
LockstepResult r = Lockstep.builder("reference", golden.debugger(), "candidate", candidate.debugger())
        .granularity(Granularity.everyStep())                 // or everySteps(n), everyCycles(n)
        .ignore("CYCLES")                                     // if timings legitimately differ
        .compareMemory("ram", golden.ram()::peek, candidate.ram()::peek, 0x0000, 0x0800)
        .build()
        .runUntil(golden::exited, 10_000_000);
if (r.diverged()) System.out.println(r.report());
```

A deliberately faulty candidate (its memory bus flips bit 0 of the byte read by instruction 437 of TST8080)
gives:

```
LOCKSTEP DIVERGENCE after step 438 (comparison #438, last match after step 437)
  reference "reference":   instruction 438, cycle 3139, PC=050B
  candidate "faulty":      instruction 438, cycle 3139, PC=050B
--- state diff (expected = reference, actual = candidate) ---
1 field(s) and 0 memory byte(s) differ (expected "reference after step 438" vs actual "faulty after step 438")
  A           expected=FF  actual=FE
--- reference "reference": last 16 instruction(s), '>' = executed last ---
  ...
  000000003122 000000000436 0507: AF        XRA A          A=FF F=56 B=12 C=FF D=12 E=FF H=12 L=FF SP=07BD SZAPC=-ZAP-
> 000000003126 000000000437 0508: 3A BF 06  LDA 06BFH      A=00 F=46 B=12 C=FF D=12 E=FF H=12 L=FF SP=07BD SZAPC=-Z-P-
--- candidate "faulty": last 16 instruction(s), '>' = executed last ---
  ...
```

With cycle granularity the divergence lies within the last window; re-run with `everyStep()` to pin it down.

**Against a golden trace file** (`GoldenTrace`). Record a trace once from a model you trust, keep the file (it is
plain trace text, so it diffs well in version control and could come from another emulator), and compare any
later run against it line by line:

```bash
# record (or GoldenTrace.record(debugger, path, maxCycles) in code)
build/install/logic-simulator/bin/logic-simulator run-com TST8080.COM --trace tst8080.trace
# compare later; exit code 1 and a report on the first mismatch
build/install/logic-simulator/bin/logic-simulator run-com TST8080.COM --golden tst8080.trace
build/install/logic-simulator/bin/logic-simulator run-com X.COM --golden other-emulator.trace --ignore-columns cycle
```

```java
GoldenTraceResult r = GoldenTrace.of(Path.of("tst8080.trace")).compare(machine.debugger(), 1_000_000);
```

`#` lines and blank lines in the file are skipped; `ignoreColumns` takes column names (`cycle`, `instruction`,
`pc`, `bytes`, `disassembly`, register names, `SZAPC`). The repository keeps a 650-line golden trace of TST8080
(`src/test/resources/golden/TST8080.trace`, recorded from this core after it passed all four CP/M diagnostics
with reference-exact cycle counts). A mismatch report:

```
GOLDEN TRACE MISMATCH at line 440 of TST8080.trace (instruction 438)
  expected: 000000003139 000000000438 050B: FE FF     CPI 0FFH       A=FF F=46 B=12 C=FF D=12 E=FF H=12 L=FF SP=07BD SZAPC=-Z-P-
  actual:   000000003139 000000000438 050B: FE FF     CPI 0FFH       A=FE F=46 B=12 C=FF D=12 E=FF H=12 L=FF SP=07BD SZAPC=-Z-P-
                                                                        ^
  differing columns: A: expected FF, actual FE
--- context: 8 matching line(s) before ---
  ...
  000000003126 000000000437 0508: 3A BF 06  LDA 06BFH      A=00 F=46 B=12 C=FF D=12 E=FF H=12 L=FF SP=07BD SZAPC=-Z-P-
--- the golden trace continues with ---
  000000003146 000000000439 050D: C4 A0 06  CNZ 06A0H      A=FF F=56 B=12 C=FF D=12 E=FF H=12 L=FF SP=07BD SZAPC=-ZAP-
  ...
```

A line shows the state *before* its instruction, so the culprit is the last context line (here `LDA 06BFH`).

**Against saved snapshot files** (`SnapshotCheckpoints`): named checkpoints (after N instructions, at cycle N,
or the k-th time the PC reaches an address), one `SnapshotJson` file each. Record them once, verify later:

```java
SnapshotCheckpoints cps = SnapshotCheckpoints.in(Path.of("checkpoints"))
        .add(Checkpoint.atInstruction("i300", 300))
        .add(Checkpoint.atCycle("c3000", 3000))
        .add(Checkpoint.atPc("exit", 0x0000))
        .snapshot(() -> m.cpu().snapshot().withRegion("ram", m.ram()::peek, 0, 0x0800));
cps.record(m.debugger(), 1_000_000);                         // writes checkpoints/i300.json, ...
CheckpointReport r = cps.verify(other.debugger(), 1_000_000);  // later / another build
System.out.println(r.report());
// checkpoints: 1 of 3 passed; run ended: CONDITION (stopped at the first mismatch) at cycle 3009, PC=06AD
//   MATCH       i300 @ instruction 300  (instruction 300, cycle 2122, PC=0411)
//   MISMATCH    c3000 @ cycle 3000  (instruction 408, cycle 3009, PC=06AD): 11 field(s) and 11 memory byte(s) differ
//   NOT_REACHED exit @ PC=0000: not run: an earlier checkpoint mismatched
// --- c3000 @ cycle 3000 ---  (the SnapshotDiff and the last instructions follow)
```

### Gate level as a debug target, and co-simulation with the 8080

`com.darchan.physical.probe` exposes gate-level state to the diagnostics layer. A `ProbeSet` registers named
probes (dot-separated hierarchical names) on buses or signals of one `Simulator` and reads them as
`ProbeValue`s (`value()` reads Z/X as 0; `bits()` gives `0 1 z x` per bit). In snapshots every probe is a HEX
field; when bits are Z or X, extra fields `NAME.Z` / `NAME.X` hold their masks, so a floating or contended bus
shows up in a diff instead of silently reading as 0. `ClockedTarget` makes a clocked circuit a `DebugTarget`:
**one step is one clock cycle** (the stimulus drives the inputs, the circuit settles, the clock rises and falls),
so `Debugger`, `Lockstep`, `SnapshotCheckpoints` and traces work on it unchanged.

```java
Simulator sim = new Simulator();
Clock clk = new Clock(sim, false);
Counter counter = new Counter(sim, clk, 4);
sim.settle();
ProbeSet probes = new ProbeSet(sim);
probes.add("count", counter.getOutputBus());
ClockedTarget target = new ClockedTarget("counter4", clk, probes);
target.setProgramCounter(() -> (int) probes.value("count"));  // optional: lets breakpoints use the count
Debugger dbg = new Debugger(target, null);
dbg.addBreakpoint(5);
dbg.run(1000);                                                 // BREAKPOINT after 5 clock cycles
```

`com.darchan.cosim` is the only production package allowed to use both layers (enforced by `ArchitectureTest`).
`AluDatapathCosim` runs one program of 8080 register instructions (MOV, MVI, ADD..CMP r, ADI..CPI) on the
gate-level `AluDatapath` (decoded by a small control unit, one instruction per clock) and on the behavioral
`Cpu8080`, and compares A F B C D E H L after every instruction:

```java
AluDatapathCosim cosim = new AluDatapathCosim(program);                  // or (program, stuckAt(3, false))
LockstepResult r = cosim.lockstep().run(cosim.instructionCount());
```

A random 1500-instruction program agrees on both layers; with a stuck-at-0 fault on bit 3 of the ALU's operand
B the lockstep stops at the first instruction that needs that bit (`ADI 08H`: A expected 10, actual 08), and both
sides' traces look alike because the gate-level trace uses the control unit's PC and the 8080 disassembler.

### Waveforms (VCD) and simulator statistics

`VcdWriter` (`com.darchan.physical.wave`) records chosen probes to a Value Change Dump. Dotted probe names
become scopes (`alu.result` is `result` in scope `top.alu`), buses are vectors, Z/X bits are written as `z`/`x`.
VCD has one time axis but the simulator has simulated time *and* delta cycles, so the time mapping is a choice
(see the `VcdWriter` javadoc for the trade-offs):

| `Timing` | one VCD tick per | shows | use for |
|---|---|---|---|
| `SETTLE` (default) | `settle()`/`runUntil()` call, i.e. per `Clock.tick()` | settled values | zero-delay synchronous designs: clean cycle-level waves |
| `DELTA` | delta cycle | every intermediate value, glitches, carry ripple | debugging combinational logic and races (axis not physical) |
| `SIM_TIME` | simulated time unit | value at the end of each time step | circuits with transport delays driven by `runUntil` |

```java
try (VcdWriter vcd = VcdWriter.builder(probes).timing(VcdWriter.Timing.SETTLE).timescale("1ns")
        .topScope("counter4").open(Path.of("counter.vcd"))) {
    for (int i = 0; i < 40; i++) clk.tick();
}
System.out.println(sim.stats());   // or sim.toString()
// simulator: 40 run(s) (settle/runUntil), 0 time step(s)
//   delta cycles: ... total, per run min 1 / mean ... / max ...
//   events:       ... total, per run min 1 / mean ... / max ...
//   updates:      ... (... without a value change)
```

From the command line, `vcd-demo` writes a 4-bit counter (clock, count, next-state logic, individual bits):

```bash
build/install/logic-simulator/bin/logic-simulator vcd-demo counter.vcd --cycles 20 [--timing delta]
gtkwave counter.vcd        # GTKWave: drag signals from the SST tree into the wave pane
surfer counter.vcd         # Surfer (https://surfer-project.org), also runs in a browser: open the file there
```

`Simulator.stats()` (always on, cheap) counts runs, delta cycles and events (nets that changed) per
settle/runUntil — min/mean/max/total — plus updates that did not change a value and time steps;
`resetStats()` zeroes them.

### Interactive debugger

```
./gradlew installDist
build/install/logic-simulator/bin/logic-simulator debug src/test/resources/cpm/TST8080.COM
build/install/logic-simulator/bin/logic-simulator debug --raw prog.bin --org 0x200   # raw binary at 0200
```

Commands: `step [n]`, `next-cycles <n>`, `cont [max-cycles]`, `until cycle|instr <n>`, `break <addr>`,
`watch <r|w|rw|x> <addr[-addr]>`, `io <in|out|inout> <port>`, `breaks`, `delete <id|all>`, `regs`,
`mem <addr> [len]`, `dis [addr] [n]`, `history [n]`, `dump`, `trace on [file]` / `trace off`,
`save|load|diff <file.json>` (registers + all memory), `help`, `quit`. Addresses and ports are hex (`0100`,
`0x100`, `100H`), counts decimal. Every stop shows the reason, the next instruction and the registers. Ctrl-C
stops a running command; Ctrl-D or `quit` leaves. The REPL reads from any `Reader` and writes to any
`PrintStream`, so sessions are scripted in tests (`CpmReplTest`).

```
i8080 debugger. Type 'help' for commands.
-> 0100: C3 B2 01  JMP 01B2H
   PC=0100 SP=0000 A=00 F=02 B=00 C=00 D=00 E=00 H=00 L=00 INTE=0 HALT=0 EI_PENDING=0 CYCLES=0 INSTRUCTIONS=0
dbg> break 5
#2 break 0005 (BDOS call)
dbg> cont
[BREAKPOINT] breakpoint at 0005 (BDOS call) at cycle 86, instruction 8
-> 0005: D3 01     OUT 01H
   PC=0005 SP=07B7 A=00 F=02 B=00 C=09 D=01 E=03 H=00 L=00 INTE=0 HALT=0 EI_PENDING=0 CYCLES=86 INSTRUCTIONS=8
dbg> watch w 06BF
#3 watch [WRITE] 06BF
dbg> cont
MICROCOSM ASSOCIATES 8080/8085 CPU DIAGNOSTIC
 VERSION 1.0  (C) 1980
[WATCHPOINT] watch [WRITE] 06BF: WRITE 06BF value 44 at cycle 2235, instruction 318
-> 042F: 06 00     MVI B,00H
   PC=042F SP=07BD A=00 F=46 B=44 C=45 D=46 E=47 H=06 L=BF INTE=0 HALT=0 EI_PENDING=0 CYCLES=2235 INSTRUCTIONS=318
dbg> history 2
000000002221 000000000316 042C: 2E BF     MVI L,0BFH     A=00 F=46 B=44 C=45 D=46 E=47 H=06 L=4F SP=07BD SZAPC=-Z-P-
000000002228 000000000317 042E: 70        MOV M,B        A=00 F=46 B=44 C=45 D=46 E=47 H=06 L=BF SP=07BD SZAPC=-Z-P-
dbg> mem 06B0 32
06B0: 01 C3 00 00 21 7A 01 CD  4B 01 C3 00 00 BF 06 44  |....!z..K......D|
06C0: 00 00 00 00 00 00 00 00  00 00 00 00 00 00 00 00  |................|
dbg> save s.json
saved 15 field(s) and 65536 byte(s) of memory to s.json
dbg> cont
[WATCHPOINT] watch [WRITE] 06BF: WRITE 06BF value 45 at cycle 2278, instruction 324
-> 0439: 0E 00     MVI C,00H
   PC=0439 SP=07BD A=44 F=56 B=44 C=45 D=46 E=47 H=06 L=BF INTE=0 HALT=0 EI_PENDING=0 CYCLES=2278 INSTRUCTIONS=324
dbg> diff s.json
5 field(s) and 1 memory byte(s) differ (expected "cycle 2235" vs actual "now")
  PC          expected=042F  actual=0439
  A           expected=00  actual=44
  F           expected=46  actual=56
  CYCLES      expected=2235  actual=2278
  INSTRUCTIONS expected=318  actual=324
  ram[06BF]   expected=44  actual=45
dbg> quit
```
