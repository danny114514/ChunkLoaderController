# ChunkLoaderController

[![Build](https://github.com/danny114514/ChunkLoaderController/actions/workflows/build.yml/badge.svg)](https://github.com/danny114514/ChunkLoaderController/actions/workflows/build.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

**Folia 1.20.1 的区块加载控制器**：按**单个区块**设置弱加载 / 强加载，带权限、配额、审计与箱子 GUI。

> ⚠️ **只支持 Folia 1.20.1。** 在 Paper/Spigot 上会主动拒绝启动（[原因见下](#design-notes)）。

---

## 它解决什么问题

Folia 把区块加载与 tick 变成了分布式的东西。如果你想「让这块地一直活着」，原版只有两个选择：永远站在那里，或用 `/forceload`（全局强制加载、写世界数据、不可按玩家配额管理）。

本插件给你第三种：**按块、按人、带配额、可审计**的加载控制。

- **强加载（31）** —— 实体刻与方块刻全跑：刷怪塔工作、掉落物会消失、村民会交易
- **弱加载（32）** —— 只跑方块刻：作物生长、熔炉冶炼、漏斗传输正常，**但实体冻结**
- **未控制** —— 插件什么都不做，交给原版

---

## 插件设计「诚实声明」

**这五条是设计后果，不是 bug。** 读完才知道它能不能解决你的问题。

### 1. 弱加载在玩家视距内**不会生效**

原版玩家自身的 ticket 已经在视距内给出 31/32/33。你设的 32 与它取 `min`，所以**玩家站在旁边时看不出区别**。

弱加载的真实作用是 **保活**：你离开之后区块继续算方块刻。种地、烧矿、漏斗传输发生在你不在场的时候——那才是它有用的场景。

### 2. 强加载会**自动**把四邻变成弱加载，实际负载远大于设置数

| 你设的 | 实际已加载 | 实体刻 | 方块刻 | 仅加载 |
|---|---|---|---|---|
| 1 格 弱(32) | 5 | 0 | 1 | 4 |
| 1 格 强(31) | **13** | 1 | 4 | 8 |
| 3×3 强 | 37 | 9 | 12 | 16 |
| 45 格 强（满配额） | **105** | 45 | 28 | 32 |

`max-strong-chunks: 9` 实际意味着约 **117** 个区块被加载。配额按插件的**设置数**计，`/cl admin status` 报告**实测**值。

### 3. 「未控制」的格子在视距内**本来就是** 31/32/33

那不是插件在偷偷加载，是原版在正常工作。GUI 里如实显示实际等级。

### 4. ticket 增删后等级**不会立刻变化**

`delay-chunk-unloads-by: 10s` + 每 tick 卸载上限 + Folia 的 `UNLOAD_COOLDOWN`（约 5 秒）。看到 `正在应用…` 是正常的，等几秒。

### 5. 弱加载依赖 **NMS**，而目标版本已停止更新

强加载走公开 API（`World#addPluginChunkTicket`，31 级），稳定。
弱加载（32 级）在 Folia 1.20.1 **没有公开 API**，只能反射 `ChunkHolderManager#addTicketAtLevel`。

Folia 1.20.1 最后构建于 2023-09-11，`ver/1.20.1` 分支已删除——**不会再有新构建破坏它，但也不会有修复**。换了服务端构建（自己编译或用非官方 build）先跑：

```
/cl admin selftest
```

它会真的加一个 32 级 ticket、读回实际等级、再移除。自检失败时插件**不会崩溃**：弱加载在 GUI 中置灰并给出原因，强加载照常可用。

---

## 安装

1. 前置：**Folia 1.20.1**（build 17 实测通过）、Java 17+
2. 把 `ChunkLoaderController-<version>.jar` 放进 `plugins/`
3. 启动服务端
4. **立刻执行 `/cl admin selftest`** 确认弱加载可用
5. 按需改 `plugins/ChunkLoaderController/config.yml`，`/cl admin reload`

SQLite 驱动由服务端自带（`org.xerial:sqlite-jdbc`，在 `libraries.list` 里），插件不打包驱动。

---

## 命令

| 命令 | 权限 | 说明 |
|---|---|---|
| `/cl` | `chunkloader.use` | 打开周围区块地图（GUI） |
| `/cl info` | `chunkloader.use` | 脚下区块：持有者 / 意图 / 实际等级 / 配额 |
| `/cl list [页]` | `chunkloader.use` | 自己受控的区块 |
| `/cl set <weak\|strong\|none>` | `chunkloader.use` | 设置**脚下**区块 |
| `/cl release all` | `chunkloader.use` | 释放自己在本世界的全部区块 |
| `/cl admin list <玩家>` | `chunkloader.admin.others` | 查看他人受控区块 |
| `/cl admin set <玩家> <x> <z> <状态>` | `chunkloader.admin.others` | 直接设置（方块坐标） |
| `/cl admin release <玩家\|all>` | `chunkloader.admin.others` | 撤销 |
| `/cl admin status` | `chunkloader.admin.others` | 用量、实测加载数、**受控区块实测等级** |
| `/cl admin selftest` | `chunkloader.admin.others` | **实测 ticket 是否真正生效** |
| `/cl admin log [玩家] [页]` | `chunkloader.admin.log` | 审计日志 |
| `/cl admin reload` | `chunkloader.admin.reload` | 重载配置（不追溯已有记录） |

**没有状态机**：所有命令一次进、一次出，不需要 `confirm` 之类的多轮交互。

---

## GUI

### 视图 A · 周围区块地图

```
9 列 × 5 行 = 45 个区块格（你所在的区块在正中央），底部 1 行信息栏
```

- 格子名称 = **绝对区块坐标**，如 `[2, 25]` —— 与 F3 的 `Chunk:` 行直接对照
- Lore 里有该区块的方块坐标范围
- 中心格带 `▶ 你在这里`
- **左键升级**：未控制 → 弱加载 → 强加载
- **右键降级**：强加载 → 弱加载 → 未控制（降到底 = 释放）
- 名称 + 材质 = **你的设置**（稳定，不闪）
- Lore 第一行 = **实际等级**（运行时真实值）

**为什么只能操作视野内的区块**：这是防远程圈地的**有意设计**。区块加载要消耗 CPU，允许远程指定坐标就等于允许任何人在几分钟内占满地图。

### 视图 B · 我的受控区块

列出你已控制的区块，**可以远程改状态或释放**，但**不能远程新增**——新增必须走到那里打开地图。

---

## 权限

只有 4 个节点，纯 LuckPerms，没有父子层级：

| 节点 | 默认 | 作用 |
|---|---|---|
| `chunkloader.use` | 所有人 | 打开 GUI、控制自己的区块 |
| `chunkloader.admin.others` | OP | 查看/修改/撤销他人区块、status、selftest |
| `chunkloader.admin.log` | OP | 审计日志 |
| `chunkloader.admin.reload` | OP | 重载配置 |

**强加载不需要额外权限节点**——配额才是限制手段。

---

## 配置

```yaml
max-total-chunks: 45              # 每玩家每世界受控总量
max-strong-chunks: 9             # 每玩家每世界强加载（≈实体刻配额）
global-max-total-chunks: 500     # 全服跨世界总量
global-max-strong-chunks: 100    # 全服跨世界强加载
global-loaded-warn-threshold: 300  # 实测已加载数超此值只告警
release-on-logout: false         # 下线后保留（永久）
auto-release-idle-days: 0        # 0 = 不自动释放，只能管理员手动
log-retention-days: 90           # 审计日志保留
```

**配额收紧不追溯**：把 `max-total-chunks` 从 45 改成 20，已控制 45 个的玩家仍保留全部，只是不能再新增。`/cl list` 顶部会给超额警告。释放永远是人工操作。

---

## 彩蛋

有一个闲聊彩蛋。**触发词不在文档里** —— 写出来就不叫彩蛋了，留给玩家自己去撞。

管理员需要知道的只有这些：

```yaml
easter-egg:
  enabled: true            # false = 完全关闭
  mode: lightning          # lightning = 真实 | effect = 只有视觉
  cooldown-seconds: 5      # 防止连续触发
```

- 默认是**真实**模式。不想承担这个风险就把 `mode` 改成 `effect`
- 改完 `/cl admin reload` 立刻生效


---

## 数据

全部在 `plugins/ChunkLoaderController/data.db`（SQLite）：

- `chunk_control` —— 谁控制哪个区块、什么状态。**删除行 = 未控制**
- `audit_log` —— 每次操作、拒绝原因。默认保留 90 天

备份 = 复制这一个文件（连同 `-wal` / `-shm`）。重启后按 `chunk_control` 全量重建 ticket。

---

## 构建

### 需要什么

- **JDK 17+**（用 toolchain，装 21 也能编出 Java 17 字节码）
- 网络能访问 `repo.papermc.io`（拉 `folia-api`，约 2 MB）

### 怎么编

```bash
git clone https://github.com/danny114514/ChunkLoaderController.git
cd ChunkLoaderController
./gradlew build          # Windows: gradlew.bat build
```

产物：`build/libs/ChunkLoaderController-<version>.jar`

| 任务 | 作用 |
|---|---|
| `./gradlew build` | 编译 + 测试 + 打包 |
| `./gradlew jar` | 只打包 |
| `./gradlew test` | 只跑单元测试（不启动服务端） |
| `./gradlew javadoc` | 生成 API 文档到 `build/docs/javadoc` |
| `./gradlew printBuildInfo` | 打印 JDK/Gradle 版本，排查「别人能编我不能」 |
| `./gradlew clean` | 清理 |

> `build.gradle` 里用了 configuration cache，若你的 Gradle 版本更老（< 8.1）
> 请在 `gradle.properties` 中删掉 `org.gradle.configuration-cache=true` 那行。
>
> 如果工程路径含中文，`javadoc` 任务已经通过 `-J-Dsun.jnu.encoding=UTF-8`
> 处理过了 —— 不设置这个参数，javadoc 会用平台默认编码读 `@argfile`，
> 中文路径会变成乱码并报「找不到文件」。

---

## 开发

### 代码结构

```
com.danny.chunkloader
├── ChunkLoaderPlugin      启动、装配、生命周期
├── config/PluginConfig    配置快照（不可变）
├── model/                 ChunkState / ChunkControl / ChunkKey（不可变值对象）
├── storage/               SQLite：Database / 两个 DAO / 审计枚举
├── service/               ChunkControlService（校验→执行→审计）/ QuotaService（四层配额）
├── ticket/                NmsBridge（唯一的 NMS 反射入口）/ TicketController
├── command/               路由 / PlayerCommands / AdminCommands / CommandSupport
├── gui/                   ChunkMapGui / MyChunksGui / GuiListener / GuiItems
│                          SessionStore（会话簿记，纯逻辑、可单测）
├── platform/FoliaSupport  Folia 环境检测
└── selftest/SelfTest      8 项自检
```

### 设计要点

- **数据库是事实来源，ticket 是投影**：先写库再动 ticket。库里有时 ticket 可能没生效（重启全量重建会修复），反过来则无处可查。
- **所有 NMS 反射集中在 `NmsBridge`**：一个类出问题，降级范围可控。
- **按类型而非名字定位反射成员**：spigot 映射把 `chunkSource` 压成 `k()`、`distanceManager` 压成 `c`，这些单字母名没有语义保障。
- **GUI 与命令共用同一入口**：配额/状态校验在 `service` 层，两个入口的行为不会不一致。
- **GUI 会话用 `SessionStore` 管理，且与界面实例绑定**：这条单独列出来，因为它是本项目踩坑最多的地方。



---

<a id="design-notes"></a>
## 为什么拒绝在非 Folia 上启动

本插件依赖三个 Folia 特有前提：

1. ticket 增删的 **MT-safe** 保证（Folia commit `99429cee`）
2. region 线程模型（`RegionScheduler` / `GlobalRegionScheduler`）
3. 反射链针对 Folia 的 `ChunkHolderManager` 结构

在 Paper 上反射可能「碰巧能跑」，但线程模型完全不同——结果会是偶发、难复现的数据竞争。**直接拒绝启动比带病运行好。**

---

## 许可

[MIT](LICENSE)。自用插件，欢迎 issue / PR。
