#!/usr/bin/env bash
# 应用内差分更新的附件：新版本 M.m.p 为已公开的 M.(m-1).x 与 M.m.x 各出一份
# piko-windows-<架构>-<新版本>-from-<旧版本>.zip，里面是补丁包每个文件以旧版同名文件为前缀字典的
# zstd 差分（--patch-from），客户端的还原见 UpdateManifest.kt 的 applyDelta。
#
# 旧版清单里没有 zstd 的 DLL，说明它的客户端还不会用差分，跳过。跨度更大的升级走完整的 app.zip。
#
# 用法：delta-updates.sh <新版本> <dist 目录>。dist 里要已有两个架构的 -app.zip；需要 GH_TOKEN。
set -euo pipefail

version=$1
dist=$(cd "$2" && pwd)
work="${RUNNER_TEMP:-/tmp}/piko-delta"
rm -rf "$work"
mkdir -p "$work"

IFS=. read -r major minor _ <<< "$version"
bases=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases" \
    --jq '.[] | select(.draft | not) | select(.prerelease | not) | .tag_name | ltrimstr("v")' |
    awk -F. -v M="$major" -v m="$minor" -v self="$version" '$0 != self && $1 == M && ($2 == m || $2 == m - 1)')

for arch in x64 arm64; do
    mkdir -p "$work/new-$arch"
    unzip -q "$dist/piko-windows-$arch-$version-app.zip" -d "$work/new-$arch"
done

delta() {
    local arch=$1 base=$2
    local prefix="piko-windows-$arch"
    local dir="$work/$arch-$base"
    local new="$work/new-$arch"
    local out="$dist/$prefix-$version-from-$base.zip"
    mkdir -p "$dir/old" "$dir/out"
    if ! gh release download "v$base" --repo "$GITHUB_REPOSITORY" --dir "$dir" \
        -p "$prefix-$base-files.json" -p "$prefix-$base-app.zip"; then
        echo "v$base 没有 $arch 的补丁包，跳过"
        return 0
    fi
    if ! grep -q '"app/resources/zstd/' "$dir/$prefix-$base-files.json"; then
        echo "v$base 的客户端不会用差分，跳过"
        return 0
    fi
    unzip -q "$dir/$prefix-$base-app.zip" -d "$dir/old"
    (cd "$new" && find . -type f -printf '%P\n') | while read -r file; do
        mkdir -p "$(dirname "$dir/out/$file")"
        # 窗口固定 2^27：zstd-jni 解压时不能调大窗口上限，默认就是 2^27。
        # 文件超过 128 MB 时 CLI 会自行加大窗口，客户端解不开，退回完整补丁包
        if [ -f "$dir/old/$file" ]; then
            zstd -19 --long=27 -q --patch-from="$dir/old/$file" "$new/$file" -o "$dir/out/$file.zst"
        else
            zstd -19 -q "$new/$file" -o "$dir/out/$file.zst"
        fi
    done
    # 已是 zstd，不再压缩；-D 不写目录条目
    (cd "$dir/out" && zip -q -r -D -0 "$out" .)
    echo "v$base -> v$version ($arch): $(stat -c %s "$out") 字节"
}
export -f delta
export version dist work

# 每份差分里 AOT 缓存那一个文件就要压约一分钟，按核数并行
for base in $bases; do
    for arch in x64 arm64; do
        echo "$arch $base"
    done
done | xargs -r -P "$(nproc)" -L 1 bash -c 'delta "$0" "$1"'
