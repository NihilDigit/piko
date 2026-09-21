# Windows 打包资源

`icon.ico` 由仓库根目录的 `docs/icon.svg`（蓝底白猫品牌图标）渲染得到，
供 `desktopApp/build.gradle.kts` 的 `nativeDistributions.windows.iconFile` 使用：
`createDistributable` 产出的 `Piko.exe`、MSI 安装的快捷方式与"添加或删除程序"条目都会带上它。

改了 SVG 后重跑（需 ImageMagick，`winget install ImageMagick.ImageMagick`）：

```powershell
magick -background none docs/icon.svg `
  -define icon:auto-resize=256,128,64,48,32,16 `
  desktopApp/package/windows/icon.ico
```

注意 `-background none` 必须放在输入文件前面，否则圆角外的透明区会被铺成白色。
窗口标题栏/任务栏图标另见 `desktopApp/src/desktopMain/resources/app-icon.png`（同源 256px PNG）。
