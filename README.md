# 水位-流量率定曲线管理系统（离线）

面向水文站的离线单机系统：管理实测水位-流量点、分段率定曲线（公共段/上涨支/下落支）、
曲线发布生效时间与修订理由，并把原始水位序列派生为带质量标志的流量序列。
原始实测数据与原始水位**只能追加、不可被拟合结果改写**。

技术栈：Kotlin（JVM 17 字节码）+ JDK 内置 HTTP Server + SQLite（`sqlite-jdbc`）+ 原生 HTML/JS 页面。
无前端构建步骤。

## 构建与运行

```bash
# 安装（跳过测试打包）
mvn -q -DskipTests package

# 演示：跑全部测试后启动服务
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5338'
```

打开 <http://127.0.0.1:5338> 。可选参数 `--db <path>`（默认 `data/rating.db`）。
首次启动会写入确定性演示数据并用 v1 已发布曲线生成初始派生序列。

## 页面能做什么

- 同页展示：实测点、曲线段（SVG 率定图）、逐点残差、历史水位/派生流量时序、
  以及质量异常点（超标定范围、分支无法判定、水位缺失、计算溢出）。
- 排除/恢复明确受回水影响的实测点（仅切换 `excluded` 标志与备注；原始水位、实测流量列不动）。
- 基于最新版本创建草稿、最小二乘二次拟合、手工调整段端点/系数/开闭、建立上涨/下落分支、
  调整分段连接点；保存前实时做分段数学校验。
- 对草稿做**只读影响预览**：逐点比较“将产生的流量/质量”与“当前生产派生”，
  高亮将改变的历史日流量；预览绝不写库。
- 填生效时间与修订理由后**原子发布**。发布不触碰任何派生序列；旧发布版本永久保留。
- 用户**显式选择时间窗**后才批量重算；窗口外派生序列不变。

## 分段数学

每段是一条升幂多项式：

```
Q(h) = c0 + c1·h + c2·h² + …
```

段的水位定义域是带**开闭端点**的区间 `[low, high] / (low, high) / [low, high) / (low, high]`，
由 `lowInclude / highInclude` 决定。

同一分支（`COMMON/RISING/FALLING` 各自独立）内，相邻段必须满足：

1. 上界水位 == 下界水位（**恰好接续**）；不等时，上界小于下界报“缝隙”，大于下界报“重叠”。
2. 接缝处开闭**互补**：上段不含则下段含，反之亦然。例如 `[a,b)` 接 `[b,c]`：
   接缝水位 `b` 恰好命中下段一次；`(a,b]` 接 `(b,c]` 同理。
3. 两端都闭 → 接缝命中两次（重复）；两端都开 → 接缝无段可命中（缝隙）。两种都被禁止。

率定单值时的分支选择：趋势已知优先对应分支（上涨→`RISING`，下落→`FALLING`），
未命中再回退 `COMMON`；趋势未知时只允许 `COMMON`。因此“公共段 + 涨落支”的组合天然无重叠。

拟合（`RatingMath.fitPolynomial`）对每个分支的**已纳入**实测点独立做二次最小二乘：
正规方程 + 列缩放 + 部分主元高斯消元；点数不足或矩阵奇异返回失败，不会写出半成品。
“规范化端点”按钮/服务端逻辑把同分支段统一为下闭上开、末段上闭。

非有限水位、非有限系数、求值产生 NaN/Infinity 一律映射为 `OVERFLOW`，**不返回零**。

## 时间语义

- 曲线发布记录只存 `valid_from`。某站在时刻 `t` 的生效曲线是
  `valid_from <= t` 的最新一条，即每条曲线占据半开区间 **`[valid_from, next.valid_from)`**。
- 新发布的 `valid_from` 必须**严格晚于**站内既有最大生效时间，数据库有唯一约束、
  应用层再校验；任何时刻最多一份已发布曲线。
- 曲线有 `DRAFT / PUBLISHED` 两种状态。**草稿永不参与生产率定**（`publishedCurveAt`
  只查发布记录），预览曲线不可能“偷偷进入生产”。发布不可变；修订必须新建草稿→发布为新版本。
- 重算时间窗同样是半开 **`[from, to)`**。
- 所有时间以 UTC ISO-8601（如 `2026-07-01T00:00:00Z`）存取与展示。

## 质量标志（互不等价，非 OK 一律不写流量值）

| 标志 | 含义 | 典型情形 |
| --- | --- | --- |
| `OK` | 正常率定，流量为有限值 | 水位落在生效曲线定义域且分支可判定 |
| `OUT_OF_RANGE` | 超出标定范围 | 水位高于曲线最高段/低于最低段，或该时刻无已发布曲线 |
| `BRANCH_UNDETERMINED` | 分支无法判定 | 与上一有效水位差值在死区内（平水），或首点/超出最大回看时长，且该水位只有涨/落支可命中 |
| `MISSING_STAGE` | 原始水位缺失 | `stage_series.stage_m` 为 NULL；不用相邻值插补，也不参与趋势差分 |
| `OVERFLOW` | 计算溢出 | 系数或求值结果为 NaN/Infinity |

