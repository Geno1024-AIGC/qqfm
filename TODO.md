# 待办 / 想法

记下暂时不做、但以后可能要做的事。带日期，方便判断是不是还成立。

## 图片内容搜索（OCR）— 基本可用（2026-10-16 记，2026-10-24 起做）

在聊天图片里认出文字，然后按文字搜图。全本地，不联网、不上传。

**已做**
- 接入 `com.google.mlkit:text-recognition-chinese`（内置模型，无 GMS 也能跑）
- 打开图片时顺手 OCR，结果存 SQLite（`ocr.db`），查看器里可查看/复制识别文字
- 图库顶部搜索按识别文字过滤
- 关于页显示已识别数量，可清空索引
- 后台全量补齐：`OcrScanService` 前台服务（`dataSync`），按时间从新到旧，
  可暂停/继续/停止，断点存 SharedPreferences，通知栏显示进度与用时；
  批次用一次 `su` 拷进应用缓存再解码，避免每张图起一次 root shell

**还没做**
- Android 15+ 的 `dataSync` 前台服务约 6 小时后会被系统叫停；目前只保证手动继续，
  以后可以用 WorkManager 在冷却后自动接着跑
- 索引检索目前是 `LIKE '%词%'`；数据量大到几十万条后再评估 FTS

**约束**
- APK 内多一个端侧模型 + 各 ABI 的 native 库，debug 包约 +18 MB（已裁掉 x86）
- 识别速度估算：1080p 单帧约 **100–300 ms**
- 16.5 万张全量索引：单线程约 **7 小时**，2–3 线程约 **2.5–4 小时**
- 后台补索引需要 `WorkManager` + **前台服务通知**，否则系统随时杀进程
- 索引体积估 20–60 MB

**推进顺序（已完成）**
1. 只在**打开图片时**顺手 OCR，先索引你看过的图
2. 用户真的在用了，再做**后台全量补齐**（按时间从新到旧、可暂停、断点续跑、带进度）

## 元数据筛选 — 可以随时做（低成本）

时间和大小范围的筛选器，外加"只看已锁定"。在已扫好的列表上过滤，16.5 万条约 5–15 ms，跑在 `Dispatchers.Default`。
注意：**关键词搜 base 没意义**，base 是 `crc64` 哈希，不是人能读的词——真正有价值的是时间和大小。

## 二期：按聊天记录搜图（比 OCR 更准更省电）

解 `nt_msg.db` 之后，消息里的图片 md5 在 protobuf 字段 45406 / 45424（`group_msg_table` 里 `40800` 字段的嵌套内容）。

**⚠️ 旧结论已推翻（2026-11-11 实测）**：曾经认为文件名是
`crc64("chatimg:" + md5)`（来自旧版 QQ 的第三方实现），在 QQ 9.1.35 上**不成立**。
详见下面「缓存名 ↔ 消息」一节——`Cache_` 名是下载时生成的**随机 64 位 id**，
既推不出，也没有写进消息体。**"md5 → 直接算出文件路径"这条路走不通。**

### 方案 B「原地只读」推进记录（2026-11-10 记）

- **决定**：读库走"原地只读"（不把 2.3 GB 级的 nt_msg.db 复制出来），只做会话分类，不碰"删记录"。
- **Phase 0 已做**：关于页 →「消息数据库（调试）」→ 开始探测。root 读库头 9 KB，
  按 `key = md5(md5(nt_uid) + rand)` 离线派生，把所有假设当候选穷举：
  salt 来源（流起点 1024 / 文件头 0）、文件形态（1024 偏移 VFS / 明文头 1024）、
  rand、页大小 × 保留字节（48/80/16，按 `round_up(16+hmac,16)` 推得）、
  kdf（4000 → 64000 → 256000 限量兜底）；开跑前先做 PBKDF2 已知答案自检，
  系统提供者口令编码不符就切手动 RFC 2898 实现。命中后校验 SQLite 头
  （页大小/版本字节/文本编码，误判率≈1e-10），再直解 `sqlite_master` 列出全部表名。全程只读、零拷贝。
