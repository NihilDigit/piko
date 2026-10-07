#!/usr/bin/env bash
# 取仍在用的已发布版本的 Windows 文件清单，交给 packageReleaseUpdate（-PpikoUpdateBases）：与其中任一份不同的
# 运行时、mpv 等文件也标为补丁、装进 app.zip，见 desktopApp/build.gradle.kts 的 UpdateArtifactsTask。
# 这是 1.1.0 及更早的客户端唯一的更新路径（它们只认 files.json、app.zip 与 .msi），所以取 1.0.0 起全部已公开、
# 带清单的版本，不限主版本，换主版本时上一个主版本照样在内；0.x 不算。代价是某个文件在任一旧版之后变过，以后每个补丁包都带着它，
# 构建日志逐个列出这些文件。
#
# 用法：update-bases.sh <新版本> <架构> <输出目录>；需要 GH_TOKEN。
set -euo pipefail

version=$1
arch=$2
out=$3
mkdir -p "$out"

bases=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases" \
    --jq '.[] | select(.draft | not) | select(.prerelease | not) | .tag_name | ltrimstr("v")' |
    awk -F. -v self="$version" '$0 != self && $1 >= 1')

for base in $bases; do
    # 旧版可能没有这个架构的清单，跳过
    gh release download "v$base" --repo "$GITHUB_REPOSITORY" --dir "$out" -p "piko-windows-$arch-$base-files.json" ||
        echo "v$base 没有 $arch 的清单，跳过"
done
ls -l "$out"
