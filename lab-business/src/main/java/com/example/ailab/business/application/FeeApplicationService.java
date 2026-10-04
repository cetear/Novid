package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.FeeStorePort;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.List;

/** 本人费用和管理聚合分开授权；金额接口不接受客户端改上限、价款或身份。 */
@Service
public class FeeApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final FeeStorePort fees;
    /** 费用存储与知识权限通过窄端口装配。 */
    public FeeApplicationService(KnowledgeAccessPolicy policy, FeeStorePort fees) { this.policy = policy; this.fees = fees; }
    /** 只读本人稳定资源；管理员读取私人费用没有旁路。 */
    public FeeSummary summary(UserContext actor, String kind, String id) {
        if (!java.util.Set.of("RUN", "TASK", "INGESTION").contains(kind) || id == null
                || !(kind.equals("RUN") ? id.matches("[0-9a-fA-F-]{36}") : id.matches("[1-9][0-9]{0,18}")))
            throw LabException.invalid("费用资源标识无效");
        return fees.summary(policy.current(actor), kind, id);
    }
    /** 最近有界窗口的低基数管理指标，普通用户拒绝且不调用存储。 */
    public List<FeeAggregate> aggregate(UserContext actor, int days) {
        policy.current(actor);
        if (actor.role() != UserContext.Role.ADMIN) throw LabException.denied();
        if (days < 1 || days > 30) throw LabException.invalid("聚合天数须为1～30");
        return fees.aggregate(actor, Instant.now().minusSeconds(days * 86400L));
    }
}
