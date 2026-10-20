# Flatdroid

Android app (`com.garathon.flatdroid`, Kotlin, GPL-3.0-or-later) that installs Flatpak apps from Flathub and runs them without root. A static proot (shipped as native libraries) runs an Alpine rootfs in private storage; Flatpak inside Alpine only installs and updates apps. Each app runs through our own launcher in its own proot root (runtime on `/usr`, app on `/app`). Windows go to Xvnc + Openbox, viewed in an external VNC viewer; audio goes through PulseAudio in Alpine to an AudioTrack player in the app. One Flatpak runs at a time. HOME is `Documents/Flatdroid`.

## Hard rules

- **Never create git commits or push.** When a commit is due (unit tests pass, or a feature works on the phone), stop, summarise what changed, propose a commit message, and wait.
- **No forks or copied code** from other projects without explicit approval. To propose one, give the repository plus alternatives (with one of the alternatives being no fork), each with pros and cons.
- **Mark anything untested as (verify)** in code comments and in what you report; keep confirmed and planned apart.
- **Never describe proot, a setting or a home mode as a sandbox.** It narrows exposure; it doesn't isolate.
- Don't build a terminal emulator. The settings "terminal" is one command field with its output.
- Don't spend time on bubblewrap or stock `flatpak run`; the custom launcher is the design.
- Write temporary files to cache, if something is being downloaded, or will be deleted later that goes to cache. The only files that remain outside of cache are files and directories needed for the app to function and have persistence (Alpine rootfs, Flatpaks, settings, etc).
- Do not modify the README.md, if you believe it should be changed, if it needs changing, give short suggestions to the owner, suggestions are not lines to copy paste.

## Working with the owner

- Concise answers. Say directly when the owner (or you) got something wrong.
- When a tool or library is the wrong fit, recommend an alternative instead of chasing its problems.
- The owner tests on the phone and pastes output back; give exact commands, one block per session (Termux, Alpine, adb, in app, etc).
  - Give the owner commands for testing on both ARM phones and X86 wayland through ADB, if the tests are the same merge them (ARM+X86 individual tests vs. ARM+X86 install -> tests).

## Environment

- Host: JDK 21 (`JAVA_HOME` set), Android SDK (`ANDROID_HOME`): platform-tools, `platforms;android-35`, build-tools 35.0.0, NDK 30.0.16248370, CMake 3.22.1.
- App: targetSdk 35+, minSdk 24. ABIs: arm64-v8a and x86_64 only. Native libraries packaged with `useLegacyPackaging = true`, 16 KB page aligned.
- Test device: moto g power 5G (2024), aarch64, Android 15, 4 KB pages, no user namespaces, over `adb`. Waydroid for x86_64, over `adb`.

## Commands

<!-- Fill in once the Gradle project exists. -->
- Build: `./gradlew assembleDebug` (flavors `arm64`, `x86_64`, `universal`; native/build-proot.sh runs from Gradle and needs make, git, curl, gawk, readelf on the host)
- Unit tests: `./gradlew testDebugUnitTest`
- Install and run: `./gradlew installArm64Debug` (Waydroid: `installX86_64Debug`), then `adb shell am start -n com.garathon.flatdroid/.MainActivity`
- Logs: `adb logcat --pid=$(adb shell pidof com.garathon.flatdroid)`

## Code conventions

- Kotlin for the app; POSIX sh for guest glue (Alpine setup, helper start-up); C only for native helpers (proot build, `fdsec`).
- Build the proot invocation as an argument list in one place, used by setup and the launcher. Never build it as a shell string.
- Start every proot from a cleared environment (`env -i` equivalent) with absolute paths; never rely on ambient state.
- Pure Kotlin, unit-tested: metadata parser (keyfile; trim spaces around `=`), bind and argument builders, path mapping, ELF architecture check.
- Every launch: non-dumpable proot, `fdsec` run first inside the root (before `/usr/bin/env`), refuse to launch if `Seccomp_filters` shows no filter.
- Keep architecture strings (`aarch64`, `aarch64-linux-gnu`) parameterised.
- Avoid referencing documents outside of the repository outside of the README. Comments shouldn't refer to "section 13", or "DocumentNotInRepository.md". Avoid referencing prompts in comments (e.g. "applied the requested change to optimize the file storage process", etc).

