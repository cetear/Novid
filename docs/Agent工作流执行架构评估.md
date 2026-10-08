# Agent 工作流执行架构评估

研究日期：2026-10-09。范围：ReAct、Plan & Execute、固定工作流、多 Agent 协作，以及按业务工作流固定选择执行策略的可行性。以下“来源事实”来自一手资料；“项目建议”属于设计推断，不能视为框架对当前项目的保证。

## 结论

**项目建议：方案可行，但应采用“工作流 → 固定执行配置”，不把 ReAct、Plan & Execute、多 Agent 当成三个完全互斥的选项。** 执行配置分别声明调度策略、角色集合、Skill、工具权限、预算、审批规则和版本。业务类型已明确时，用 Java 配置路由即可，无须再增加一次模型分类调用。

**PPT 推荐先采用固定工作流 + 多个专职角色 + 局部 ReAct 工具循环。** 去掉模型 Planner 后保留“内容 → 布局 → 质检”的程序依赖关系；研究步骤可以继续自主选择检索工具。若要求 PPT 整体成为 ReAct，则需把生成、布局、检查、修复封装成可调用动作，让模型根据动作结果决定下一步，而不只是删除 Planner。

## 概念区别与组合

| 概念 | 谁决定下一步 | 来源事实 |
|---|---|---|
| 固定工作流 | 代码决定顺序、分支和循环 | Anthropic 将预定义代码路径的编排定义为 workflow；固定子任务适合 prompt chaining。[来源](https://www.anthropic.com/engineering/building-effective-agents) |
| ReAct | 模型结合当前观察决定下一动作 | 原论文将推理与外部动作交替，利用环境反馈更新行动；它不是“加载 Skill 后依次调用角色”的同义词。[来源](https://arxiv.org/abs/2210.03629) |
| Plan & Execute | 模型先形成多步计划，执行器按计划完成步骤，必要时重规划 | LangChain 官方将 planner 与 executor 分离；执行器内部也可以采用 ReAct 风格的 action agent。[来源](https://www.langchain.com/blog/plan-and-execute-agents) |
| 多 Agent 协作 | 可以由代码，也可以由监督 Agent 调度 | LangChain4j 的 sequential workflow 本身就能顺序调用多个 subagents；多 Agent 不要求一定存在一个动态主 Agent。[来源](https://docs.langchain4j.dev/tutorials/agents/#sequential-workflow) |

**设计推断：调度策略与角色组织是两个维度。** 一个固定工作流节点可以是完整 Agent，一个 Plan & Execute 的 executor 可以运行 ReAct，也可以分工给多个 Agent。LangChain 官方 custom workflow 明确允许代码、LLM 与 Agent 节点组合，并可将其他架构嵌入节点。[组合能力来源](https://docs.langchain.com/oss/python/langchain/multi-agent/custom-workflow)

## Java / LangChain4j 的可行性

来源事实：LangChain4j 官方提供 sequential、loop、parallel、conditional 等工作流及 supervisor，并允许实现 `Planner.nextAction()` 自定义调度。其示例 `SequentialPlanner` 只按游标返回下一个 subagent，不调用 LLM。因此，“保留 Planner 调度接口”与“每个任务都调用模型规划”并不是一回事。该 agentic 模块仍标为 experimental。[官方文档](https://docs.langchain4j.dev/tutorials/agents/#custom-agentic-patterns)

项目建议：无需为此立即引入或迁移到该实验模块。当前 Java 编排可以抽取统一执行接口，PPT 先提供固定计划生成器，复杂任务保留模型计划生成器，实际需要持续动作决策的工作流再实现 ReAct 执行器。共用模型网关、工具执行、权限、预算和任务状态；将执行配置及版本绑定到任务，恢复时沿用原策略。

## PPT 适配条件与成本

- **固定 PPT 流程**：适用于步骤已知、内容结构固定、排版可检验、有审批门槛的交付任务。可移除多余模型规划调用，但正文生成与质检仍有模型不确定性。
- **整体 ReAct PPT**：仅当模型需要依据中间结果持续选择“补充资料、生成内容、修改版式、重新检查”等动作时值得引入；必须设置动作白名单、步骤上限、质量门槛、终止条件、检查点及副作用幂等。Skill 提供业务知识，代码约束执行权限和状态迁移。
- **复杂 Plan & Execute**：适用于事先难以确定子任务的工作，例如多源研究、数据分析与图表制作，并可在 worker 内嵌 ReAct。上述场景是项目推断；动态分工适用未知子任务的依据见 [Anthropic orchestrator-workers](https://www.anthropic.com/engineering/building-effective-agents#workflow-orchestrator-workers)。

来源事实：LangChain 指出典型 ReAct 每个工具动作都需模型决策调用；计划执行能让大模型集中在规划/重规划，而执行步骤可以用较小模型或直接调用工具。因此，删除 Planner、改为整个 PPT 的 ReAct **并不保证总 Token 或时延下降**，应测量实际调用路径。[官方比较](https://www.langchain.com/blog/planning-agents)

项目建议：以成功率、质量检查通过率、模型调用次数、Token、总时延、返工率与恢复后的副作用次数评估收益，不预设某种架构必然更优。

## 避免过度架构化

来源事实：Anthropic 建议从最简单可用方案开始，复杂度应由实际收益支撑；Agent 通常以费用和时延换取能力，定义清晰的任务更适合可预测工作流。[官方建议](https://www.anthropic.com/engineering/building-effective-agents#when-and-when-not-to-use-agents)

项目建议：第一阶段只增加执行策略入口，保留当前任务生命周期，提供 PPT 固定执行和既有动态计划执行；不要一次实现未来所有策略，避免同时维护没有实际工作流使用的执行引擎。后续以具体任务证明需要 ReAct 或监督型多 Agent，再增加对应执行器。

## 来源清单

1. [ReAct 原始论文，Yao 等，ICLR 2023](https://arxiv.org/abs/2210.03629)
2. [LangChain4j：Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents/)
3. [LangChain：Plan-and-Execute Agents，2023-05-10](https://www.langchain.com/blog/plan-and-execute-agents)
4. [LangChain：Plan-and-Execute Agents，2024-02-13](https://www.langchain.com/blog/planning-agents)
5. [LangChain：Custom workflow](https://docs.langchain.com/oss/python/langchain/multi-agent/custom-workflow)
6. [Anthropic：Building effective agents，2024-12-19](https://www.anthropic.com/engineering/building-effective-agents)

说明：框架资料为研究日在线版本；概念可用于现有 Java 编排设计，具体 API 能否直接使用仍须结合项目依赖版本确认。

## 当前项目映射与改造边界

以下为与本地源码审查交叉核对的项目结论。

| 当前位置 | 对方案的影响 |
|---|---|
| [`MediaTaskWorker.runObserved()`](D:/AICodeProject/Novid/lab-ai/src/main/java/com/example/ailab/ai/workflows/media/MediaTaskWorker.java:98)、`plan()`、`executePlan()` | 可在编排入口固定路由，保留既有动态计划路径；PPT 可由代码构建静态步骤对象，继续复用依赖执行。 |
| [`MediaTaskWorker` 的 research/visual 分支](D:/AICodeProject/Novid/lab-ai/src/main/java/com/example/ailab/ai/workflows/media/MediaTaskWorker.java:349) | 已局部调用 `BoundedToolLoop`，支持研究环节的 ReAct 风格工具循环。这里指动作与反馈循环，不要求暴露或保存模型内部推理。 |
| [`ToolPolicy`](D:/AICodeProject/Novid/lab-ai/src/main/java/com/example/ailab/ai/tools/ToolPolicy.java) | 目前仅有知识问答、研究、分析、视觉研究等策略，工具能力以知识与外部只读访问为主；没有可让 ReAct 直接选择的 PPT 内容、布局、质检动作。整体 ReAct 需要补充这些受控动作，不能直接扩大只读工具目录的权限。 |
| [`BoundedToolLoop`](D:/AICodeProject/Novid/lab-ai/src/main/java/com/example/ailab/ai/orchestration/react/BoundedToolLoop.java:55) | 对话与交换记录目前在内存，PPT 调用方仅消费最终结果；已有角色结果检查点不等于循环内部可精确恢复。整体 ReAct 要可靠恢复，需逐轮记录已选择的动作、参数、工具结果及完成状态。 |
| [`media_worker_results`、`media_plans`](D:/AICodeProject/Novid/lab-data/src/main/resources/db/migration/V13__durable_media_workflows.sql:11) | 现有角色结果通过外键依赖计划；完整 ReAct 执行轨迹应新增或扩展执行记录，不能简单伪装为初始计划。 |
| [`TaskExecutionBinding`](D:/AICodeProject/Novid/lab-contract/src/main/java/com/example/ailab/contract/dto/TaskExecutionBinding.java) | 已固定 Skill、工具契约与动作策略版本，但未独立绑定 `executionMode` / `executorVersion`；新增执行配置需要随任务持久化。 |

建议将执行策略（`FIXED` / `REACT` / `PLAN_EXECUTE`）与角色组织（`SINGLE` / `MULTI`）分开，业务工作流绑定一个有版本的组合。现有外部 `TaskRequest.strategy` 的 `FIXED` / `PLANNED` 及媒体必须使用 `PLANNED` 的校验属于旧接口契约，不应直接重定义成新枚举而破坏已有请求。

项目目前声明 LangChain4j core/openai/mcp、版本 `1.20.2`，未依赖 `langchain4j-agentic`。本报告引用官方 agentic 能力仅说明设计模式可行，不表示在线最新版 API 可直接用于当前依赖，不建议为此更换框架。

影响评估（设计推断）：**静态计划替代 PPT 的模型 Planner 属于小到中等改造；整个 PPT 内容制作阶段采用可恢复 ReAct 属于中等到较大改造。** 两者都应保留 Java 对审批、付费生成及产物导出的强制门控。若目标是架构学习验证，可以选择受控内容 ReAct；若目标是交付稳定及降低费用，优先固定主链加局部 ReAct。验收应包含成功率、费用、P95 时延、恢复与重复购买检查。

## 建议的落地配置

以下是待实现设计，不代表当前已有这些执行器。

每个工作流版本固定登记 `workflowId`、`workflowVersion`、`executionMode`、`executorVersion`、角色集合、Skill 快照、工具契约及执行限额。Java 路由器根据可信工作流标识读取登记配置；本次任务首次执行时固定该配置，恢复时不重新按最新配置选择架构。一个工作流版本可以封装不同子模式，但同一任务不能因临时模型判断在执行中静默切换执行器。

若以本项目验证多种架构为目标，可将 PPT 登记为 `REACT`，限定其负责内容制作：在有状态动作集合中选择资料研究、生成或修改页面、选择布局、检查和修复，满足程序质量门槛后提交预览并结束循环。之后继续使用原 Java 审批、生图和导出链路。复杂研究工作流则登记为 `PLAN_EXECUTE`，其步骤可以交给多个角色执行。多 Agent 组织方式独立于上述执行模式。

建议先从 `MediaTaskWorker` 提取角色执行能力，让固定流程、模型计划执行和 ReAct 动作分发共用同一实现，避免复制内容、布局和质检逻辑；再增加路由与持久运行记录。旧任务继续沿用原计划、原批准及原素材。现有接口的 `strategy` 兼容处理需另行明确，不能只将 PPT 请求改成 `FIXED`，因为目前媒体业务校验明确要求 `PLANNED`。

验证重点是：各工作流确实使用登记的执行模式；PPT ReAct 无初始模型 Planner 调用且能根据检查反馈选择修复；必需步骤与来源不可绕过；重启不切换执行模式、不重复已完成动作或已付费生成；以相同输入分别记录调用数、费用、耗时和质量。这里尚未实施业务改动，也未运行架构性能或质量对比。
