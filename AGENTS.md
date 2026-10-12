# Flatdroid

Android app (`com.garathon.flatdroid`, Kotlin, GPL-3.0-or-later) that installs Flatpak apps from Flathub and runs them without root. A static proot (shipped as native libraries) runs an Alpine rootfs in private storage; Flatpak inside Alpine only installs and updates apps. Each app runs through our own launcher in its own proot root (runtime on `/usr`, app on `/app`). Windows go to Xvnc + Openbox, viewed in an external VNC viewer; audio goes through PulseAudio in Alpine to an AudioTrack player in the app. One Flatpak runs at a time. HOME is `Documents/Flatdroid`.

## Hard rules

- **Never create git commits or push.** When a commit is due (unit tests pass, or a feature works on the phone), stop, summarise what changed, propose a commit message, and wait.
- **No forks or copied code** from other projects without explicit approval. To propose one, give the repository plus alternatives (with one of the alternatives being no fork), each with pros and cons.
- **Mark anything untested as (verify)** in code comments and in what you report; keep confirmed and planned apart.
- **Never describe proot, a setting or a home mode as a sandbox.** It narrows exposure; it doesn't isolate.
- Don't build a terminal emulator. The settings "terminal" is one command field with its output.
- Don't spend time on bubblewrap or stock `flatpak run`; the custom launcher is the design.

## Working with the owner

- Concise answers. Say directly when the owner (or you) got something wrong.
- When a tool or library is the wrong fit, recommend an alternative instead of chasing its problems.
- The owner tests on the phone and pastes output back; give exact commands, one block per session (Termux, Alpine, adb, in app, etc).

## Environment

- Host: JDK 21 (`JAVA_HOME` set), Android SDK (`ANDROID_HOME`): platform-tools, `platforms;android-35`, build-tools 35.0.0, NDK 30.0.16248370, CMake 3.22.1.
- App: targetSdk 35+, minSdk 24. ABIs: arm64-v8a and x86_64 only. Native libraries packaged with `useLegacyPackaging = true`, 16 KB page aligned.
- Test device: moto g power 5G (2024), aarch64, Android 15, 4 KB pages, no user namespaces, over `adb`. Waydroid for x86_64.

## Commands

<!-- Fill in once the Gradle project exists. -->
- Build: `./gradlew assembleDebug`
- Unit tests: `./gradlew testDebugUnitTest`
- Install and run: `./gradlew installDebug`, then `adb shell am start -n com.garathon.flatdroid/.MainActivity`
- Logs: `adb logcat --pid=$(adb shell pidof com.garathon.flatdroid)`

## Code conventions

- Kotlin for the app; POSIX sh for guest glue (Alpine setup, helper start-up); C only for native helpers (proot build, `fdsec`).
- Build the proot invocation as an argument list in one place, used by setup and the launcher. Never build it as a shell string.
- Start every proot from a cleared environment (`env -i` equivalent) with absolute paths; never rely on ambient state.
- Pure Kotlin, unit-tested: metadata parser (keyfile; trim spaces around `=`), bind and argument builders, path mapping, ELF architecture check.
- Every launch: non-dumpable proot, `fdsec` run first inside the root (before `/usr/bin/env`), refuse to launch if `Seccomp_filters` shows no filter.
- Keep architecture strings (`aarch64`, `aarch64-linux-gnu`) parameterised.

## Suggested first milestones

1. **Skeleton APK:** application ID, Kotlin, targetSdk 35+, minSdk 24, proot native libraries (use RedTerm's build script as reference), run `echo hi` in an extracted Alpine. This closes experiment 2 with our own APK.
2. **Setup flow:** extract the rootfs, `apk add flatpak tigervnc openbox pulseaudio ca-certificates` (tigervnc until milestone 6 replaces it), add Flathub, write Openbox config, VNC password; progress UI and resume.
3. **Metadata parser and launcher builder** (pure Kotlin, unit-tested), producing the proot argument list from ConfirmedWorkingProcess.md section 6 (`fdrun` in FinalTests.md is the shell equivalent). Reproduce the tested launches from the app.
4. **Hardening:** build proot non-dumpable and add the `fdsec` seccomp helper to every launch (decision doc section 13; stand-ins in SecurityTests/); refuse to launch if `Seccomp_filters` shows no filter.
5. **Foreground service and display:** a `specialUse` foreground service that owns Xvnc/Openbox (and later PulseAudio and the app), then opens the external viewer.
6. **Xvnc build:** GitHub Actions workflow (arm64 and x86_64 runners, `alpine:3.24` container) applying the verified `--disable-glx` APKBUILD change and packaging the archive (decision doc section 5). Replaces Alpine's `tigervnc` in setup.
7. **Audio:** the AudioTrack player reading the simple-protocol socket (server side already confirmed).
8. **Files:** HOME on `Documents/Flatdroid`, private dot-dir binds, Import picker, DocumentsProvider, optional All files access toggle.
9. **Testing:** experiment 9 (background survival and child-process limits).
10. **Link handling:** OpenURI portal and/or `xdg-open` stand-in (decision doc section 5).
11. **UI:** if not fully done yet, it should be done here. It is expected that by this point most UI will already be done, and the UI Document has already been requested.
12. **Build scripts:** arm64-v8a and x86_64 APKs only (Flathub publishes only aarch64 and x86_64); keep architecture strings (`aarch64`, `aarch64-linux-gnu`, ELF check) parameterised. Only arm64-v8a has been tested.

