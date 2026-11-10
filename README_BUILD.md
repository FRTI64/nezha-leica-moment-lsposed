# LSPosed module build toolchain (Gradle-free) — `work/leica/lsposed/`

Status: **WORKING / verified 2026-11-10** on WSL2 (Ubuntu, uid 1000, no sudo, `$HOME` read-only),
JDK 27, workspace on DrvFs (`/mnt/d/...`). Builds a signed, LSPosed-installable APK from a plain
source tree in ~2–4 s with no Gradle, no network at build time, and no writes outside the workspace.

Deliverables in this directory:

| Path | What |
|---|---|
| `toolchain/` | self-contained Android SDK (cmdline-tools + platforms;android-35 + build-tools;35.0.0), Xposed API 82 jar, debug keystore |
| `build.sh` | the builder: `./build.sh <SRC_DIR> <OUT_APK>` (aapt2 → javac → d8 → zipalign → apksigner → verify) |
| `hello/` | minimal **real** legacy-API LSPosed module source tree + `hello/out/hello-lsposed.apk` (built & verified) |
| `README_BUILD.md` | this file |
| `toolchain/logs/` | captured end-to-end build logs (`hello-build.log`, `app-build.log`) |

---

## 1. TL;DR — build an APK

```bash
cd work/leica/lsposed

# hello demo module (validated)
./build.sh hello hello/out/hello-lsposed.apk

# team module (module-dev's app/ tree; same layout, no res/ needed)
VERSION_CODE=1 VERSION_NAME=1.0.0 ./build.sh app dist/LeicaMoment_LSPosed_v1.0.0.apk
```

Output on success ends with `BUILD OK` and prints APK path, size, sha256. Exit code 0 = built,
signed, and verified. Nothing is written outside `work/leica/lsposed/` (scratch under
`toolchain/build/`). Re-running on unchanged sources produces the **same sha256** (deterministic,
see §5).

---

## 2. Installed toolchain — exact versions, URLs, checksums

Everything lives under `work/leica/lsposed/toolchain/` (≈539 MB total; under the 1.5 GB budget).

| Component | Version | Path | Source / checksum |
|---|---|---|---|
| Android SDK command-line tools | 12.0 (`cmdline-tools/latest`) | `toolchain/cmdline-tools/latest/bin/sdkmanager` | `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip` — 153,607,504 B, sha256 `2d2d50857e4eb553af5a6dc3ad507a17adf43d115264b1afc116f95c92e5e258` |
| SDK Platform | `platforms;android-35` rev 2 (Android 15, API 35) | `toolchain/platforms/android-35/android.jar` (27,092,450 B) | installed by sdkmanager from dl.google.com |
| SDK Build-Tools | `build-tools;35.0.0` | `toolchain/build-tools/35.0.0/` | installed by sdkmanager; `aapt2` reports `Android Asset Packaging Tool (aapt) 2.19-11948202`, `d8` is R8 8.6.2-dev |
| Xposed API (legacy) | API 82 (`de.robv.android.xposed.*`) | `toolchain/libs/xposed-api-82.jar` — 25,478 B, 45 entries | `https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar`, sha256 `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25` (api.xposed.info and the Ali-maven mirror `https://maven.aliyun.com/repository/public/de/robv/android/xposed/api/82/api-82.jar` both answered HTTP 200; api.xposed.info was used) |
| JDK | OpenJDK **27.0.1** (`javac 27.0.1.0.3`, system `/usr/sbin/javac`) | — | `javac --release 17` is used, so class files are Java 17 (major 61) and d8 can consume them |
| Signing key | PKCS12, RSA-2048, alias `androiddebugkey`, store/key password `android`, validity 10000 d | `toolchain/keystore/debug.keystore` (generated on first build by `keytool`) | cert DN `CN=Android Debug,O=Android,C=US`, cert SHA-256 `7e2a733533124e3d40ddbd601874ea2d32288ec083e0fec525faf9b7134aa0d4` |
| LSPosed source snapshot (research only) | master @ 2024-01-07, libxposed API 100 | `toolchain/downloads/lsposed-src/LSPosed-master/` + `.tar.gz` (543 KB) | `https://codeload.github.com/LSPosed/LSPosed/tar.gz/refs/heads/master` — used to verify the metadata format in §6; safe to delete |

