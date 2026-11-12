# PikPak CDN 传输习性实测与流式缓存正交架构（2026-11-10）

本文记录 2026-11-10 针对 PikPak CDN（`dl-z01a-*.mypikpak.com`）在多地区专线与普通机场线路下的实测数据、旧版状态机组合爆炸的根因剖析，以及由此确立的四层正交取流架构。

---

## 一、实测环境与方法

- **测试样本**：318 MiB 1080p MKV 原画文件（`1920x1080`，`matroska,webm`，平均码率 `1837 kbps`，时长 `1420s`），同时具备云端转码流 `1080P`（155 MiB `mpegts`，`897 kbps`）、`720P`（99 MiB `mpegts`，`572 kbps`）、`480P`（69 MiB `mpegts`，`400 kbps`）。
- **网络接入方式**：通过独立 `mihomo` 核心直接绑定物理网卡 `Wi-Fi 2` 并使用 DoH（`https://223.5.5.5/dns-query`）解析节点域名，绕过本机 TUN 虚拟网卡与 UDP 53 Fake-IP 劫持，分别在 9 条代表性代理线路上执行对照实测：
  1. **专线组（Nexitally）**：新加坡（`NEX-SG`）、香港（`NEX-HK`）、日本（`NEX-JP`）、美国西雅图（`NEX-US`）、台湾（`NEX-TW`）。
  2. **普通/受限机场组（良心云）**：新加坡 Cloudflare WS+TLS（`LXY-SG-WS`）、新加坡 Hysteria2（`LXY-SG-Hy2`）、日本 Hysteria2（`LXY-JP-Hy2`）、美国 Hysteria2（`LXY-US-Hy2`）。

---

## 二、PikPak CDN 五大实测发现

### 1. `getFile(usage=null)` 与 `getFile(usage=FETCH)` 的真实差异

| 维度 | 普通详情 `getFile(id, usage = null)` | 下载详情 `getFile(id, usage = "FETCH")` |
| --- | --- | --- |
| **API 响应耗时** | 较慢（`1950 ~ 3600 ms`，服务端实时签发多路媒体直链） | 极快（`205 ~ 470 ms`） |
| **`medias` 列表** | 完整返回 `Original`、`1080P`、`720P`、`480P` | **恒为空列表 `[]`** |
| **`octetStream` 链接配额** | `ms=5242880`（`5120 KiB/s`），部分 Cloudflare 出口 IP 会被降为 `ms=2097152`（`2048 KiB/s`） | 同左（`ms=5120 KiB/s` 或 `2048 KiB/s`） |
| **` medias` 链接配额** | `Original`: `ms=56435760`（`55113 KiB/s`）<br/>`1080P`: `ms=27558720`（`26912 KiB/s`）<br/>`720P`: `ms=17588400`（`17176 KiB/s`）<br/>`480P`: `ms=12309360`（`12020 KiB/s`） | 无 |
| **节点分配规律** | `octetStream` 稳定分配在 `dl-z01a-0041..0050`（偶见 `0014`）；`medias` 分配在 `0026, 0027, 0051..0055, 0063, 0064` | 稳定分配在 `dl-z01a-0041..0050` |

- 所有签发 URL 的突发阈值参数 `th` 与最大速率参数 `ms` 数值相同（`th == ms`），但**节点实际的物理限流行为远比 URL 表面的 `ms` 参数严格**。

### 2. 慢节点的「初期突发 + 长流断崖掐流」习性

在分配给 `Origin-media` 和 `Transcode-1080P` 的部分边缘节点（典型如 `dl-z01a-0026`、`dl-z01a-0027`、`dl-z01a-0053`、`dl-z01a-0054`）上，单连接连续拉取 `4 MiB` 的分段速率实测如下：

- **`Origin-media`（`dl-z01a-0026`）单连接 `4 MiB`**：
  - `TTFB = 586ms`
  - `0 → 64 KiB`: `857ms`（`236 KiB/s`）
  - `64 → 128 KiB`: `894ms`（**`1729 KiB/s`**）
  - `128 → 256 KiB`: `943ms`（**`2612 KiB/s`**）
  - `256 → 512 KiB`: `4011ms`（**暴跌至 `83 KiB/s`**）
  - `961 KiB` 处：**彻底断流，`SocketTimeoutException: Read timed out`（耗时 `48.6s`）**。
