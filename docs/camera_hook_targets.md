# CN MiuiCamera 6.7.000280.0 — no-property-spoof hook targets to force Leica Edition capture UI

Target: `com.android.camera` 6.7.000280.0 (versionCode 670002800), CN APK `work/leica/cn/apps2/MiuiCamera.apk`, mounted read-only via Magisk "CameraOnly"; LSPosed scope `com.android.camera`.
Constraint: **no resetprop / no SystemProperties hook**. All targets below are Java/Kotlin methods; none requires faking a property.
Evidence source: fresh baksmali of all 11 CN camera dex:
`work/leica/cn/decomp/camera_smali_d1` (classes.dex) and `camera_smali_d4` (classes4.dex) + `work/leica/lsposed/analysis/camera_smali/classes{2,3,5..11}` (this task).
Names are R8 obfuscated exactly as they appear in the CN APK; **do not trust global-ROM names** (global 6.6 has different obfuscation).

## 0. The two gates (why hooks work)

Mode list construction is centralised in `Lu3/a;` (`work/leica/cn/decomp/camera_smali_d1/u3/a.smali`):
- `<clinit>` registers 55 entry classes, incl. **`LegendaryEnter` at index 15** (`u3/a.smali:68`).
- `u3/a.a(Landroid/util/SparseArray;)` instantiates each entry class by reflection and calls **`com.android.camera.module.entry.a;->support()Z`** (`u3/a.smali:420`); only entries returning `true` are `put(getModuleId(), entry)` into the mode list.
  → **Hook #1 = `LegendaryEnter.support()Z` → true** makes mode id **0x100** (徕卡一瞬 / Leica Essential) appear.

`LegendaryEnter.support()Z` does not read props itself (`camera_smali_d1/com/android/camera/features/mode/legendary/LegendaryEnter.smali:123-142`): it returns
`Te/c$b.a` singleton → field `Te/c;->e:L䏖䏚䏘䎛…;` → `h5()Z`. At runtime `Te/c;->e` is created by `LDw/a;->m()` (`camera_smali_d4/Te/c.smali:227`), a class **not present in the APK dex** (external camera framework jar) — so hook `support()` directly instead of trying to hook the provider.
The device-config implementation that does `h5()` is `L뾓뾟뾝뿞…;` (`camera_smali_d4/뾓뾟뾝뿞뾝뾙뿞뾔뾕뾆뾙뾓뾕뿞뾾뾕뾊뾘뾑.smali:2053-2065`) and it simply calls `Te/c.W()Z`.

`Te/c.W()Z` is the actual Leica capability predicate (`camera_smali_d4/Te/c.smali:351-376`):
```
sget-object LVa/b;->u           // static cached SystemProperties.get("ro.theme_customize","")
const-string "LCC"
equals(...) && !LVa/b;->w       // LVa/b->w = SystemProperties "camera.debug.theme_customize.disabled"
```
`Va/b` cache is built in `camera_smali_d4/Va/b.smali` `<clinit>`: `ro.boot.product.theme_customize`→`t` (line 346), `ro.theme_customize`→`u` (line 354), `ro.product.mod_device`→`v`, `camera.debug.theme_customize.disabled`→`w` (line 370). Since `Va/b` fields are **static final booleans/strings (not hookable as fields)**, hook the **methods** that read them (below).
`Te/c.W()` has **15 call sites** in classes4.dex alone (Leica look/watermark/zoom/UI): `ba/F`, `ba/k`, `ba/l`, `ba/t`, `ba/w`, `C9/n`, `aa/j0`, `ea/o`, `s9/a`, `X9/i0`, `com/android/camera/ui/zoom/ZoomTextImageView`, `com/android/camera2/compat/theme/custom/mm/top/MenuExpendViewMM`, `com/android/camera2/compat/theme/custom/mm/cinemaster/view/a`, plus `e6/c` (= `FragmentLegendarySelected`) at `camera_smali_d1/e6/c.smali:530`, plus the device-config impl above.
→ **Hook #2 = `Te/c.W()Z` → true** turns on the whole Leica UI/branding path that otherwise requires `ro.theme_customize=LCC`.

## 1. LSPosed hook target list (CN camera 6.7.000280.0)