Toolchain parts: `cmdline-tools/` 147 MB, `platforms/` 92 MB, `build-tools/` 147 MB,
`downloads/` 150 MB (zip + LSPosed snapshot; the zip can be deleted after install),
`home/`, `android-user-home/`, `licenses/` are the redirected HOME/SDK config dirs.

### Recreate from scratch (exact commands)

`$HOME` is read-only in this sandbox, and `sdkmanager` writes `~/.android` + `$ANDROID_USER_HOME`.
So we point `HOME`, `ANDROID_SDK_ROOT`, `ANDROID_HOME`, `ANDROID_USER_HOME` into the workspace:

```bash
cd work/leica/lsposed/toolchain
mkdir -p downloads home android-user-home libs keystore
export HOME=$PWD/home
export ANDROID_SDK_ROOT=$PWD ANDROID_HOME=$PWD ANDROID_USER_HOME=$PWD/android-user-home

# 1) command-line tools
curl -fL -o downloads/commandlinetools-linux-11076708_latest.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q downloads/commandlinetools-linux-11076708_latest.zip -d /tmp-ct   # any scratch dir
mkdir -p cmdline-tools && mv /tmp-ct/cmdline-tools cmdline-tools/latest && rmdir /tmp-ct

# 2) platform + build-tools (accept licenses non-interactively; --sdk_root is required
#    because cmdline-tools is not in the conventional <sdk>/cmdline-tools/latest layout
#    unless you move it there, which we did above)
yes | cmdline-tools/latest/bin/sdkmanager --sdk_root=$PWD --licenses
yes | cmdline-tools/latest/bin/sdkmanager --sdk_root=$PWD \
      "platforms;android-35" "build-tools;35.0.0"

# 3) legacy Xposed API jar (compile classpath only, never packaged into the APK)
curl -fL -o libs/xposed-api-82.jar \
  https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar

# 4) keystore is auto-generated by build.sh on first run (toolchain/keystore/debug.keystore)
```

Verify installed packages: `sdkmanager --sdk_root=$PWD --list_installed` (with the same env).
The `licenses/` dir holds the accepted license hashes; it is inside the toolchain, not `$HOME`.

---

## 3. Source-tree contract for `build.sh`

```
SRC_DIR/
├── AndroidManifest.xml          REQUIRED. aapt2 compiles it to binary XML (must contain
│                                <uses-sdk android:minSdkVersion=.. targetSdkVersion=..>;
│                                versionCode/versionName recommended, see below)
├── src/**/*.java                REQUIRED. All .java under src/ are compiled (package dirs).
├── res/**                       optional. aapt2 compile --dir; R.java is generated into
│                                <stage>/gen and compiled with your sources.
├── assets/**                    optional. Copied verbatim to APK assets/ (e.g. assets/xposed_init).
├── META-INF/xposed/**           optional. Copied to APK META-INF/xposed/ (module.prop, scope.list).
└── lib/**                       optional. Copied to APK lib/ (native .so; page alignment caveat below).
```

Interface and environment overrides (all optional except the two positionals):

```bash
./build.sh <SRC_DIR> <OUT_APK>
# relative SRC_DIR/OUT_APK are resolved against the caller's current directory

TOOLCHAIN=…          # default: <dir of build.sh>/toolchain
ANDROID_JAR=…        # default: highest toolchain/platforms/android-*/android.jar
BUILD_TOOLS=…        # default: highest toolchain/build-tools/*/
XPOSED_JAR=…         # default: toolchain/libs/xposed-api-82.jar
EXTRA_CLASSPATH=…    # colon-separated extra jars for javac (and d8 --classpath)
JAVA_HOME/JAVAC=…    # default: javac on PATH
MIN_API=29           # d8 --min-api
MIN_SDK/TARGET_SDK=… # override the manifest's uses-sdk at aapt2 link time
VERSION_CODE/VERSION_NAME=…  # override manifest version (fallback 1 / 1.0.0 if manifest omits them)
BUILD_DIR=…          # scratch directory (default toolchain/build/<src-basename>)
KEYSTORE/KS_PASS/KS_ALIAS  # default toolchain/keystore/debug.keystore, android, androiddebugkey
SOURCE_DATE_EPOCH=1700000000 # fixed zip timestamp for deterministic output
```

### Pipeline (each step fails loudly with `ERROR: …` and non-zero exit)

