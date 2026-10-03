---
title: StarLab 实战（四）：一万颗真卫星进平台，防御式解析与规模的第一次考试
slug: starlab-04-starlink-catalog
categories: project
---

# StarLab 实战（四）：一万颗真卫星进平台，防御式解析与规模的第一次考试

## 一、先给星库做个体检

仓库里躺了一个 1.75 MB 的文本文件：`starlink-2026-10.tle`，Celestrak 上抓的全量 Starlink 在轨目录。前三章的平台里只住着 12 颗 Walker 虚拟卫星，这一章让 10681 颗真卫星整体搬进来。搬之前先体检——拿脚本按标准算法逐组核验，报告比想象中干净，也比想象中危险：

| 体检项 | 结果 |
|--------|------|
| TLE 组数 | 10681（名称行 + line1 + line2，零残组） |
| 校验和失败 | **0** |
| NORAD 编号重复 | 0 |
| 历元分布 | 全部 2026 年，跨度 9 天（第 264~273 天） |
| 倾角（轨道壳） | 43°×3616 / 53°×4996 / 70°×709 / 97.6°×1360 |
| 轨道高度 | 166 ~ 580 km，平均 467 km |
| 负 BSTAR | **1285 颗（12%）** |

四个倾角壳就是 Starlink 的真实部署架构：43° 和 53° 是低纬度主力壳，70° 补中纬度，97.6° 极轨壳补两极覆盖。最低那颗 166 km——那是在做离轨衰减的退役卫星，平均运动 16.4 圈/天，比正常服役星快了一圈。这份数据里每一条"异常"背后都是真实世界的运行策略，这是 Walker 工厂的 for 循环永远造不出来的质感。

但体检也暴露了两个炸弹，一个在数据里，一个在我自己的代码里。

## 二、第一现场：负 BSTAR 崩了 12% 的星

第一个炸弹就是上表最后一行。TLE 第一行第 54~61 列的 BSTAR 字段，在这个文件里有 1285 个是负值，比如 `-43656-4`。这不是脏数据——Starlink 卫星装着氪工质电推进，频繁的轨道提升机动会让 SGP4 拟合出负阻力系数，这是星链目录的常态而非例外。

而我第 2 章写的 `parseExponential`，处理它的方式是当场爆炸：

```java
// s = "-43656-4"
int signPos = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));  // = 6（指数符号）
String mantissaStr = s.substring(0, signPos);                     // = "-43656"
if (!mantissaStr.contains(".")) {
    mantissaStr = "0." + mantissaStr;                             // = "0.-43656" 💥
}
return Double.parseDouble(mantissaStr + "e" + expStr);            // NumberFormatException
```

`"0." + "-43656"` 根本不是合法数字。这个 bug 在前几章从未暴露，因为 ISS 示例的 BSTAR 是正数、Walker 星座压根没有阻力项——**测试数据的单一性掩盖了实现假设的脆弱性**，直到一万条真实数据进场，12% 的星集体引爆。

修复只需要三行：把尾数可能携带的前导符号摘出来，数值上再还回去。

```java
if (!mantissaStr.contains(".")) {
    boolean negative = false;
    if (mantissaStr.startsWith("-")) {
        negative = true;
        mantissaStr = mantissaStr.substring(1);
    } else if (mantissaStr.startsWith("+")) {
        mantissaStr = mantissaStr.substring(1);
    }
    double value = Double.parseDouble("0." + mantissaStr + "e" + expStr);
    return negative ? -value : value;
}
```

顺手把 TLE 紧凑记法的全部变体做成断言表，钉死在测试里（这一版我自己都写错过两次期望值——指数是作用在 0.23701 上的，心算时很容易把 10 的次方数错一位。**别信心算，信断言**）：

| 字段原文 | 含义 | 数值 |
|----------|------|------|
| ` 27039-3` | +0.27039 × 10⁻³ | 2.7039e-4 |
| `-43656-4` | −0.43656 × 10⁻⁴ | -4.3656e-5 |
| `-23701-5` | −0.23701 × 10⁻⁵ | -2.3701e-6 |
| `00000+0` | 零 | 0.0 |
| `.12345-2` | 0.12345 × 10⁻² | 1.2345e-3 |

