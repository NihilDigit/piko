"""假的 GitHub releases/latest，供更新冒烟在本机或 CI 上对着它走完整条应用内更新。

目录结构：<root>/<版本>/<附件>，<root>/latest.txt 写要公布的版本，改它即换「最新版」，不必重启服务。
  GET /latest               与 GitHub 同形的 Release JSON，附件带 digest（应用据此校验下载）
  GET /assets/<版本>/<名字> 附件本身，认单段的 Range（<root>/no-range 在时不认）
每个请求记进 <root>/requests.log，失败时对照应用到底下了什么；每次送出的附件字节数记进 <root>/bytes.log。

用法：python fake_release.py <root> [端口]
"""
import hashlib
import http.server
import json
import os
import sys
import time

ROOT = os.path.abspath(sys.argv[1])
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
_digests = {}


def sha256(path):
    key = (path, os.path.getmtime(path), os.path.getsize(path))
    if key not in _digests:
        h = hashlib.sha256()
        with open(path, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                h.update(chunk)
        _digests[key] = h.hexdigest()
    return _digests[key]


def latest_version():
    with open(os.path.join(ROOT, "latest.txt"), encoding="utf-8") as f:
        return f.read().strip()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        with open(os.path.join(ROOT, "requests.log"), "a", encoding="utf-8") as f:
            f.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def do_GET(self):
        if self.path == "/latest":
            self.send_release(latest_version())
        elif self.path.startswith("/assets/"):
            _, _, version, name = self.path.split("/", 3)
            self.send_asset(os.path.join(ROOT, version, name))
        else:
            self.send_error(404)

    def send_release(self, version):
        folder = os.path.join(ROOT, version)
        assets = [
            {
                "name": name,
                "size": os.path.getsize(os.path.join(folder, name)),
                "browser_download_url": "http://127.0.0.1:%d/assets/%s/%s" % (PORT, version, name),
                "digest": "sha256:" + sha256(os.path.join(folder, name)),
            }
            for name in sorted(os.listdir(folder))
        ]
        # 正文带「## 下载」，与真实 Release 一样由应用截掉，顺带走一遍 updateNotesOf
        body = json.dumps({
            "tag_name": "v" + version,
            "name": "v" + version,
            "body": "Smoke %s\n\n## 修复\n\n- smoke\n\n## 下载\n\nassets" % version,
            "html_url": "http://127.0.0.1:%d/page/%s" % (PORT, version),
            "draft": False,
            "prerelease": False,
            "assets": assets,
        }).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_asset(self, path):
        if not os.path.isfile(path):
            self.send_error(404)
            return
        size = os.path.getsize(path)
        start, end = 0, size - 1
        # Linux 的 zsync 差分更新按 Range 取单段，与 GitHub 的附件地址一样回 206；每段记进 bytes.log，
        # 冒烟据此算差分实际下了多少字节
        # <root>/no-range 在时照不认 Range 的镜像回整个文件（200），冒烟据此验应用退回整个下载
        ranged = "" if os.path.exists(os.path.join(ROOT, "no-range")) else self.headers.get("Range", "")
        if ranged.startswith("bytes="):
            first, _, last = ranged[len("bytes="):].partition("-")
            start, end = int(first), min(int(last), size - 1) if last else size - 1
            if start > end:
                self.send_error(416)
                return
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        else:
            self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        # 记实际送出的字节，不记应答声明的长度：应用见到 200 就当场断开（取 image.zip 的目录时），
        # 按声明记的话这次也算成整个文件，不认 Range 的场景就像下了两遍
        sent = 0
        try:
            with open(path, "rb") as f:
                f.seek(start)
                remaining = end - start + 1
                while remaining > 0:
                    chunk = f.read(min(1 << 16, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    sent += len(chunk)
                    remaining -= len(chunk)
        finally:
            with open(os.path.join(ROOT, "bytes.log"), "a", encoding="utf-8") as log:
                log.write("%s %d\n" % (os.path.basename(path), sent))


# Windows 上 SO_REUSEADDR 让第二个进程也能绑同一个端口，请求被分给上一次没关掉的那个，
# 表现为连上了却什么也取不到。关掉它，端口被占时当场报错
http.server.ThreadingHTTPServer.allow_reuse_address = os.name != "nt"
http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