1. **aapt2 compile/link** — `aapt2 compile --dir res` (only if `res/` has files) then
   `aapt2 link -I android.jar --manifest AndroidManifest.xml [-R res.zip --java gen]`.
   Produces the base APK with binary `AndroidManifest.xml` (+ `resources.arsc`).
2. **javac** — `javac --release 17 -sourcepath SRC_DIR/src -classpath android.jar:xposed-api.jar
   -d classes <all .java> [+ generated R.java]`.
3. **d8** — `d8 --min-api 29 --lib android.jar --output dex classes.jar`.
   (The `.class` files are first bundled into a jar: R8 8.6.2's d8 **rejects a directory input**
   with `Unsupported source file type`; this is a build-tools-35 quirk handled inside build.sh.)
4. **package** — the base APK is copied and `classes.dex`, `assets/`, `META-INF/xposed/`, `lib/`
   are appended with `zip -X`. File mtimes are pinned to `SOURCE_DATE_EPOCH` (TZ=UTC).
5. **zipalign** — `zipalign -p -f 4`.
6. **apksigner** — signs with v1+v2+v3 (`--v1/2/3-signing-enabled true`, v4 off). Debug keystore is
   created on first use.
7. **verify** — `apksigner verify --verbose --print-certs` (exit non-zero = build fails),
   full-scheme check with `--min-sdk-version 21`, `zipalign -c -p 4`, `aapt dump badging`,
   `unzip -l`, required-entry check, and a `classes.dex` scan proving every class listed in
   `assets/xposed_init` exists in the dex (python3 zip/dex byte scan; `dexdump` is also available in
   build-tools). Prints final path/size/sha256.

Notes / limits:
- `res/` is genuinely optional (manifest-only link path is tested); without `res/` there is no `R` class.
- `lib/*.so` are added as-is; zipalign `-p` only page-aligns *stored* (uncompressed) `.so` entries —
  if you add native libs, `zip -0` them first (out of scope for the current module).
- APK is signed after `META-INF/xposed` is added, so those entries are covered by the signature.
  **Never modify an APK after signing** (breaks v2/v3) — edit sources and rebuild instead.

---

## 4. `hello/` — minimal real LSPosed module (built & verified)

Source tree:

```
hello/
├── AndroidManifest.xml                 # package com.nezha.leica.lsposed, minSdk 29, targetSdk 35,
│                                       # exported launcher MainActivity, legacy Xposed meta-data
├── res/values/strings.xml              # proves the aapt2 resource + generated-R.java path
├── src/com/nezha/leica/lsposed/HelloHookEntry.java   # implements IXposedHookLoadPackage, logs
├── src/com/nezha/leica/lsposed/MainActivity.java      # programmatic UI launcher
├── assets/xposed_init                  # -> com.nezha.leica.lsposed.HelloHookEntry
├── META-INF/xposed/module.prop         # modern-API metadata (ignored for legacy modules, documented in §6)
├── META-INF/xposed/scope.list          # com.miui.mediaeditor / com.miui.gallery / com.android.camera
└── out/hello-lsposed.apk               # verified build output
```

### Recorded verification (2026-11-10, full log `toolchain/logs/hello-build.log`)

- Command: `./build.sh hello hello/out/hello-lsposed.apk` → `BUILD OK`
- APK: `work/leica/lsposed/hello/out/hello-lsposed.apk`
  - size **12,900 bytes**, sha256 **`e4f9293cd364a3293ee7933de7c9a3d499f4856b1d669975124525e01ef957a6`**
- `apksigner verify --print-certs`: **Verifies**. v3 scheme verified for minSdk 29; with
  `--min-sdk-version 21` all of v1+v2+v3 verify `true`. Signer cert SHA-256
  `7e2a7335…4019bd704…` (debug keystore, see §2).
- `aapt dump badging`:
  `package: name='com.nezha.leica.lsposed' versionCode='1' versionName='1.0.0' …`,
  `sdkVersion:'29'`, `targetSdkVersion:'35'`, `application-label:'Leica Moment LSP (hello)'`.
- `unzip -l`: `AndroidManifest.xml`, `resources.arsc`, `classes.dex`, `assets/xposed_init`,
  `META-INF/xposed/module.prop`, `META-INF/xposed/scope.list` (+ v1 signature files
  `META-INF/ANDROIDD.SF`, `META-INF/ANDROIDD.RSA`, `META-INF/MANIFEST.MF`).
