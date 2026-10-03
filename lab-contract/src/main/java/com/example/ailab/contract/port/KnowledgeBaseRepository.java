package com.example.ailab.contract.port;
import java.util.*;
import java.time.Instant;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;
/** KnowledgeBaseRepository 窄端口，实现由运行入口装配。 */
public interface KnowledgeBaseRepository {
    /** 按 ID 获取元数据，授权由统一策略决定。 */ Optional<KnowledgeBaseSnapshot> find(long id);
    /** 创建固定归属于当前用户的知识库。 */ KnowledgeBaseSnapshot create(UserContext actor, String name, String description);
    /** 对当前可信范围查询启用库。 */ List<KnowledgeBaseSnapshot> list(AuthorizedKnowledgeScope scope, int offset, int limit);
    /** 用 owner 和版本 CAS 更新元数据；管理员也不越权写入。 */ KnowledgeBaseSnapshot update(UserContext actor, long id, long expectedVersion, String name, String description, boolean enabled);
    /** 删除本人知识库并登记索引清理事件。 */ void delete(UserContext actor, long id, long expectedVersion);
}