- **Phase 0 真机通过（2026-11-11，QQ 9.1.35 / 15.8 GB 库）**：
  - 命中参数：`流@1024 (OFFSET_VFS)`、salt=流起点 16 字节、`page=4096`、IV@页内 4048
    （crypto reserve 48）、`kdf_iter=4000`、`HMAC_SHA1`、rand=`T0Q3eav8`（`QQ_NT DB` 标记 @32 后）、
    uid=`754097987###u_NEM31P9-…`（路径公式吻合）、key=`af1401c2…`；派生+解密 433 ms，全程 1.2 s。
  - `sqlite_master` 走读 7 页解出 **34 张表**（`c2c_msg_table`/`group_msg_table`/
    `recent_contact_v3_table` 等与文档一致）→ 原地解密路径成立。
  - 过程中修掉的真机问题：KernelSU 给本 app 的 su 交回**隔离挂载命名空间**
    （`/data/data` 被 tmpfs 盖住，Termux/Root Explorer 正常）→ RootShell 检测到与 init
    命名空间不同时自动 `nsenter -t 1 -m -- sh -c` 执行（glob 必须在目标命名空间内展开）。
  - 待解小疑点：解密出的头 `byte20=80`（SQLite 视角 usable=4016）与 crypto IV 位置
    （4048 → reserve 48）不一致——记录解析时按实测定 usable，不阻塞。
- **Phase 1 真机通过（2026-11-11）**：`ntdbread --md5key` 在真机 `su` 下直接跑通——
  `page_count=3875332`、**34 张表全解**（与 Phase 0 一致）、
  `wal_mode=raw wal_frames=80 wal_pages=3875332 wal_plain=0`
  → 真实 QQ `-wal` 被成功合并：80 帧有效、校验和对存储字节（mode raw）、
  投票判定为**密文帧**（QQ 加密了 WAL，非明文）。`master_other=72` 为索引/视图，正常。
- **Phase 1 已完成**：NDK + `sqlite3.c` 3.54.1 + 1024 偏移解密 VFS（native/，只读），
  离线 fixture 测试含三种 WAL 口味（plain / enc-raw / enc-dec）全绿，CI 已加 NDK 构建步骤；
  下一步 Kotlin 接线（assets 打包 → `su` 执行 → 解析 → md5→会话映射）与
  全量扫 `40800` 抽图片 md5。
- **Phase 1 注意**：shortvideo 的文件名规则未验证（图片的 crc64 规则已确定）。
- **真机并发读（2026-11-11 记）**：QQ 正在写库时全表扫大 blob 会读到撕裂页
  （`database disk image is malformed`）→ `--pics` 改为**按 `40001` 主键分块续扫**
  （5 万行/块）：失败只丢当前块，重开库从 `last msgid` 续扫，已抽行全部保留；
  同一位置连续 3 次无进展才停，并把**已抽部分结果照常输出** + stderr 报
  resume point。一次性 SQL 查询仍是"缓冲 + 关库重开重试 3 次"。
- **真机「固定 msgid 处 malformed」根因（2026-11-11 定案）**：**SQLite 的锁字节页**。
  SQLite 把文件偏移 `PENDING_BYTE = 0x40000000`（**正好 1 GiB**）所在的页视为保留页
  （4 KiB 页 → 第 `262145` 页），`getPageNormal()` 里 `pgno == PAGER_SJ_PGNO(pPager)`
  直接返回 `SQLITE_CORRUPT`（"database disk image is malformed"）**且根本不发起 I/O**；
  日志里表现为 `getAndInitPage pgno=262145` 之后没有任何 `vfs: read`。
  QQ 的库 15.8 GB，那一页是**正常数据**（QQ 自己的 SQLite 处理了它），我们的 VFS 又自己
  负责 I/O 与加锁，这个保留页规则对我们无意义 → 编译 `sqlite3.c` 时加 `-DNTDB_UNLIMITED_PAGES`
  （`native/build.sh`），把 `pPager->lckPgno` 置 0（永不匹配真实页号），只读场景安全。
  **这个坑只在库 > 1 GiB 时出现**，所以所有离线 fixture 测试都测不到，只能真机验证。
