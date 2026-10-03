# StarLab — 低轨卫星互联网仿真平台

> 数字孪生卫星巡检平台的计算内核：真实 TLE 星库 → SGP4 传播 → 星地链路 → 同频干扰 → 切换策略 → 过境窗口表
> 每一层都有黄金数据断言与全量基准测试背书，"先有断言，再有平台"

![](./docs/screenshot-globe.png)

## 项目定位

StarLab 是一个**低轨卫星互联网（LEO）端到端仿真平台**，覆盖从轨道力学到通信链路再到巡检排班的完整技术栈。项目起源于对 Starlink 概念股热潮的逆向思考——没人讲清楚 STCN 切换到底在切什么、ISL 拓扑怎么构建、C/I 载干比什么时候恶化。

连载期间（2026-09 ~ 2026-10）项目完成了从"演示级 Walker 星座"到"工程级真实星库"的升级：10,681 颗真实 Starlink 卫星入库，全部核心数字由可一键重跑的基准测试钉死，九章连载的每一个结论都能在 CI 里复现。孪生闭环的轨道层已经打通：预测侧定期与 CelesTrak 发布的真实星历对表，模型误差不再是自己跟自己比。

技术栈：

| 层 | 技术 |
|----|------|
| 后端 | Java 21 / Spring Boot 3.2 / WebFlux / Maven |
| 前端 | Vue 3 / TypeScript / Cesium / ECharts / Vite |
| 物理 | 开普勒解析解 + SGP4-lite (J2 + BSTAR secular) |
| 链路 | ITU-R P.618 雨衰 / ITU-R P.834 折射 / 自由空间损耗 / C/I 载干比 |
| 路由 | Dijkstra 最短时延 + 两跳转发 |
| 质量门 | 黄金数据断言（Python 生成 + Java 比对）+ 7 个全量基准测试 |

---

## 核心功能

### 1. 轨道传播（可插拔）

- **Walker 星座模式**：开普勒解析解，适合架构验证和快速可视化
- **SGP4 TLE 模式**：J2 摄动 secular + BSTAR 阻力 secular，实测吞吐 **0.4 µs/颗·步**

```java
// OrbitalElements.hasDragTerms() 自动路由
if (el.hasDragTerms()) {
    return sgp4.propagate(el, time);   // TLE 导入 → SGP4
}
return propagateKepler(el, time);       // Walker 星座 → 开普勒
```

**API**：`POST /api/orbit/propagate` 输入 ISS 真实 TLE → 返回 altitude 426 km（实际 416 km）

### 2. TLE 星库管道

`TleCatalogService` 五道防御关卡：解析、去重、校验、拒绝计数、 BSTAR 兼容处理——**12% 的 Starlink 卫星是负 BSTAR**（`"0." + 负尾数` 写法，朴素解析会 NumberFormatException），已正确修复。真实星库 10,681 颗解析 0 拒绝 0 重复。

### 3. STCN 地面站切换（3 种策略）

| 策略 | 算法 | 触发条件 |
|------|------|----------|
| v1 贪心 | 最高仰角优先 | 最高仰角站 ≠ 当前站 |
| v2 预测式 | 预判未来 60s 可见窗口 | 当前仰角 < 15° |
| v3 干扰感知 | C/I 载干比优先 | 当前 C/I < 9dB 或新站 C/I 高 2dB |

三算法可同时跑同参数对比，输出 handoverCount / avgLatency / linkBreakCount 并排。真实星库基准：greedy 断链 11,903 vs predictive 11,753，切换时延 ~12 ms 量级。

### 4. 干扰模型

- **同频干扰**：非相干功率线性求和
- **邻信道干扰（ACI）**：ACIR = 30 dB 抑制
- **宽带噪声底**：-174 dBm/Hz + 10log₁₀(B) + NF
- **链路可用性**：C/I > 9 dB **且** SNR > 3 dB **且** 视在仰角 > 10°（含 ITU-R P.834 折射修正）

真实星座普查的核心发现：单信道假设下每站同频邻居中位 205 颗，C/I 中位 **−25.2 dB**（干扰高出噪声底 55 dB）——系统是干扰受限，不是噪声受限。

### 5. 星间链路（ISL）

- 物理可见性：ECEF 距离 + cone 法地球遮挡判定
- 同面 ISL：跳过 LOS 检查（Starlink 工程实践——预规划 + 相控阵锁定）
- 拓扑发现：距离阈值内所有卫星对
- Dijkstra 最短时延路由：地面站 → 多跳 → 卫星，逐段明细

