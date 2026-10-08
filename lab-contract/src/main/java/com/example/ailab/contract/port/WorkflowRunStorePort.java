package com.example.ailab.contract.port;

import com.example.ailab.contract.dto.*;
import java.util.List;
import java.util.Optional;

/** 工作流执行基线与动作日志；所有访问必须核验任务租约，远程调用在事务外。 */
public interface WorkflowRunStorePort {
    boolean eligible(TaskLease lease);
    Optional<WorkflowExecutionBinding> binding(TaskLease lease);
    void bind(TaskLease lease, WorkflowExecutionBinding binding);
    List<WorkflowAction> actions(TaskLease lease);
    /** 幂等保存决策；repair与唯一语义返工额度在同一事务消费。 */
    void start(TaskLease lease, WorkflowAction action);
    /** 完整成功观察不可覆盖，旧fencing不能提交迟到结果。 */
    void complete(TaskLease lease, WorkflowAction action);
}
