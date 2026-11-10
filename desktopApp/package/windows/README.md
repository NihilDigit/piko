# Windows 打包资源

- `icon.ico` 由仓库根目录的 `docs/icon.svg`（蓝底白猫品牌图标）渲染得到，供 `desktopApp/build.gradle.kts` 的 `nativeDistributions.windows.iconFile` 使用：`createDistributable` 产出的 `Piko.exe`、MSI 安装的快捷方式与“添加或删除程序”条目都会带上它。
- `torrent.ico` 由 `docs/torrent-icon.svg`（白底蓝框加 `BT` 的种子文档图标）渲染得到，随 `bundledAppResources` 打入 `app/resources/torrent.ico`，供 `LinkRegistration` 注册 `Piko.Torrent\DefaultIcon` 使用（避免 `.torrent` 文件直接显示应用品牌图标）。

改了 SVG 后重跑（需 ImageMagick，`winget install ImageMagick.ImageMagick`）：

```powershell
magick -background none -density 1536 docs/icon.svg `
  -define icon:auto-resize=256,128,64,48,40,32,24,20,16 `
  desktopApp/package/windows/icon.ico

magick -background none -density 1536 docs/icon.svg `
  -resize 256x256 PNG32:desktopApp/src/desktopMain/resources/app-icon.png

magick -background none -density 1536 docs/torrent-icon.svg `
  -define icon:auto-resize=256,128,64,48,40,32,24,20,16 `
  desktopApp/package/windows/torrent.ico
```

20、24、40 是 125%、150% 缩放下小图标与开始菜单用的尺寸，缺了由系统从邻近尺寸缩放，边缘发虚。

注意 `-background none` 与 `-density 1536` 必须放在输入文件前面：省略 `-density` 时 ImageMagick 会先按 `viewBox="0 0 48 48"` 栅格化成 `48×48` 位图再放大到 `256×256` 导致发糊；省略前置 `-background none` 则圆角外的透明区会被铺成白色。
窗口标题栏/任务栏图标另见 `desktopApp/src/desktopMain/resources/app-icon.png`（同源 256px PNG）。