### 6. 过境窗口表

24h × 10,681 颗 × 5 站端到端生成（tick 30s，视在仰角门限）：**31.3 秒**产出 233,422 个过境窗口（46,684 窗/站/天），每站覆盖 100%、最坏空窗 0 分钟。窗口时长中位 6.5 min、max 9.0 min 恰好贴住 550 km 低轨几何上限——分布形状本身就是正确性验证。

### 7. 孪生闭环（TLE 遥测对表）

数字孪生区别于"仿真玩具"的下半段：**让模型跟现实对表**。CelesTrak 每 2 小时发布基于真实观测的新 TLE（GP 数据），以"旧快照 TLE 预测 vs 新发布 TLE 实测"的差作为传播模型的真实误差：

```
POST /api/twin/snapshots/fetch?group=starlink   # 拉取当前星历存快照
POST /api/twin/snapshots/upload                 # 上传 TLE 文本存快照（离线场景）
GET  /api/twin/snapshots                        # 快照列表
POST /api/twin/reconcile?snapshot=&group=       # 对表 → 报告落盘
GET  /api/twin/report/latest                    # 最近一次对表报告
```

**实测报告（2026-10-04，快照 starlink-2026-10.tle vs CelesTrak 实时目录）**：

| 指标 | 数值 |
|------|------|
| 匹配卫星 | 10,679 / 10,681（快照中 2 颗已从目录消失——退役/再入被对表抓出） |
| TLE 龄期 | 中位 3.91 天，max 6.48 天 |
| 位置误差 | p50 = 31.6 km，p90 = 140.5 km，p99 = 3328.8 km |
| 龄期-误差趋势 | p50：30.3 km（2-3d）→ 43.2 km（5-7d），约 6~8 km/天 |
| 离群星 | 10 颗误差 6,200~13,600 km——升轨/离轨机动星，归因一目了然 |

**校准结论**：25 km 告警门限在 p90 口径下任何龄期都达不到（p99 被机动星污染），平台的可执行策略是**逐日全量刷新 + 机动星白名单**，p50 ≈ 30 km 对 10° 仰角门限的可见性判定足够。

---

## 基准测试与黄金数据

项目的可信度建立在"每个数字可断言、可复现"上：

| 测试类 | 内容 | 关键数字 |
|--------|------|---------|
| `Sgp4GoldenDataTest` | 黄金数据比对（`tools/golden_sgp4.py` 生成参考值） | 传播误差断言 |
| `TleCatalogServiceTest` | 星库管道防御关卡 | 10,681 颗 / 12% 负 BSTAR |
| `HandoverBenchmarkTest` | 1,075 万次链路计算 + 三策略对决 | 在线率 7.03%，43% 单周期零可见 |
| `InterferenceBenchmarkTest` | 干扰普查 + 生产版黄金校验 | C/I 中位 −25.2 dB，需 2,624 正交信道 |
| `FrequencyReuseBenchmarkTest` | 频率复用七档阶梯 | 随机 200 信道天花板 36.37% |
| `BeamIsolationBenchmarkTest` | 相控阵空分三口径 | 随机 200 × 波束 98.75% |
| `PatrolWindowBenchmarkTest` | 24h 巡检窗口表端到端 | 31.3 s / 233,422 窗 |

**跑法**：基准测试标注 `@Disabled`（全量扫描分钟级，不进日常构建）。手动运行需临时注释 `@Disabled`，执行后恢复：

```bash
mvn test -Dtest=PatrolWindowBenchmarkTest
```

黄金数据方法论：`tools/golden_sgp4.py` 用独立实现生成 SGP4 参考轨迹，`Sgp4GoldenDataTest` 逐点比对 Java 实现——两个独立实现互相咬合，误差超标即构建失败。

---

## 快速启动

```bash
# 后端
cd starlab
mvn spring-boot:run        # http://localhost:8090

# 前端
cd starlab/frontend
pnpm install
pnpm dev                   # http://localhost:5174
```

### TLE 导入示例

