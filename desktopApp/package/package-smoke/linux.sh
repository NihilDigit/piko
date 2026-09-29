#!/bin/bash
# Linux 上应用内更新的端到端冒烟：真启动 AppImage、真更新，断言落到磁盘上的结果。
#
# 输入是同一份源码打出的两个版本（Base 与 Next），各放一个目录，名字与 Release 附件相同：
# piko-linux-<架构>-<版本>.AppImage 与 .AppImage.zsync。AppImage 拷成 ~/Applications/Piko.AppImage，
# 与用户下载后放的位置一样；它不改系统里的任何东西，不需要包名另起一个产品。
#
# 应用经 JAVA_TOOL_OPTIONS 指向本脚本起的假 Release（fake_release.py），并带 piko.update.auto：开屏查到新版即
# 按 zsync 拼出新的 AppImage、换上、退出，再由它起的 sh 重新打开。重新打开的那一次不带这些参数，查的是真的
# GitHub，版本比它旧，不会再动。
#
# 场景：
#   1. 全新启动后存活，没有新版时不动
#   1b. 设为 magnet 与 .torrent 的默认打开方式（.desktop 加 xdg-mime）：xdg-open 真把磁力链接与种子交给开着的 Piko，
#       再取消关联，mimeapps.list 里不再指向 Piko
#   2. 应用内更新：AppImage 换成 Next（逐字节相同），新版本被重新打开，旁边不留暂存文件，
#      用户自己放在旁边的同名备份原样留着；差分只下了整包的一部分
#
# 没有显示器的机器上整个脚本套一层 xvfb-run。没有 /dev/fuse 时 AppImage 解开了跑（APPIMAGE_EXTRACT_AND_RUN），
# APPIMAGE 照样指向文件本身，更新走的是同一条路。
#
# 用法：linux.sh <Base 目录> <Next 目录> <Base 版本> <Next 版本> <工作目录> [架构]
set -euo pipefail
base_dir="$1"; next_dir="$2"; base_version="$3"; next_version="$4"; work="$5"; arch="${6:-x64}"
port=8765
here="$(cd "$(dirname "$0")" && pwd)"
apps="$HOME/Applications"
appimage="$apps/Piko.AppImage"
releases="$work/releases"
logs="$work/logs"
staging="${TMPDIR:-/tmp}/piko-update"
# 本机 WSL 里 xdg-open 会认出 WSL、转给 Windows，可以用 SMOKE_OPEN='gio open' 换掉
open_cmd="${SMOKE_OPEN:-xdg-open}"
mkdir -p "$releases" "$logs" "$apps"
[ -e /dev/fuse ] || export APPIMAGE_EXTRACT_AND_RUN=1

