# AI 模块目录与维护说明

## 当前学习规划与执行（2026-10-11）

学习工作流为 version=3，PPT 为 version=2，均绑定 fixed-v1；document-content@3.0.0 使用 document-content-v3 动作策略。旧学习 units 规划／单题单节生成及字节切片备用路径已删除，不补建旧冻结阅读快照。当前无未完成旧任务，后续只创建新任务。

`DocumentDrivenWorkflow` 负责共享冻结阅读、精确证据、固定阶段、父预算及当前 PPT。`LearningRuntime` 是学习规划／执行的窄接口；`LearningPlanner` 实现整编目录和自测蓝图；`LearningPackages` 独立构建包与依赖，拆分保持职责；`LearningExecutor` 实现持久调度、逐项验收、全局协调、针对性修复及覆盖报告。`ReadingSchemas`／`LearningBusinessSchemas` 分别验证阅读／业务协议；数据层用 `LearningPlanRules` 复核事实归属、配额、职责和依赖。

新建整编与自测使用structure-read-v3-progress。`ProgressReading`定义逐片完整性与紧凑模型字段，`ReadingTasks`把阅读包／事实区间的拆分子任务派发到共享固定模型线程池，编排线程不在该池内等待。完整片段独立保存readobs检查点，最终仍验证全部事实、主题及原文证据；成功的read/topics/evidence派生节点按包展示，失败仍单独留诊断。阅读／事实提取的low参数与阅读协议解耦。

当前紧凑自测的`QuizPlanning`将小章节合包（最多8组／32条事实、48000字节），最多4路并行，逐组验证覆盖并稳定分配目标编号；章内顺序去重、章间并行。规划角色使用显式none／low及输出预算，成功结果与模型回执原子提交；题目生成与验收沿用原规则。有效章节超过128时在目标规划前拒绝。按用户要求不为旧任务增加迁移／续跑兼容，旧任务由使用者后续取消；验证与真实任务容量边界见[实施记录](../docs/temp/自测命题规划优化实施与验证记录-2026-10-15.md)。

整编全局规划目录、归并重复知识、保留冲突条件，规范知识具有唯一主要小节。自测优先有效逻辑映射，固定章节／考点／认知目的／题型／实际证据；跨章题只计一个主章节。默认每个有效章节至少一题，明确备注可聚焦或代表性出题；独立考点容量不足明确失败。配额按有上下限的稳定最大余数分配。

包及其职责片段独立持久化，相关小章合包、大章拆包，逻辑 ID 不变。上限128业务章节、512题位、1024包、8层拆分，小节与 PPT 目标另受策略约束。调度使用有界在途队列及父线程池，REPLACED 依赖递归等待全部子包。恢复复用验收节点和生产回执；缺失回执、旧租约或损坏证据禁止发布。局部修复固定原生产模型；实际输出分层摘要与完整范围协调、有界 INSPECT 和最多一轮针对性修复完成全局验收。单题不可拆分改变语义。

Skill 按 directory／assign／normalize／sections／quiz-intent／objectives／objective-evidence／package-write／unit-review／global-review 分工；plan／intent／merge／generate／review 仅用于当前 PPT。所有调用、修复和拆包使用父持久预算。输出容量使用实际注册 output-limit，结构提示预留不得抬高，SDK 上限不变。

V25／V26／V27 为增量迁移，不删除任务；旧 V23／V24 为历史清理记录。自动测试与真实环境未执行项见[剩余阶段实施记录](../docs/temp/整编与单文档自测剩余阶段实施与验证记录.md)。以下早期执行说明中的旧学习路径不作为现行实现，通用架构接入仍适用。

Workflow、ReAct、Plan-and-Execute、Multi-Agent 使用独立执行器，通过统一注册路由并存。学习自测、资料整编、PPT 绑定固定 Workflow（`fixed-v1`），主题、数量和目标在完整读取资料后形成；客户端只选资料并按需填写备注。视频绑定 Plan-and-Execute（`plan-execute-v1`），内部继续使用角色 DAG、有限工具循环和持久计划恢复，审批、提供方操作及人工验收仍通过媒体端口。独立 ReAct 与 Multi-Agent 已实现并登记，目前没有单独绑定的业务工作流。

| 职责 | 代码位置 |
|---|---|
| 三类资料执行器、完整读取、分层归并、并行生成及节点恢复 | `workflows/content/DocumentDrivenWorkflow` |
| 来源事实、动态内容计划和单个产出验证 | `workflows/content/ContentSchemas` |
| 独立架构接口、工作流登记、绑定核验与执行派发 | `orchestration/ArchitectureExecutor`、`WorkflowProgram`、`WorkflowModule`、`BuiltInWorkflows`、`WorkflowRouter` |
| 固定步骤／模型工具循环／有限重规划／角色依赖执行器 | `orchestration/fixed`、`react`、`planexecute`、`multiagent` |
| 学习队列、租约心跳、题目验证与 Markdown | `workflows/support/TaskQueueWorker`、`LearningTaskWorker`、`LearningSchemas`、`LearningRenderer` |
| PPT 与视频队列、预览、视频计划 | `workflows/media/MediaTaskWorker`、`MediaSchemas` |
| 审批后素材执行与 PPT 导出 | `workflows/media/MediaExecution`、`PresentationExecution` |
| 视频角色 DAG 调度 | `orchestration/multiagent/AgentDagExecutor` |
| 在线与视频只读工具循环 | `orchestration/react/BoundedToolLoop` |
| 唯一资料 Skill 目录与不可变快照 | `skills/SkillCatalog`、`ai/skills/document-content` |
| 共享预算、模型网关、费用、来源授权 | `runtime`、`model`、`fees`、`tools` |