| # | Class (exact smali descriptor) | Method / signature | static? | Force | Purpose | Evidence |
|---|---|---|---|---|---|---|
| **H1** | `com.android.camera.features.mode.legendary.LegendaryEnter` | `support()Z` | instance | `return true` | makes 徕卡一瞬 mode (id `0x100`) visible in mode list / mode drawer | `camera_smali_d1/.../LegendaryEnter.smali:123`; filter `u3/a.smali:420`; registry `u3/a.smali:68` |
| **H2** | `Te.c` (obf. `LTe/c;`) | `public static W()Z` | static | `return true` | global "isLeicaCustomize": enables Leica UI/branding (11 call sites) + makes `LegendaryEnter.support()`'s backing impl `h5()` true | `camera_smali_d4/Te/c.smali:351-376`; impl `뾓뾟…smali:2053`; settings cache `Va/b.smali:346,354,370` |
| **H3 (fallback)** | obf. device-config impl `L뾓뾟뾝뿞뾝뾙뿞뾔뾕뾆뾙뾓뾕뿞뾾뾕뾊뾘뾑;` | `public final h5()Z` | instance | `return true` | if H1 cannot be resolved in some process (class may be verified early) or another path calls h5 through `Te/c.e` | `camera_smali_d4/뾓…smali:2053-2065` |
| **H4 (fallback)** | `com.android.camera.features.mode.legendary.LegendaryModule` | class constructor / `getModuleId` independent — see note | instance | not needed if H1 works | module itself is instantiated only after mode is entered; no prop check found in `LegendaryModule.smali`/`LegendaryModule$a.smali` (280/282 lines) | `camera_smali_d1/.../LegendaryModule.smali` |
| **H5 (fallback, style picker)** | `com.android.camera.data.data.config.ComponentConfigLegendary` = obf. `Ls2/A;` | `isSupportMode(I)Z` | instance | `return (mode==0x100)` — **already returns exactly that**, no hook needed; listed for completeness | legendary styles (M3/M9/CCD/CMOS/BW, 35/75mm) list is built unconditionally for mode 0x100 | `camera_smali_d1/s2/A.smali:243-258`, item build `s2/A.smali:1-200` |
| **H6 (fallback, style-picker item)** | `com.android.camera.features.mode.capture.W0` | `public static a(Landroid/content/Context;I)La5/c;` | static | if null returned, hook `v2/U;->M()Z` & `v2/U;->S()Z` → true instead | builds the "Leica 35mm/75mm style" top item; returns null only if `h2/a.g().M()/S()` false (camera state, not props) — normally fine | `camera_smali_d1/com/android/camera/features/mode/capture/W0.smali:69-160`; UI `U3/b.smali:1-40` |

### Implementation notes for LSPosed
- H1 and H2 are **static-free vs instance**: H1 is an `invoke-interface` on `BaseModuleEntry` implementation → hook as instance method on `LegendaryEnter`; H2 is `invoke-static {}` → hook static method.
- Hook early: `u3/a` mode list is built during camera startup, so hook in `IXposedHookLoadPackage` `handleLoadPackage("com.android.camera")` with `XposedHelpers.findAndHookMethod(..., "support", ...)` and use class-loading callback / `XposedBridge.hookAllMethods` after `lpparam.classLoader` is available; if the class is already initialized before your hook is attached, additionally hook H2 (consumed at runtime) as safety net.
- Both MethodsReturn `boolean`; do not touch properties/fields.
- `Va/b` (settings cache) and `W8/h` are **watermark/EXIF consumers**, not needed for mode entry: `W8/h.smali:491` reads `ro.theme_customize` (fallback `ro.boot.product.theme_customize`) and `ro.miui.build.region` to build the watermark theme `Rg/s$a`; `n7/d$a.smali:613` writes `XiaomiAuxiliaryInfo.customize` from `ro.boot.product.theme_customize` into EXIF. With no props these degrade to global branding but do not block the Leica UI.
- H2's predicate also respects `camera.debug.theme_customize.disabled`; a method hook ignores it (desired here).

## 2. Full camera prop-read inventory (as requested)