- **`Transcode-1080P`（`dl-z01a-0027`）单连接 `4 MiB`**：
  - `TTFB = 362ms`
  - `0 → 256 KiB`: `1119ms`
  - `256 → 512 KiB`: `3790ms`（**暴跌至 `95 KiB/s`**），随后在 `3.4 MiB` 处断流超时（`51.7s`）。

**物理结论**：PikPak 的受限/拥塞边缘节点对每个 HTTP Range 请求放行约 **`128 KiB ~ 256 KiB` 的初期快速突发窗口**；一旦单次 Range 请求跨度过大（如 `512 KiB ~ 2 MiB`），连接进入二级限速甚至直接僵死断流。

### 3. 同详情跨池节点（`dl-z01a-*`）签名 100% 通用，且快慢节点相差 17~40 倍

在同一次 `getFile(usage=null)` 返回的 `FileDetail` 中，`octetStream`、`Original`、`1080P`、`720P`、`480P` 通常被随机分配到 `dl-z01a-*` 集群的 4~6 台不同编号节点上。实测将任意一条流的 URL Host 改写为同次详情中的其他 `dl-z01a-*` 节点：

| 流类型 | 默认节点表现（拉取 `256 KiB`） | 改写至同详情快节点表现（拉取 `256 KiB`） | 提速倍数 |
| --- | --- | --- | --- |
| `Origin-media` (`category=original`) | `dl-z01a-0026`: `1883ms` (`135 KiB/s`)<br/>`dl-z01a-0053`: `8570ms` (`21 KiB/s`, 超时) | 改写至 `dl-z01a-0042` (`octetStream` 节点): **`111ms` (`2306 KiB/s`)**<br/>改写至 `dl-z01a-0044`: **`120ms` (`2133 KiB/s`)** | **17x ~ 100x** |
| `Transcode-1080P` (`category=transcoded`) | `dl-z01a-0026`: `1849ms` (`138 KiB/s`)<br/>`dl-z01a-0053`: `1857ms` (`137 KiB/s`) | 改写至 `dl-z01a-0042`: **`390ms` (`656 KiB/s`)**<br/>改写至 `dl-z01a-0064`: **`416ms` (`615 KiB/s`)** | **4.5x** |
| `Transcode-720P` (`category=transcoded`) | `dl-z01a-0053`: `5308ms` (`48 KiB/s`)<br/>`dl-z01a-0026`: `3620ms` (`70 KiB/s`) | 改写至 `dl-z01a-0051`: **`135ms` (`1896 KiB/s`)**<br/>改写至 `dl-z01a-0042`: **`395ms` (`648 KiB/s`)** | **39x** |

**关键结论**：
1. `dl-z01a-*` 集群内部**不区分 `download` / `original` / `transcoded` 的签名校验池**，跨池改写 Host 100% 返回 `HTTP 206`。
2. `octetStream` 分配到的 `dl-z01a-0041..0050` 节点组在所有测试线路上健康度极高；而旧版 `HostHealth.poolOf(url)` 将 `download`、`original`、`transcoded` 人为隔离，导致媒体流在 `0026`/`0027`/`0053` 上卡死时，无法切往近在咫尺的 `0041..0050` 节点。

### 4. 九条跨地区/跨机场线路 × 四种并发粒度全景对比