- `dexdump classes.dex | grep 'Class descriptor'` → `Lcom/nezha/leica/lsposed/HelloHookEntry;`,
  `…/MainActivity;`, `…/R;`, `…/R$string;`.
- Determinism: building twice from unchanged sources gives the identical sha256.

### Team module (`app/`, module-dev's tree) — also built to prove the contract

`VERSION_CODE=1 VERSION_NAME=1.0.0 ./build.sh app dist/LeicaMoment_LSPosed_v1.0.0.apk` → `BUILD OK`
(log `toolchain/logs/app-build.log`): 25,188 B, sha256 `64a5e2f50837636c514d00fe562fe97121b5b1454151fa38d7d45998fe7d9f83`,
verifies (v1+v2+v3), badging package `com.nezha.leica.lsposed` v1.0.0 (1), launchable
`.MainActivity`, no `res/` needed, `assets/xposed_init` → `HookEntry` present in dex.
(The manifest omits versionCode/versionName, so build.sh injects 1 / 1.0.0 — logged at build time.)

---

## 5. Determinism

The pipeline is reproducible for unchanged inputs and keystore: fixed stage path, `TZ=UTC`,
`zip -X`, every added entry's mtime pinned to `SOURCE_DATE_EPOCH` (default 1700000000), and the
debug keystore is generated once and reused (RSA signatures are deterministic). Observed: two
consecutive hello builds → same sha256. If you need a different epoch: `SOURCE_DATE_EPOCH=… ./build.sh …`.

---

## 6. LSPosed module metadata — exactly what is needed (researched, with evidence)

LSPosed supports **two module flavours**. They are distinguished by which entry list the APK ships:

| | Legacy Xposed API (what we use) | Modern libxposed API |
|---|---|---|
| Entry class source | `assets/xposed_init` (one class per line) | `META-INF/xposed/java_init.list` |
| Entry interface | `de.robv.android.xposed.IXposedHookLoadPackage` etc. (API 82/93) | `io.github.libxposed.api.XposedModule` |
| LSPosed recognises the module by | `AndroidManifest.xml` meta-data **`xposedminversion`** | presence of `META-INF/xposed/java_init.list` |
| `META-INF/xposed/module.prop` | **ignored** | parsed: `minApiVersion`, `targetApiVersion`, `staticScope` |
| `META-INF/xposed/scope.list` | **ignored** (user ticks scope manually in Manager) | static scope, one package per line |
| Module name / description | `android:label` + `xposeddescription` meta-data | `android:label` / `android:description` |
| API version meaning | rovo89 Xposed API (82 = classic API 82; 93 used by some modules) | libxposed API number: LSPosed master ships **100** |

**Proof (LSPosed source snapshot `toolchain/downloads/lsposed-src/LSPosed-master/`):**

- `app/src/main/java/org/lsposed/manager/util/ModuleUtil.java`
  - `isLegacyModule(info)` = `info.metaData.containsKey("xposedminversion")` → a legacy module
    without that manifest meta-data is **not listed by LSPosed Manager at all**.
  - `getModernModuleApk()` requires `META-INF/xposed/java_init.list`; `InstalledModule` parses
    `META-INF/xposed/module.prop` with keys `minApiVersion`, `targetApiVersion`, `staticScope`
    (`extractIntPart`, `TextUtils.equals(..., "true")`), and `META-INF/xposed/scope.list`.
- `daemon/src/main/java/org/lsposed/lspd/service/ConfigFileManager.java` `loadModule()`:
  reads `META-INF/xposed/java_init.list`; only if empty sets `legacy = true` and reads
  `assets/xposed_init` (+ `assets/native_init`).
- `daemon/src/main/java/org/lsposed/lspd/service/ConfigManager.java`: module is a candidate if
  `java_init.list` **or** `assets/xposed_init` exists.
- `core/src/main/java/de/robv/android/xposed/XposedInit.java` `loadModule()`: **rejects** an APK
  where `de.robv.android.xposed.XposedBridge` resolves from the module's own classloader
  ("The Xposed API classes are compiled into the module's APK") → the api jar must be
  compile-classpath ONLY and must not be fed to d8. `build.sh` never does.