fail() { echo "SMOKE FAILED: $*" >&2; save_logs; exit 1; }
save_logs() {
    cp -R "$staging" "$logs/staging" 2>/dev/null || true
    cp -R "$HOME/.piko/logs" "$logs/app-logs" 2>/dev/null || true
    cp "$releases/requests.log" "$releases/bytes.log" "$logs/" 2>/dev/null || true
}
asset() { echo "$1/piko-linux-$arch-$2.AppImage"; }
sha() { sha256sum "$1" | cut -d' ' -f1; }
# 跑着的是 JVM：AppImage 的运行时挂载之后 exec 进 AppRun，AppRun 再 exec 启动器，命令行是挂载目录里的 usr/bin/Piko
app_pids() { pgrep -f '/usr/bin/Piko( |$)' || true; }
app_running() { [ -n "$(app_pids)" ]; }
# 只停 JVM，不碰运行时：先杀运行时会把挂载从正在跑的 JVM 底下拆掉，它读类文件时收到 SIGBUS
stop_app() {
    local pids; pids="$(app_pids)"
    [ -n "$pids" ] && kill $pids 2>/dev/null
    for _ in $(seq 50); do app_running || return 0; sleep 0.2; done
    pids="$(app_pids)"; [ -n "$pids" ] && kill -9 $pids 2>/dev/null
    return 0
}
publish() { echo "$1" > "$releases/latest.txt"; }
start_app() {
    local options="-Dpiko.update.api=http://127.0.0.1:$port/latest"
    [ "${1:-}" = auto ] && options="$options -Dpiko.update.auto=true"
    JAVA_TOOL_OPTIONS="$options" nohup "$appimage" > "$logs/app-$(date +%s).out" 2>&1 &
}
# AppImage 里跑一段自检（SelfTest.kt），前台跑完，结果写进文件
self_test() {
    local out="$logs/selftest-$1.txt"
    rm -f "$out"
    JAVA_TOOL_OPTIONS="-Dpiko.selftest=$1 -Dpiko.selftest.out=$out" "$appimage" > "$logs/selftest-$1.out" 2>&1 || true
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
started_as() { cat "$HOME/.piko/logs/"* 2>/dev/null | grep -c "启动 $1，" || true; }

for pair in "$base_dir:$base_version" "$next_dir:$next_version"; do
    dir="${pair%%:*}"; version="${pair##*:}"
    mkdir -p "$releases/$version"
    cp "$(asset "$dir" "$version")" "$(asset "$dir" "$version").zsync" "$releases/$version/" || fail "missing AppImage or zsync for $version"
done
next_sha="$(sha "$(asset "$next_dir" "$next_version")")"
next_size="$(stat -c %s "$(asset "$next_dir" "$next_version")")"

python3 "$here/fake_release.py" "$releases" "$port" > "$logs/server.out" 2>&1 &
server=$!
trap 'stop_app; kill $server 2>/dev/null || true' EXIT
for _ in $(seq 50); do curl -fs "http://127.0.0.1:$port/latest" > /dev/null 2>&1 && break; sleep 0.2; done

echo '::group::1. fresh start'
publish "$base_version"
stop_app
rm -rf "$staging" "$HOME/.piko/logs"
cp "$(asset "$base_dir" "$base_version")" "$appimage"
chmod +x "$appimage"
start_app auto
sleep 25
app_running || fail 'app exited within 25 s after launch'
[ "$(started_as "$base_version")" -gt 0 ] || fail "the app did not log starting $base_version"
[ "$(sha "$appimage")" = "$(sha "$(asset "$base_dir" "$base_version")")" ] || fail 'app changed itself without a newer release'
stop_app
echo '::endgroup::'

echo '::group::1b. magnet and torrent association'
self_test link-register
start_app
sleep 15
before="$(incoming_links magnet)"
$open_cmd 'magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=smoke'
wait_incoming magnet "$before" 'the magnet scheme'
# 最小的种子：info 里只有一个 1 字节的文件，Piko 在本地算 infohash 换成磁力链接。目录名带空格，验 Exec 的引号
mkdir -p "$work/with space"
torrent="$work/with space/smoke.torrent"
{ printf 'd4:infod6:lengthi1e4:name9:smoke.bin12:piece lengthi16384e6:pieces20:'; head -c 20 /dev/zero; printf 'ee'; } > "$torrent"
before="$(incoming_links torrent)"
$open_cmd "$torrent"
wait_incoming torrent "$before" 'a .torrent file'
stop_app
self_test link-unregister
echo '::endgroup::'

echo '::group::2. in-app update'
publish "$next_version"
: > "$releases/bytes.log"
# 用户自己留的同名备份：更新不能动它
echo backup > "$apps/Piko.AppImage.bak"
start_app auto
deadline=$((SECONDS + 300))
until [ "$(sha "$appimage")" = "$next_sha" ] && [ "$(started_as "$next_version")" -gt 0 ] && app_running; do
    (( SECONDS > deadline )) && fail "not updated to $next_version and restarted within 300 s"
    sleep 2
done
echo "updated to $next_version and reopened"
[ -x "$appimage" ] || fail 'the updated AppImage is not executable'
leftover="$(find "$apps" -maxdepth 1 -name '.Piko.AppImage.piko-update*' | head -n 1)"
[ -n "$leftover" ] && fail "staged AppImage left at $leftover"
[ "$(cat "$apps/Piko.AppImage.bak")" = backup ] || fail 'the user backup next to the AppImage was changed'
downloaded="$(awk -v name="piko-linux-$arch-$next_version.AppImage" '$1 == name { sum += $2 } END { print sum + 0 }' "$releases/bytes.log")"
echo "zsync fetched $downloaded of $next_size bytes"
[ "$downloaded" -gt 0 ] || fail 'the update did not download any part of the new AppImage'
[ "$downloaded" -lt "$next_size" ] || fail 'the update downloaded the whole AppImage instead of the zsync delta'
save_logs
stop_app
echo '::endgroup::'

echo 'package smoke passed'
