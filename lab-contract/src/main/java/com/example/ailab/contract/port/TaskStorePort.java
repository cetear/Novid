package com.example.ailab.contract.port;

import java.util.*;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;

/**
 * TaskStorePort，状态和并发条件由 MySQL 实现。
 */
public interface TaskStorePort {
    /**
     * 创建与请求去重同事务提交。
     */
    TaskSnapshot create(UserContext actor, TaskRequest request);

    /**
     * 仅本人读取，不提供 ADMIN 旁路。
     */
    TaskSnapshot read(UserContext actor, long id);

    /**
     * 原子暂停／恢复／取消，终态不能普通 resume。
     */
    TaskSnapshot action(UserContext actor, long id, String action);

    /**
     * 领取或恢复过期租约，返回当前可信请求者。
     */
    Optional<TaskLease> claim(String workerId);

    /**
     * 续租核验用户、worker、fencing 与状态。
     */
    boolean renew(TaskLease lease);

    /** 远程调用前持久记录当前步骤；必须满足当前租约和 fencing，不能覆盖已成功步骤。 */
    void beginStep(TaskLease lease, String stepId);

    /** 已授权资料准备完成后持久推进进度，不消费或伪造模型生成检查点。 */
    void completePreparation(TaskLease lease);

    /**
     * 真实模型提交前持久消费尝试预算，重启不清零。
     */
    void reserveModelAttempt(TaskLease lease);

    /**
     * 每次逻辑生成前持久消费轮数，恢复不重置六轮额度。
     */
    void reserveModelTurn(TaskLease lease);

    /**
     * 保存实际完成检查点，重复 step 不覆盖既有成功。
     */
    void checkpoint(TaskLease lease, TaskCheckpoint checkpoint);

    /**
     * 返回本执行可用的成功检查点。
     */
    List<TaskCheckpoint> checkpoints(TaskLease lease);

    /** 正式分页检查点，旧执行者不能提交；已完成页次不可覆盖。 */
    default void checkpointPage(TaskLease lease, TaskPageCheckpoint page) {
        throw new UnsupportedOperationException("此存储没有分页检查点能力");
    }

    /** 恢复时读取连续成功页次并复核其来源和代次，不能重放已完成页。 */
    default List<TaskPageCheckpoint> pages(TaskLease lease) { return List.of(); }

    /** 初始化所有所选文档的剩余范围，随后逐页单调推进，包含完全未读文档。 */
    default void initializeCoverage(TaskLease lease, List<DocumentCoverage> coverage) {
        throw new UnsupportedOperationException("此存储没有覆盖能力");
    }

    /** 当前持久剩余轮数用于给分析／发布预留额度，进程恢复不能重复获得六轮。 */
    default int remainingModelTurns(TaskLease lease) { return 6; }

    /** 读取已校验计划，恢复只复用原版，不重新规划成功节点。 */
    default Optional<TaskPlan> plan(TaskLease lease) { return Optional.empty(); }
    /** 本人读取计划；尚未规划或固定工作流返回空，不造一张静态图。 */
    default Optional<TaskPlanSnapshot> readPlan(UserContext actor, long taskId) { return Optional.empty(); }
    /** 当前租约下保存唯一计划、角色版本与策略事实，旧实现不能假装持久成功。 */
    default void savePlan(TaskLease lease, TaskPlan plan, String modelId, String policyVersion) {
        throw new UnsupportedOperationException("此存储没有动态计划能力");
    }
    /** 工具预算与修复额度独立于可丢追踪，正式存储持久单调消费。 */
    default void reserveToolCall(TaskLease lease) { }
    /** 结构修复与所有模型调用共享原任务预算。 */
    default void reserveModelRepair(TaskLease lease) { }
    /**
     * 执行完毕原子创建私人产物并进入 SUCCEEDED/PARTIAL。
     */
    void publish(TaskLease lease, TaskCheckpoint report);

    /**
     * 安全边界处理失败，不覆盖更晚的执行者。
     */
    void fail(TaskLease lease, String code);
}
