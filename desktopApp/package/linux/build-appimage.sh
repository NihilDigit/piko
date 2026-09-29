#!/bin/bash
# 把 jpackage 的 app-image 打成 Linux 的发布产物，由 desktopApp 的 packageReleaseAppImage 调用，CI 与本机同一个脚本：
#   <前缀>.tar.gz            app-image 原样打包，日后 Flathub 的 manifest 取它重新打包
#   <前缀>.AppImage          内嵌更新信息（gh-releases-zsync），AppImageUpdate 一类工具也认
#   <前缀>.AppImage.zsync    zsync 控制文件，应用内更新据此只下变了的块（desktopApp 的 Zsync.kt）
#
# 用法：build-appimage.sh <app-image 目录> <包名> <版本> <应用 ID> <产物名前缀> <输出目录> <工具缓存目录>
set -euo pipefail
app="$1"; package="$2"; version="$3"; app_id="$4"; prefix="$5"; out="$6"; tools="$7"
here="$(cd "$(dirname "$0")" && pwd)"
module="$(cd "$here/../.." && pwd)"
repo="$(cd "$module/.." && pwd)"

# 按版本与摘要钉死，不用 continuous：那个 tag 下的文件会被悄悄换掉，同一份源码两次构建出的包不同
APPIMAGETOOL_URL=https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage
APPIMAGETOOL_SHA256=ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0
# AppImage 头部的运行时。appimagetool 默认下载 continuous 的那份，这里另给一份钉死的
RUNTIME_URL=https://github.com/AppImage/type2-runtime/releases/download/20251108/runtime-x86_64
RUNTIME_SHA256=2fca8b443c92510f1483a883f60061ad09b46b978b2631c807cd873a47ec260d
# gh-releases-zsync：AppImageUpdate 这类外部工具按它到 GitHub 找最新版的 .zsync。应用内更新不读它
UPDATE_INFO="gh-releases-zsync|NihilDigit|piko|latest|piko-linux-x64-*.AppImage.zsync"

fetch() {
    local url="$1" sha="$2" dest="$3"
    if [ -f "$dest" ] && echo "$sha  $dest" | sha256sum -c --status; then return 0; fi
    mkdir -p "$(dirname "$dest")"
    curl -fsSL --retry 3 -o "$dest.part" "$url"
    echo "$sha  $dest.part" | sha256sum -c --status || { echo "摘要不符：$url" >&2; rm -f "$dest.part"; exit 1; }
    mv "$dest.part" "$dest"
    chmod +x "$dest"
}

for tool in zsyncmake tar; do
    command -v "$tool" > /dev/null || { echo "缺少 $tool（Debian/Ubuntu 上装 zsync）" >&2; exit 1; }
done
[ -x "$app/bin/$package" ] || { echo "不是 jpackage 的 app-image：$app" >&2; exit 1; }

fetch "$APPIMAGETOOL_URL" "$APPIMAGETOOL_SHA256" "$tools/appimagetool-x86_64.AppImage"
fetch "$RUNTIME_URL" "$RUNTIME_SHA256" "$tools/runtime-x86_64"

rm -rf "$out"
mkdir -p "$out"

# tar 保留文件的修改时间：AOT 缓存按 jar 的修改时间校验，改了缓存即作废。属主归零，不带构建机的用户名
tar -C "$(dirname "$app")" --sort=name --owner=0 --group=0 --numeric-owner -czf "$out/$prefix.tar.gz" "$(basename "$app")"

appdir="$out/AppDir"
mkdir -p "$appdir/usr"
cp -a "$app/." "$appdir/usr/"

# 启动器按自己的真实路径找 lib/app，AppRun 只转一手。参数原样传：magnet 链接与带空格的种子路径都在里面
cat > "$appdir/AppRun" <<EOF
#!/bin/sh
here="\$(dirname "\$(readlink -f "\$0")")"
exec "\$here/usr/bin/$package" "\$@"
EOF
chmod +x "$appdir/AppRun"

sed "s|@EXEC@|$package|" "$here/$app_id.desktop" > "$appdir/$app_id.desktop"
install -Dm644 "$appdir/$app_id.desktop" "$appdir/usr/share/applications/$app_id.desktop"
install -Dm644 "$module/src/desktopMain/resources/app-icon.png" "$appdir/usr/share/icons/hicolor/256x256/apps/$app_id.png"
install -Dm644 "$repo/docs/icon.png" "$appdir/usr/share/icons/hicolor/512x512/apps/$app_id.png"
cp "$module/src/desktopMain/resources/app-icon.png" "$appdir/$app_id.png"
ln -s "$app_id.png" "$appdir/.DirIcon"
mkdir -p "$appdir/usr/share/metainfo"
sed -e "s|@EXEC@|$package|" -e "s|@VERSION@|$version|" -e "s|@DATE@|$(date -u +%Y-%m-%d)|" \
    "$here/$app_id.metainfo.xml" > "$appdir/usr/share/metainfo/$app_id.metainfo.xml"

if command -v desktop-file-validate > /dev/null; then desktop-file-validate "$appdir/$app_id.desktop"; fi
# 截图是 GitHub 上的地址，--no-net 不去取；Flathub 审核时再联网校验
if command -v appstreamcli > /dev/null; then appstreamcli validate --no-net --explain "$appdir/usr/share/metainfo/$app_id.metainfo.xml"; fi

# appimagetool 自己也是 AppImage。APPIMAGE_EXTRACT_AND_RUN 让它解开了跑，不依赖构建机上的 FUSE（容器里常没有）。
# 不设 SOURCE_DATE_EPOCH：mksquashfs 见到它会把所有文件的修改时间改成同一个值，AOT 缓存随之作废
APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 VERSION="$version" "$tools/appimagetool-x86_64.AppImage" \
    --no-appstream \
    --runtime-file "$tools/runtime-x86_64" \
    --updateinformation "$UPDATE_INFO" \
    "$appdir" "$out/$prefix.AppImage"
rm -f "$out"/*.zsync

# -u 写相对地址：控制文件与 AppImage 在同一个 Release 下，经镜像取时也按镜像上的相对位置找
(cd "$out" && zsyncmake -u "$prefix.AppImage" -o "$prefix.AppImage.zsync" "$prefix.AppImage")
rm -rf "$appdir"
ls -l "$out"