趋势由相邻有效水位差分判定：`|Δh| ≤ deadband`（站点死区，演示站 0.03 m）视为平水；
缺失水位被跳过，继续向更早找有效值，但超过 `max_age_seconds` 即不判定。

## 重算边界、原子性与幂等

- **发布原子**：分段校验 → 生效时间校验 → 草稿置为已发布 → 写发布记录，全部在一个
  SQLite 事务内；校验失败整体回滚（不产生发布记录、曲线仍是草稿）。
- **批量重算原子**：登记作业 → 删除窗口 `[from,to)` 内旧派生日 → 按新规则批量写入 →
  回填统计，在一个事务内完成，对外全有或全无；窗口外行不受影响。
- 每行派生流量都记录 `curve_id` 与 `job_id`。旧发布版本保留，配合 `PUBLISHED` 模式
  （逐点取当时生效曲线）可按当时曲线完整复现历史。
- **幂等作业**：以调用方给定的 `jobId` 为主键；相同 ID 重试直接返回首次统计
  （`idempotentRetry=true`），即使请求参数不同也不删除、不重写，绝不产生第二套序列。
- 两种模式：`PUBLISHED`（逐时刻使用当时已发布曲线，用于复现）与
  `CURVE`（窗口内全程使用某一条**已发布**曲线；草稿被拒绝）。
- 新曲线发布后只影响显式选择重算的窗口/序列；未重算的历史派生保持原值与原曲线引用。

## 并发修改

- 草稿带 `rev` 令牌。保存/发布必须带 `expectedRev`；过期提交返回 `409 Conflict`，
  响应体内含服务端当前 `serverRev` 与 `serverSegments`，前端据此提示并展示差异，
  后写者的修改不会被静默覆盖。已发布曲线收到修改请求同样返回 `409`。
- 进程内写操作以互斥锁串行化，配合 SQLite 事务与 `busy_timeout`。

## 数据模型（SQLite）

| 表 | 要点 |
| --- | --- |
| `station` | 站码、名称、死区、最大回看时长 |
| `measurement` | 原始实测点；只有 `excluded/note` 可改，唯一 `(station, observed_at)` |
| `rating_curve` | 版本、状态、修订理由、乐观令牌 `rev` |
| `curve_segment` | 分支、上下界及开闭、升幂系数 JSON、段内序号 |
| `curve_publication` | `(station, valid_from)` 唯一；半开生效区间 |
| `stage_series` | 原始水位，只追加；`stage_m` 允许 NULL（缺失） |
| `discharge_series` | 派生流量 + 质量标志 + 分支 + `curve_id` + `job_id`，唯一 `(station, ts)` |
| `recompute_job` | 作业主键即幂等键，含窗口、模式、改变/写入计数 |

## 主要 HTTP 接口

- `GET /api/state`：页面全量快照（站、实测点、曲线、发布、窗口内水位/派生）。
- `POST /api/measurements/exclude`：切换实测点纳入/排除（不动原始数值）。
- `POST /api/curves/draft`、`PUT /api/curves/{id}/segments`（带 `expectedRev`）、
  `POST /api/curves/{id}/fit`、`GET /api/curves/{id}/residuals`。
- `POST /api/segments/validate`：分段数学校验（编辑时实时调用）。
- `POST /api/curves/{id}/preview`：只读影响预览。
- `POST /api/curves/{id}/publish`：原子发布（`validFrom/reason/expectedRev`）。
- `POST /api/recompute`：原子+幂等批量重算（`jobId/mode/curveId/from/to`）；`GET /api/jobs/{id}`。

## 测试

```bash
mvn -q test
```

21 个 JUnit 5 用例（内存 SQLite），覆盖：

- `SegmentEndpointTest`：开闭端点恰接、重复命中/缝隙拦截、超界与溢出、死区趋势、
  最小二乘恢复系数与奇异阵。
- `ValidityAndQualityTest`：半开有效期一时刻一曲线、草稿不进生产、生效时间严格递增、
  发布失败回滚、四类质量标志互异且不填零、缺失水位不插补。
- `RecomputeConcurrencyTest`：相同作业 ID 重试不产生第二套序列、窗口原子替换且窗外不动、
  `PUBLISHED` 模式按当时曲线复现、过期 `rev` 冲突携带服务端版本、
  已发布曲线不可改、草稿禁止生产重算、拟合/预览不改写原始数据。

## 代码结构

```
src/main/kotlin/app/
  Models.kt        枚举（分支/趋势/质量/状态/模式）与数据类
  RatingMath.kt    多项式求值、分段校验、最小二乘拟合、涨落趋势
  Db.kt            SQLite 连接、建表、事务与 JDBC 小工具
  Repo.kt          仓储：曲线/段/发布/序列读写、发布与乐观并发
  RatingService.kt 拟合、残差、只读预览、率定序列、原子幂等重算
  Seed.kt          确定性演示数据（含回水点、缺失、洪峰超界、高水平水）
  Server.kt        HTTP 路由与错误码（409/422/404/400）
  Main.kt          入口与首次启动引导
src/main/resources/web/index.html   编辑/影响预览单页
src/test/kotlin/app/                分段端点/有效期/质量标志/重算与并发测试
```
