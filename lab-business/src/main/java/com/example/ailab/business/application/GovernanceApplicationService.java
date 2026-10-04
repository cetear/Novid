package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.GovernanceStorePort;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

/** 管理用例只提供无内容聚合和访问元数据。 */
@Service
public class GovernanceApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final GovernanceStorePort store;

    /** 使用与知识访问相同的当前身份规则。 */
    public GovernanceApplicationService(KnowledgeAccessPolicy policy, GovernanceStorePort store) {
        this.policy = policy;
        this.store = store;
    }

    /** 游标分页避免大偏移和全量审计导出。 */
    public List<AccessAudit> audits(UserContext actor, long afterId, int size) {
        admin(actor);
        if (afterId < 0 || size < 1 || size > 100) throw LabException.invalid("审计分页超限");
        return store.audits(actor, afterId, size);
    }

    /** 窗口最多三十天；未知费用仍由独立费用接口查询。 */
    public OperationalMetrics metrics(UserContext actor, int days) {
        admin(actor);
        if (days < 1 || days > 30) throw LabException.invalid("指标窗口超限");
        return store.metrics(actor, Instant.now().minusSeconds(days * 86400L));
    }

    /** 数据库当前角色降级或禁用立即拒绝管理读取。 */
    private void admin(UserContext actor) {
        policy.current(actor);
        if (actor.role() != UserContext.Role.ADMIN) throw LabException.denied();
    }
}