## 三、防御式导入：五道关卡

单点修复不够。真实 TLE 文件的来源是别人的 FTP、别人的编辑器、别人的复制粘贴，防御必须成体系。`TleCatalogService` 对每一组 TLE 过五道关卡，任何一道不过只拒绝这一组（附原因），绝不中断整批：

```java
private String validate(String l1, String l2) {
    if (l1 == null || l2 == null || l1.length() < 69 || l2.length() < 69) {
        return "行不足 69 列（尾随空格被编辑器吃掉是最常见事故）";
    }
    if (!l1.startsWith("1 ") || !l2.startsWith("2 ")) {
        return "行首标识符非法（应为 \"1 \"/\"2 \"）";
    }
    if (!l1.substring(2, 7).equals(l2.substring(2, 7))) {
        return "line1/line2 NORAD 编号不匹配";
    }
    if (checksum(l1) != Character.getNumericValue(l1.charAt(68))
            || checksum(l2) != Character.getNumericValue(l2.charAt(68))) {
        return "校验和错误";
    }
    // ... 倾角 [0,180]、偏心率 [0,1)、平均运动 (0,20) 圈/天 的物理范围检查
    return null;
}
```

设计考量逐条说：

**69 列检查排第一**是有原因的。TLE 规定每行 69 列，最后一位是校验和，但 Windows 编辑器保存时会自动去掉尾随空格，很多行末尾本来就是空格——于是第 69 列的校验和被吞掉，后面所有 `substring` 全部错位。列解析对行长度的敏感性，是它比 CSV 危险的地方：CSV 错位是值错了，定长格式错位是语义整体漂移。

**校验和是唯一能证明"数据在传输途中没被改过"的机制**：前 68 列数字求和（`-` 记 1）模 10，必须等于第 69 列。这道关卡抓到的第一个"非法数据"就是我自己——写测试夹具时我凭记忆手敲 ISS 历元行，把校验位敲错了一位，防御当场拒绝了我自己的 fixture。**校验和不会区分恶意篡改和作者手滑，这正是它可靠的原因。**

**物理范围检查**（n < 20 圈/天对 LEO 是硬上界——20 圈/天对应约 160 km 高度，再低就该坠毁了）在解析之后、入库之前，防止的是"格式合法但物理荒谬"的数据污染星库。

分组本身用三状态推进（名称行 → line1 → line2），容忍 CRLF、空行和残组；错误报告截取前 20 条样本，防止一万条垃圾把 report 刷爆。

## 四、去重与幂等：NORAD 编号即主键

目录文件可能来自多个来源的拼接，同一颗卫星出现两次很正常。去重策略一句话：**NORAD 编号做主键，历元新者胜**。

```java
OrbitalElements prev = byCatalog.get(el.catalogNumber());
if (prev != null) {
    duplicates++;
    if (el.epochSeconds() > prev.epochSeconds()) {
        byCatalog.put(el.catalogNumber(), el);
    }
}
```

为什么是"新者胜"而不是"后到胜"或"先到胜"？TLE 的本质是带时间戳的轨道快照，历元越新越接近真实位置（第 3 章说过：历元起 24 小时内误差才有界）。同名文件里新旧两条 TLE 混排时，唯一正确的选择就是历元新的那条。同时整个导入是幂等的——同一份文件导两遍，结果完全一致，这让"定时从 Celestrak 拉全量刷新"成为安全的常规操作。

## 五、规模考试：3 毫秒、30 毫秒和 2.7 MB

第 1 章留过一个问题：那个 for 循环造星座的工厂，在万级规模下还撑得住吗？数据说话（JUnit 基准，10681 颗 × 10 个时间步）：

```text
SGP4-lite: 10681 sats x 10 steps = 43 ms  (0.4 us/sat·步)
Kepler   : 10681 sats x 10 steps = 34 ms  (0.3 us/sat·步)
```

三个数字三个结论：

