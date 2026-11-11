# NL File Editor — by NotLexi

Upload a game file, see every number with its name (gold, level, jump_power…), change them, save a new copy.
Only the numbers change; everything else stays byte-for-byte the same. 100% offline (no internet permission).

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
            ├── parser/   NumberParser, JsonScanner, XmlScanner, GenericScanner, ValuePatcher, RawNumber
            ├── util/     FileUtils, TextCodec, NumberUtils
            ├── viewmodel/MainViewModel.kt
            └── ui/       AppNavigation, Components, theme/Theme.kt,
                          screens/ HomeScreen, EditorScreen, StatusScreens (Error + Success)
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