- `core/src/main/java/org/lsposed/lspd/hooker/LoadedApkCreateCLHooker.java`: reads
  `xposedminversion`; values `> 92` (or `xposedsharedprefs`) additionally trigger the legacy
  XSharedPreferences support hooks. API 82 avoids that irrelevant path.
- `gradle/libs.versions.toml`: `libxposed = "100"` (modern API version of current master).
- LSPosed wiki *Develop Xposed Modules Using Modern Xposed API* (raw:
  `https://raw.githubusercontent.com/wiki/LSPosed/LSPosed/Develop-Xposed-Modules-Using-Modern-Xposed-API.md`)
  confirms `java_init.list`, `module.prop` key set, `scope.list`, label/description source.

**Version requirements / compatibility notes (important for the device):**

- A legacy (API 82) module works on original Xposed, EdXposed and **LSPosed 1.x** (all LSPosed
  releases through v1.9.2, Oct 2023) — the legacy loader code is still present in the master
  snapshot from 2024-01-07. Modules using the modern API need **LSPosed ≥ 1.9.2** (first release
  with libxposed API; `minApiVersion`/`targetApiVersion` must be ≤ the framework's API, i.e. 100
  for that line). Our modules are legacy, so `module.prop` values are irrelevant to recognition
  (harmless if present, correct if the tree is ever migrated by also adding `java_init.list` and
  modern-API classes).
- Upstream LSPosed's README (snapshot) states supported Android **8.1–14** and GitHub releases stop
  at v1.9.2; Android 15/16/17 ROMs (like this HyperOS 4 / Android 17 device) need a maintained
  LSPosed fork (e.g. JingMatrix/LSPosed or similar) that carries the same master code forward.
  Those builds keep legacy-module support — verify on-device by seeing the module listed in
  LSPosed Manager with its description; if a fork ever drops legacy API, port `HookEntry` to the
  libxposed API (API 100) and add `META-INF/xposed/java_init.list` implementing
  `io.github.libxposed.api.XposedModule` (then `module.prop`/`scope.list` above start to matter).
- **Do not add `META-INF/xposed/java_init.list`** to a legacy module: its mere presence flips
  LSPosed to modern mode and the legacy `assets/xposed_init` classes are no longer loaded the same
  way.

Exact files shipped in `hello/` (verified format; same as `app/`):

`AndroidManifest.xml` (relevant part):

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.nezha.leica.lsposed" android:versionCode="1" android:versionName="1.0.0">
  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="35" />
  <application android:label="@string/app_name" android:hasCode="true">
    <meta-data android:name="xposedmodule" android:value="true" />
    <meta-data android:name="xposedminversion" android:value="82" />
    <meta-data android:name="xposeddescription" android:value="…" />
    <activity android:name=".MainActivity" android:exported="true"> … MAIN/LAUNCHER … </activity>
  </application>
</manifest>
```

`assets/xposed_init`:

```
com.nezha.leica.lsposed.HelloHookEntry
```

`META-INF/xposed/module.prop` (only used if the module is modern; harmless for legacy):

```properties
minApiVersion=100
targetApiVersion=100
staticScope=true
```

`META-INF/xposed/scope.list` (modern only; one package per line, `#` comments allowed):

```
com.miui.mediaeditor
com.miui.gallery
com.android.camera
```

---

## 7. Install & test on a device with LSPosed

```bash
adb install -r work/leica/lsposed/hello/out/hello-lsposed.apk
# (module-dev's build) adb install -r work/leica/lsposed/dist/LeicaMoment_LSPosed_v1.0.0.apk

# LSPosed Manager (opens from its notification or `adb shell am start -n <manager pkg>/.ui.activity.MainActivity`):
#   1. Modules list → enable the module (name from android:label / xposeddescription)
#   2. Tap the module → 作用域 / Scope → tick com.miui.mediaeditor (+ com.miui.gallery, com.android.camera)
#      (legacy modules have NO static scope; it must be selected manually)
#   3. Force-stop the scoped apps: adb shell am force-stop com.miui.mediaeditor
#   4. Reboot (or soft reboot) so the daemon injects into the apps

# Logs:
adb logcat | grep LeicaMomentLSP      # XposedBridge.log("LeicaMomentLSP: …") lines
# and LSPosed Manager → 日志 / Logs for module load/compile errors
```