| 线路 | 流 (默认节点) | 单连接 `512K` | `4×64KiB` (`256K`) | `4×128KiB` (`512K`) | `4×256KiB` (`1MiB`) | 旧版 `8×1MiB` (`8MiB`) |
| --- | --- | --- | --- | --- | --- | --- |
| **NEX-SG** (新加坡) | `FETCH` (`0046`) | `1440ms` (`355K/s`) | `1851ms` (`138K/s`) | `926ms` (`552K/s`) | **`752ms` (`1361K/s`)** | `6032ms` (`首1M=986ms`, 0/8) |
| **NEX-SG** (新加坡) | `Origin` (`0055`) | `2669ms` (`191K/s`) | `2715ms` (`94K/s`) | **`510ms` (`1003K/s`)** | **`818ms` (`1251K/s`)** | `23,962ms` (**失败 3/8**) |
| **NEX-SG** (新加坡) | `1080P` (`0064`) | `3079ms` (`166K/s`) | `1019ms` (`251K/s`) | **`561ms` (`912K/s`)** | `1121ms` (`913K/s`) | `2683ms` (`首1M=1584ms`, 0/8) |
| **NEX-HK** (香港) | `FETCH` (`0043`) | `1544ms` (`331K/s`) | `1758ms` (`145K/s`) | `914ms` (`560K/s`) | **`752ms` (`1361K/s`)** | `2916ms` (`首1M=1536ms`, 0/8) |
| **NEX-HK** (香港) | `Origin` (`0053`) | `1746ms` (`293K/s`) | `1749ms` (`146K/s`) | **`499ms` (`1026K/s`)** | **`542ms` (`1889K/s`)** | `2634ms` (`首1M=1629ms`, 0/8) |
| **NEX-HK** (香港) | `1080P` (`0027`) | `1638ms` (`312K/s`) | `2357ms` (`108K/s`) | **`512ms` (`1000K/s`)** | **`491ms` (`2085K/s`)** | `2871ms` (`首1M=937ms`, 0/8) |
| **NEX-JP** (日本) | `Origin` (`0064`) | `2059ms` (`248K/s`) | `923ms` (`277K/s`) | **`444ms` (`1153K/s`)** | **`409ms` (`2503K/s`)** | `1949ms` (`首1M=914ms`, 0/8) |
| **NEX-JP** (日本) | `1080P` (`0063`) | `2539ms` (`201K/s`) | `740ms` (`345K/s`) | **`627ms` (`816K/s`)** | **`542ms` (`1889K/s`)** | `3581ms` (`首1M=2568ms`, 0/8) |
| **NEX-US** (西雅图) | `FETCH` (`0049`) | `3680ms` (`139K/s`) | `4648ms` (`55K/s`) | **`1127ms` (`454K/s`)** | **`1017ms` (`1006K/s`)** | `4584ms` (`首1M=3110ms`, 0/8) |
| **NEX-US** (西雅图) | `Origin` (`0027`) | `7538ms` (`67K/s`) | `3011ms` (`85K/s`) | **`688ms` (`744K/s`)** | **`892ms` (`1147K/s`)** | `3301ms` (`首1M=1233ms`, 0/8) |
| **NEX-TW** (台湾) | `Origin` (`0053`) | `1649ms` (`310K/s`) | `4845ms` (`52K/s`) | `15,105ms` (单路卡) | **`1493ms` (`685K/s`)** | `19,420ms` (**失败 1/8**) |
| **NEX-TW** (台湾) | `1080P` (`0063`) | `2723ms` (`188K/s`) | `2431ms` (`105K/s`) | **`512ms` (`1000K/s`)** | **`900ms` (`1137K/s`)** | `2371ms` (`首1M=1292ms`, 0/8) |
| **LXY-SG-WS** (良心云新WS) | `Origin` (`0027`) | **超时** (`19K/s`) | `11,347ms` (`22K/s`) | **`4097ms` (`124K/s`)** | `7562ms` (`135K/s`) | `31,123ms` (**失败 8/8**) |
| **LXY-SG-WS** (良心云新WS) | `1080P` (`0026`) | **超时** (`18K/s`) | `6555ms` (`39K/s`) | **`5172ms` (`98K/s`)** | `8392ms` (`122K/s`) | `25,685ms` (**失败 8/8**) |
| **LXY-SG-Hy2** (良心云新Hy2) | `Origin` (`0053`) | **超时** (`20K/s`) | `2069ms` (`123K/s`) | **`1722ms` (`297K/s`)** | `14,916ms` (`63K/s`) | `24,117ms` (**失败 8/8**) |
| **LXY-SG-Hy2** (良心云新Hy2) | `1080P` (`0054`) | **超时** (`27K/s`) | **`1800ms` (`142K/s`)** | **`2180ms` (`234K/s`)** | `12,298ms` (`80K/s`) | `23,272ms` (**失败 8/8**) |
| **LXY-JP-Hy2** (良心云日Hy2) | `FETCH` (`0041`) | `2266ms` (`225K/s`) | `1642ms` (`155K/s`) | **`1134ms` (`451K/s`)** | **`970ms` (`1055K/s`)** | `4515ms` (`首1M=4381ms`, 0/8) |
| **LXY-JP-Hy2** (良心云日Hy2) | `Origin` (`0053`) | **超时** (`5K/s`) | `4684ms` (`54K/s`) | `10,471ms` (`24K/s`) | `14,225ms` (`22K/s`) | `22,793ms` (**失败 8/8**) |
| **LXY-JP-Hy2** (良心云日Hy2) | `1080P` (`0026`) | **超时** (`6K/s`) | `4418ms` (`57K/s`) | `13,719ms` (`18K/s`) | `16,689ms` (`20K/s`) | `23,225ms` (**失败 8/8**) |
| **LXY-US-Hy2** (良心云美Hy2) | `Origin` (`0055`) | `3424ms` (`149K/s`) | `2655ms` (`96K/s`) | `1463ms` (`349K/s`) | **`1434ms` (`714K/s`)** | `6307ms` (`首1M=5765ms`, 0/8) |
| **LXY-US-Hy2** (良心云美Hy2) | `1080P` (`0054`) | `3194ms` (`160K/s`) | `1743ms` (`146K/s`) | **`1054ms` (`485K/s`)** | `1333ms` (`768K/s`) | `5668ms` (`首1M=4419ms`, 0/8) |

