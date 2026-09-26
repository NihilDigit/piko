# macOS 打包资源

`icon.icns` 由仓库根目录的 `docs/icon.svg` 渲染得到，供 `desktopApp/build.gradle.kts` 的
`nativeDistributions.macOS.iconFile` 使用。

macOS 的图标按系统网格在 1024 画布里只占 824，四周留边，与 Dock 里其他图标同大；直接铺满会显得大一圈。
ImageMagick 读得了 ICNS 却写不出，这里把各尺寸的 PNG 按 ICNS 的结构拼起来：文件头 `icns` 加总长，
其后每项是四字节类型、含头部的长度与 PNG 数据，长度都是大端。改了 SVG 后在 Git Bash 里重跑：

```bash
out=$(mktemp -d)
be32() { printf "\\x$(printf %02x $(( ($1>>24)&255 )))\\x$(printf %02x $(( ($1>>16)&255 )))\\x$(printf %02x $(( ($1>>8)&255 )))\\x$(printf %02x $(( $1&255 )))"; }
: > "$out/body"
for e in ic11:32 ic12:64 ic07:128 ic13:256 ic08:256 ic14:512 ic09:512 ic10:1024; do
  t=${e%:*}; px=${e#*:}; art=$(( px * 824 / 1024 ))
  magick -background none -density 400 docs/icon.svg -resize ${art}x${art} -gravity center \
    -extent ${px}x${px} "PNG32:$(cygpath -m "$out/$t.png")"
  { printf %s $t; be32 $(( $(stat -c %s "$out/$t.png") + 8 )); cat "$out/$t.png"; } >> "$out/body"
done
{ printf icns; be32 $(( $(stat -c %s "$out/body") + 8 )); cat "$out/body"; } > desktopApp/package/macos/icon.icns
```