- **诊断工具（2026-11-11 记）**：`--locate N` 不经过 SQLite 解析器，自己从
  `group_msg_table` 的 rootpage（可用 `--root` 覆盖）走 b-tree 到持有 rowid N 的叶子页，
  报告 `page / source(main|wal) / frame / type / ncells / depth / usable`、该页
  `first..last` rowid 以及越界单元格（`badcells` + 首个坏单元格的 offset/payload/need）；
  同时沿每个大消息体的**溢出页链**走一遍，报告 `overflow: cells/pages/bad`（坏链给出
  越界页号与链深）以及目标记录自己的 `payload / over_pgno / over_source(main|wal)`。
  `--dump-page N` 直接输出解密后的页头字节。SQLite 判定 `malformed` 的页照样能看，
  用于区分"主文件残页 / WAL 合并漏帧 / 解密错位 / 溢出页指针撕裂"几种根因。
  **坑：SQLite 的 varint 是大端**（protobuf 是小端），第一版按小端解析导致
  rowid/payload 全错、误报 `badcells`。
- **`40800` 图片消息结构已实测（2026-11-11，真机样本 1448 B）**：
  外层 `f40800` 包消息体，图片元素（`f40010`=2）关键字段——
  `f45402`=文件名（md5 大写 + `.png`）、`f45405`=字节数、
  **`f45406`=16 字节裸 md5**、`f45408`=sha1、`f45411/45412`=宽高、
  **`f45424`=32 位 hex md5**（与本文件早前猜测一致）、`f45503`=fileid、
  `f45802/03/04`=`/download?…spec=198/720/0`、`f45816`=`multimedia.nt.qq.com.cn`。
  抽取规则：**`f45424` 统一小写优先，缺则 `f45406` 转 hex**（同一 md5 两种编码）。
  红包等非图片消息里 32 位 hex 全是 authkey/pcbody 噪声，**必须先按元素类型过滤**
  （元素类型是 blob 内的 `f40010`，不是列 `40011`）。
- **会话列已定案（2026-11-11 真机样本）**：`group_msg_table` 与 `c2c_msg_table` 列完全相同，
  其中 `40020`=**发送者 uid**（`u_xxx`），`40021`=**对端**：
  群消息是**群号**（如 `761140305`，TEXT 存数字），C2C 是**好友 uid**（`u_xxx`）。
  好友 uid → QQ 号查 `nt_uid_mapping_table`（`"48902"`=uid，`"1002"`=uin，
  `"48901"`=自增 id，`"48912"` 实测为空）。`--pics` 的 `peer` 列即 `40021`、
  `sender` 即 `40020`。
- **`--pics` 输出改为流式 + 带 peer（2026-11-11 记）**：早期实现把全部 TSV 攒在内存里、
  结束时一次性写出（237 万行 ≈ 200 MB），**进程一被杀就全丢**；现在每扫完一个
  `PICS_CHUNK` 就 `fwrite`+`fflush` 到 stdout，内存恒定、被 kill 也只丢当前块。
  列义：`table / msgid / peer / sender / elem / md5 / filename / size`。
  **坑：`adb exec-out` 会把设备端 stderr 合并进 stdout**，所以采集时必须写
  `su -c '… 2>/data/local/tmp/x.err'`，否则进度行会混进 TSV。

## 缓存名 ↔ 消息：负结果清单与下一步（2026-11-11 记）

把 `nt_msg.db`（15.9 GB）整库拉到 PC 后做了全量核对，真机 15.8 万条 `chatimg` 缓存文件的
内容 md5、大小、名字都拿到了（设备端 `md5sum` 全量 **4 分 47 秒**，说明"App 内自算 md5"可行）。

**已证伪（不必再试）**：

1. `Cache_<16hex>` **不是**任何消息字段的哈希。用 3000 组（内容 md5, 名字）真值扫了
   crc64（Jones / XZ / ISO × init=全1/0 × xorout=0/全1）× 前缀（`""`/`chatimg:`/
   `chatimg`/`chatpic:`/`Cache_`）× 编码（小写 / 大写 / 裸 16 字节 / UTF-16LE / UTF-16BE），
   外加 FNV-1a、xxhash64、md5/sha1/sha256 任意 8 字节窗口——**全部 0 命中**。