Direct prop read sites found in the 11 CN camera dex (all `SystemProperties` via `Wr/g`/`Ss/g` wrappers):
- `camera_smali_d4/Va/b.smali` `<clinit>`: `ro.boot.product.theme_customize` (L346), `ro.theme_customize` (L354), `ro.product.mod_device` (L368), `camera.debug.theme_customize.disabled` (L370), `ro.miui.cust_variant` (L332), plus many `camera.debug.*`/`camera.lab.options`/`camera.feature.*` flags — cached into static final fields `LVa/b;->a..z/A..`, `t`(boot theme), `u`(theme), `v`(mod_device), `w`(theme disabled).
- `camera_smali_d4/Te/c.smali`: `W()Z` (L351) = `Va/b->u=="LCC" && !Va/b->w`; `W1()Z` (L378) = `Va/b->t=="WestCoast"`.
- `camera_smali_d4/W8/h.smali`: L491 `ro.theme_customize` → fallback L499 `ro.boot.product.theme_customize`; L~528 `ro.miui.build.region` default `"cn"` — watermark theme only.
- `camera_smali_d4/Ue/c$a.smali`: L33 `ro.boot.product.theme_customize` cached into static `Ue/c$a->b` (theme/watermark helper; global counterpart `Lag/c$a`, i.e. Watermark text builder; not a mode gate).
- `camera_smali_d1/n7/d$a.smali`: L613 `ro.boot.product.theme_customize` → `XiaomiAuxiliaryInfo.customize` (EXIF aux written at save time).
- `ro.theme_leica` is **not read by CN camera dex at all** (only ODM prop files set it). `ro.boot.hwc`, `ro.product.mod_device`, `IS_INTERNATIONAL_BUILD`, `miui.util.FeatureParser` appear in classes.dex strings but do not gate Legendary/Leica UI (FeatureParser is used for pad/foldable checks).
- No `camera.debug.safe.check` string found in CN camera 6.7 dex (likely lives in `CameraTools_beta.apk` or global 6.6 only) — NOT DONE, low priority.

## 3. What a Java hook CANNOT fix (be explicit to user)

1. **Vendor camera HAL legendary capabilities.** Capture-side legendary profiles (M9 CCD / M3 MONOPAN-50 / M10R) are applied in the vendor camera pipeline, not in the APK. The APK only requests session params/features (`com.xiaomi.sessionparams.legendMode`, `com.xiaomi.legendMode.capsInfo`, `getLegendBokehCapsInfo`, `legendApertureListMax`, `legendFocalLength` — camera dex strings; `LegendaryModule` native bridge classes are referenced but not defined in the APK dex, see §4). If the phone's real vendor HAL does not expose legendary caps/session params, forcing the UI shows the mode and controls but the actual Leica look/post-processing may be missing or fall back. Java hooks cannot add HAL capability — this is the main residual risk.
2. **Cloud "二次增强" entitlement** for photos is editor/server-side (`com.miui.mediaeditor` / `api.open.ai.xiaomi.com`), out of scope of a camera hook; camera hook only makes the *capture* UI exist.
3. **Signature/privilege**: CN MiuiCamera must be able to run as system app via the magic mount (same package `com.android.camera`, cert = Xiaomi MIUI release cert per `cn_analysis.md`). LSPosed hooks need the CN APK's class names (they differ from global 6.6).
4. **Global ROM 6.6.000570.5 equivalents**: NOT DONE here (global APK not my input). Cheap method: baksmali global camera, find the global `LegendaryEnter`-equivalent (class whose `support()Z` returns a call into global settings class `Lcb/c`, and locate the global `W()`-equivalent by searching smali for the string `"LCC"` literal — global settings cache is `Lcb/c` per global-analyst). Apply same 2-hook pattern. Conversely the global ROM per global-analyst may already contain `LegendaryModule` but lack CN-only resources — then the CN APK swap + H1/H2 is the path.

## 4. Open items (NOT DONE)

1. `LegendaryNativeCaptureBridge` / `LegendaryProcessingProvider` / `LegendM9Container` are **referenced** in CN camera dex strings but **not defined** in any of the 11 CN dllexes (verified string scan + smali class list) → they are either dynamically loaded/plugin/native, or obfuscated equivalents. H1/H2 do not depend on them for UI, but this is the risk in §3.1. Time-box ended before locating the loader.
2. H3's exact runtime class chain depends on the external provider `LDw/a;->m()` (not in APK dex); harmless because H1 hooks the caller.
3. Global 6.6 exact hook descriptors: NOT DONE (see §3.4).

## 5. Minimal recommended hook set (for the module)

```
H1: com.android.camera.features.mode.legendary.LegendaryEnter#support()Z -> true      (primary, mode entry)
H2: Te.c#W()Z (static) -> true                                                        (primary, Leica UI/branding)
```
Optional/defensive: H3 `L뾓뾟뾝뿞…;#h5()Z -> true`; if the style picker item is missing, hook `v2.U#M()Z` and `v2.U#S()Z` -> true.
