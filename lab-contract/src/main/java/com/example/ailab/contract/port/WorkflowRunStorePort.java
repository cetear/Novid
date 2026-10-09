package com.example.ailab.contract.port;

import com.example.ailab.contract.dto.*;
import java.util.List;
import java.util.Optional;

/** 工作流执行基线与动作日志；所有访问必须核验任务租约，远程调用在事务外。 */
public interface WorkflowRunStorePort {
    Optional<WorkflowExecutionBinding> binding(TaskLease lease);
    void bind(TaskLease lease, WorkflowExecutionBinding binding);
}
