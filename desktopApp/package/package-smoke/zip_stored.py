"""顶替 Info-ZIP 的 zip，只认 delta-updates.sh 的那一种用法：zip -q -r -D -0 <输出> .

Windows 的 runner 与 Git Bash 都没有 zip，test.yml 在 Windows 上出差分包时把它包成名为 zip 的命令放进 PATH。
与原命令相同：只存不压，不写目录条目，条目名相对当前目录、以 / 分隔。
"""
import os
import sys
import zipfile

args = [a for a in sys.argv[1:] if not a.startswith("-")]
if len(args) != 2 or args[1] != ".":
    sys.exit("zip_stored.py: unsupported arguments %r" % sys.argv[1:])
output = args[0]
with zipfile.ZipFile(output, "w", zipfile.ZIP_STORED) as archive:
    for folder, _, names in os.walk("."):
        for name in sorted(names):
            path = os.path.join(folder, name)
            archive.write(path, os.path.relpath(path, ".").replace(os.sep, "/"))
