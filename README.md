# NL File Editor — by NotLexi

Upload a game file, see every number with its name (gold, level, jump_power…), change them, save a new copy.
Only the numbers change; everything else stays byte-for-byte the same.
(v6.0: the INTERNET permission is now used by the HTML Viewer and by the NL TMX Full installer only. Files you edit never leave the phone.)

## Project structure

```
NLFileEditor/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── .github/workflows/build-apk.yml        ← online APK build
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/ (values: strings, colors, themes · drawable: icon · xml)
        └── java/com/notlexi/nlfileeditor/
            ├── MainActivity.kt
            ├── model/Models.kt
            ├── parser/   NumberParser, JsonScanner, XmlScanner, GenericScanner, FlatScanner, CodeScanner,
            │             BooleanScanner, QuickScan, ValuePatcher, RawNumber
            ├── util/     FileUtils, TextCodec, NumberUtils, AppDataScanner, InstalledApps, PickAppDataFolder
            ├── viewmodel/MainViewModel.kt
            └── ui/       AppNavigation, Components, theme/Theme.kt,
                          screens/ HomeScreen, EditorScreen, AppDataScreens (apps list, files list),
                                  HtmlViewerScreen, StatusScreens (Error + Success)
```

## Build the APK with Android Studio (beginner steps)

1. Install **Android Studio** (free) from developer.android.com/studio. Open it once and let it finish downloading the SDK.
2. Unzip `NLFileEditor.zip` somewhere easy (e.g. Desktop).
3. In Android Studio choose **File → Open**, select the **NLFileEditor** folder (the one containing `settings.gradle.kts`), press OK.
4. Wait for **Gradle sync** to finish (bottom bar, first time takes several minutes and needs internet). If it asks to install an SDK platform or "Trust project", accept.
5. Build: **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. When it says "APK(s) generated", click **locate**. The file is `app/build/outputs/apk/debug/app-debug.apk`.
7. Copy the APK to your phone, tap it, and allow "Install unknown apps" for your file manager/browser when asked.

To test quickly: connect your phone with USB debugging on (or use the emulator) and press the green ▶ **Run** button.

## Build the APK without a PC (GitHub Actions)

1. Make a free account at github.com and create a new **empty repository** (name it e.g. `NLFileEditor`).
2. Put all project files in it, **keeping the folder layout**, including the hidden `.github/workflows/build-apk.yml`.
   - Easiest on a phone: use the **Termux** app: `pkg install git unzip`, unzip the project, `git init`, `git add .`, `git commit -m init`, `git remote add origin <your repo URL>`, `git push -u origin main` (use a GitHub personal access token as the password).
   - GitHub's web page can only upload loose files from a phone, so folders are awkward there.
3. Open the repo → **Actions** tab → **Build APK** → **Run workflow** (it also runs on every push).
4. After ~5–8 minutes open the finished run and download **NL-File-Editor-debug-apk** under *Artifacts*. Unzip it to get `app-debug.apk` and install it.

## How it works

- **Formats:** JSON (full paths like `player.inventory.gold`), `key=value`, `key: value`, INI sections, XML attributes/text, and plain `name 100` lines.
- **Safe saving:** each number's exact start/end position is stored; saving swaps only those ranges. Files are read and written losslessly (UTF-8, or Latin-1 if the file isn't valid UTF-8).
- **Skipped on purpose:** numbers inside sentences or long strings, version numbers (1.2.3), hashes/ids, and exponent numbers like `1e5`.
- **Not supported:** binary files (shows a friendly error), files over 30 MB.
- **Save flow:** Save → system "Create document" dialog named `originalname_edited` → success screen with Share. The original is never overwritten unless you pick it yourself in that dialog.
- **Theme:** dark by default; the sun/moon button on the home screen switches to light.


## What's new

- **Name / Number search** on the editor: "Number" finds every value that currently equals the typed number.
- **True/False tab**: on-demand scan for booleans (JSON, XML, `key: true`, `key=true`, flat dumps), with toggles; edits are saved in place like numbers.
- **Flat-dump tokenizer** rewritten (keys, numbers, True/False, date+time pairs skipped, `Count13` split).
- **Minified JS / SDK code** is detected and only plain config constants are shown, with a notice.
- **Browse App Data** on the home screen (system folder picker, no extra permission) with automatic best-file pick or a short list.
- **About** panel reachable from the corner button and from a link at the bottom of the home screen.

## v5.0.0 — Floating HUD + Snapshot Engine (no root)

A draggable neon bubble that floats over your game. Pick a save file you can access (system file picker),
press **5s Record** or **10s Record**, do something in the game, and the HUD lists only the values that changed
(e.g. `coins 120 → 110`). Tap one, enter a new number, press **Save / Patch**.

- Home screen → **Floating HUD** → *Choose target file* → *Start HUD* (asks for notification + "display over other apps").
- Works only on files the system picker lets you open. It cannot read game memory or other apps' private storage.
- Time-related values are hidden; unknown labels are shown as "Unknown value", never guessed as coins/gold.
- Before every patch a backup is stored in the app's private `hud_backups` folder; the write is verified and rolled back on failure.
- Games may keep data in memory, encrypt it, or overwrite your edit — "no changes detected" and "unsupported format" are reported honestly.

