package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.util.List;

/** 服务端从本人已发布任务建立保存确认；禁止客户端覆盖正文和来源。 */
public interface CompilationStorePort {
    Compilation.SaveStatus prepare(UserContext actor, long taskId, long targetBaseId, String key);
    Compilation.SaveStatus status(UserContext actor, String approvalId);
    Compilation.Origin origin(AuthorizedKnowledgeScope scope, long documentId);
    List<Compilation.Participation> participations(AuthorizedKnowledgeScope scope, long sourceDocumentId, int offset, int limit);
}