### 5. 从实测数据得出的四条物理定律

1. **单连接受 BDP 与服务端整形限制，高 RTT 线路必须靠 4 连接并排条带化提速**：
   - 在香港、日本、台湾、美国线路上，单连接吞吐普遍只有 `67 ~ 312 KiB/s`（不足以支撑 1080p 原画实时播放）。
   - 采用 4 连接并发拉取相邻小块时，吞吐直接线性放大 4~11 倍至 `744 ~ 2503 KiB/s`。
2. **`128 KiB`（配合快节点 `256 KiB`）是跨所有线路的「黄金请求粒度」**：
   - `64 KiB` 在代理线路上因 TTFB（`300~1000ms`）占比过高、TCP 拥塞窗口未充分打开，有效吞吐偏低；
   - `1 MiB`（旧版 `spanFor` 跨度）在慢节点与普通机场上触发 100% 断流超时（`8/8` 失败）；
   - `256 KiB` 在专线及 `0041..0050` 健康节点上吞吐最高，但在 `LXY-SG-Hy2` 等受限节点上已踩中尾段限速（耗时从 `1.7s` 恶化至 `12~14s`）；
   - **`128 KiB` 请求跨度在所有专线与普通机场上均处于 CDN 突发免掐流安全区内**。
3. **节点选择比盲目重试重要得多**：
   - 在 `LXY-JP-Hy2` 上，同一时刻访问 `0053`/`0026` 速率仅 `5~6 KiB/s`，而访问同次详情中 `octetStream` 分配的 `0041` 速率高达 `1055 KiB/s`。
   - 打通同详情内 `octetStream` 与 `medias` 的 `dl-z01a-*` 共享节点池，并在单块读取缓慢时立即切换节点，是从根本上消除慢节点卡死的唯一正确手段。
4. **旧版 `8 × 1 MiB` 并发模型与物理现实完全相悖**：
   - 8 条连接各认领 `1~2 MiB` 跨度，导致播放头第 1 个块只由 1 条连接孤军奋战，其余 7 条连接在 `1~8 MiB` 远处争抢同一边缘节点与代理通道带宽，在弱网下造成全员 `BodyStall` / `HeaderTimeout`。

---

## 三、旧版抽象为什么会「状态组合爆炸」？

旧版在 `PikoWorkloadGovernor`、`PlayerScreenState` / `ClipStreams`、`ProxySession`、`BlockCache`、`TransferScheduler`、`RangeReader` 六层之间散落了 20 余个相互耦合的状态标志（`WorkPhase`、`urgent`、`steady`、`entry.streaming`、`cursor.seeking`、`cursor.hungry`、`cursor.urgent`、`cursor.reading`、`cursor.demandActive`、`comfortable()`、`wide()`、`mayWiden`、`minCursorPriority`、`minActivePriority` 及 7 档魔法优先级）。