```bash
# 单颗传播
curl -X POST http://localhost:8090/api/orbit/propagate \
  -H "Content-Type: application/json" \
  -d '{
    "name": "ISS (ZARYA)",
    "line1": "1 25544U 98067A   26266.50000000  .00019378  00000+0  33590-3 0  9990",
    "line2": "2 25544  51.6439  26.6697  0007045  65.9786  13.4740 15.50209478451577"
  }'

# 批量替换星座
curl -X POST http://localhost:8090/api/constellation/import-tle \
  -H "Content-Type: application/json" \
  -d '{"tles": [ { "name": "...", "line1": "...", "line2": "..." } ]}'
```

---

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    Vue3 + Cesium 3D                      │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐   │
│  │ STCN 面板 │ │ 干扰面板  │ │ ISL 面板  │ │ C/I 曲线 │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘   │
└──────────────────────────┬──────────────────────────────┘
                           │ REST JSON
┌──────────────────────────▼──────────────────────────────┐
│              Spring Boot 3.2 / WebFlux                   │
│  ┌─────────────┐ ┌──────────────┐ ┌─────────────────┐  │
│  │ 3 STCN 策略  │ │ 干扰计算器     │ │ ISL 路由器       │  │
│  └─────────────┘ └──────────────┘ └─────────────────┘  │
│  ┌────────────────────────────────────────────────────┐  │
│  │  OrbitPropagator (策略委派)                         │  │
│  │  ├─ Kepler (Walker 星座, 无 BSTAR)                 │  │
│  │  └─ Sgp4Propagator (TLE 导入, 有 BSTAR)            │  │
│  └────────────────────────────────────────────────────┘  │
│  ┌────────────────────────────────────────────────────┐  │
│  │  TleCatalogService (五道防御关卡)                    │  │
│  │  → TleParser → OrbitalElements (bstar/ndot/nddot)  │  │
│  └────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

### 设计决策

| 决策 | 理由 |
|------|------|
| 策略模式实现 STCN | v1/v2/v3 算法差异大（贪心 vs 预测 vs C/I 优先），策略模式让对比 API 只需 `engine.compareThree(s1, s2, s3, ...)` |
| 传播器可插拔 | Walker 星座开普勒足够快，TLE 真实数据需要 SGP4，统一上层接口 |
| ISL 同面跳过 LOS | Walker 12/3/1 同面 meanAnomaly 差 90° > 遮挡阈值 46°，但 Starlink 同面 ISL 预规划 + 相控阵锁定 |
| SGP4-lite 而非完整 Vallado | 14 阶共振项对 near-earth 可忽略，24h 误差 < 3 km 满足仿真可视化；0.4 µs/颗·步让全星座扫描按毫秒计 |
| 基准测试 `@Disabled` | 全量扫描分钟级，不拖累日常构建；数字只在被质疑时重跑，重跑必咬合 |
| 干扰感知用"每 tick 摊平"版 | 生产版 O(N²) 单次决策 2.9 ms × 256 万次 ≈ 2.1 h 不可行；摊平版黄金校验 36/36 决策一致、C/I 偏差 0.0000 dB |

---

## 物理模型参考

- SGP4 算法：Vallado AIAA 2006-6753 "Revisiting Spacetrack Report #3"
- 雨衰：ITU-R P.618-13
- 大气折射（视在仰角）：ITU-R P.834 简化
- J2 摄动系数：`J2 = 1.08262668 × 10⁻³`
- WGS84 椭球：`XKMPER = 6378.137 km`, `f = 1/298.257223563`
- 光速：`299792.458 km/s`

---

## 项目结构

```
starlab/
├── src/main/java/com/starlab/
│   ├── api/                      # REST Controllers（轨道/切换/干扰/ISL/两跳路由/TLE 导入）
│   ├── config/SimConstants.java  # 物理常量
│   ├── constellation/            # Walker 生成器 + 星座管理器
│   ├── handover/                 # STCN 策略接口 + 3 种实现 + 事件 + 仿真引擎
│   ├── link/                     # 链路计算 + 干扰 + ISL + 地面站
│   ├── orbit/                    # 轨道传播 + SGP4 + TLE 目录服务/解析 + 坐标转换
│   ├── twin/                     # 孪生闭环：CelesTrak 现实源 + 快照存储 + 对表服务
│   └── route/                    # 两跳路由
├── src/test/java/com/starlab/
│   ├── orbit/                    # Sgp4GoldenDataTest（黄金断言）、TleCatalogServiceTest
│   ├── handover/                 # HandoverBenchmarkTest（切换基准）
│   ├── twin/                     # TwinLoopReconciliationTest（自对表 0 误差 + live 对表）
│   └── link/                     # 干扰/频率复用/相控阵/巡检窗口 4 个基准
├── src/test/resources/tle/       # starlink-2026-10.tle（10,681 颗真实星库）
├── tools/
│   ├── golden_sgp4.py            # SGP4 黄金数据生成器（独立实现）
│   └── iss.tle
├── frontend/                     # Vue 3 + Cesium 3D 前端（Scene3D.vue 主界面）
└── docs/
    ├── 连载/                     # 九章技术连载源稿（见下）
    └── screenshot-*.png
```

