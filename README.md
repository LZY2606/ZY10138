# 水文站率定曲线离线管理系统

一个**离线**运行的率定曲线（水位 → 流量）管理与派生服务：管理原始实测点、分段率定曲线、
涨/落分支、生效时间与修订理由；把原始水位序列派生为流量序列；并在改动曲线前预览
哪些历史日流量会变化。

- 服务端：Kotlin（JDK 内置 HTTP Server，无外部 Web 容器）
- 存储：SQLite（单文件，发布/重算均为数据库事务）
- 前端：原生 HTML/CSS/SVG/JS 单页（无构建步骤）
- 构建：Maven

## 安装

```bash
mvn -q -DskipTests package
```

## 演示

```bash
mvn -q test && mvn -q exec:java -Dexec.mainClass=app.MainKt -Dexec.args='--port 5338'
```

然后打开 <http://127.0.0.1:5338>。

首次启动会在 `data/rating.db` 写入一套确定性演示数据（可用 `--db 路径` 修改，
`--no-seed` 跳过演示数据）：

- 站点 `ST01`（涨落判定死区 ±0.02 m）；
- 41 个原始实测点，其中 2 个明确受回水顶托、默认排除；
- 序列 `daily`：40 个、每 6 小时一个水位，含 1 个**原始缺测**与 1 个**超出标定范围**的水位；
- rev1 已发布的 `common` 两段二次曲线（自序列起点持续生效），并已用固定作业 ID 完成一次生产重算；
- rev2 `rising/falling` 分支草稿（未发布，不进入生产）。

## 分段数学

每一段是水位的多项式，用 Horner 法求值：

```
Q(h) = c0 + c1*h + c2*h^2 + ...
```

每段带有自己的水位区间 `[levelLow, levelHigh]`，区间端点各自声明**开（open）/闭（closed）**。

- 命中判定：`low` 闭则含下界、`high` 闭则含上界。
- 同一分支的相邻段必须**恰好接续**：公共端点的两个端点开闭必须互斥（XOR）。
  - 左段上端 **闭**、右段下端 **开** ⇒ 端点只属于左段；
  - 左段上端 **开**、右段下端 **闭** ⇒ 端点只属于右段（演示数据采用这一种）。
- 两端皆闭会在该水位**重复命中**；两端皆开会在该水位留下**单点缝隙**；
  区间不重合且留有正长度间隔则是**缝隙**；区间交叠则是**重叠**。
  这四类问题在保存/发布前都会被拒绝（服务端为权威，前端做同构提示）。
- 分支集合只允许两种形态：`{common}`，或 `{rising, falling}`，不允许只有单个涨落分支，也不允许混用。

拟合（`POST /api/curves/fit`）对**未排除**的实测点做最小二乘（正规方程 + 部分主元高斯消元），
只返回新段的系数，**永不改写任何原始实测点**。阶数 0–4，且点数必须大于阶数以防数值退化。

## 分支判定

水位序列逐点派生时，用当前水位与“上一个存在且有限的水位”之差判断水势：

- `Δh > 死区` ⇒ `rising`，`Δh < -死区` ⇒ `falling`；
- `|Δh| ≤ 死区` 或序列开头/前值缺失 ⇒ **分支无法判定**（`branch_unknown`），
  绝不擅自归入某一支，更不补零；
- 缺测点不会更新“上一有效水位”，因此不会污染后续点的方向判断。

`common` 曲线不需要方向；`rising/falling` 曲线必须先能判定方向。

## 时间语义（半开区间）

生效期统一为半开区间 **`[start, end)`**：`start` 闭合、`end` 开放，`end` 缺省为 `+∞`。
一个时刻 `t` 命中曲线当且仅当 `start ≤ t < end`，因此相邻两份曲线在交接时刻**恰好一份**生效，
无重叠、无空档。数据库用 `effective_period` 上的 `BEFORE INSERT/UPDATE` 触发器拒绝重叠区间。

发布（`POST /api/curves/publish`）是一个**原子事务**：

1. 校验曲线存在、为草稿、分段合法；
2. 若当前开放区间 `[x, +∞)` 覆盖 `start`，将其收束为 `[x, start)`；
3. 插入新生效期 `[start, end)`；
4. 把曲线置为 `published`。

任一步失败整体回滚。已发布曲线**永不就地改写**：再次保存会生成单调递增的新 revision，
旧曲线与其生效期保持不变，因此**旧发布版本仍能按当时曲线逐点复现**历史派生。

## 质量标志（互斥，绝不补零）

派生点的 `quality` 恰为下列之一；非 `ok` 的 `discharge` 恒为 `NULL`：