## Suggested first milestones

1. **Skeleton APK:** application ID, Kotlin, targetSdk 35+, minSdk 24, proot native libraries (use RedTerm's build script as reference), run `echo hi` in an extracted Alpine. This closes experiment 2 with our own APK.
2. **Setup flow:** extract the rootfs, `apk add flatpak tigervnc openbox pulseaudio ca-certificates` (tigervnc until milestone 7 replaces it), add Flathub, other config file setup; progress UI and resume. Along with settings setup menu (will be filled with more settings later on, initially just the language question).
3. **Metadata parser and launcher builder:** (pure Kotlin, unit-tested), producing the proot argument list from ConfirmedWorkingProcess.md section 6 (`fdrun` in FinalTests.md is the shell equivalent). Reproduce the tested launches from the app. No shared storage access yet. This should let us connect to flatpaks from a VNC viewer. Possible version number `0.3`.
3.5. **Code review follow-ups:** the accepted changes from the full code review: `/dev` built from metadata without Binder, external viewer hardening, helpers without fake root (measured: not possible, kept), Electron swap clean-up at start-up, FileObserver waits, no abstract X socket (`-nolisten local`), a run on the Android 17 emulator, the second review's fixes (relaunch bind check, bounded reads, synced settings files, Electron cache clean-up on reset, strings), 30 frames/s by default with a 60 frames/s setting and developer frame rate settings, Python removed from the build, title bar and GPU runtime developer settings, repair after a killed task, bigger section headers.
4. **Audio and microphone:** the AudioTrack player reading the simple-protocol socket; microphone input (AudioRecord into a PulseAudio source, used only while the app records); per-app Sound and Microphone permissions (the microphone asked per session in a notification, "Always allow" as a developer setting); camera research (done; built in 5.5); proot pinned to 5.1.107.97 for its SysV shared memory fix.
5. **Files:** HOME on `Documents/Flatdroid`, private dot-dir binds, standard home folders (Pictures, Documents, …), Import picker, DocumentsProvider, optional All files access toggle; a Storage category (clear, reset, export and import of Flatdroid's settings, per-app settings and app data); no minimise button in GTK apps' own title bars. (By this point something resembling the final UI should be implemented, since there's app interactions and setting toggles). Possible version number `0.5`.
5.5. **Camera:** Flatpak's camera portal fed by Android's camera (D-Bus, PipeWire, WirePlumber, `xdg-desktop-portal` and a GStreamer source in the helpers; the app's bus through `xdg-dbus-proxy`), which also gives every app a session bus (KDE single-instance apps such as KMines need one). Off for every app by default, at every security level; turned on per app. Research and tests in decision doc section 6.
6. **Hardening:** build proot non-dumpable and add the `fdsec` seccomp helper to every launch (decision doc section 13; stand-ins in SecurityTests/); refuse to launch if `Seccomp_filters` shows no filter. Adds the security level setup setting (Performant / Default / Hardened presets, decision doc section 13), which needs the shared home from milestone 5.
7. **Xvnc build:** GitHub Actions workflow (arm64 and x86_64 runners, `alpine:3.24` container) applying the verified `--disable-glx` APKBUILD change and packaging the archive (decision doc section 5). Replaces Alpine's `tigervnc` in setup.
8. **UI:** if not fully done yet, it should be done here. It is expected that by this point most UI will already be done, and the UI Document has already been requested. Possible version number `0.7`.
9. **Feature cleanup:** It is expected that some requested features will be missed as they have not fully pertained to what was needed in the previous milestones, this milestone is aimed at getting ther est of the features that could have been missed. Also handle links using possibly OpenURI portal and/or `xdg-open` stand-in. Also at this point with a mostly working app we consider what else might be nice before initial release. Possible version number `0.9`.
10. **Testing:** experiment 9 (background survival and child-process limits). Possible version number `1.0`. Also handle release aspects here like signing.

