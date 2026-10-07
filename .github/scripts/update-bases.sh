#!/usr/bin/env bash
# 取仍在用的已发布版本的 Windows 文件清单，交给 packageReleaseUpdate（-PpikoUpdateBases）：与其中任一份不同的
# 运行时、mpv 等文件也标为补丁、装进 app.zip，见 desktopApp/build.gradle.kts 的 UpdateArtifactsTask。
# 这是 1.1.0 及更早的客户端唯一的更新路径（它们只认 files.json、app.zip 与 .msi），所以取 1.0.0 起全部已公开、
# 带清单的版本，不限主版本，换主版本时上一个主版本照样在内；0.x 不算。代价是某个文件在任一旧版之后变过，以后每个补丁包都带着它，
# 构建日志逐个列出这些文件。
#
# 例外是 RETIRED 里的版本，它们不该走补丁。1.0.0 的更新脚本按前缀长度截相对路径，临时目录是 8.3 短路径时把补丁
# 写进错位的子目录、仍报成功，重启后还是旧版，下次开屏再提示，循环。不拿它作对照，补丁包就不为它带上它缺的文件，
# 它的 canPatch 必然失败：安装版退回 msiexec 整包重装，便携版只给下载页。它们的清单另放进 <退役目录>，
# 交给 -PpikoUpdateRetired，由 UpdateArtifactsTask 核对这一点在本版确实成立，不成立时构建失败。
#
# 用法：update-bases.sh <新版本> <架构> <对照目录> <退役目录>；需要 GH_TOKEN。
set -euo pipefail

version=$1
arch=$2
out=$3
retired_out=$4
mkdir -p "$out" "$retired_out"

RETIRED="1.0.0"

published=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases" \
    --jq '.[] | select(.draft | not) | select(.prerelease | not) | .tag_name | ltrimstr("v")' |
    awk -F. -v self="$version" '$0 != self && $1 >= 1')

for base in $published; do
    dir=$out
    for retired in $RETIRED; do
        [ "$base" != "$retired" ] || dir=$retired_out
    done
    # 旧版可能没有这个架构的清单，跳过
    gh release download "v$base" --repo "$GITHUB_REPOSITORY" --dir "$dir" -p "piko-windows-$arch-$base-files.json" ||
        echo "v$base 没有 $arch 的清单，跳过"
done
ls -l "$out" "$retired_out"
