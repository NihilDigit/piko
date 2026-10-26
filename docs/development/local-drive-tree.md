# 本地文件树索引与差分同步设计

## 背景与目标

PikPak 服务端不提供按名称搜索接口，且文件夹的 `modified_time` 不会随其内部子项的增删改而更新（实测恒等于创建时间）。过去客户端采用「进入哪个文件夹才拉取哪个文件夹」的按需列目录模型，导致：

1. 进入未缓存的子目录、展开目录图（`FolderMap`）或地址栏路径补全时，每层均需等待一次网络往返（250 至 800 ms）；
2. 文件夹行的作品名解析（`FolderContentMemory`）必须等列表滚动停留 400 ms 后逐个文件夹发请求采样；
3. 文件夹属性中的文件数与总大小统计（`folderUsage`）、查重（`DuplicateFinderState`）、瀑布流（`GallerySession`）与信息流（`ClipFeedSession`）各自独立发起 BFS 递归遍历，重复请求大量目录。

SDK 2.2.0 引入 `parent_id=*` 平铺分页接口（`listAllDriveFolders` 与 `listAllDriveEntriesFlow`，单页 500 项）后，几千个条目的网盘可在 2 至 4 秒内拉齐全部存活节点。本设计将网盘读取模型演进为**「本地完整文件树驱动 + 磁盘快照持久化 + 三层水位线差分与后台双缓冲重建」**。

---

## 核心数据结构与持久化

### 1. 内存索引（`DriveTreeIndex`）

按当前登录账号维护进程内单份状态：

- `entries: Map<String, FileStat>`：包含全盘存活文件夹、真实文件以及已解析的归档虚拟条目（`piko-vault:`）；
- `childrenByParent: Map<String, List<String>>`：由 `entries` 派生的父子邻接表，支持 $O(1)$ 读取任意目录的直接子项、$O(K)$ 统计任意子树的文件数与总字节数；
- `manifestsByFolder: Map<String, FileStat>`：各文件夹下当前胜出的 `.piko-vault-v<版本>-<随机串>.json` 清单文件元信息；
- `lastUsageBytes: Long?`：上次与服务端对齐时的网盘已用空间字节数（来自 `getQuota()`）；
- `complete: Boolean`：当前树是否已完成全量对齐。

### 2. 磁盘快照持久化（按账号隔离）

存于 `PikoCacheStore`（缓存目录，键名带账号标识，换号隔离）：

1. **`drive-tree-<account>.json`**：
   保存 `lastUsageBytes`、树快照生成时间戳、全盘文件夹与真实文件的精简记录（`id`、`parentId`、`name`、`size`、`hash`、`createdTime`、`modifiedTime`、`mimeType`、`params`、`tags`）。
2. **`vault-manifests-<account>.json`**：
   按归档清单文件的 **文件 ID（或文件名 `.piko-vault-v<版本>-<随机串>.json`）** 缓存已解析的 `List<VaultEntry>`。
   由于归档清单每次修改都会生成新版本号与新文件 ID，只要平铺列表中该目录胜出的清单文件名未变，即可直接复用本地已解析的 `VaultEntry` 列表，无需在启动或建树时对几十个归档目录逐一发起详情与下载请求。

---

## 首屏启动与阻塞边界（Blocking vs Shadow Rebuild）

区分「账号首次建树」与「已有本地快照后的日常启动」：

### 1. 首次登录或无本地快照（Blocking Loading）

当账号在当前设备首次登录、且 `drive-tree-<account>.json` 不存在时：

- 网盘首屏呈现居中进度界面（显示已读取的文件夹数与文件数）；
- 顺序执行 `listAllDriveFolders(pageSize = 500)` 与 `listAllDriveEntriesFlow(pageSize = 500)`，拉齐全部存活文件夹与文件；
- 对发现的 `.piko-vault-*.json` 清单按需拉取解析并写入 `vault-manifests-<account>.json`；
- 完成后将完整树写入 `drive-tree-<account>.json`，随即进入主界面。

### 2. 日常冷启动（0 ms 恢复 + 后台静默对账）

当本地已存在 `drive-tree-<account>.json` 时：

- 启动时直接从磁盘反序列化树快照与归档清单缓存，**0 ms 渲染任意目录、0 ms 响应全盘搜索与目录图**，不展示阻塞式全盘加载页；
- 首屏就绪后，在后台触发一次轻量级「哨兵探针（Canary Probe）」核对跨端改动。

---

## 跨端差分更新与整树重建机制

由于用户可能在另一台 Piko 设备或官方客户端增删改文件，本地树通过三层机制保持最终一致：

### 第一层：当前浏览目录的即时自愈（Stale-While-Revalidate）

当用户进入或停留在目录 `F` 时：

1. 界面先立即用 `DriveTreeIndex` 中 `parentId == F` 的条目渲染，无白屏或骨架屏等待；
2. 后台静默发起单次 `listFiles(F)` 请求：
   - 若服务端返回的条目集合与本地树中 `F` 的直接子项完全一致，不触发任何界面更新；
   - 若存在局部差异（新增文件、文件被删、重命名，或 `.piko-vault-v*.json` 版本号更新），原地将差异 Patch 进 `DriveTreeIndex` 并更新当前列表。
   - 若发现某文件 `X` 的 `id` 原先记录在本地树的目录 `A` 下，而此次出现在目录 `F` 下，直接将其 `parentId` 修正为 `F`，目录 `A` 与 `F` 同步收敛。

