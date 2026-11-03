# 下载与片段截取

本机下载调度集中在 `PikoDownloadCoordinator`，持久化任务表为 `DownloadTask` 列表（经 `PikoUserPreferences.saveDownloadTasks` 读写，结构变更时以 `PERSIST_INTERVAL_MS` 节流合并落盘，避免千级小文件文件夹下载频繁重写整份 DataStore）。`DownloadTask` 序列化格式自 `v1.0.0` 起已正式发布，**所有新增字段必须提供默认值**，确保旧版持久化的任务表可无损反序列化恢复。

## 整文件下载与共享稀疏暂存池

- **播放与下载共享块缓存（`media/cache/PikoFileCachePool`）**：整文件下载并非直接顺序写入最终文件，而是先写入共享稀疏暂存池，与在线播放、信息流及片段截取共用同一份已下载数据块（暂停中的原画下载任务在播放同一文件时也会由播放流顺带补齐块）；全部字节落满后再复制至目标下载目录并调用 `PikoDownloadStorage.commit` 交付。
- **暂存寻址与启动恢复**：暂存文件位置由内容身份唯一推导（`账号 + GCID + 字节长度 + 原画或转码档 mediaId`），`DownloadTask` 仅记录布尔标志 `sparseCache`、转码档 `mediaId` 与流长度 `fullFileSize`。应用启动执行 `restore()` 时：上次进程退出时处于 `DOWNLOADING` 或 `PENDING` 的任务统一转为 `PAUSED` 并按暂存池实际落盘进度刷新 `downloadedBytes`，未完成任务通过 `fileCachePool.remember` 重新登记占用，随后 `fileCachePool.prune()` 自动清理无任务登记的孤立暂存文件；已完成任务（`COMPLETED`）按磁盘实际文件存在性与字节长度严格校验（片段任务校验文件存在且长度大于 `0`，防止早期版本 `totalBytes == 0` 将抽取失败残留的空文件误判为已完成），本机文件已被外部删除或长度不符的条目自动从任务表剔除。播放器开播前调用 `findCompletedLocalPath` 按磁盘实测长度核对已完成副本（Android SAF 目录返回的 `content:` URI 除外），直接走本地零流量播放。
- **多账号与限速**：全局下载速率受 `BandwidthLimiter` 统一约束，修改「蜗牛模式」限速即时作用于全部在途连接，无需重启任务。切换账号时自动整批暂停非当前账号的在途下载任务与未列完的文件夹准备任务（`FolderListing`），防止旧客户端实例关闭引发批量报错，切回原账号后可继续执行。

## 转码档探测、默认画质与匹配规则

- **1 字节真实读取探测（2026-10-07 实测）**：PikPak 的转码档（`1080P`、`720P`、`480P`）均为 MPEG-TS 封装（HEVC 视频 + AAC 音频），文件详情接口不返回转码流字节大小，必须由 `PikoMediaRepository.downloadVariant` / `downloadQualities` 发起 `1 字节` HTTP Range 请求探测。探测时**必须真实读出这 1 个响应字节**：实测部分异常转码档服务端虽返回 `HTTP 206` 且 `Content-Range` 标明完整总长，但响应正文实际为空。探测不可读的档位在画质对话框与片段面板中置灰停用，并在按上限自动挑档时跳过；若用户显式指定的档位不可读则以 `UnreadableTranscodeException`（「PikPak 的转码文件暂不可读」）报错，档位已从服务端详情消失则以 `TRANSCODE_GONE_MESSAGE`（「PikPak 已不提供所选画质」）报错，文案均明确点明服务端原因。探测结果按 `账号 + 文件` 缓存 `10 分钟`，仅在用户悬停或按下「下载指定画质…」「下载指定段落…」菜单项时通过 `prefetchDownloadQualities` 提前预热，普通下载菜单项不触发探测。
- **默认下载画质与入队策略**：默认下载画质为设备本地偏好 `downloadMaxHeightFlow`（`null` 或 `0` 均代表原画，不跨设备同步；沿用 `v1.2.0` 已发布的存储键 Android `download_default_max_height` 与桌面 `download.defaultMaxHeight`，早期开发期未正式作为设置发布的 `download_max_height` / `download.maxHeight` 不迁移）。普通「下载到本地」不弹窗询问、不预先递归扫描文件夹是否包含视频，直接入队（文件夹先生成可见的 `FolderListing` 准备任务，后台展开目录树后再批量排入任务）；仅用户主动点击「下载指定画质…」才弹出画质选择框（界面入口规则见 `ui/src/commonMain/kotlin/dev/piko/ui/screens/drive/AGENTS.md`）。归档虚拟条目、压缩包内借出文件与链接解析临时对象（`leasedSource = true`）仅支持下载原画。
- **单一挑档纯函数（`media/DownloadQualityOrder.kt`）**：按画质上限自动挑档统一调用 `downloadQualityOrder`（单测 `DownloadQualityOrderTest`），下载调度、片段截取与画质弹窗默认选中项共用同一规则：
  1. 上限为 `0` 时直接选原画；原画与转码档一律按实际视频像素高度比较（不按档位字符串比较；原画高度未知时视为最高，同高度时原画优先）。
  2. 选取像素高度**不高于所设上限的最高可用一档**；若所有可用档位均高于上限，则选取**可用档位中最低的一档，绝不回退至原画**（用户设置低画质上限旨在节省流量与磁盘空间，最低转码档最接近用户意图，而原画体积最大；注意此规则与在线播放 `transcodeNameAtMost` 找不到转码即回退原画的策略有意区分，不得混用）。
