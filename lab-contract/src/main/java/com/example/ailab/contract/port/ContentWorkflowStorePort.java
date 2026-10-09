package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.util.*;

/** 资料驱动执行的持久接口。所有写入核验父任务租约；完成节点与接受计划不可覆盖。 */
public interface ContentWorkflowStorePort {
    Optional<ContentWorkflow.SourcePlan> sourcePlan(TaskLease lease);
    void bind(TaskLease lease, ContentWorkflow.SourcePlan plan);
    Optional<String> completed(TaskLease lease, String nodeId, String inputHash);
    void begin(TaskLease lease, String nodeId, String stage, String inputHash);
    void complete(TaskLease lease, String nodeId, String inputHash, String output);
    void accept(TaskLease lease, ContentWorkflow.OutputPlan plan);
    void finish(TaskLease lease, String stage, List<String> requiredNodes);
    void verifyComplete(TaskLease lease);
    void publish(TaskLease lease, String resultJson, String markdown);
    Optional<ContentWorkflow.Snapshot> readPlan(UserContext actor, long taskId);
    String result(UserContext actor, long taskId);
}
