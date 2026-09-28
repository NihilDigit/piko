#!/bin/bash
# macOS 上应用内更新的端到端冒烟：真装、真启动、真更新，断言落到磁盘上的结果。
#
# 输入是同一份源码打出的两个 DMG（Base 与 Next），各放一个目录，名字与 Release 附件相同：
# piko-macos-<架构>-<版本>.dmg。包名用测试专用的（build.gradle.kts 的 pikoDesktopPackageName），
# 装进 ~/Applications，不碰 /Applications 里可能有的 Piko。
#
# 应用直接执行包里的启动器，经 JAVA_TOOL_OPTIONS 指向本脚本起的假 Release（fake_release.py），
# 并带 piko.update.auto：开屏查到新版即自动下载、退出、交给 apply-update-mac.sh。
# 更新后由脚本经 open 重新打开，那一次不带这些参数，查的是真的 GitHub，版本比它旧，不会再动。
#
# 场景：
#   1. 从 DMG 全新安装，启动后存活，没有新版时不动
#   2. 应用内更新：Info.plist 的版本变成 Next，签名仍完好（codesign --verify），新版本被重新打开，
#      旁边不留 .old 与 .new
#
# 用法：macos.sh <Base 目录> <Next 目录> <Base 版本> <Next 版本> <包名> <工作目录> [架构]
set -euo pipefail
base_dir="$1"; next_dir="$2"; base_version="$3"; next_version="$4"; package="$5"; work="$6"; arch="${7:-arm64}"
port=8765
here="$(cd "$(dirname "$0")" && pwd)"
apps="$HOME/Applications"
bundle="$apps/$package.app"
releases="$work/releases"
logs="$work/logs"
staging="${TMPDIR:-/tmp}/piko-update"
mkdir -p "$releases" "$logs" "$apps"

fail() { echo "SMOKE FAILED: $*" >&2; save_logs; exit 1; }
save_logs() {
    cp -R "$staging" "$logs/staging" 2>/dev/null || true
    cp -R "$HOME/.piko/logs" "$logs/app-logs" 2>/dev/null || true
}
dmg_of() { echo "$1/piko-macos-$arch-$2.dmg"; }
bundle_version() { /usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$bundle/Contents/Info.plist" 2>/dev/null || true; }
app_running() { pgrep -f "$bundle/Contents/MacOS/" > /dev/null; }
stop_app() {
    pkill -f "$bundle/Contents/MacOS/" 2>/dev/null || true
    for _ in $(seq 50); do app_running || return 0; sleep 0.2; done
}
publish() { echo "$1" > "$releases/latest.txt"; }
start_app() {
    local options="-Dpiko.update.api=http://127.0.0.1:$port/latest"
    [ "${1:-}" = auto ] && options="$options -Dpiko.update.auto=true"
    JAVA_TOOL_OPTIONS="$options" nohup "$bundle/Contents/MacOS/$package" > "$logs/app-$(date +%s).out" 2>&1 &
}

for pair in "$base_dir:$base_version" "$next_dir:$next_version"; do
    dir="${pair%%:*}"; version="${pair##*:}"
    mkdir -p "$releases/$version"
    cp "$(dmg_of "$dir" "$version")" "$releases/$version/" || fail "missing DMG for $version"
done

python3 "$here/fake_release.py" "$releases" "$port" > "$logs/server.out" 2>&1 &
server=$!
trap 'stop_app; kill $server 2>/dev/null || true' EXIT
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$port/latest" > /dev/null 2>&1 && break; sleep 0.2; done

echo '::group::1. fresh install from DMG'
publish "$base_version"
rm -rf "$bundle" "$bundle.old" "$bundle.new" "$staging"
mount="$(mktemp -d)"
hdiutil attach -nobrowse -readonly -noautoopen -mountpoint "$mount" "$(dmg_of "$base_dir" "$base_version")" > /dev/null
ditto "$mount/$package.app" "$bundle"
hdiutil detach "$mount" -force > /dev/null
[ "$(bundle_version)" = "$base_version" ] || fail "fresh install has version $(bundle_version)"
codesign --verify --deep --strict "$bundle" || fail 'fresh install fails codesign --verify'
start_app auto
sleep 25
app_running || fail 'app exited within 25 s after launch'
[ "$(bundle_version)" = "$base_version" ] || fail 'app changed itself without a newer release'
stop_app
echo '::endgroup::'

echo '::group::2. in-app update'
publish "$next_version"
start_app auto
deadline=$((SECONDS + 300))
until [ "$(bundle_version)" = "$next_version" ] && app_running; do
    [ -f "$staging/$next_version/failed" ] && fail "update script reported failure: $(cat "$staging/$next_version/failed")"
    (( SECONDS > deadline )) && fail "not updated to $next_version within 300 s"
    sleep 2
done
echo "updated to $next_version and reopened"
sleep 5
codesign --verify --deep --strict "$bundle" || fail 'updated bundle fails codesign --verify'
[ -e "$bundle.old" ] && fail "old bundle left at $bundle.old"
[ -e "$bundle.new" ] && fail "new bundle left at $bundle.new"
save_logs
stop_app
echo '::endgroup::'

echo 'update smoke passed'