2. 抽取**没有选错字段**：图片元素里 `45406`（裸 16 B）与 `45424`（32 位 hex）是**同一个 md5**
   （`45424` 有时为空串），不存在"原图 md5 vs 缩略图 md5"两套值。
3. 也不是由 `45503` fileid / `45802` 下载 URL / `45408` sha1 推出来的（400 组铁真值，
   同样的哈希全家桶，含 base64 解码后的 fileid 原始字节与其中内嵌的 sha1，全 0 命中）。
   **顺带发现**：`45503` base64 解码后是 protobuf，`field 2`（长度 20）= 图片的 **sha1**。
4. **消息体里没有本地缓存名，也没有任何 `/storage` 路径**（对 60 条"内容 md5 完全相同"的
   消息直接 `instr` 其 `40800` 全字节，0 命中；`Cache_` / `chatimg` 字样同样搜不到）。
   例外：`c2c_msg_table` 里有 **1 行**含 `chatimg` 字样，待查（可能是"自己发的图"记录了本地路径）。

**关键事实：`chatimg` 不是原图字节**。15.8 万条 `chatimg` 缓存里只有 **4048 条（2.6%）**的内容 md5
与消息 md5 完全相同（这批多为贴纸/动画表情，体积小、不经重编码）；其余 97.4% 是 QQ 下载时
**按 spec 重编码**过的版本（`45405` 大小与本地文件大小精确一致，但 md5 不同）。
所以"算 `chatimg` 的本地 md5 → 查消息 md5"对普通照片**无效**。

### 解法已找到：以 `chatraw` 为基线（2026-11-11 实测）

`chatraw`（原图树，116,544 个文件 / 17.45 GB）存的是**发送时的原图字节**：

| 树 | 文件数 | 内容 md5 能对上消息 |
|---|---|---|
| `chatraw` | 116,544 | **65,948（56.6%）** |
| `chatimg` | 157,962 | 923（0.6%） |
| `chatthumb` | 145,107 | 缩略图，天然对不上 |

- 配对上的文件里 **98.7%** 的 size 与消息 `45405` 完全一致（进一步确认是同一张图）。
- 全库 `--pics` 结果：2,180,322 条图片元素、1,084,208 个不同 md5（含 `c2c_msg_table`），
  对上后落到 **439 个会话**（396 个群 + 43 个单聊），最多的是 811635507（6000 张）。
- 剩下 43.4% 对不上是正常的：原图可能已被 QQ 清理、或这条消息的图从未下载过原图。

**跨树按名字关联彻底不可行（已实测，不必再试）**：
三棵树的文件名各自是**独立的随机 64 位 id**，`chatimg` ∩ `chatraw` = **0**，
`chatthumb`（`Cache_xxx_hd` 与 `Cache_xxx`）∩ `chatimg` = **0**，∩ `chatraw` = **0**。
所以 `Cache_${base}_hd` 这种"缩略图按同名去找"的猜测是错的，代码里已删掉。

**更正（2026-11-12）：`files_in_chat.db` 其实有本地路径，但对图片仍然无用。**

之前的结论"两个附带库都没有本地路径"是**错的**，原因是 SQLite 的坑：这些库的列名是纯数字，
`SELECT 45403` 会被解析成整数字面量 45403，必须写成 `"45403"`。改正后重测：

`files_in_chat_table` 共 538,453 行，`45403` 列确实是本地路径（360,270 行含 `/storage/`、
273,984 行含 `Cache_`）。但按元素类型 `45002` 拆开，没有一行同时具备"图片 + 会话 + 本地路径"：

| `45002` | 行数 | 会话 `40021` | 本地路径 `45403` | 说明 |
|---|---|---|---|---|
| 0 | 273,981 | 空 | chatimg 146,532 / chatthumb 107,466 / chatraw **19,189** | 纯缓存清单，无法归类 |
| 2 | 178,101 | 有 | **全空** | 图片消息，`45406` 是 16 字节 base64（与 md5 同长），无文件名 |
| 3 | 335 | 有 | `QQfile_recv`；`Cache_` 仅 2 行 | 文件/视频 |
| 4 | 40,216 | 有 | `ptt/*.amr` | 语音 |