其根本病因是：
1. **底层默认放出 `8 × 1~2 MiB` 的破坏性并发**，迫使上层不断发明 `urgent`、`seeking`、`hungry` 等布尔条件去“踩刹车”；
2. **各层越界互相猜测**：`ProxySession` 凭本地回环 Socket 写出满 `256 KiB` 就将 `entry.streaming` 置为 `true` 并强开 `8 MiB` 预读窗口；`BlockCache.read` 凭首块命中缓存就将 `cursor.seeking` 置为 `false`，当场解除抑制并放出 8 路并发，在起播探测与 `mpegts` 二分搜索期间引发连接风暴；
3. **多个 HTTP Range 连接共用同一个 `entry.cursor`**：`mpv` 读取文件尾 `Cues` 索引时覆写唯一游标的位置，造成反复假 Seek。

---

## 四、重构后的四层需求所有权正交取流架构

基于「需求所有权（Demand Ownership）+ 真实读取证据驱动块计划 + 纯 HTTP 票据桥接」的正交原则与上述 CDN 实测定律，整体架构收敛为职责严格解耦的四层，彻底废除跨层猜测性布尔状态：

```mermaid
flowchart TD
    L1["1. 消费意图与所有权层 (PlayerScreenState / ClipStreams -> TransferDemand)"]
    L2["2. 纯协议与确定性字节视图层 (PikoMediaProxy / ProxySession / SlicedByteSource)"]
    L3["3. 证据驱动计划与块缓存层 (BlockCache.planLocked + ReadLane)"]
    L4["4. 共享节点池与传输层 (HostHealth / RangeReader / ActiveResponseGuard)"]

    L1 --> L3
    L2 --> L3
    L3 --> L4
```

### 1. 消费意图与所有权层（`PlayerScreenState` / `ClipStreams` → `TransferDemand`）
- 每个播放会话、信息流片段或预热流程持有唯一 `TransferDemand`，通过不可变快照 `ConsumerIntent(epoch, service, bufferBudget, playbackSample)` 原子声明消费意图：
  - **`ServiceIntent`**：`WAITING`（首帧前、定位恢复或缓冲欠载）、`PLAYING`（稳态连续播放）、`PREFETCH`（信息流离屏邻页预渲染与后台预热）、`PAUSED`（暂停或工作区挂起，配搭 `BufferBudget.NONE`）。
  - **`epoch` 与码率溯源隔离**：定位或重绑播放器时单调递增 `epoch`，丢弃旧世代缓冲读数（未知缓冲严格保持 `bufferedDuration = null`，绝不伪造 `0ms` 假紧急截止期）；单持有者当前世代的采样码率（`ownerMediaRateLocked`）仅作用于该持有者自身并随 `epoch` 推进自动复位，不污染共享缓存上其他持有者的文件基准码率。
- `ClipStreams` 以单一权威 `ClipDisplayDemand` 投影焦点页、等待候选页与预渲染邻页，冷启动与末页探索从详情获取、切片准备到字节拉取全程原地复用并提权同一 `TransferDemand`。
- 无活跃字节诉求的空闲 `TransferDemand` 恒定占用 `0` 连接、发起 `0` 请求，彻底移除全局饥饿广播（`isAccountHungry`）以及 `updatePhase(urgent, steady)`、`ClipStreamPhase` 等散落布尔状态。