The debug signature is fine for LSPosed (it does not tie a module to a signer); if the APK must be
distributed, pass a release key: `KEYSTORE=… KS_PASS=… KS_ALIAS=… ./build.sh app out.apk`.

---

## 8. Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `sdkmanager` fails reading/writing SDK or `~/.android` | `$HOME` read-only. `export HOME=<toolchain>/home ANDROID_USER_HOME=<toolchain>/android-user-home ANDROID_SDK_ROOT=<toolchain> ANDROID_HOME=<toolchain>` and pass `--sdk_root=<toolchain>`. |
| `Warning: Failed to find package …` | Wrong `--sdk_root` or missing license acceptance; run `yes \| sdkmanager --sdk_root=$PWD --licenses` first. `cmdline-tools/latest/` layout is required (not `cmdline-tools/` directly). |
| `d8: Unsupported source file type` | d8 in build-tools 35 rejects a **directory** of `.class` files. `build.sh` jars them first (`jar cf classes.jar -C classes .`). Only an issue if you call d8 by hand. |
| `javac: error: cannot find symbol android.os.SystemProperties` | Public SDK `android.jar` (API 35) has no hidden APIs. Use `XposedHelpers.findAndHookMethod("android.os.SystemProperties", …)` / reflection; or feed hidden-API stubs via `EXTRA_CLASSPATH=…` (compile only). |
| `Target SDK version 35 requires a minimum of signature scheme v2` (apksigner verify) | You signed v1-only. `build.sh` enables v1+v2+v3 explicitly; don't disable v2/v3. |
| `apksigner verify` says `v1: false, v2: false, v3: true` | **Expected**: plain `verify` reports only the schemes *needed* by the APK's own `minSdkVersion` (29 → v3 suffices). `build.sh` also runs `--min-sdk-version 21`, which proves v1+v2+v3 are all present and valid. |
| `WARNING: A restricted method in java.lang.System has been called … org.conscrypt` | JDK 27 warning from apksigner's bundled Conscrypt; harmless (exit code unaffected). Optionally `JAVA_OPTS=--enable-native-access=ALL-UNNAMED`. |
| `aapt2` fails on DrvFs / slow I/O | aapt2 is a native binary but works from `/mnt/d`; the whole build is ~2–4 s for these module sizes. Keep sources small; `toolchain/build/` scratch is on the same disk, acceptable. |
| `zipalign` / signing mismatch, APK won't install after manual edits | v2/v3 signatures cover the whole file: any post-signing change breaks them. Re-run `build.sh` instead of editing the APK. |
| LSPosed Manager doesn't list the module | Legacy module missing `xposedminversion` meta-data (see §6), or the APK was built from a tree without it. Check `aapt dump xmltree <apk> AndroidManifest.xml \| grep xposed`. |
| Module listed but hooks never run | Scope not selected (legacy modules need manual scope), or app not force-stopped/rebooted, or module class name in `assets/xposed_init` doesn't match the compiled class (build.sh verifies this against `classes.dex`). |
| `/tmp` disappears between commands | Sandbox: `/tmp` is not persistent across bash invocations — keep all artifacts under the workspace (this toolchain already does). |
| PKCS12 keystore + `keypass`/`storepass` mismatch | PKCS12 requires the same password for both; `build.sh` uses `android` for both. |

---

## 9. Maintenance / provenance notes

- `toolchain/downloads/` holds the original cmdline-tools zip (can be deleted to save 146 MB; the
  installed SDK does not need it) and the LSPosed master source snapshot used for the metadata
  research in §6 (can be deleted).
- The toolchain is fully self-contained: `build.sh` requires only `bash`, `javac`/`java`
  (system JDK), `zip`/`unzip`, `python3`, and the files under `toolchain/`. No Gradle, no network,
  no writes to `$HOME`.
- To upgrade: drop new build-tools/platforms into `toolchain/` (sdkmanager) — `build.sh`
  auto-selects the highest `platforms/android-*` and `build-tools/*` unless overridden; then re-run
  `./build.sh hello hello/out/hello-lsposed.apk` and confirm `BUILD OK` + §4 checks.
- Ownership: `toolchain/`, `hello/`, `build.sh`, `README_BUILD.md` → sdk-builder.
  `app/`, `dist/` → module-dev; `analysis/` → cookie-analyst. `build.sh` interface is frozen
  (Lead + module-dev depend on it).
