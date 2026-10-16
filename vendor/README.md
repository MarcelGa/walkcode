# Vendored: Termux terminal emulator and view

Source: https://github.com/termux/termux-app at commit
`8629e632fcb95da272221be327db653fb24befe9`, directories `terminal-emulator/` and
`terminal-view/` (`src/main` only).

License: Apache License 2.0 (see `LICENSE-APACHE-2.0.txt`). The termux-app
repository is GPLv3 overall, but these two libraries are an explicit Apache 2.0
exception (see `TERMUX-LICENSE.md`).

## Walkcode changes

- `terminal-emulator`: removed `JNI.java` and the native `jni/` sources (local
  process spawning). `TerminalSession.java` now runs over a `TerminalTransport`
  (new file) instead of a local pseudo-terminal; its file header says so.
- Build files are Walkcode's own (`build.gradle.kts`).

Everything else is unmodified.