| 标志 | 含义 |
| --- | --- |
| `ok` | 正常，`discharge` 有有限值 |
| `missing_level` | 原始水位缺失或非有限（`NULL`/`NaN`） |
| `out_of_range` | 水位落在该分支所有分段的标定范围之外 |
| `branch_unknown` | 涨/落曲线无法判定分支（开头、前值缺失、死区内） |
| `no_active_curve` | 该时刻没有任何已发布曲线生效 |
| `overflow` | 多项式结果非有限（溢出/系数异常） |

这些情形在数据库与页面上彼此区分，**不允许统一填 0**。

## 重算边界（发布与重算解耦）

- **预览**（`POST /api/preview`）是只读的：用候选曲线对历史水位求值，与当前生产派生逐点比较，
  返回“会改变的历史日流量”。预览**不写**派生表、不登记作业，预览版本不会偷偷进入生产。
- **发布**只改变“某时刻之后用哪条曲线”，**不**自动改动任何既有派生序列。
- 只有对**显式选择**的 `(station, seriesKey)` 调用**批量重算**
  （`POST /api/recompute`），才会重写该序列；其它派生序列保持不变。
- 重算是**原子操作**：逐点选择时刻 `t` 生效的已发布曲线求值、UPSERT 派生表、登记作业，
  全部在一个事务内；失败整体回滚。
- 重算**幂等**：相同 `jobUuid` 的重试直接返回首次作业（响应里 `retried=true`），
  **不会生成第二套序列**，也不会再插一条作业。

### 乐观并发（过期版本返回差异）

保存草稿时带 `baseRevision`。若它与服务端当前版本不一致，接口返回 **409**，并给出：

- `currentRevision`：服务端当前版本；
- `serverSegments`：服务端当前分段；
- `onlyInClient` / `onlyOnServer`：身份键为 `(branch, levelLow, levelHigh)`，分别列出
  仅本地有 / 仅服务端有的段；
- `changedInPlace`：身份键相同但系数或端点已被对方改动的段。

页面会弹出冲突面板，可一键放弃本地、采用服务端版本后再改。

## 数据不可变性

- `measured_point`（原始实测）与 `level_series`（原始水位）只接受**新增**；
  实测点唯一可改的是“是否回水排除”的标记与理由，观测值本身不可更新。
- 派生结果只写入独立的 `derived_discharge` 表，与原始表物理分离。

## HTTP 接口（节选）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/stations` | 站点列表 |
| GET/POST | `/api/points` | 实测点列表 / 新增 |
| POST | `/api/points/{id}/excluded` | 切换回水排除标记 |
| GET | `/api/curves?stationId=` | 曲线版本列表 |
| POST | `/api/curves/save` | 保存草稿（乐观并发） |
| POST | `/api/curves/publish` | 原子发布并写入生效期 |
| GET | `/api/periods?stationId=` | 生效期列表 |
| POST | `/api/curves/fit?stationId=` | 最小二乘拟合一段（不落库） |
| POST | `/api/curves/residuals` | 计算实测点残差 |
| GET | `/api/levels` / `/api/series` | 水位序列 / 序列键 |
| GET | `/api/derived` | 生产派生流量 |
| POST | `/api/preview` | 历史变化预览（只读） |
| POST | `/api/recompute` | 原子、幂等的批量重算 |
| GET | `/api/jobs/{uuid}` | 查询重算作业 |

## 测试

```bash
mvn -q test
```

覆盖（共 33 个用例）：

- `SegmentMathTest`：开闭端点的恰好接续、双闭重复命中、双开点缝隙、正长度缝隙、重叠、
  分支集合合法性、Horner 求值；
- `TimeIntervalTest`：半开生效期 `[start,end)`、相邻期不重叠、`+∞`、重叠检测；
- `EvaluationTest`：缺测、超量程、分支未知、死区、涨/落选支、溢出等**互斥**质量标志；
- `ServiceIntegrationTest`：原始数据不被改写、缺测不补零、无生效曲线、
  相同作业 ID 重试不产生第二套序列、只重算所选序列、发布不等于重算、
  交接时刻使用后一条曲线、生效期重叠被原子拒绝、非法分段不能保存/发布、
  过期版本返回 409 与差异、预览不写生产、回水点不参与拟合。

## 目录

```
src/main/kotlin/app/domain/   分段数学、曲线校验、求值、拟合、时间区间
src/main/kotlin/app/db/       SQLite schema 与 JDBC 扩展
src/main/kotlin/app/service/  站点/实测点/曲线/预览/重算服务与演示数据
src/main/kotlin/app/web/      JDK HTTP 路由与静态资源
src/main/resources/web/       曲线编辑与影响预览单页
src/test/kotlin/app/          分段端点 / 有效期 / 缺失值等测试
data/                         运行时生成的 SQLite 文件（不随构建产物分发）
```
