# AI 模块目录与维护说明

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

资料工作流按准备、提取、组织、生成、质检、发布六阶段执行。每个模型工作包有独立输入和结果记录，共用父任务的持久调用、时间和工具预算；生成并发由服务器策略限制。遇到提取密度、上下文或输出容量不足时进一步拆分，恢复复用已完成的组合节点。提取引用在一次结构修复后仍不合法时，也只对失败分片继续二分；不接受错误引用，分片缩小到不超过 500 UTF-8 字节仍失败时保留原错误，所有子调用继续消耗原持久预算。来源身份错误、租约失效、取消与预算耗尽不进入引用恢复。计划一旦接受，数量、目标 ID、来源和配图方式不可由生成器或编辑请求改变。

`ContentWorkflowStorePort` 由数据模块保存来源、内容计划和节点。模型不生成可执行 SQL、线程或任意角色；任务取消、来源变更或租约失效时禁止迟到发布。全量读取证明不等于提取全部知识或人工教学验收。

内容长度由 `ContentLimits` 统一定义：摘要标题／正文 600／1800 UTF-8 字节，事实正文／引用 2000／1200，整编单节 12000，单题／PPT 单页完整 JSON 8000；PPT 标题 600、正文／讲者备注各 6000 字节，仍以实际版式预检决定正文是否能显示。索引与局部输入默认各 8000 字节，索引包至少 6000 字节以保证归并可收敛。不保留旧长度规则、版本选择或旧完成节点兼容；V24 清理升级前的三类内容任务及其关联记录。

结构诊断包含程序定义的字段路径、规则、实际计数、上限和单位，一次收集最多 32 项；错误不包含未知字段名、正文或 Jackson 原始异常。结构纠正回送原参数、候选回答和诊断，固定实际产出模型；质检、跨题重复及接受计划前的数量纠正也固定已保存的原模型身份，不嵌套第二次结构纠正。数量纠正由原主题规划模型处理；已接受的计划不可改变。生成回执在原节点完成前保存合法结果、模型 ID 和是否已经纠正，恢复可复用回执；缺少回执的生成节点视为状态损坏，返回 WORKFLOW_STATE_CONFLICT。所有调用继续消费原持久额度。窗口无法容纳整份候选回答时明确标记 `DRAFT_OMITTED_FOR_CONTEXT`，保留全部原参数与诊断重新生成；原输入本身不能容纳时仍拆分或报告容量不足。运行图保留各次模型调用和校验记录，受已配置的记录数量及负载捕获范围限制。

旧学习执行器、PPT ReAct／DAG 路径、旧 Skill、旧动作日志接口及旧报告计划接口已删除。V23 清理这三类已有任务及关联数据，删除废弃表和兼容标记；历史迁移文件保持原字节。原有说明归档于[旧工作流历史说明](../docs/temp/旧工作流历史说明归档.md)，不得作为现行接口。

配置、请求和部署见[接口说明](../docs/前端接口与联调说明.md)、[部署说明](../docs/部署配置与运行维护.md)。默认原文 1,000,000 UTF-8 字节、256 目标、工作流并发 8；模型网关由聊天和向量调用共享 8 个许可，聊天目标默认 8、允许配置 1～8。许可满额时在原预算内等待，取消、中断或截止会退出；等待不消费实际模型尝试额度。先取得目标许可再取得全局许可，防止单目标排队挤占其他目标的容量。配置变更仅影响新任务的工作流策略，已创建任务恢复仍使用原快照。容量并不保证所有模型和费用配置都能完成该体量。真实模型质量和最大容量性能仍需专项验收。

维护时保持 contract/business/data/ai/web 的依赖方向，业务工作流组合共享能力，模型调用与文件导出位于数据库短事务外。回归必须覆盖资料全量读取、计划核验、恢复复用、取消与来源变化，以及媒体批准与实际导出。

## 新工作流接入架构路由

1. 新增 Spring `WorkflowModule`，以 `Map<String, WorkflowExecutionBinding>` 声明任务类型、工作流 ID／版本、架构与执行器版本。无需修改 `WorkflowRouter`。重复类型、重复工作流身份、重复执行器版本或不存在的执行器导致启动失败。
2. 业务入口登记任务类型并定义授权、参数、资源策略、队列及产物协议。创建任务经 `WorkflowRoutingPort` 选择服务端绑定，在同一个数据库事务写入任务及绑定。客户端和模型不能选择或切换架构。
3. Worker 构造对应的 `FixedWorkflowProgram<R>`、`ReActProgram`、`PlanExecuteProgram<P,R>` 或 `MultiAgentProgram<N,R>`，调用 `router.execute(lease, workflowStore, budget, program)`。传入同一个任务预算；并行程序复用业务拥有的有界线程池。
4. 工作流负责类型化内容校验、来源与工具契约、持久检查点、重规划额度预留、审批及发布。执行器负责对应控制方式，不替业务生成权限或数据库状态。终态发布放在路由返回之后，避免发布后继续使用已结束的租约。

恢复只读取创建时绑定，绑定缺失或与登记不一致直接失败，不按最新路由重选，不降级为固定 Workflow。当前通用规划架构最多允许 8 次重规划，视频仍固定最多一次；程序上限不能替代任务累计预算。ReAct 必须显式提供工具白名单及其契约；Multi-Agent 在运行任何节点前校验完整依赖图，截止或中断会关闭图并取消在途本地动作，不能据此宣称远程模型请求已取消。

接入示例和验证范围见[多架构路由实施记录](../docs/temp/多架构路由与独立执行器实施记录.md)。
