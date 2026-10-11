# Roadmap

Goal: a CPU simulator I can write programs against, starting with the Intel 8080
and ending with a working Game Boy (DMG).

## Architecture direction

Two layers, kept separate:

1. **Gate/RTL layer** (`com.darchan.physical`) – the existing signal/bus/gate
   library. Used to build and *verify* hardware blocks (adders, ALU, register
   file) the way a hardware team would.
2. **Behavioral layer** (`com.darchan.behavioral`) – instruction-level,
   M-cycle-timed CPU and system models using `int`s, not signal objects. This is
   what runs real programs at real speed.

Packages: `com.darchan.physical` (gate level), `com.darchan.behavioral` (instruction
level) and `com.darchan.diag` (shared diagnostics). `physical` and `behavioral` must
not depend on each other; both may depend on `diag`, which depends on neither. This
is enforced by `ArchitectureTest` (ArchUnit, production classes only).

The behavioral models act as the golden reference: gate-level blocks are tested
by checking them against the behavioral ALU/register logic.

Core behavioral interfaces:

```java
interface MemoryBus { int read(int addr); void write(int addr, int value); }
interface Tickable  { void tick(int cycles); }   // timers, PPU, APU advance in lockstep
```

The 8080 and the Game Boy's SM83 are related, so they share register and ALU
helpers but are separate cores. The SM83 has a different F layout (Z N H C in
the high nibble), no parity flag, no IN/OUT, and a CB-prefixed bit-op table.

## Phase 0 – Housekeeping
- [x] Gradle wrapper 8.x, Java 21 toolchain, JUnit 5.10 + `junit-platform-launcher`,
      pin Mockito (no `2.+`), `application { mainClass = ... }`.
- [x] Stop tracking `out/`, `bin/`, `.idea/workspace.xml`, `*.iws`, `*.ipr`, `*.iml`.
- [x] Replace Jenkinsfile with a GitHub Actions workflow (`gradle build`).
- [x] Fix package names: `com.darchan.com.darchan.validation` → `com.darchan.validation`;
      tests under `.../impl/com/darchan/...` → mirror main packages.
- [x] Delete or implement the 8 empty `RegisterFileTest` methods (they pass vacuously).
      (Deleted; real RegisterFile tests are Phase 1.)

## Phase 1 – Make the gate-level simulator correct
- [x] **Event scheduler with delta cycles.** Today `setState` notifies listeners
      synchronously and depth-first. Results:
      - Three flip-flops chained as a shift register all load the input on a
        single clock edge (`a=b=c=true` after one tick), because A's output
        changes before B samples.
      - Any combinational feedback loop (ring oscillator, NOR latch) ends in
        `StackOverflowError`.
      Fix: signals schedule changes into a queue; edge-triggered elements sample
      in one phase and commit in the next (VHDL/Verilog delta-cycle semantics).
      Optional per-gate propagation delay.
      (Done: `com.darchan.physical.sim.Simulator`, `settle()`/`runUntil()`, `OscillationException`,
      `Wire` for feedback, optional transport delay on `LogicSignal`.)
- [x] Validate bus widths once, at construction, in every component (`AndGate`,
      `BusSelector8By8Bit` return `null` ranges; `Register`/`RegisterFile` never validate).
      (Logic units are validated by `LogicSignal`'s constructor via `ILogicUnit.validateInputBus`;
      the empty `Cpu` stub was removed — the CPU will be the Phase 2 behavioral core.)
- [x] Tri-state / high-Z signals and bus resolution (needed for a shared data bus).
      (`LogicValue` 0/1/Z/X, multi-driver resolved `Wire`, `TriStateBuffer`; `isOn()` reads Z/X
      as false.)
- [x] X-propagation through gates (deferred from the item above; done in "Pre-Phase 3 tightening"): four-valued
      pessimistic gate evaluation, Z on an input behaves as X, Verilog-UDP-style flip-flop clock rule.
