#!/usr/bin/env bash
# =============================================================================
# build.sh — Gradle-free builder for LSPosed / Xposed module APKs
#
#   ./build.sh <SRC_DIR> <OUT_APK>
#
# SRC_DIR layout (all except AndroidManifest.xml + src/ are optional):
#   SRC_DIR/AndroidManifest.xml     binary-compiled by aapt2 (required)
#   SRC_DIR/src/**/*.java           compiled by javac --release 17  (required)
#   SRC_DIR/res/**                  compiled by aapt2 (optional; generates R.java)
#   SRC_DIR/assets/**               copied verbatim into APK assets/  (optional)
#   SRC_DIR/META-INF/xposed/**      copied into APK META-INF/xposed/ (optional)
#   SRC_DIR/lib/<abi>/*.so          copied into APK lib/<abi>/ (optional)
#
# Pipeline:  aapt2 compile/link  ->  javac  ->  d8  ->  zip into linked APK
#            ->  zipalign -p 4  ->  apksigner (auto-generated debug keystore)
#            ->  verification (apksigner verify, aapt badging, zip listing,
#                classes.dex string scan for the xposed_init entry classes)
#
# Environment overrides (all optional):
#   TOOLCHAIN=...        toolchain root (default: <dir of build.sh>/toolchain)
#   ANDROID_JAR=...      android.jar (default: highest toolchain/platforms/android-*)
#   BUILD_TOOLS=...      build-tools dir (default: highest toolchain/build-tools/*)
#   XPOSED_JAR=...       Xposed API jar (default: toolchain/libs/xposed-api-82.jar)
#   EXTRA_CLASSPATH=...  colon-separated extra jars (compile classpath only)
#   JAVA_HOME / JAVAC    javac to use (default: javac on PATH)
#   MIN_API=29           d8 --min-api (default 29)
#   MIN_SDK / TARGET_SDK override the manifest's uses-sdk at link time
#   VERSION_CODE / VERSION_NAME override the manifest version
#   BUILD_DIR=...        scratch dir (default: toolchain/build/<src-name>)
#   KEYSTORE / KS_PASS / KS_ALIAS   signing key (default: toolchain/keystore/debug.keystore,
#                                    password "android", alias "androiddebugkey")
#   SOURCE_DATE_EPOCH=...  fixed timestamp for determinism (default 1700000000)
#
# Exit code 0 = APK built, signed and verified. Non-zero = first failing step.
# =============================================================================
set -euo pipefail

# ---------- locate everything -------------------------------------------------
CALLER_PWD="$(pwd)"
SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
TOOLCHAIN="${TOOLCHAIN:-$SCRIPT_DIR/toolchain}"

# pick highest platform / build-tools if not explicitly given
pick_android_jar() {
  local p
  p="$(ls -d "$TOOLCHAIN"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -n 1 || true)"
  echo "$p"
}
pick_build_tools() {
  local d
  d="$(ls -d "$TOOLCHAIN"/build-tools/*/ 2>/dev/null | sort -V | tail -n 1 || true)"
  echo "${d%/}"
}

ANDROID_JAR="${ANDROID_JAR:-$(pick_android_jar)}"
BUILD_TOOLS="${BUILD_TOOLS:-$(pick_build_tools)}"
XPOSED_JAR="${XPOSED_JAR:-$TOOLCHAIN/libs/xposed-api-82.jar}"
EXTRA_CLASSPATH="${EXTRA_CLASSPATH:-}"
MIN_API="${MIN_API:-29}"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1700000000}"

AAPT2="$BUILD_TOOLS/aapt2"
AAPT="$BUILD_TOOLS/aapt"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
D8="$BUILD_TOOLS/d8"
KEYTOOL="${KEYTOOL:-$(command -v keytool || true)}"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JAVAC="${JAVAC:-$JAVA_HOME/bin/javac}"
else
  JAVAC="${JAVAC:-$(command -v javac || true)}"
fi

