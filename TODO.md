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
**文件名算法已经确定**，消息 md5 可以直接算出文件路径：

```
crc64("chatimg:" + md5) → hex → "Cache_" + hex      文件名
hex 的最后 3 个字符                                桶目录名
路径 = chatpic/chatimg/<桶>/<文件名>
```

即 `chatpic/chatimg/ab7/Cache_ab7` 这种形式（桶目录共 4096 个，十六进制 3 位）。
查聊天记录 → 拿到 md5 → 算出文件名 → 直接定位到图。**这条路的性价比远高于 OCR**，二期本来就要做。

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

## 语义 / 向量搜索 — 评估后暂不做（2026-10-16 记）

- 需要 ONNX Runtime + MobileCLIP/CLIP（int8 约 35–45 MB）+ 文本编码器
- 索引：每图 512 维，16.5 万张约 **169 MB**；检索用暴力点积约 30–80 ms，够快
- 瓶颈同样在建库：每张过一遍 ViT，CPU 上 150–300 ms → 又是 7 小时量级
- 收益存疑：聊天图的主要信息是**文字**而不是画面内容，"想找那张图"多数能被数据库搜索或 OCR 解决