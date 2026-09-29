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
#   1b. 设为 magnet 与 .torrent 的默认打开方式（NSWorkspace）：系统真把磁力链接交给 Piko（openURI，拉起），
#       再把种子交给开着的 Piko（openFiles）。macOS 不做取消关联，见 LinkAssociation.canUnregister
#   2. 应用内更新：Info.plist 的版本变成 Next，签名仍完好（codesign --verify），新版本被重新打开，
#      旁边不留临时的包，用户自己放在旁边的同名备份原样留着
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
# 装好的包里跑一段自检（SelfTest.kt），前台跑完，结果写进文件
self_test() {
    local out="$logs/selftest-$1.txt"
    rm -f "$out"
    JAVA_TOOL_OPTIONS="-Dpiko.selftest=$1 -Dpiko.selftest.out=$out" "$bundle/Contents/MacOS/$package" > "$logs/selftest-$1.out" 2>&1 || true
    echo "self test $1: $(cat "$out" 2>/dev/null || echo '(no output)')"
    grep -qx PASS "$out" 2>/dev/null || fail "self test $1 failed"
}
# 应用日志里「收到外部链接」的行数。Piko 只记类别与来路，不记链接本身（Main.kt 的 deliverIncoming）
incoming_links() { cat "$HOME/.piko/logs/"* 2>/dev/null | grep -c "IncomingLink.*$1" || true; }
wait_incoming() {
    local kind="$1" before="$2" what="$3"
    for _ in $(seq 60); do
        [ "$(incoming_links "$kind")" -gt "$before" ] && { echo "Piko received the $kind from $what"; return 0; }
        sleep 1
    done
    fail "Piko did not log receiving the $kind from $what within 60 s"
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
rm -rf "$bundle" "$bundle.old" "$staging"
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

echo '::group::1b. magnet and torrent association'
# 刚解出来的包先登记进 LaunchServices：只启动过一次时它未必已登记，设默认打开方式要它在册
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$bundle"
self_test link-register
before="$(incoming_links magnet)"
open 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=smoke'
wait_incoming magnet "$before" 'the magnet scheme'
# 最小的种子：info 里只有一个 1 字节的文件，Piko 在本地算 infohash 换成磁力链接
torrent="$work/smoke.torrent"
{ printf 'd4:infod6:lengthi1e4:name9:smoke.bin12:piece lengthi16384e6:pieces20:'; head -c 20 /dev/zero; printf 'ee'; } > "$torrent"
before="$(incoming_links torrent)"
open "$torrent"
wait_incoming torrent "$before" 'a .torrent file'
stop_app
echo '::endgroup::'

echo '::group::2. in-app update'
publish "$next_version"
# 用户自己留的同名备份：更新不能动它（旧脚本拿 .app.old 当临时名，会把它删掉）
mkdir -p "$apps/$package.app.old/Contents"
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
leftover="$(find "$apps" -maxdepth 1 -name "$package.app.piko-update-*" | head -n 1)"
[ -n "$leftover" ] && fail "temporary bundle left at $leftover"
[ -d "$apps/$package.app.old" ] || fail 'the user backup next to the app was removed'
save_logs
stop_app
echo '::endgroup::'

echo 'package smoke passed'
