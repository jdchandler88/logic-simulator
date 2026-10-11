# CP/M 8080 CPU diagnostics

Test inputs for `CpmRomTest` (run by `CpmMachine`: loaded at 0100h, BDOS stub at 0005h, exit on a jump to
0000h). If a file is missing, its tests are skipped with a message rather than failing, except in CI: when the
environment variable `CI` is `true` (GitHub Actions sets it) a missing file fails the tests that need it, so the
suites cannot silently turn into skips there.

| File          | Size   | Banner / author                                                                                         |
|---------------|--------|---------------------------------------------------------------------------------------------------------|
| `TST8080.COM` | 1536   | "MICROCOSM ASSOCIATES 8080/8085 CPU DIAGNOSTIC VERSION 1.0 (C) 1980" — Kelly Smith, Microcosm Associates |
| `8080PRE.COM` | 1024   | "8080 Preliminary tests" — Ian Bartholomew's 8080 port (2009) of the preliminary tests from Frank D. Cringle's Z80 instruction exerciser |
| `CPUTEST.COM` | 19200  | "DIAGNOSTICS II V1.2 - CPU TEST, COPYRIGHT (C) 1981 - SUPERSOFT ASSOCIATES" — SuperSoft Associates       |
| `8080EXM.COM` | 4608   | "8080 instruction exerciser" — Ian Bartholomew's 8080 port (2009) of Frank D. Cringle's ZEXALL/ZEXDOC   |

SHA-256:

```
9561c6fb6c99efe3de00eb77e4044fd102151058b39ac2d7bce10483838a08e7  TST8080.COM
18eb3c79cba42c0718f160be6a1853cb64cdce7aa47d65780189a57bdd98c4e0  8080PRE.COM
e61a9a75348c774486c2207080ea4effbf6c2367fdace31b0731081a4144030b  CPUTEST.COM
6e3286e11bb1a8f47b8ee1280b4a067be813193363e3223c99b0d21912f44aeb  8080EXM.COM
```

## Source

Downloaded on 2026-10-11 from the `cpu_tests` folder of the MIT-licensed superzazu/8080 emulator:
<https://github.com/superzazu/8080/tree/master/cpu_tests>
(`https://raw.githubusercontent.com/superzazu/8080/master/cpu_tests/<name>`). That repository states the files
come from <http://altairclone.com/downloads/cpu_tests/>, the usual distribution point for these binaries
(not reachable from the environment the files were fetched in, so not re-checked there).

The banners above were read from the binaries. Only the Microcosm and SuperSoft programs carry an author line;
the attribution of `8080PRE` and `8080EXM` to Ian Bartholomew (after Frank D. Cringle) is the conventional one and
is not recorded in the binaries themselves.

## Distribution status

- `TST8080.COM` (Microcosm, 1980) and `CPUTEST.COM` (SuperSoft, 1981) are early-1980s CP/M diagnostics that
  have circulated freely in CP/M archives and emulator test suites for decades. No explicit licence accompanies
  them; their copyright notices are kept intact here and they are used only as test inputs.
- `8080PRE.COM` and `8080EXM.COM` derive from Frank D. Cringle's Z80 exerciser (yaze), which is distributed under
  the GNU General Public License (version 2 or later); Ian Bartholomew's 8080 adaptation is distributed on the
  same terms.

Expected results (this harness does not execute the final instruction at 0000h, so the counts are one
instruction / 10 states below those published by superzazu/8080):

| File          | Pass message                    | Instructions   | States (cycles)  |
|---------------|---------------------------------|---------------:|-----------------:|
| `8080PRE.COM` | `8080 Preliminary tests complete` | 1,060        | 7,807            |
| `TST8080.COM` | `CPU IS OPERATIONAL`            | 650            | 4,914            |
| `CPUTEST.COM` | `CPU TESTS OK`                  | 33,971,310     | 255,653,373      |
| `8080EXM.COM` | 25 × `PASS!`, `Tests complete`  | 2,919,050,697  | 23,803,381,161   |
