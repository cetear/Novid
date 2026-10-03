package com.example.ailab.contract.port;
import java.util.*;
import java.time.Instant;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;
/** OperationStorePort 窄端口，实现由运行入口装配。 */
public interface OperationStorePort {
    /** 保存绑定内容、来源和目标版本的待确认记录。 */ ApprovalSnapshot prepare(UserContext actor, long knowledgeBaseId, String title, String content, List<SourceDependency> sources);
    /** 本人读取预览并复核来源。 */ ApprovalSnapshot read(UserContext actor, String approvalId);
    /** 确认消费、笔记、来源、Outbox 与 operation 在同一短事务提交。 */ ApprovalSnapshot decide(UserContext actor, String approvalId, boolean approve);
}

