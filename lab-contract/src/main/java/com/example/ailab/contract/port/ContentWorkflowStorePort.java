package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.util.*;

/** 资料驱动执行的持久接口。所有写入核验父任务租约；完成节点与接受计划不可覆盖。 */
public interface ContentWorkflowStorePort {
    Optional<ContentReading.Plan> readingPlan(TaskLease lease);
    Optional<ContentReading.Snapshot> readingPlan(UserContext actor, long taskId);
    Optional<ContentWorkflow.SourcePlan> sourcePlan(TaskLease lease);
    void bind(TaskLease lease, ContentWorkflow.SourcePlan plan);
    Optional<String> completed(TaskLease lease, String nodeId, String inputHash);
    Optional<String> completedNode(TaskLease lease,String nodeId);
    record ReadingCheckpoint(String inputHash,String output) { }
    /** 会话级恢复快照；完成节点不可覆盖，输入哈希仍由执行器逐项核对。 */
    Map<String,ReadingCheckpoint> completedReadingNodes(TaskLease lease);
    record ReadingWrite(String nodeId,String inputHash,String output) { }
    /** 仅保存冻结阅读片段的确定性派生节点，同一短事务内验证并提交。 */
    void completeReadingNodes(TaskLease lease,List<ReadingWrite> nodes);
    void begin(TaskLease lease, String nodeId, String stage, String inputHash);
    void complete(TaskLease lease, String nodeId, String inputHash, String output);
    void accept(TaskLease lease, ContentWorkflow.OutputPlan plan);
    void accept(TaskLease lease, LearningContent.Plan plan);
    Optional<LearningContent.Plan> acceptedLearningPlan(TaskLease lease);
    List<LearningContent.PackageStatus> packages(TaskLease lease);
    List<LearningContent.PackageStatus> packages(UserContext actor,long taskId,int offset,int limit);
    void beginPackage(TaskLease lease,String packageId);
    void completePackage(TaskLease lease,String packageId,String resultHash);
    void failPackage(TaskLease lease,String packageId,String errorCode);
    void splitPackage(TaskLease lease,String packageId,List<LearningContent.Part> parts,List<LearningContent.WorkPackage> children);
    void finish(TaskLease lease, String stage, List<String> requiredNodes);
    void verifyComplete(TaskLease lease);
    void publish(TaskLease lease, String resultJson, String markdown);
    Optional<ContentWorkflow.Snapshot> readPlan(UserContext actor, long taskId);
    String result(UserContext actor, long taskId);
    /** 本人已发布结果的完整权限依赖；与实际采用的直接业务来源分别保存。 */
    List<SourceDependency> publishedSources(UserContext actor, long taskId);
}
