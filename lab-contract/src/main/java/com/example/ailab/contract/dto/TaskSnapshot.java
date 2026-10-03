package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** TaskSnapshot 持久任务契约，不保存可执行 Java 对象。 */
public record TaskSnapshot(long taskId, long requesterUserId, String taskType, String status, long stateVersion, int modelAttempts, int completedSteps, String errorCode, Long artifactId){
}