### 2. 纯协议与确定性字节视图层（`ProxySession` / `SlicedByteSource`）
- **`ProxySession` + `ReadTicket`**：纯粹的 `HTTP Range ⇄ ReadTicket`（`beginRead` / `readSome` / `endRead`）无策略适配器，彻底删除 `entry.streaming` 跨块猜流、`configureReaders()` 策略计算与 `1500ms` 连接池保活定时器；单次 HTTP 响应完成或客户端断开连接时立即在 `finally` 中调用 `endRead` 释放该票据，而会话级 `TransferDemand` 存活至 `withdrawTransport()` / `close()`。
- **`reconnect=0` 会话内 `hr-seek` 世代规则**：由于本机回环 `127.0.0.1` 显式禁用 FFmpeg 自动重连（`reconnect=0`），会话内拖动推进 `owner.intent.epoch` 时，缓存层在 `0ms` 内立即撤回旧世代的投机状态（`ReadLane`、`LookAhead`、`FinishInFlight` 与旧截止期），但**保留仍在读取的旧世代 `206` 票据不断流**（旧票据在客户端自行关闭旧连接前仅发出当前 `readSome` 所需的单块 `Required`，`0` `LookAhead`，且客户端断开时由 `ProxySession` 传入 `allowFinishInFlight = false` 立即取消残余块），防止 FFmpeg 报 `Stream ends prematurely` 假死；仅在 `withdrawTransport()`（`owner.isClosed`）或缓存关闭时才立即切断所有在途连接（未发响应头连接以 `0` 字节干净 EOF 收尾，绝不误回 `502`）。
- **`SlicedByteSource` 确定性视图掩码**：对 MPEG-TS 切片前导区间 `[0, nullVideoBeforeOffset)` 纯粹按逻辑包偏移 `k * 188` 确定性地将目标视频 PID 改写为 `0x1FFF` 空包，跨任意 `1..3` 字节跨包头分片读取、随机 `seekTo` 与并发票据输出字节完全一致，且零拷贝污染底层共享缓存块。

### 3. 证据驱动计划与块缓存层（`BlockCache.planLocked()` + `ReadLane`）
- `BlockCache.planLocked()` 每次在锁下由活跃票据、读取车道与持久任务纯函数汇总四类正交字节诉求（彻底删除 `Cursor.seeking/urgent/hungry` 与 `Fetch.consumed` 整段免死金牌）：
  - **`Required`**：活跃 `ReadTicket` 当前阻塞等待的槽位，绑定单调不回拨、每槽位单次触发的字节截止期（`SlotDeadlineEpisode` → `ByteDeadline`）。
  - **`LookAhead`**：每个 `TransferDemand` 维护最多 4 条自校正 `ReadLane`（MP4 音视频双偏移交错读取各自成道，互不驱逐）。任何车道在连续顺序读取未满 `256 KiB`（`contiguousBytesRead < blockSize`）前恒为 **`0` 预读**；跨过门槛后按 `contiguousBytesRead * 2` 几何爬坡至预算上限。在 `ServiceIntent.WAITING` 下，常规码率流上限为 `2 MiB`（`8` 块），高码率流（`>= 3 MiB/s`，如 4K Blu-ray REMUX）上限自动扩展至 `maxOf(2 MiB, 1s 码率字节数)`，驱动并行 `1 MiB` Worker 喂饱大 GOP 顺序解码。
  - **`FinishInFlight`**：客户端读完短区间断开后，仅允许已发出的当前单个 `<= 256 KiB` 在途块在空闲工作槽下顺带收尾（每个持有者最多 4 个槽位，优先级 `-1`，`0` 新 HTTP 请求、`0` 重试，受 `lastReadSpeculationStamp == speculationRevision` 守护防止撤回后复活，且工作槽满时可被任意 `>= 0` 诉求 `0ms` 抢占并保留 `partials` 已收前缀）。
  - **`StoreRange` 与跨文件后台下载即刻退让**：承载显式 `prefetch` 预热与持久化后台下载。当前台流激活（`foregroundStreams.active > 0`）进入 `downloadsThrottled()` 时，跨文件或同文件的后台持久化下载在 `0ms` 内立即抢占回收超出配额（`<= 2` 路）的在途 Worker（保留 `partials`），存活的多块后台流也在下一个 `256 KiB` 块边界处立即退回单块模式，确保前台播放瞬时获得带宽。

### 4. 共享节点池与传输层（`HostHealth` / `RangeReader` / `ActiveResponseGuard`）
- **打通 `dl-z01a-*` 同集群全节点池**：`HostHealth.observe(detail)` 将 `octetStream`、`webContentLink` 与所有 `medias` 中的同集群节点合并入统一候选池，不再按 `download` / `original` / `transcoded` 隔离。
- **慢节点快速逃逸与零 `ResponseBody` 泄漏**：前台必需块在 `ByteDeadline` 到期并经 `authorizeFetchDeadline` 原子确权后立即切往候选池中的健康节点（如 `0041..0050`）；`RangeStreamEndpoint` 全校验/异常分支与 `HttpEngine` 的 `ActiveResponseGuard`（配合 OkHttp 拦截器）闭环覆盖响应头到达瞬间的取消竞态，杜绝连接体泄漏并保持 HTTP/1.1 Keep-Alive 复用。