New code lives in `app/src/main/java/com/notlexi/nlfileeditor/hud/`:
`FloatingHudService`, `FloatingHudView`, `SnapshotEngine`, `SnapshotDiff`, `FileAccessManager`, `RecordingTimer`,
`ChangedValueEditor`, `HudFilePickerActivity`, `HudEditActivity`, `FloatingHudDialog`.
Existing parsers (`NumberParser`, `BooleanScanner`) and `TextCodec` are reused for detection.

Build: open in Android Studio (Koala+ / JDK 17) and run `assembleDebug`, or push to GitHub: the included
workflow `.github/workflows/build-apk.yml` builds `app-debug.apk` as a downloadable artifact.

## v6.0.0 — PRO MAX ULTRA upgrade (this release)

### New
- **NL TMX** (home → NL TMX): two cards.
  - **Light · PRO** — Android's `/system/bin/sh` on a real pseudo-terminal (Termux's terminal emulator + PTY code, vendored in `terminal-emulator/` and `terminal-view/`). Multi-line "Script" runner, paste, scrollback, shell history (arrow keys), ESC / TAB / sticky CTRL / ALT / arrows / Home / End / PgUp / PgDn / ^C ^D ^Z keys, pinch to zoom. Runs with this app's permissions only.
  - **Full · ULTRA** — Download → real progress (download, SHA-256 check, extraction) → the button becomes **Open Terminal**. Installs an Alpine Linux root filesystem into app storage (kept between launches) and enters it with **proot** (shipped as native libs) inside the same real terminal. Package manager: `apk`. **It is not real root and not Termux's `pkg` repository**: Termux's packages are compiled for `/data/data/com.termux` and cannot run from another app.
- **Automatic game detection** (home card): with *Usage access* allowed, the foreground / recently used game is found (Android game category or a known-game list). Its folder grant is remembered per game; the folder picker opens once at `Android/data/<package>` (or at `Android/data` if no game is running). Android 11+ blocks `Android/data` for most apps (Android 13+ everywhere): the app says so and suggests `Android/media/<pkg>` (or `games/com.mojang` for Minecraft PE).
- **Search & Changes** (home → Search): real search engine over the chosen folder — file names (wildcards), text, and exact numbers. Number search uses the editor's format-aware parser (JSON paths, XML, key=value, INI ...) so every hit is a named value that equals the number; optional whole-token "unlabeled" numbers. Shows path, line, value, context, cancel + live progress, refresh, stale-result detection, open in editor.
- **Change detection**: take a baseline of the folder, play, compare. Text formats: exact named value changes (`coins 120 → 110`, with delta). Small binary files: changed 32-bit positions. Encrypted / compressed / server-side data cannot be interpreted and is reported only as "changed".

### Fixed
- **HUD**: opening / closing is now ONE animation (v5 kept both layouts alive during `AnimatedContent`, so the wrap-content overlay window jumped in size and was re-clamped repeatedly); expensive coloured shadows replaced by a static glow; ring countdown is a single continuous draw-phase animation; window no longer shifts while collapsing.
- **Upload**: no more 700 ms artificial delay; duplicate picker launches blocked; picker failures reported.
- **HTML Viewer**: JavaScript, DOM storage, images and mixed content enabled; internet resources load; relative images / CSS / fonts / icons load from the page's folder (grant it once with the folder button, served through a virtual origin, `..` rejected, `file://` still disabled); Code ⇄ View switching no longer rebuilds / reloads the WebView.
- **Lag**: removed 450–700 ms artificial waits, throttled progress updates, shorter screen transitions.

### Build
Android Studio (Koala+ / JDK 17) → open the folder → Build APK. Needs Android SDK 35 and NDK 26.1 (the PTY library `libtermux.so` is compiled from `terminal-emulator/src/main/jni`). Or push to GitHub and run the included workflow.

### Licences
`terminal-emulator/`, `terminal-view/`: Apache-2.0 (Termux / Jack Palevich). proot (`libproot.so`, `libproot-loader*.so`): GPL-2.0, prebuilt by green-green-avk/build-proot-android. Alpine Linux: downloaded at run time.

## v5.x HUD / Snapshot Engine notes

- **Clear selection**: an X next to the target file name clears the file and all snapshot state; "Change Target File" re-opens the picker at any time and replaces the selection.
- **"I Started The Game"** arms the engine by re-reading the file as Snapshot A. 5s / 10s Record are only enabled afterwards; Snapshot B is a fresh read of the same URI when the countdown ends.
- Clear "No changes detected" message in the Detected Changes panel.
- The collapsed bubble returns to exactly where it was left after expanding/collapsing.
- The file picker is hinted to open in `Android/data` (Android may ignore or block this hint, especially on Android 11+/13+).
- Stability: overlay/SAF permission loss handled with messages, live notification text during recording, safer overlay cleanup.
