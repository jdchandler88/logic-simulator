# Roadmap

Goal: a CPU simulator I can write programs against, starting with the Intel 8080
and ending with a working Game Boy (DMG).

## Architecture direction

Two layers, kept separate:

1. **Gate/RTL layer** (`com.darchan.elements`) – the existing signal/bus/gate
   library. Used to build and *verify* hardware blocks (adders, ALU, register
   file) the way a hardware team would.
2. **Behavioral layer** (new, e.g. `com.darchan.emu`) – instruction-level,
   M-cycle-timed CPU and system models using `int`s, not signal objects. This is
   what runs real programs at real speed.

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
      (Done: `com.darchan.sim.Simulator`, `settle()`/`runUntil()`, `OscillationException`,
      `Wire` for feedback, optional transport delay on `LogicSignal`.)
- [x] Validate bus widths once, at construction, in every component (`AndGate`,
      `BusSelector8By8Bit` return `null` ranges; `Register`/`RegisterFile` never validate).
      (Logic units are validated by `LogicSignal`'s constructor via `ILogicUnit.validateInputBus`;
      the empty `Cpu` stub was removed — the CPU will be the Phase 2 behavioral core.)
- [x] Tri-state / high-Z signals and bus resolution (needed for a shared data bus).
      (`LogicValue` 0/1/Z/X, multi-driver resolved `Wire`, `TriStateBuffer`; `isOn()` reads Z/X
      as false. Deferred: X-propagation through gates — `ILogicUnit` is still boolean.)
- [ ] Fix `RegisterFile`: docs say `[15:0]` data and F in the high bits; code wires
      F/B/D/H to bits 8–15, A/C/E/L to bits 0–7, puts F on output `[7:0]`, and declares
      an 8-bit output width for a 16-bit bus. Gated clocks via latch lines should
      become clock + write-enable.
- [ ] Build an 8-bit ripple-carry adder and ALU from gates; test against the
      behavioral ALU for all 256×256×carry inputs.

## Phase 2 – Behavioral Intel 8080
- [ ] Registers, flags (S Z AC P CY), SP/PC, `MemoryBus`, `IN`/`OUT` port interface.
- [ ] All 256 opcodes (including undocumented duplicates) with cycle counts.
- [ ] Interrupts: `EI`/`DI`, `RST n` injection, `HLT`.
- [ ] Disassembler and a step/trace debugger (registers + disassembly per step).
- [ ] Pass the CP/M diagnostics: `8080PRE`, `TST8080`, `CPUTEST`, `8080EXM`
      (stub BDOS call 5, functions 2 and 9, to print output).

## Phase 3 – Space Invaders (fun 8080 milestone)
- [ ] Memory map: ROM 0x0000–0x1FFF, RAM 0x2000–0x23FF, VRAM 0x2400–0x3FFF.
- [ ] Shift-register hardware on ports 2/3/4, input ports 0/1/2.
- [ ] `RST 1` mid-frame and `RST 2` at VBlank, 60 Hz, 2 MHz.
- [ ] Swing window (rotated 1-bpp framebuffer), keyboard input.

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