- **单视频指定档位、批量延迟挑档与本机转封装**：
  - 批量文件与文件夹入队时仅在任务上记录画质上限 `qualityCap`（文件夹上限同步记入 `ListingWork.maxHeight` 供重试复用，超出每日流量额度的确认框仍按原画总大小保守估算），待单个视频真正轮到开始下载时才查详情并探测挑档（避免千级文件入队时串行查详情造成长时间阻塞）；命中转码档时输出文件名自动改写为 `原主名 [720P].mp4`。
  - 单个视频通过「下载指定画质…」选定具体档位时，直接将 `quality`、`mediaId` 与 `totalBytes` 写入任务；开始下载时若该档位已失效则直接报错失败，不静默降级为原画。
  - 转码档先按其专属 `mediaId` 完整下载 MPEG-TS 流至稀疏暂存池（字节进度实时更新），下载完成后调用 `PikoSegmentDownloader.remux` 在本机无损流复制转封装为 `moov` 前置的 MP4（期间任务状态显示「转换中」，转换进度写入 `progressFraction`）；转封装成功后将任务 `totalBytes` 更新为最终 MP4 字节长度并释放 TS 暂存；若转封装中途失败或进程退出，完整 TS 流仍保留在暂存池内，用户点击重试或重启恢复后仅需重新执行本机转封装，无需重新消耗网络流量下载。

## 视频片段截取

「下载指定段落…」调用 `PikoSegmentDownloader.extract` 将所选时间区间无损流复制保存为独立 MP4：

- **随机访问流与块复用**：片段面板支持选择截取画质（默认遵循默认下载画质；若档位探测尚未返回时用户即点击确认，则先记录上限并在开始截取时挑档）。读取源统一通过 `PikoMediaRepository.openRandomAccess` 对接 SDK 文件句柄与对应档位（原画或指定 `mediaId` 转码流，开始截取打开来源时将 `fullFileSize` 设为该档流的真实字节长度）的稀疏暂存池按字节偏移随机读取，**严禁直接使用入队时保存的裸直链**（裸直链会绕过客户端连接预算引发 CDN `503`，且直链过期后无法续传）。已读取的数据块挂在片段任务名下（`sparseCache = true`），暂停、失败或重启应用后重试无需重下已读块；任务完成或取消时释放暂存引用。
- **关键帧对齐与平台实现**：截取起点自动回退至目标时刻之前最近的视频关键帧，终点按解码时间戳精确截断，任务进度按已复制媒体时长占目标片段总时长的比例回报。MPEG-TS 流无头部索引表，桌面端通过 FFM 直调随包 FFmpeg 8（`desktopApp/.../media/FfmpegRemuxer`）按时间戳二分定位；Android 端基于系统 `MediaExtractor` 与 `MediaMuxer`（`VideoSegmentExtractor`）实现。双端在转封装与截取期间均先写入目标文件同目录旁的 `.part` 临时文件，完整落盘后再原子替换为最终文件名。
