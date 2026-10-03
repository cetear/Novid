package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** TaskRequest 持久任务契约，不保存可执行 Java 对象。 */
public record TaskRequest(String taskType, String topic, ScopeRequest scope, List<Long> documentIds, String idempotencyKey){
    /** 防御性复制参数和来源列表。 */
    public TaskRequest { documentIds=List.copyOf(documentIds); }
}
