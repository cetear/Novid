package com.example.ailab.contract.dto;

/**
 * TaskSnapshot 持久任务契约，不保存可执行 Java 对象。
 */
public record TaskSnapshot(long taskId, long requesterUserId, String taskType, String status, long stateVersion,
                           int modelAttempts, int completedSteps, String errorCode, Long artifactId,
                           TaskProgress progress, java.util.List<DocumentCoverage> coverage) {
    /** 正式响应防御复制，历史任务未登记覆盖时为空，不猜测为全文。 */
    public TaskSnapshot { coverage = coverage == null ? java.util.List.of() : java.util.List.copyOf(coverage); }
    /** 兼容 S01 正式构造入口。 */
    public TaskSnapshot(long taskId, long requesterUserId, String taskType, String status, long stateVersion,
                        int modelAttempts, int completedSteps, String errorCode, Long artifactId, TaskProgress progress) {
        this(taskId, requesterUserId, taskType, status, stateVersion, modelAttempts, completedSteps, errorCode,
                artifactId, progress, java.util.List.of());
    }
    /** 保留独立测试/CLI 的原构造方式；正式查询从存储补全进度，不猜测并行角色状态。 */
    public TaskSnapshot(long taskId, long requesterUserId, String taskType, String status, long stateVersion,
                        int modelAttempts, int completedSteps, String errorCode, Long artifactId) {
        this(taskId, requesterUserId, taskType, status, stateVersion, modelAttempts, completedSteps,
                errorCode, artifactId, null);
    }
}