**解析整份文件约 30 ms**。一万颗星的目录从文本到内存对象，比一次数据库往返还快。瓶颈根本不在解析，在任何你想对它做的事。

**SGP4-lite 对 Kepler 的"精度税"只有 1.4 倍**。单颗单步 0.4 µs——第 3 章那个让误差从 5397 km 降到 14.7 km 的 J2 + 阻力传播器，并没有比二体解析解贵多少，因为两者的主体都是几次三角函数加一次牛顿迭代。按 1 Hz 仿真步频算，10681 颗卫星全量推进一步只要 **4.6 ms**，CPU 占用 0.5%。"策略委派 + 全量换 SGP4"在预算上是免费的，所以第 5 章的切换仿真直接全程走 SGP4，不再有"演示用开普勒、正式用 SGP4"的双轨制。

**整个星库常驻约 2.7 MB**。`OrbitalElements` 是个 record：14 个 double（112 B）加几个短字符串引用，单颗约 250 B。一万颗的星库抵不上一张手机拍的照片。规模从来不是 record 的敌人，引用链和缓存才是。

对照第 1 章的架构直觉：Walker 工厂的 for 循环没有死，它只是退到了"虚拟星座"的场景；真实目录走的是另一条管道（TLE 文本 → 防御式解析 → 去重 → `replaceSatellites` 整体换装）。两条管道在 `Constellation` 这个数据结构上汇合，下游的切换、链路、路由代码对卫星来源完全无感——这是当初坚持"星座管理器只认 `OrbitalElements` 列表"的回报。

## 六、换装的并发边界

`replaceSatellites` 是这个故事里唯一的可变共享状态，值得多说两句：

```java
public synchronized void replaceSatellites(List<OrbitalElements> newSats) {
    this.satellites = Collections.unmodifiableList(newSats);
    this.satelliteById = indexSatellites(newSats);
    ...
}
```

写侧 `synchronized` 防止两次导入交错；读侧（仿真引擎每步 `getSatellites()`）不加锁，靠 volatile 引用的整体切换保证原子可见——读到的要么是旧列表要么是新列表，永远不会是"一半新一半旧"的杂交状态。代价是导入瞬间正在进行的仿真会突然面对一批新卫星，这是文档里写明的预期行为，不是缺陷：星库换装本来就该是"下一次仿真从新目录开始"的语义。万级列表的构建在 synchronized 区间内只有索引重建（~30 ms），不构成写锁瓶颈。

## 七、预设追问清单

1. 负 BSTAR 是脏数据吗？——不是，电推进卫星轨道机动的正常拟合结果，占比可达 12%，解析器必须支持；
2. TLE 为什么用定宽列而不是分隔符？——1960 年代的穿孔卡片遗产，但换来的是 69 字节定长记录的极致紧凑与流式可切分；代价是列错位即语义漂移，防御必须前置；
3. 校验和算法是什么？能防什么？——前 68 列数字和（`-` 记 1）模 10；防传输篡改与转录手滑，不防精心伪造；
4. 去重为什么用"历元新者胜"？——TLE 是带时间戳快照，只有历元最新的才代表当前轨道；
5. 万级卫星仿真撑得住吗？——SGP4-lite 0.4 µs/颗·步，1 Hz 步频下万星一步 4.6 ms，CPU 0.5%；
6. 换装时正在跑的仿真怎么办？——volatile 引用整体切换保证无中间态；进行中的仿真受影响是明确语义而非竞态缺陷；
7. 为什么不用数据库存星库？——2.7 MB 的只读快照，内存就是最好的存储；数据库留给真正需要持久化查询的东西（第 6 章会用到）。

## 八、下一章预告（星球专属）

第 5 章：**10681 颗真卫星的切换仿真**。Walker 12 星时代的切换算法在真实星座上重考：地面站可见性计算（仰角门限与大气折射修正的取舍）、Greedy 与 Predictive 两种切换策略在真实星链拓扑下的成败率对比、以及真实星座倾角壳带来的"不可见时间窗"——53° 壳对中国地面站的覆盖盲区，比教科书上写的要麻烦得多。

[→ 返回章节目录](/column/starlab)