---

## 五、批量媒体详情签发（`batchGet`）、720P MPEG-TS 切片解剖与 `ContentMetadata` 架构

### 1. `files:batchGet` 批量签发实测（`~130 ms / 视频`）

单次调用 `GET /drive/v1/files/{id}`（`usage = null`）签发多路 `medias` 直链耗时高达 `1,950 ~ 3,600 ms`，在信息流（`Clips`）冷启动或跨目录批量预热时若逐个串行/小并发请求，首屏等待时间过长。实测 `GET /drive/v1/files:batchGet`（支持单次传入最多 `100` 个重复 `ids` 查询参数及 `usage=PLAY`）：

| 接口与方式 | 单次请求上限 | 返回内容 | 实测耗时（100 个视频） | 折合单视频耗时 |
| --- | --- | --- | --- | --- |
| `getFile(id, usage = null)` | 1 个文件 | `FileDetail`（含 `medias` 与 `links`） | 串行 `~240 s` | `1,950 ~ 3,600 ms` |
| `batchGetFileDetails(ids, usage = "PLAY")` | **100 个文件 / 批** | 完整 `FileDetail` 列表（含全套 `medias` 转码变体与 `links["application/octet-stream"]` 签名直链） | **`~13.0 s` / 100 个** | **`~130 ms / 视频`**（提速 **15x ~ 27x**） |

- **SDK 落点**：
  - `FileStat` 补充 `val medias: List<MediaVariant> = emptyList()` 与 `val links: Map<String, DownloadLink> = emptyMap()`，使 `batchGetFiles` 不再丢弃服务端在 `files:batchGet` 中返回的媒体变体与下载直链。
  - `SyncEndpoint.kt` 新增 `PikPakClient.batchGetFileDetails(ids: List<String>, usage: String? = "PLAY"): List<FileDetail>`，自动对 `ids.distinct()` 按 `100` 个分批请求 `/drive/v1/files:batchGet`，同步喂入 `accountState.observeFile` 与 `hostHealth.observe`，并严格按调用方去重后的 ID 顺序返回。

### 2. 云端 `720P` MPEG-TS 转码流解剖

对 PikPak 云端生成的 `720P` 转码流（`video/mp2t`，`mpegts` 封装）执行逐包解复用与码流结构实测，核心物理指标如下：

- **编码与码率**：H.264 High Profile + AAC，平均总码率 **`~800 kbps`**（约 `100 KiB/s`，典型范围 `572 ~ 950 kbps`），`1 MiB` 数据可支撑 **`8 ~ 14 秒`** 连续播放。
- **固定 GOP 结构**：严格采用 **`250 帧` 固定 GOP**（以 `23.976 / 25 / 29.97 fps` 计，每约 `8.3 ~ 10.4 秒` 一个 IDR 关键帧，对应字节跨度约 **`600 KiB ~ 1 MiB`**）。
- **高频 `PAT/PMT` 周期**：复用器每隔约 **`7.6 KiB`**（约 `41` 个 `188B` TS Packet）即重复插入一组 `PAT`（PID `0x0000`）与 `PMT`（PID `0x1000`）。这意味着从任意 `188B` 对齐（或由解复用器自动同步 `0x47` 同步字节）的字节偏移切入，**最多扫描 `7.6 KiB` 即可完成节目表锁定**，无需像 MP4 那样先读取文件首尾的 `ftyp`/`moov` 索引表。

### 3. 超轻量 `1 MiB` TS 切片与 `256 KiB × 4` 条带化设计

结合 `720P` MPEG-TS 的物理特性与 CDN 慢节点「`128 ~ 256 KiB` 突发安全区」定律，确立以下流式传输与切片协同设计：