- [x] Fix `RegisterFile`: docs say `[15:0]` data and F in the high bits; code wires
      F/B/D/H to bits 8–15, A/C/E/L to bits 0–7, puts F on output `[7:0]`, and declares
      an 8-bit output width for a 16-bit bus. Gated clocks via latch lines should
      become clock + write-enable.
      (Now: one clock, shared 8-bit data-in `[7:0]`, per-register write enables `[15:8]`,
      64-bit output with slot order L,H,E,D,C,B,F,A so pairs HL/DE/BC/PSW are 16-bit slices.
      Write enable is a gate mux in front of each flip-flop's D input.)
- [x] Build an 8-bit ripple-carry adder and ALU from gates; test against the
      behavioral ALU for all 256×256×carry inputs.
      (`HalfAdder`, `FullAdder`, `RippleCarryAdder`, 8080 `Alu` with S Z AC P CY. The golden
      model is `Alu8080`, now in `com.darchan.behavioral.i8080`; the gate-level `AluTest`
      checks against it. Exhaustive run of all
      8 ops × 256 × 256 × CY takes ~2.5 s.)

## Phase 2 – Behavioral Intel 8080
- [x] Registers, flags (S Z AC P CY), SP/PC, `MemoryBus`, `IN`/`OUT` port interface.
      (`com.darchan.behavioral.bus`: `MemoryBus` with `fetch`/`peek`, `IoBus`, 64 KiB `Ram`; `Cpu8080` keeps F in
      the `S Z 0 AC 0 P 1 CY` layout and takes every flag from `Alu8080`, which gained INR/DCR/DAA/rotate/DAD.)
- [x] All 256 opcodes (including undocumented duplicates) with cycle counts.
      (Data-sheet states incl. conditional CALL 17/11 and RET 11/5; every opcode checked taken/not taken.)
- [x] Interrupts: `EI`/`DI`, `RST n` injection, `HLT`.
      (EI enables after the next instruction; a held request injects a single-byte instruction at the next
      boundary with INTE set; HLT idles 4 cycles per step.)
- [x] Disassembler and a step/trace debugger (registers + disassembly per step).
      (`Disassembler8080`, Intel mnemonics, `*` marks undocumented opcodes; tracing and stepping through
      `com.darchan.diag`, wired by `Debug8080.attach`.)
- [x] Pass the CP/M diagnostics: `8080PRE`, `TST8080`, `CPUTEST`, `8080EXM`
      (stub BDOS call 5, functions 2 and 9, to print output).
      (`CpmMachine`; cycle counts match superzazu/8080 exactly. 8080EXM is tagged `slow`: `./gradlew slowTest`.)
- [x] Diagnostics core (`com.darchan.diag`): named state snapshots for both layers; run control (step instruction / N cycles / run-until, breakpoints on PC, memory read/write watchpoints, I/O port, cycle count); per-instruction text trace in a fixed diffable format; ring buffer of last N trace lines dumped on failure; register + memory hex dumps at breakpoints / every N cycles / on exception; JSON snapshot save/load.
      (`Snapshot`/`SnapshotDiff`/`SnapshotJson`/`HexDump`, `diag.trace` (`TraceFormatter`, `TraceRingBuffer`,
      `TextTraceWriter`), `diag.run` (`DebugTarget`, `Debugger`, `Watchpoint`). Implemented by the 8080 and,
      through `ProbeSet`/`ClockedTarget`, by the gate-level layer.)
- [x] Lockstep comparison: two live models, recorded golden trace logs, and saved snapshot files; stop on first divergence with a field diff plus recent trace.
      (`diag.compare`: `Lockstep` (every N steps or N cycles, ignore set, `compareOnly` across machines, memory
      regions), `GoldenTrace` (record/compare, line number + `^` markers + named differing columns + context;
      `run-com --golden`; 650-line TST8080 golden trace in test resources), `SnapshotCheckpoints` (at instruction,
      cycle or k-th PC hit). Fault-injection tests catch a corrupted read/write at the exact instruction. The gate
      level became a `DebugTarget` via `ProbeSet` + `ClockedTarget` (one step = one clock), and `com.darchan.cosim`
      runs the gate-level `AluDatapath` in lockstep with `Cpu8080`. A coarse (step- or cycle-window) divergence is
      now narrowed to the exact step automatically: see "Pre-Phase 3 tightening".)
- [x] VCD waveform output for the physical layer (GTKWave/Surfer) plus simulator stats (deltas per settle, events per tick).
      (`VcdWriter`: probes → scopes from dotted names, vectors, z/x; time mapping `SETTLE` (default, one tick per
      settle/Clock.tick), `DELTA` (one tick per delta cycle) or `SIM_TIME`. `Simulator.stats()`: runs, deltas and
      events per settle min/mean/max/total, wasted updates, time steps; `SimulationObserver` hooks. `vcd-demo`.)
- [x] Interactive CLI debugger (REPL): step, continue, break/watch, regs, mem, trace on/off, dump, save/load snapshot.
      (`diag.repl.DebuggerRepl` over a CPU-agnostic `ReplTarget` (8080: `ReplTarget8080`); `App debug FILE.COM` or
      `debug --raw FILE --org ADDR`; also next-cycles, until, io watch, dis, history, diff; Ctrl-C interrupts a run.)

## Pre-Phase 3 tightening
Make the diagnostics trustworthy before running real ROMs: a check that can pass when it should not is worse than
no check.
- [x] PR + review of Phases 0–2 (fixes for budgets, halt boundaries, lockstep comparisons, golden-trace stop state,
      input handling, CI ROM checks).
- [x] Divergence + X/Z tightening:
      - Lockstep narrowing: a coarse divergence (`everySteps(n)`, `everyCycles(n)`) is narrowed to the first
        diverging step by restoring the last matching state (`StateSaver`, `Snapshottable.isSnapshotComplete()`)
        and re-running the window, or by replaying fresh identically configured models (`replayWith`); otherwise
        reported as `NOT NARROWED` with the window and reason. Tested with faults at known steps for 8080-vs-8080
        and the gate-level co-simulation.
      - Gate-level restore: `StateElement` storage (flip-flops, registers, counters) registered with the
        `Simulator` at construction under stable hierarchical names; `ClockedTarget` snapshots include storage,
        testbench state and inputs, and `restore` forces them, settles, and verifies the probes (lossless or it
        throws).
      - X propagation through gates and flip-flops (four-valued, pessimistic), and Z/X shown in gate-level traces
        (`z`/`x`/`?` digits), golden comparisons and VCD.
- [ ] Test hardening (next).
- [ ] Netlist + visualizer (after).

## Phase 3 – Space Invaders (fun 8080 milestone)
- [ ] Memory map: ROM 0x0000–0x1FFF, RAM 0x2000–0x23FF, VRAM 0x2400–0x3FFF.
- [ ] Shift-register hardware on ports 2/3/4, input ports 0/1/2.
- [ ] `RST 1` mid-frame and `RST 2` at VBlank, 60 Hz, 2 MHz.
- [ ] Swing window (rotated 1-bpp framebuffer), keyboard input.
- [ ] Debugger GUI (technology open: JavaFX, local web UI served by the emulator, or ImGui/LWJGL) — registers, memory, disassembly, breakpoints, waveforms.

## Phase 4 – SM83 (Game Boy CPU)
- [ ] Core + CB-prefix table, HALT (and HALT bug), STOP, IME delay after `EI`.
- [ ] Per-opcode JSON tests (SingleStepTests `sm83`).
- [ ] Blargg `cpu_instrs` via serial output (0xFF01/0xFF02); then `instr_timing`.

## Phase 5 – Game Boy system
- [ ] MMU/memory map, echo RAM, unusable region, I/O registers, HRAM, IE.
- [ ] Cartridge: ROM-only → MBC1 → MBC3 (RTC optional) → MBC5; battery saves.
- [ ] Timer (DIV/TIMA/TMA/TAC), interrupt controller (IF/IE).
- [ ] PPU: modes 0–3, LY/LYC/STAT, BG/window/sprites, OAM DMA. Start with a scanline
      renderer, move to a pixel FIFO if accuracy needs it. Validate with `dmg-acid2`.
- [ ] Joypad, Swing frontend at 59.7 Hz, frame pacing.
- [ ] Mooneye acceptance tests for timing details.
- [ ] APU (last; the hardest part to get sounding right).

## Phase 6 – Writing programs for it
- [ ] Use existing toolchains first: RGBDS for Game Boy, an 8080 assembler (e.g. zmac)
      for 8080 programs. Write a small assembler later if wanted.
- [ ] Load `.gb` / raw binaries from the command line; headless mode for tests.