## 技术连载（docs/连载/）

九章连载记录了本平台从零到全星座真实数据的全过程，每章对应一次基准提交：

| 章 | 标题 | 交付物 |
|----|------|--------|
| 01 | 开篇：为什么用 Java 仿真 4000 颗卫星 | 问题定义 |
| 02 | 开普勒六参数，30 分钟讲透 | 轨道力学基础 |
| 03 | SGP4-lite：把误差变成 CI 断言 | 黄金数据体系 |
| 04 | 一万颗真卫星进平台：TLE 目录管道 | 五道防御关卡 |
| 05 | 地面站才是稀缺资源：真实星座切换仿真 | 在线率 7.03% |
| 06 | 205 个同频邻居，C/I 是负的 | 干扰普查基准 |
| 07 | 频率复用实验：随机完爆轨道簿记 | 七档阶梯基准 |
| 08 | 相控阵：把偏轴的 205 个邻居抹掉 | 空分基准 |
| 09 | 把三小时压成三十秒：巡检窗口表与全专栏总盘 | 端到端窗口表 |

### 核心数字总盘

| 章 | 问题 | 一句话答案 | 背书数字 |
|----|------|-----------|---------|
| 03 | 根数怎么变成位置 | SGP4-lite 传播 + 黄金数据断言 | 0.4 µs/颗·步 |
| 04 | 星库从哪来、凭什么信 | TLE 目录服务，五道防御关卡 | 10,681 颗入库，12% 负 BSTAR |
| 05 | 谁能看见谁 | 视在仰角定可见，单星在线率很低 | 在线率 7.03%，单周期 43% 零可见 |
| 06 | 同频邻居有多狠 | 干扰高出噪声底 55 dB | C/I 中位 −25.2 dB |
| 07 | 频率能救多少 | 轨道簿记不如随机均匀分配 | 复用天花板 36.37%（200 信道） |
| 08 | 空间还能救多少 | 波束 + 重指两道闸门相乘 | 98.75%（随机 200 × 波束） |
| 09 | 平台每天靠什么排班 | 31 秒生成 23 万窗口的过境表 | 覆盖 100%，最坏空窗 0 min |

---

## 限制与已知问题

| 问题 | 状态 |
|------|------|
| SGP4-lite 跳过长周期共振项 | 对 LEO 精度足够，GEO/中轨道需补 SDP4 |
| O(n²) 干扰/ISL 扫描 | 10,681 颗全量扫描秒级可接受，万星以上需空间索引 |
| Doppler 频移未计入干扰 | LEO 相对速度 ~7 km/s，同频干扰频偏可达几十 kHz |
| TEME → ICRF 修正未计入 | 差异 < 1.5 arcsec，短时间仿真可忽略 |
| 相控阵方向图为理想化模型 | 结论只依赖"偏轴 20~30 dB 压制"量级事实，未建栅瓣/扫描跌落 |
| 孪生闭环已通轨道层 | TLE 对表可用（见功能 7）；站级遥测闭环（真实 AOS/LOS/SNR 对表）待真实地面站数据 |

---

## 路线图

- [x] TLE 批量导入 + Starlink 真实星库实测（10,681 颗）
- [x] 同频干扰建模 + 频率复用阶梯实验
- [x] 相控阵空分模型 + 频率×空间复合增益
- [x] 24h 巡检窗口表端到端生成
- [x] 孪生闭环（轨道层）：TLE 快照对表 + 龄期-误差归因 + 刷新策略
- [ ] 补 SDP4 深空传播（周期 > 225 min）
- [ ] Doppler 同频干扰建模
- [ ] O(n²) → 空间索引优化
- [ ] 孪生闭环（站级）：预测窗口/SNR vs 真实地面站遥测对表

---

## License

Apache License 2.0
