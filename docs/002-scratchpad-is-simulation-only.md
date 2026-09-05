# 002 — scratchpad 只能活在仿真里

**结论:** `Scratchpad` 在 7z020 上**综合不出来**。它 1 MB 且是异步读,BRAM 和
LUTRAM 两种资源都装不下。这不构成问题(stage 3 起走 TileLink 接 DDR),但仓库里
此前没有任何地方写着这件事,而它是个容易踩的坑 —— 名字叫 scratchpad 会让人以为
这是个可以综合的片上存储。

---

## 先说 scratchpad 是什么

一块**软件直接管理**的片上存储,占一段固定地址范围,没有标签、没有命中/缺失、
没有自动填充。

| | scratchpad | cache |
|---|---|---|
| 地址空间 | 专属的一段,软件看得见 | 透明,是更大空间的镜像 |
| 里面是什么 | 软件放进去的 | 硬件自己决定留下的 |
| 会缺失吗 | 不会 | 会,缺失触发填充 |
| 延迟 | 固定、可预测 | 可变 |
| 硬件成本 | 一块 RAM + 地址译码 | 标签阵列、比较器、替换策略、填充/逐出 FSM |

> cache 是"我猜你还会用到"的自动缓存;scratchpad 是"你自己负责放对东西"的一块地。

stage 0 用它的理由:它是**能装下一个程序的最小东西**,而且让核**完全不需要知道
"缺失"这个概念存在**。

## 用词上的坦白

严格说 "scratchpad" 通常指**和 cache 并存**的那块专属存储。我们这块不是 ——
stage 0 里它就是全部的存储器,不是谁旁边的补充。叫这个名字是跟着 Sodor 的命名,
更准确的说法是"仿真存储器模型"。

---

## 为什么综合不出来

`Scratchpad` 用 Chisel 的 `Mem`,生成出来是**异步读**:

```verilog
// generated/mem_131072x64.sv
reg [63:0] Memory[0:131071];
always @(posedge W0_clk) begin ... end          // 同步写
assign R0_data = R0_en ? Memory[R0_addr] : 64'bx;   // 组合读 ← 关键
```

**Xilinx 7 系列的 Block RAM 只有同步读** —— 数据在时钟沿之后才出现,没有异步读
模式。所以组合读只能映射成 SLICEM LUT 组成的分布式 RAM。

Chisel 里两者是分开的:`Mem` = 异步读,`SyncReadMem` = 同步读。Sodor 写得很直白:

```scala
val mem = if (useAsync) Mem(...) else SyncReadMem(...)
```

容量对不上:

| 资源(xc7z020) | 容量 |
|---|---|
| 全部 Block RAM | 140 × 36Kb ≈ 4.9 Mbit |
| 可作 LUTRAM 的 SLICEM | ≈ 1.1 Mbit |
| **我们要的** | **8 Mbit**(1 MB × 8) |

**两种都装不下。** 即使改成 `SyncReadMem` 让它能进 BRAM,1 MB 也超出 4.9 Mbit。

## 这为什么不要紧

stage 3 起,核通过两个 TileLink master 接到 Chipyard 的总线上,程序由 TSI 经串行
链路装进 PS 侧的 DDR3。**scratchpad 从那时起只是快速迭代用的仿真模型**,不参与
上板。

真实路径:`RiscvhwConfig` → boot ROM → TSI 装载 → DDR3。已在 stage 3 验证。

## 考虑过但没做的改动

把 scratchpad 改成 `SyncReadMem` 并缩到 64 KB,让 stage 0/1/2 也能独立上板。

没做,因为它想验证的那件事 —— **核能否应付非当拍应答** —— 已经被
`MemDelay` 的变化延迟模型覆盖得更彻底了(1–13 拍循环,每次访问不同)。
`SyncReadMem` 那固定的一拍延迟,对一个本来就带握手的核是完全透明的,教学价值有限。

---

## 顺带:H&H 第 7 章的存储器也不是 BRAM

HDL Example 7.15 的数据存储器:

```systemverilog
logic [31:0] RAM[63:0];
assign rd = RAM[a[31:2]];                    // 组合读
always_ff @(posedge clk)
  if (we) RAM[a[31:2]] <= wd;
```

同样是异步读,同样推断成分布式 RAM。64 字 × 32 位很小,LUTRAM 装得下,所以那个
例子本身没问题。

但更重要的是:**H&H 第 7 章的单周期和多周期设计,整个建立在"存储器当拍应答"这个
假设上**,而那个假设只有异步读才成立。换成真 BRAM,取指要多一拍,数据通路时序全变。

这正是 **Sodor 要专门做一个 3 级核**的理由 —— 它的 1 级和 2 级核异步读指令存储器,
3 级核换成 `SyncReadMem`,教的就是"真实 SRAM 不当拍应答"这件事。

我们绕过了这个问题,因为存储器接口从 stage 0 就带完整握手:多一拍、多五十拍,对核
是同一件事。参见 [001](001-separate-memory-ports.md)。
