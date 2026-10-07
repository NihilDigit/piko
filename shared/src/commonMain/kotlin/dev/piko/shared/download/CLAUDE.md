# 下载

调度在 `PikoDownloadCoordinator`，任务表是 `DownloadTask` 的列表，经 `PikoUserPreferences.saveDownloadTasks` 持久化。
`DownloadTask` 随 1.0.0 发版，新增字段一律带默认值，旧任务表照常读回。

## 整文件下载与暂存

整文件先写进共享的稀疏暂存（`media/cache/PikoFileCachePool`），播放、预取与下载共用同一份块；写满后复制到下载目录，
经 `PikoDownloadStorage.commit` 交付。暂存的位置由内容身份推出（账号、gcid、长度、原画或转码档的 media ID），
任务表只记 `sparseCache` 一个布尔值。启动时 `restoreTask` 把未完成任务的暂存登记回池里，`prune` 删掉没人登记的。

## 画质

转码档（1080P、720P、480P）只有 MPEG-TS（HEVC 与 AAC），大小不在详情里，要探测（`PikoMediaRepository.downloadVariant`、
`downloadQualities`，各发一次 1 字节的 Range 请求）。这一字节也要真读出来：有的档服务端回 206、Content-Range 写着全长，
正文却是空的（2026-10-07 实测），读不出的档在对话框与片段面板里停用，按上限挑时跳过，用户选定的那一档读不出则以
`UnreadableTranscodeException`（「PikPak 的转码文件暂不可读」）失败，档已从详情里消失的以 `TRANSCODE_GONE_MESSAGE` 失败，都点明是服务端。
探测按账号与文件留 10 分钟，对话框、片段面板与下载开始时共用；在菜单项上按下或悬停时就开始（`prefetchDownloadQualities`）。
默认下载画质是每台设备各自的偏好 `downloadMaxHeightFlow`（可空，null 是未设置、每次询问），不同步，档位与播放画质相同。
问不问、怎么问在界面的 `DownloadLauncher`，见 `ui/.../screens/drive/CLAUDE.md`；调度这边没给上限时取它，未设置按原画。
这一项与它取代的「下载画质」「下载前选择画质」都只在 1.1.0 之后的开发期存在过，没有迁移；存储键换了名字
（`download_default_max_height`、`download.defaultMaxHeight`），开发期存下的旧值按旧语义只是默认选中，不沿用。

按上限挑档的规则只有一处，纯函数 `media/DownloadQualityOrder.kt` 的 `downloadQualityOrder`（测试 `DownloadQualityOrderTest`），
下载、片段、对话框的默认选中都用它：不高于上限的最高一档；一档都没有时取最低的一档，不退回原画（选低档是为了省流量与空间，
剩下的里最低的离所选最近，原画最大）；上限 0 即原画。原画与转码一律按画面高度比，档名比不出高低；原画高度未知时当作最高，
同高时原画在前。播放画质的 `transcodeNameAtMost` 是另一条规则（挑不到放原画），没有并过来。

- 普通下载、批量与文件夹下载：视频任务入队时只记上限（`qualityCap`，几项一起时是对话框里选的级别，不问时是设置的），
  开始下载时才查详情、挑档。挑到转码档就把文件名改成「名字 [720P].mp4」，挑到原画照旧。入队时不查：文件夹一批上千个文件，
  逐个查详情太慢。文件夹的上限随列出的工作记着（`ListingWork.maxHeight`），重新列出照用；超出今日额度的确认仍按原画大小估算。
- 单个视频：对话框列出各档与大小，选定的档直接写进任务（`quality`、`mediaId`、`totalBytes`）。
  这一档开始下载时已经没有了就失败，不悄悄换成原画。
- 转码档先完整下到暂存（按 media ID 与原画分开），进度按字节；下完在本机转封装成 MP4（`PikoSegmentDownloader.remux`，
  流复制，不转码），任务显示「转换中」，进度在 `progressFraction`。完成后 `totalBytes` 换成 MP4 的长度，暂存放手删除。
  转封装失败或中断时转码流仍在暂存里，重试只重做转封装。
- 归档条目与解析内容（借出的对象）只下原画。

## 片段

「下载指定段落」截一段存成 MP4（`PikoSegmentDownloader.extract`）。面板里可选从哪一档截，默认按下载画质；档位还没列出来就确认的，
入队时记上限，开始截取时再挑（与整文件同一组字段）。源经 `PikoMediaRepository.openRandomAccess` 按偏移读所选那一档
（原画或某个 media ID 的 TS），走 SDK 的 handle 与那一档的暂存，不用入队时存下的直链（绕过连接预算，CDN 回 503，且会过期）。
读过的块记在片段任务名下（`sparseCache`），暂停、失败、退出后再来不必重下；完成或取消时放手。片段的暂存按它读的那一档认：
`fullFileSize` 对转码档是转码流的长度，开始截取打开来源时写入。进度按已复制到的时间相对片段长度。
起点退到之前最近的视频关键帧，终点照 ffmpeg 的流复制按解码时间截。TS 没有索引：桌面端 FFmpeg 按时间戳二分定位，
Android 的 MediaExtractor 能否不从头读到起点，以真机实测为准（见下）。

平台实现：Android 是 MediaExtractor 与 MediaMuxer（`VideoSegmentExtractor`），桌面端经 FFM 调 mpv 运行库里的 FFmpeg
（`desktopApp/.../media/FfmpegRemuxer`，见 `desktopApp/CLAUDE.md`）。两者都写目标旁的 `.part`，完成后替换。
