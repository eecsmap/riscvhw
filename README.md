# riscvhw

一个刻意简单、刻意慢的 RV64 处理器实现,目标是**启动 xv6**。

`riscvm` 的硬件孪生:那是一个 Python RV64 模拟器,用 xv6 内核二进制驱动开发——
跑一遍看缺哪条指令就实现哪条。这个仓库用同样的方法做硬件,并且**把 riscvm 当黄金
参考模型**做逐指令对照。

## 为什么不做流水线

处理器学习有两条正交的复杂度轴:

| 轴 | 内容 |
|---|---|
| 微架构 | 流水线、冒险、旁路、乱序 |
| 系统/特权 | CSR、陷入、中断、MMU、设备、访存延迟 |

Sodor 把第一轴教得很好,但刻意省掉了第二轴(无 MMU、无设备、只有 scratchpad)。
Rocket 两轴都有,但 5 万行读不完。

这个项目**把第一轴压到最低**(非流水,状态机驱动),**把第二轴完整走一遍**。
产物会很慢,但每一个系统机制都看得见。

## 阶段

每一阶段的验收标准是「跑通某个具体东西」,不是「实现了某功能」。

| 阶段 | 加什么 | 验收 |
|---|---|---|
| **0** | RV64I 数据通路 + 状态机 | `rv64ui-p-*` 全过 |
| 1 | 逐指令对照基础设施 | 与 riscvm 的 trace 一致 |
| 2 | CSR + M 模式陷入 | `rv64mi-p-*` 全过 |
| 3 | 真实存储器(AXI/DDR)+ 访存等待 | 程序跑在 DDR 里 |
| 4 | UART(轮询) | 串口打出 hello world |
| 5 | CLINT + 定时器中断 | 定时器中断周期触发 |
| 6 | PLIC + 中断驱动 UART | 按键触发中断 |
| 7 | S 模式 + Sv39 MMU | `rv64si-p-*` 全过 |
| 8 | virtio 磁盘 | 能读一个扇区 |
| 9 | **xv6 启动** | shell 提示符 |

目标平台 PYNQ-Z1(xc7z020)。参考点:Rocket + xv6 在同一块板子上占约 70% 资源。

## 两个从第 0 阶段就定下的设计决定

**存储器接口带完整握手。** 即使 stage-0 的 scratchpad 当拍就返回,请求和响应都走
`Decoupled`。Sodor 的 `MemPortIo` 给 resp 只有 valid 没有 ready,核无法拒绝响应,
于是 1 级核被迫加了一个指令缓冲区来接住"忙的时候到达的回复"——**接口的缺陷,后面
用逻辑补**。按终点设计接口,第 3 阶段换成总线时核一行都不用改。

**状态机而不是纯组合。** 单周期核里取指→译码→执行→访存→写回是一根组合长链,
阶段是看不见的。状态机把它们显式分开,而且天然能容纳"等存储器"——后面每个阶段都是
加状态,不是重构。

**don't-care 用独立编码。** Sodor 把 `OP1_X` 和 `OP1_RS1` 编成同一个值来省 mux 宽度,
代价是译码表里写着"不关心"的地方硬件上实际选了 rs1,读代码时极易误判。这里每个 mux
都有显式的零/无编码。

## 构建

```bash
cd ~/github/chipyard && source env.sh   # 工具链
cd ~/github/riscvhw
sbt compile
sbt "runMain riscvhw.Elaborate generated"
```

## 目录

```
src/main/scala/riscvhw/
  Config.scala          机器参数
  core/
    Instructions.scala  指令编码
    Consts.scala        控制信号编码
    Decode.scala        译码表
    Alu.scala           ALU + 立即数生成
    Core.scala          状态机 + 数据通路
  mem/
    MemPort.scala       存储器接口
    Scratchpad.scala    stage-0 存储器(可注入延迟)
chipyard/               Chipyard 接入(阶段 3 起)
tools/                  co-simulation 工具
```