`rich_media.db` 的 `file_table`（364 行）同样只有文件消息的 `QQfile_recv` 路径。

**该目录下全部数据库（2026-11-12 已全部拉到 `/tmp/opencode/db/`，`nt_msg.db` 除外——它早就在）**：
- 消息与索引：`nt_msg.db`、`group_msg_fts.db`（5.4 GB）、`buddy_msg_fts.db`、`msg_fts.db`、
  `data_line_msg_fts.db`、`discuss_msg_fts.db`、`guild_msg.db`（QQ 频道消息）
- 资料：`group_info.db`、`profile_info.db`、`gpro_v1-6_u_….db`
- 媒体/文件：`files_in_chat.db`、`rich_media.db`、`filebridge_download.db`、`filebridge_pc.db`、
  `file_assistant.db`、`nt_flash_transfer.db`
- 其它：`misc.db`、`emoji.db`、`settings.db`，以及 1 KB 空库 `collection` / `rdelivery` /
  `recent_contact` / `yffm`

逐个看 schema 的结论：`*_fts` 全是**纯文本**全文索引（`41701`–`41707` 文本列 + 会话 + msgid），
不可能有文件名；`guild_msg` 是频道；其余是配置/表情/文件互传。`gpro`、`file_assistant`、
`nt_flash_transfer` 用本机的 md5key 解不开（另有密钥）。**没有一个库能多归类一张图片**，
所以不必再花时间找库；要提升覆盖率只能走内容 md5 之外的路线（感知哈希或按时间邻近匹配）。

**实现（已合并）**：
- `NtdbTool`：定位 `nt_msg.db`、从厂商头推导 key、把 APK assets 里的 arm64 读取器落地执行。
- `MediaHashIndex`：对 `chatraw` 算内容 md5，按 (size, mtime) 增量缓存，一次 `md5sum` 批量。
- `ConversationIndex`：只存设备上确实存在的 md5 → peer（`INSERT OR IGNORE` + 更早 msgid 才覆盖，
  同一张图被转发多次时归到**最早**那个会话），并从 `group_info.db`/`profile_info.db` 取名。
- `ConversationIndexer`：把三步串起来（哈希 → 扫消息 → 取名）。
- 图库排序菜单新增"会话"分组，组头显示群名/昵称，未索引的图归入"未分类"。

**消息自带的本地路径（`f45403`）——已实现，但实测价值≈0**：
图片/文件元素里有一个 `45403` 字段，形如 `::NTOSFull::/storage/.../chatimg/0e6/Cache_<id>`，
是**发送端**写下的本地缓存路径（2026-11-11 用一条自己发的消息验证：路径指向的文件大小
与 `45405` 完全一致，确认可用）。全库 `--pics`（2,382,909 行）统计：
**只有 250 行**带这个字段，其中 249 行指向 `QQfile_recv`/`ptt`/`SendCache` 等本机不存在的目录，
指向图片缓存且文件确实在本机的只有 **1 行**。原因：QQ 只在"以文件形式发送"时写本地路径，
普通图片（`45002=2`）不带。因此它只能在"文件确实存在于本机缓存"时采信，作为 md5 路线的
补充（代码里已按本地 base 集合校验，其余一律丢弃），不要指望它提高覆盖率。

## 语义 / 向量搜索 — 评估后暂不做（2026-10-16 记）

- 需要 ONNX Runtime + MobileCLIP/CLIP（int8 约 35–45 MB）+ 文本编码器
- 索引：每图 512 维，16.5 万张约 **169 MB**；检索用暴力点积约 30–80 ms，够快
- 瓶颈同样在建库：每张过一遍 ViT，CPU 上 150–300 ms → 又是 7 小时量级
- 收益存疑：聊天图的主要信息是**文字**而不是画面内容，"想找那张图"多数能被数据库搜索或 OCR 解决