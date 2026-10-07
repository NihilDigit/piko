#!/usr/bin/env bash
# 冒烟测试用的老格式样片。产物直接提交，CI 不现场生成：runner 上的 ffmpeg 版本与编码器
# 集合不受控，样片字节一变，时长断言就跟着漂。
#
# 各 4 秒、176x144，每条对应一类手机端 ExoPlayer 解不开或依赖机型硬解的真实文件。
# control-mpeg4-aac.mp4 是对照组：它都播不起来，说明坏的是播放链路本身，不是格式覆盖。
#
# 没有 WMV3/VC-1 样片：ffmpeg 只有解码器没有编码器，而现实中的 .wmv 大多是这种编码。
set -euo pipefail
cd "$(dirname "$0")"

video=(-f lavfi -i testsrc2=size=176x144:rate=25:duration=4)
audio=(-f lavfi -i sine=frequency=440:sample_rate=44100:duration=4)

ffmpeg -y -v error "${video[@]}" "${audio[@]}" -c:v wmv2 -b:v 150k -c:a wmav2 -b:a 64k -ac 2 wmv2-wmav2.wmv
# Xvid 风格：带 B 帧的 MPEG-4 ASP，多数机型的硬解只认 Simple Profile
ffmpeg -y -v error "${video[@]}" "${audio[@]}" -c:v mpeg4 -vtag XVID -bf 2 -b:v 150k -c:a libmp3lame -b:a 64k mpeg4asp-mp3.avi
# DivX 3：MS-MPEG4v3，Android 平台解码器不支持
ffmpeg -y -v error "${video[@]}" "${audio[@]}" -c:v msmpeg4 -vtag DIV3 -b:v 150k -c:a mp2 -b:a 64k msmpeg4v3-mp2.avi
# real_144 只接受 8 kHz 单声道
ffmpeg -y -v error "${video[@]}" -f lavfi -i sine=frequency=440:sample_rate=8000:duration=4 -c:v rv20 -b:v 150k -c:a real_144 -ac 1 -ar 8000 rv20-ra144.rm
ffmpeg -y -v error "${video[@]}" "${audio[@]}" -c:v mpeg4 -b:v 150k -c:a aac -b:a 64k -movflags +faststart control-mpeg4-aac.mp4

# 以下两条给桌面端的转封装用（DesktopPikoSegmentDownloaderTest），关键帧固定每 2 秒一个，便于核对片段起点。
# PikPak 转码档的样子：MPEG-TS 里的 HEVC（带 B 帧）与 ADTS 的 AAC，时间戳不从 0 起（mpegts 默认偏 1.4 秒）
long_video=(-f lavfi -i testsrc2=size=176x144:rate=25:duration=10)
long_audio=(-f lavfi -i sine=frequency=440:sample_rate=44100:duration=10)
ffmpeg -y -v error "${long_video[@]}" "${long_audio[@]}" -c:v libx265 -preset fast \
  -x265-params log-level=error:keyint=50:min-keyint=50:scenecut=0:bframes=3:repeat-headers=1 -b:v 80k \
  -c:a aac -b:a 48k -f mpegts transcode-hevc-aac.ts
# 原画常见的另一种容器：MKV 里的 H.264（带 B 帧）与 AAC，12 秒
long_video[3]=testsrc2=size=176x144:rate=25:duration=12
long_audio[3]=sine=frequency=440:sample_rate=44100:duration=12
ffmpeg -y -v error "${long_video[@]}" "${long_audio[@]}" -c:v libx264 -preset fast -g 50 -keyint_min 50 -sc_threshold 0 -bf 2 \
  -b:v 80k -c:a aac -b:a 48k keyframes-h264-aac.mkv