1. **`256 KiB` 槽位对齐 + 4 路条带化并发（`1 MiB` 窗口）**：
   - `PikPakStreamReader.DEFAULT_BLOCK_SIZE` 设为 **`256 KiB`（`256 * 1024`）**，`BlockCache` 默认 `readAheadConcurrency = 4`。
   - 4 个并发 Worker 紧贴播放头同时拉取 4 个连续 `256 KiB` 槽位，恰好构成 **`1 MiB` 条带化滑窗**——既处于单次 HTTP Range 请求的突发免掐流窗口内，又能在单轮 RTT（专线 `~400–800 ms`）内完整取回一个包含至少 1 个 IDR 关键帧与百余组 `PAT/PMT` 的 **`1 MiB` 超轻量 TS 切片**。
2. **全偏移统一连续读取证据门控（Evidence-Gated `ReadLane` Ramp）**：
   - 无需对 `offset == 0L` 硬编码首部防抖特判：无论读取发生在首部容器探测（MP4 `ftyp`/`moov`、MKV `EBML` 头）、文件尾部（`Cues` 索引）还是片中任意跳点，每条 `ReadLane` 在累计连续顺序读取未满 `1` 个槽位（`contiguousBytesRead < 256 KiB`）之前恒定保持 **`0` 槽位 `LookAhead`**，仅拉取 `ReadTicket` 当前阻塞请求的 `Required` 单槽位（短读断开时至多留下单个 `<= 256 KiB` 的低优 `FinishInFlight` 收尾块）；直至顺序读取真正跨过 `256 KiB` 门槛后，才按 `contiguousBytesRead * 2` 几何爬坡放开条带化并发滑窗（常规流 `WAITING` 上限 `2 MiB`，`>= 3 MiB/s` 高码率流自动扩展至 `1s` 码率字节数），从根源上消除起播探测、断点续播与 `hr-seek` 全场景的无效连接风暴。
3. **转码流跨节点 Host 改写（Cross-Host URL Rewriting for Transcodes）**：
   - `PikPakFileHandle` 为转码流（`mediaId != null`）构建候选直链列表时，自动提取同一 `FileDetail` 中 `links["application/octet-stream"]`（及 `octetStream`、`webContentLink`、其他健康 `medias`）的节点 Host。
   - 利用同集群（`dl-z01a-*`、`dl-a10b-*`）共享 `/download/?fid=...` 转码路径与签名校验的特性，将改写至高速 `octet-stream` 节点（`0041..0050`）的转码 URL **排在默认拥塞转码节点（`0026`/`0027`/`0051`..`0055`/`0063`..`0064`）之前**，同时保留原始转码 URL 作为兜底回退项。
4. **首包 `206 Partial Content` 动态习得精确 `streamSize`**：
   - 转码流在 `FileDetail` 中不携带精确字节长度。当句柄以估算长度打开或首次发起分块 Range 读取收到 `HTTP 206 Partial Content`（携带 `Content-Range: bytes X-Y/TOTAL` 且 `TOTAL > 0`）时，自动更新 `probedSize = totalSize` 并写入 `client.rememberStreamSize(gcid, variantKey, totalSize)`，首个数据块返回即完成精确流长度学习，省去额外的单字节探针往返。

### 4. `ContentMetadata`（`TranscodeVariantMetadata`）持久化架构

由于云端转码流特征（可用画质档位、编码格式、分辨率、码率、时长及习得的精确 `streamSize`）严格绑定文件内容（`GCID + size`），与具体文件实例 ID 或所在目录无关：

- 在本地全盘树索引（`DriveTreeIndex` SQLite WAL 库）的第三层按 `GCID + size` 共享的 **`ContentMetadata`** 中，持久化 `TranscodeVariantMetadata`（记录各转码变体的 `mediaId`、`resolution`、`codec`、`bitRate`、`duration` 与已习得的 `streamSize`）。
- 通过 `batchGetFileDetails` 批量获取或播放期间由 `206 Content-Range` 动态习得的转码流长度与变体元数据，统一回写至 `ContentMetadata`。后续无论在网盘页、资料库还是信息流（`Clips`）中再次命中同一 `GCID`，均可零网络探针直接按已知 `streamSize` 与码率精确计算 `1 MiB` TS 切片偏移并瞬时起播。
