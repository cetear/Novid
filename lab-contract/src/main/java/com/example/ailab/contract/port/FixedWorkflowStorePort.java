package com.example.ailab.contract.port;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;
import java.util.Optional;
/** 固定流程独立节点日志；模型调用在事务外，完成观察不可覆盖。 */
public interface FixedWorkflowStorePort {
    Optional<Learning.Baseline> baseline(TaskLease lease);
    void bind(TaskLease lease, Learning.Baseline baseline);
    Optional<String> completed(TaskLease lease, String nodeId, String inputHash);
    void begin(TaskLease lease, String nodeId, String stage, String inputHash);
    void complete(TaskLease lease, String nodeId, String inputHash, String json);
    void finishStage(TaskLease lease, String stage);
    /** 同一修复请求重复恢复幂等，不同请求不能再次取得语义修复额度。 */
    void repair(TaskLease lease, String reviewHash);
    void publish(TaskLease lease, String resultJson, String markdown);
    String result(UserContext actor, long taskId);
}
