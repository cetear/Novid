package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.time.Instant;
import java.util.List;

/** 管理读取与受控维护分开；维护能力不注册为工具或用户写接口。 */
public interface GovernanceStorePort {
    /** 按递增游标最多读取一页访问元数据，数据库再核当前管理员。 */
    List<AccessAudit> audits(UserContext actor, long afterId, int limit);
    /** 最近有界时间窗口与当前积压计数，不借管理权限读取私人正文。 */
    OperationalMetrics metrics(UserContext actor, Instant since);
    /** 每类至多maximum行，结构共至多maximum行；调用者只能使用服务端截止时间。 */
    RetentionResult purge(Instant expiredBefore, Instant historyBefore, int maximum);
}