KEYSTORE="${KEYSTORE:-$TOOLCHAIN/keystore/debug.keystore}"
KS_PASS="${KS_PASS:-android}"
KS_ALIAS="${KS_ALIAS:-androiddebugkey}"

die()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo "  [build] $*"; }
step() { echo; echo "== $* =="; }

# ---------- args --------------------------------------------------------------
if [ "$#" -ne 2 ]; then
  echo "usage: $0 <SRC_DIR> <OUT_APK>" >&2
  exit 2
fi

SRC_DIR="$1"
OUT_APK="$2"

case "$SRC_DIR" in
  /*) : ;;
  *)  SRC_DIR="$CALLER_PWD/$SRC_DIR" ;;
esac
case "$OUT_APK" in
  /*) : ;;
  *)  OUT_APK="$CALLER_PWD/$OUT_APK" ;;
esac
[ -d "$SRC_DIR" ] || die "source dir not found: $SRC_DIR"
SRC_DIR="$(cd -- "$SRC_DIR" && pwd -P)"
[ -f "$SRC_DIR/AndroidManifest.xml" ] || die "missing $SRC_DIR/AndroidManifest.xml"
[ -d "$SRC_DIR/src" ] || die "missing $SRC_DIR/src (Java sources; package dirs under it)"
SRC_NAME="$(basename -- "$SRC_DIR")"
STAGE="${BUILD_DIR:-$TOOLCHAIN/build/$SRC_NAME}"
STAGE="$(realpath -m -- "$STAGE")"
# Safety: never rm -rf a path that is the source tree or contains it (this can
# happen if the caller puts SRC_DIR under the default scratch dir).
if [ "$STAGE" = "$SRC_DIR" ] || [[ "$SRC_DIR" == "$STAGE"/* ]]; then
  STAGE="$TOOLCHAIN/build/.stage-$SRC_NAME"
fi
OUT_DIR="$(dirname -- "$OUT_APK")"
mkdir -p -- "$OUT_DIR"

# ---------- tool sanity -------------------------------------------------------
step "Toolchain check"
[ -n "$ANDROID_JAR" ] && [ -f "$ANDROID_JAR" ] || die "android.jar not found (set ANDROID_JAR= or install platforms;android-35)"
[ -n "$BUILD_TOOLS" ] && [ -d "$BUILD_TOOLS" ] || die "build-tools not found (set BUILD_TOOLS= or install build-tools;35.0.0)"
[ -x "$AAPT2" ] || die "aapt2 not executable: $AAPT2"
[ -x "$ZIPALIGN" ] || die "zipalign not executable: $ZIPALIGN"
[ -x "$APKSIGNER" ] || die "apksigner not executable: $APKSIGNER"
[ -f "$XPOSED_JAR" ] || die "Xposed API jar not found: $XPOSED_JAR"
[ -n "$JAVAC" ] || die "javac not found on PATH"
command -v zip >/dev/null 2>&1 || die "zip util not found"
command -v unzip >/dev/null 2>&1 || die "unzip util not found"
command -v python3 >/dev/null 2>&1 || die "python3 not found"
JAR="${JAR:-$(command -v jar || true)}"
[ -n "$JAR" ] || die "jar util not found (needed to bundle .class files for d8)"

info "toolchain : $TOOLCHAIN"
info "android.jar: $ANDROID_JAR"
info "build-tools: $BUILD_TOOLS ($("$AAPT2" version 2>&1 | head -n1 || true))"
info "xposed jar : $XPOSED_JAR"
info "javac      : $JAVAC ($("$JAVAC" -version 2>&1))"
info "src        : $SRC_DIR"
info "out        : $OUT_APK"
info "stage      : $STAGE"

# ---------- keystore ----------------------------------------------------------
step "Signing key"
mkdir -p -- "$(dirname -- "$KEYSTORE")"
if [ ! -f "$KEYSTORE" ]; then
  [ -n "$KEYTOOL" ] || die "keytool not found and keystore missing: $KEYSTORE"
  info "generating debug keystore (RSA-2048, PKCS12): $KEYSTORE"
  "$KEYTOOL" -genkeypair -noprompt \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1 \
    || die "keytool failed to create $KEYSTORE"
else
  info "using existing keystore: $KEYSTORE"
fi

# ---------- clean stage -------------------------------------------------------
rm -rf -- "$STAGE"
mkdir -p -- "$STAGE/classes" "$STAGE/dex" "$STAGE/pkg"

# ---------- 1) resources + manifest ------------------------------------------
step "1/7 aapt2: compile + link resources/manifest"
HAVE_RES=0
if [ -d "$SRC_DIR/res" ] && find "$SRC_DIR/res" -type f -print -quit 2>/dev/null | grep -q .; then
  HAVE_RES=1
fi
LINK_ARGS=(-o "$STAGE/base.apk" -I "$ANDROID_JAR" --manifest "$SRC_DIR/AndroidManifest.xml")
if [ "$HAVE_RES" -eq 1 ]; then
  info "compiling $SRC_DIR/res -> res.zip"
  "$AAPT2" compile --dir "$SRC_DIR/res" -o "$STAGE/res.zip" \
    || die "aapt2 compile failed"
  LINK_ARGS+=(-R "$STAGE/res.zip" --auto-add-overlay --java "$STAGE/gen")
  mkdir -p -- "$STAGE/gen"
else
  info "no res/ directory: linking manifest only"
fi
# versionCode/versionName: env overrides win; if the manifest omits them, inject
# safe defaults so the APK is always installable (aapt2 link would otherwise
# leave them absent and `aapt dump badging` shows empty version).
if [ -n "${VERSION_CODE:-}" ]; then
  LINK_ARGS+=(--version-code "$VERSION_CODE")
elif ! grep -q 'android:versionCode' "$SRC_DIR/AndroidManifest.xml"; then
  info "manifest has no android:versionCode: using 1 (set VERSION_CODE= to override)"
  LINK_ARGS+=(--version-code 1)
fi
if [ -n "${VERSION_NAME:-}" ]; then
  LINK_ARGS+=(--version-name "$VERSION_NAME")
elif ! grep -q 'android:versionName' "$SRC_DIR/AndroidManifest.xml"; then
  info "manifest has no android:versionName: using 1.0.0 (set VERSION_NAME= to override)"
  LINK_ARGS+=(--version-name 1.0.0)
fi
[ -n "${MIN_SDK:-}" ]      && LINK_ARGS+=(--min-sdk-version "$MIN_SDK")
[ -n "${TARGET_SDK:-}" ]   && LINK_ARGS+=(--target-sdk-version "$TARGET_SDK")
"$AAPT2" link "${LINK_ARGS[@]}" || die "aapt2 link failed"
[ -f "$STAGE/base.apk" ] || die "aapt2 link produced no APK"
info "linked base APK: $(stat -c%s "$STAGE/base.apk") bytes"

# ---------- 2) javac ----------------------------------------------------------
step "2/7 javac: compile Java sources"
mapfile -t JAVA_FILES < <(find "$SRC_DIR/src" -name '*.java' -type f | sort)
[ "${#JAVA_FILES[@]}" -gt 0 ] || die "no .java files under $SRC_DIR/src"
info "java sources: ${#JAVA_FILES[@]}"
if [ "$HAVE_RES" -eq 1 ]; then
  mapfile -t R_FILES < <(find "$STAGE/gen" -name '*.java' -type f | sort)
  JAVA_FILES+=("${R_FILES[@]}")
  info "generated R.java: ${#R_FILES[@]}"
fi
CP="$ANDROID_JAR:$XPOSED_JAR"
[ -n "$EXTRA_CLASSPATH" ] && CP="$CP:$EXTRA_CLASSPATH"
"$JAVAC" --release 17 -encoding UTF-8 \
  -sourcepath "$SRC_DIR/src" \
  -classpath "$CP" \
  -d "$STAGE/classes" \
  "${JAVA_FILES[@]}" || die "javac failed"

# ---------- 3) d8 -------------------------------------------------------------
step "3/7 d8: dex classes (min-api $MIN_API)"
# d8 (R8 8.6.2 in build-tools 35) does NOT accept a directory of .class files
# ("Unsupported source file type"), so bundle them into a jar first.
"$JAR" cf "$STAGE/classes.jar" -C "$STAGE/classes" . || die "jar failed"
D8_ARGS=(--min-api "$MIN_API" --lib "$ANDROID_JAR" --output "$STAGE/dex")
if [ -n "$EXTRA_CLASSPATH" ]; then
  IFS=':' read -r -a EXTRA_ARR <<< "$EXTRA_CLASSPATH"
  for j in "${EXTRA_ARR[@]}"; do [ -n "$j" ] && D8_ARGS+=(--classpath "$j"); done
fi
"$D8" "${D8_ARGS[@]}" "$STAGE/classes.jar" || die "d8 failed"
[ -f "$STAGE/dex/classes.dex" ] || die "d8 produced no classes.dex"
info "classes.dex: $(stat -c%s "$STAGE/dex/classes.dex") bytes"

# ---------- 4) package into linked APK ---------------------------------------
step "4/7 package: classes.dex + assets + META-INF/xposed + lib"
export TZ=UTC
cp -- "$STAGE/base.apk" "$STAGE/pkg/unsigned.apk"
FIX_TS=(-d "@$SOURCE_DATE_EPOCH")
(cd "$STAGE/dex" && touch "${FIX_TS[@]}" classes.dex && zip -X -q "$STAGE/pkg/unsigned.apk" classes.dex) \
  || die "failed to add classes.dex to APK"
if [ -d "$SRC_DIR/assets" ] && find "$SRC_DIR/assets" -type f -print -quit 2>/dev/null | grep -q .; then
  find "$SRC_DIR/assets" -exec touch "${FIX_TS[@]}" {} + 2>/dev/null || true
  (cd "$SRC_DIR" && zip -X -qr "$STAGE/pkg/unsigned.apk" assets/) \
    || die "failed to add assets/ to APK"
  info "added assets/: $(find "$SRC_DIR/assets" -type f | wc -l) file(s)"
fi
if [ -d "$SRC_DIR/META-INF/xposed" ] && find "$SRC_DIR/META-INF/xposed" -type f -print -quit 2>/dev/null | grep -q .; then
  find "$SRC_DIR/META-INF/xposed" -exec touch "${FIX_TS[@]}" {} + 2>/dev/null || true
  (cd "$SRC_DIR" && zip -X -qr "$STAGE/pkg/unsigned.apk" META-INF/xposed/) \
    || die "failed to add META-INF/xposed/ to APK"
  info "added META-INF/xposed/"
fi
if [ -d "$SRC_DIR/lib" ] && find "$SRC_DIR/lib" -type f -print -quit 2>/dev/null | grep -q .; then
  find "$SRC_DIR/lib" -exec touch "${FIX_TS[@]}" {} + 2>/dev/null || true
  (cd "$SRC_DIR" && zip -X -qr "$STAGE/pkg/unsigned.apk" lib/) \
    || die "failed to add lib/ to APK"
  info "added lib/ (native libs; note: .so should be page-aligned/stored)"
fi

# ---------- 5) zipalign -------------------------------------------------------
step "5/7 zipalign -p 4"
"$ZIPALIGN" -p -f 4 "$STAGE/pkg/unsigned.apk" "$STAGE/pkg/aligned.apk" \
  || die "zipalign failed"
info "aligned APK: $(stat -c%s "$STAGE/pkg/aligned.apk") bytes"

# ---------- 6) sign -----------------------------------------------------------
step "6/7 apksigner: sign"
rm -f -- "$OUT_APK"
"$APKSIGNER" sign \
  --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true \
  --v3-signing-enabled true --v4-signing-enabled false \
  --out "$OUT_APK" "$STAGE/pkg/aligned.apk" \
  || die "apksigner sign failed"
info "signed APK: $(stat -c%s "$OUT_APK") bytes"

# ---------- 7) verify ---------------------------------------------------------
step "7/7 verify"
echo "--- apksigner verify --verbose --print-certs ---"
"$APKSIGNER" verify --verbose --print-certs "$OUT_APK" || die "apksigner verify FAILED"

echo
echo "--- apksigner full scheme check (--min-sdk-version 21) ---"
# NOTE: plain `apksigner verify` only reports the schemes NEEDED by the APK's
# own minSdkVersion. Our APKs carry v1+v2+v3; forcing min-sdk 21 makes the
# verifier check the v1 and v2 signatures too (all become true if present/valid).
"$APKSIGNER" verify --verbose --min-sdk-version 21 "$OUT_APK" > "$STAGE/verify-full.txt" 2>&1 \
  || { cat "$STAGE/verify-full.txt"; die "full scheme verification FAILED"; }
grep -E 'Verifies|Verified using (v1|v2|v3) scheme' "$STAGE/verify-full.txt"

echo
echo "--- aapt dump badging (first lines) ---"
if "$AAPT" dump badging "$OUT_APK" > "$STAGE/badging.txt" 2>/dev/null; then
  sed -n '1,6p' "$STAGE/badging.txt"
else
  info "aapt badging unavailable"
fi

echo
echo "--- zipalign -c -p 4 ---"
"$ZIPALIGN" -c -p 4 "$OUT_APK" >/dev/null || die "zipalign verification FAILED"

echo
echo "--- unzip -l (first lines) ---"
unzip -l "$OUT_APK" | sed -n '1,20p'

echo
echo "--- required APK entries ---"
ENTRIES="$(unzip -Z1 "$OUT_APK")"
REQUIRED=(AndroidManifest.xml classes.dex)
[ -d "$SRC_DIR/assets" ] && REQUIRED+=(assets/xposed_init)
[ -f "$SRC_DIR/META-INF/xposed/module.prop" ] && REQUIRED+=(META-INF/xposed/module.prop)
[ -f "$SRC_DIR/META-INF/xposed/scope.list" ] && REQUIRED+=(META-INF/xposed/scope.list)
for e in "${REQUIRED[@]}"; do
  if echo "$ENTRIES" | grep -qx -- "$e"; then
    info "OK  $e"
  else
    die "MISSING apk entry: $e"
  fi
done

echo
echo "--- classes.dex content scan (entry classes from assets/xposed_init) ---"
python3 - "$OUT_APK" "$SRC_DIR" <<'PY'
import sys, zipfile, re

apk_path, src_dir = sys.argv[1], sys.argv[2]
z = zipfile.ZipFile(apk_path)
dex = z.read('classes.dex')
classes = sorted(set(re.findall(rb'(?:L[a-zA-Z0-9_$/]+;)', dex)))
names = {c.decode('utf-8', 'replace') for c in classes}
wanted = []
try:
    with open(src_dir + '/assets/xposed_init', 'r', encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                wanted.append(line)
except FileNotFoundError:
    pass
if not wanted:
    print('  (no assets/xposed_init — nothing to scan)')
ok = True
for cls in wanted:
    desc = 'L' + cls.replace('.', '/') + ';'
    hit = desc in names
    print(f'  {"OK " if hit else "MISSING"} {desc} in classes.dex')
    ok = ok and hit
print(f'  total class descriptors in dex: {len(names)}')
sys.exit(0 if ok else 1)
PY
[ "$?" -eq 0 ] || die "classes.dex does not contain all assets/xposed_init entry classes"

echo
echo "--- result ---"
SHA256="$(sha256sum "$OUT_APK" | awk '{print $1}')"
SIZE="$(stat -c%s "$OUT_APK")"
info "APK    : $OUT_APK"
info "size   : $SIZE bytes ($(awk -v s="$SIZE" 'BEGIN{printf "%.2f", s/1024}') KiB)"
info "sha256 : $SHA256"
echo
echo "BUILD OK"