长度规则继续统一使用 ContentLimits：摘要标题／正文600／1800 UTF-8字节，事实正文／引用2000／1200，整编单节12000，单题与 PPT 单页完整 JSON 8000。200K 上下文／32K 输出基准下，自然片段默认6000、阅读包24000、索引包32000、完整生成输入包64000、预计输出包24000 UTF-8字节；自然片段和阅读包独立，合包仍核验完整输入与合法模型输出窗口。服务器策略在创建时保存，变更只影响新任务；不兼容旧任务策略。容量不保证任意模型与费用配置都能完成最大体量。

受限总摘要的生成目标为1300 UTF-8字节、基础上限1800，工作流仍保留50%容差，最终验收上限2700；主题摘要目标420、基础上限600、工作流验收900，数组数量与完整JSON容量不增加容差。局部压缩带关联事实／原文及程序选择的必需原句，`SummaryTextGuard`保守保留数字、条件、否定、例外和冲突的原句及显式条件，拒绝新增无依据数字。替换值仍不合格时，只用完整绑定事实、原文及必需原句构建模板并重新验收；缺少完整来源或仍超限时返回`WORKFLOW_TEXT_UNREPRESENTABLE`，不截断或追加付费重试。紧凑整编导航继续按整体包容量控制，正文与题目不自动进入摘要压缩。事实提取及已绑定事实的摘要另检查阿拉伯数字来源；自测复核摘要以已验收题目为数字依据，允许推算答案和干扰项。这些是有限规则约束，不能证明模型提取事实全部准确或开放语义零遗漏，原文／模型质量复核及人工验收仍保留。

维护时保持 contract／business／data／ai／web 依赖方向，模型调用和文件导出位于数据库短事务外。测试覆盖冻结正文、全文阅读、业务计划、包恢复、取消与来源变化，并保留媒体批准及导出回归。配置和接口见[部署说明](../docs/部署配置与运行维护.md)与[接口说明](../docs/前端接口与联调说明.md)。

## 新工作流接入架构路由

工具目录在注册时一次编译参数 Schema 并构建 SDK 声明；请求仍按当前身份、能力和 Skill 白名单筛选，只复用不可变声明，不缓存授权结果。预算预留与 PPT 字体／版式检查是端口必需方法，测试应显式提供替身，不在生产接口提供默认跳过检查。

视频提交与查询统一经过 `VideoApiRegistry`，调用方必须传入批准路由及原任务截止时间。旧固定视频提交／查询入口和固定时长回退已移除；`MediaProperties` 中的历史视频字段继续保留原配置摘要及元数据兼容，不再用于视频协议执行或旧端点格式检查。图片异步处理独立解析 `image_result`，保留原提供方 ID 和未知结果保护。

1. 新增 Spring `WorkflowModule`，以 `Map<String, WorkflowExecutionBinding>` 声明任务类型、工作流 ID／版本、架构与执行器版本。无需修改 `WorkflowRouter`。重复类型、重复工作流身份、重复执行器版本或不存在的执行器导致启动失败。
2. 业务入口登记任务类型并定义授权、参数、资源策略、队列及产物协议。创建任务经 `WorkflowRoutingPort` 选择服务端绑定，在同一个数据库事务写入任务及绑定。客户端和模型不能选择或切换架构。
3. Worker 构造对应的 `FixedWorkflowProgram<R>`、`ReActProgram`、`PlanExecuteProgram<P,R>` 或 `MultiAgentProgram<N,R>`，调用 `router.execute(lease, workflowStore, budget, program)`。传入同一个任务预算；并行程序复用业务拥有的有界线程池。
4. 工作流负责类型化内容校验、来源与工具契约、持久检查点、重规划额度预留、审批及发布。执行器负责对应控制方式，不替业务生成权限或数据库状态。终态发布放在路由返回之后，避免发布后继续使用已结束的租约。

恢复只读取创建时绑定，绑定缺失或与登记不一致直接失败，不按最新路由重选，不降级为固定 Workflow。当前通用规划架构最多允许 8 次重规划，视频仍固定最多一次；程序上限不能替代任务累计预算。ReAct 必须显式提供工具白名单及其契约；Multi-Agent 在运行任何节点前校验完整依赖图，截止或中断会关闭图并取消在途本地动作，不能据此宣称远程模型请求已取消。

接入示例和验证范围见[多架构路由实施记录](../docs/temp/多架构路由与独立执行器实施记录.md)。
