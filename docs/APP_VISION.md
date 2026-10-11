# App Vision: From NAND to a Running CPU

*Working title. Status: idea, not started. Last updated 2026-10-11.*

## Mission

Let anyone build a computer from logic gates up to a CPU that runs real programs,
on a phone, tablet or browser. Guided lessons teach each step, and a free-play
sandbox lets them keep building.

## Why this, why now

- Simple gate sandboxes on phones are plentiful and free. The full climb from gates
  to a working CPU, with guidance, exists only on desktop or web (Turing Complete,
  nandgame). Nothing offers it on mobile or tablet.
- Most tools say only "pass" or "fail". This project already has the machinery to
  say *why*: lockstep co-simulation against a reference model, divergence narrowing
  to the exact clock, X/Z propagation, and waveforms.
- It is a project worth building for its own sake. Success does not depend on the
  market.

## Who it's for

1. **Curious learners.** Students and self-taught programmers who want to know how
   a computer actually works.
2. **Hobbyists.** People who enjoyed nand2tetris, Ben Eater or Turing Complete and
   want a pocket version.
3. **Teachers.** A guided path they can assign, with feedback they don't have to
   write themselves.

## Principles

1. **Lessons and the sandbox are one tool.** A lesson is a sandbox with constraints.
   Every solved lesson unlocks the part you built as a reusable part in the sandbox.
2. **Feedback explains, it doesn't just grade.** "At clock 37 your carry-out was 0,
   expected 1", with the waveform around that moment. A floating wire shows as X, in
   red, at its source.
3. **Correctness is checked, not assumed.** Every lesson is graded by co-simulating
   against a trusted reference (truth table, test inputs, or a behavioral model). The
   Java simulator and `Cpu8080` stay the reference for any new engine.
4. **Hierarchy everywhere.** You can open any part to see what's inside it, and close
   it again, all the way down to gates.
5. **Touch-first, tablet-first.** Designed for fingers and a stylus, not adapted from
   a mouse UI.
6. **Real programs at the end.** The final chapters run actual 8080 code on a CPU the
   learner built.
7. **Respect the learner.** No ads. Free core; paid content unlocks once, not by
   subscription.

## The learning path (draft)

| Chapter | Builds | Unlocks |
|---|---|---|
| 1. Logic | NOT, AND, OR, XOR from NAND | basic gates |
| 2. Choosing | MUX, DEMUX, decoder | selectors |
| 3. Arithmetic | half adder, full adder, 8-bit adder | adder |
| 4. ALU | 8080-style ALU with flags | ALU |
| 5. Memory | latch, flip-flop, register, counter | storage parts |
| 6. RAM | addressable memory | RAM |
| 7. Datapath | register file + ALU + buses | datapath |
| 8. Control | instruction decode, program counter | CPU |
| 9. Run it | the learner's CPU runs real 8080 programs, co-simulated against `Cpu8080` | sandbox CPU |

## Building blocks in this repo

| Need | Status |
|---|---|
| Gate-level simulation, 0/1/Z/X, flip-flops | done (`com.darchan.physical`) |
| Behavioral reference models (`Alu8080`, `Cpu8080`) | done (`com.darchan.behavioral`) |
| Lockstep co-simulation, divergence narrowing | done (`com.darchan.diag`) |
| Waveforms (VCD) | done |
| Netlist: circuits as data, reusable subcircuits | **next** |
| Lesson format (goal, ports, allowed parts, how it's checked, hints) | **next**, with the netlist |
| Checker: lesson + circuit → structured feedback | **next**, built on `Lockstep` |
| Browser visualizer / lesson player | after the netlist |
| Fast compiled engine (Kotlin Multiplatform or Rust/WASM) | later, checked against the Java reference |
| Touch editor, lesson content, mobile shells | later |

## Rough phases

1. **Foundation:** language-neutral JSON netlist with subcircuits, the lesson
   format, and a checker built on `Lockstep`.
2. **Prototype:** a browser lesson player and sandbox built on the visualizer, with
   chapters 1–3 playable.
3. **MVP:** tablet-first editor, about 20 lessons (chapters 1–5), save and share
   links, web/PWA or Android first.
4. **Depth:** chapters 6–9, the waveform view, the CPU running real programs.
5. **Reach:** iOS, community gallery, classroom features.

## Open questions

- Engine language for the shipped app: Kotlin Multiplatform (native first) or
  Rust/TS compiled to WASM (web first)?
- First platform: web/PWA, Android, or iPad?
- Free versus paid split, and whether to offer school licences.
- Name.

## What success looks like

- A learner with no hardware background reaches a CPU running a program, and can
  explain what every part does.
- When a design is wrong, the app's feedback is what gets them unstuck, not a forum.
