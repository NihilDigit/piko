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
