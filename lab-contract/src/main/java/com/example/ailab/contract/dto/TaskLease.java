package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** TaskLease 持久任务契约，不保存可执行 Java 对象。 */
public record TaskLease(TaskSnapshot task, TaskRequest request, UserContext actor, String workerId, long fencingToken){
}