### 第二层：跨目录哨兵探针（Canary Probe，增量 Patch）

在**应用冷启动恢复快照后、窗口从后台切回前台（带 60 秒冷却间隔）、或收到 `.piko` 远端同步文件更新**时，执行一次仅需 1 至 2 个请求的探针检查：

1. 读取 `getQuota()` 的 `usageBytes`，与本地树保存的 `lastUsageBytes` 比对；
2. 请求第一页按修改时间倒序的平铺列表：`listFilesPaged(parentId = "*", pageSize = 100)`（最新的 100 个条目）；
3. 核对这 100 条记录与本地树：
   - **完全未变**：前 100 条的 `(id, parentId, name, modifiedTime, size)` 与本地树完全吻合，且 `usageBytes == lastUsageBytes`。说明另一端无任何改动，探针直接结束（耗时约 200 ms）。
   - **小幅增量改动（Incremental Patch）**：
     前 100 条中仅前 $K$ 条（$0 < K < 100$）为新条目或属性变更条目，自第 $K+1$ 条起与本地树已有记录完全吻合，且满足：
     - `usageBytes - lastUsageBytes` 恰好等于这 $K$ 条中新增真实文件的总字节数（说明期间没有发生外部文件删除）；
     - 此时直接将这 $K$ 条变更 Patch 进 `DriveTreeIndex` 与 `FolderIndex`（若包含新的 `.piko-vault-*.json` 清单文件，则仅拉取并解析该清单），更新 `lastUsageBytes` 与磁盘快照，无需重扫全盘。

### 第三层：触发整树重建（Full Tree Rebuild）的四个条件

当增量探针无法覆盖外部变动时，自动升级为后台全量平铺重建：

1. **增量窗口击穿**：
   探针拉取的最新 100 条记录全部为新记录或变更记录（即另一端一次性新增、移动或改名了超过 100 个条目）；
2. **外部删除或空间差额不匹配**：
   `usageBytes < lastUsageBytes`，或 `usageBytes - lastUsageBytes` 与前 $K$ 条新增文件体积之和不一致。由于 PikPak 的 `parent_id=*` 仅返回存活条目而不返回已删除 ID，必须拉取全量存活 ID 集合并与本地树做差集（`localIds - remoteLiveIds`），才能彻底清除被另一端移入回收站或永久删除的节点；
3. **操作或定位时遇到服务端 `404` / `file_in_recycle_bin`**：
   打开、播放、下载或操作某条目时服务端明确返回不存在或已在回收站，立即从本地树摘除该节点，并在后台触发一次整树重对账；
4. **用户在根目录手动触发「刷新（F5）」**：
   作为用户显式要求的强制全盘对齐入口。

#### 后台双缓冲替换（Shadow Tree Swap）

除「首次登录无本地快照」外，上述四类整树重建均在后台以双缓冲方式执行：

- 后台协程独立拉取新的 `folders` 与 `files` 流，复用 `vault-manifests-<account>.json` 中文件名未变的归档清单缓存，仅下载新版本的归档清单；
- 将重建期间本机刚发生的在途写操作（`changeMarks`）合并入新树；
- 构建完成后一次性原子替换 `DriveTreeIndex` 的内存状态并写回磁盘快照，仅向子项实际发生变化的目录发出 `DriveChange.ContentsChanged` 通知，全程不阻塞用户当前浏览与播放。

---

## 下游能力收敛路线

随着完整本地文件树就位，各模块按以下顺序收敛到 `DriveTreeIndex`：

1. **搜索与 CJK 模糊匹配（已落地第一阶段）**：
   全盘与子树搜索直接查询 `DriveTreeIndex`，结合 `CjkFuzzyMatch`（`NFKC`、简繁与日本新字体归并、平片假名互通、叠字符展开、有界子序列匹配）毫秒级返回真实文件与归档条目。
2. **归档清单按文件 ID 持久化缓存**：
   在 `VaultStore` 中将 `parsed: Map<String, List<VaultEntry>>` 持久化至 `vault-manifests-<account>.json`，消除建树与跨目录读取时的重复清单下载。
3. **目录浏览、目录图与文件夹统计零请求化**：
   - `listBrowsable`、`subfolders`、`folderMapLevel` 优先从 `DriveTreeIndex` 同步给出完整子列表，后台按需做 Stale-While-Revalidate；
   - `FolderContentMemory` 直接从 `DriveTreeIndex` 取各文件夹的子文件名，省去列表滚动停留 400 ms 的逐目录网络预取；
   - `folderUsage` 在 `DriveTreeIndex.isComplete` 为真时直接内存求和并立即返回 `COMPLETE`。
4. **查重、瀑布流与信息流候选集直取**：
   `DuplicateFinderState`、`GallerySession` 与 `ClipFeedSession` 直接从 `DriveTreeIndex` 切片目标子树，不再各自发起递归网络遍历。
