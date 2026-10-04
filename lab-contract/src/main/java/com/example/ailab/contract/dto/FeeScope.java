package com.example.ailab.contract.dto;

import com.example.ailab.contract.context.UserContext;

/** 服务端费用归属；任务和入库使用稳定资源键，运行仅作查询关联。 */
public record FeeScope(UserContext actor, String kind, String resourceId, String runId) {
    /** 拒绝客户端自由命名空间或无身份费用记录。 */
    public FeeScope {
        if (actor == null || !java.util.Set.of("RUN", "TASK", "INGESTION").contains(kind)
                || resourceId == null || !resourceId.matches("[A-Za-z0-9-]{1,64}")
                || runId == null || !runId.matches("[0-9a-fA-F-]{36}"))
            throw new IllegalArgumentException("费用归属无效");
    }
}
