package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.util.List;
import java.util.Optional;
import java.time.Instant;

/** 私有会话窄存储端口；身份来自服务端，管理员也仅能操作本人会话。 */
public interface SessionStorePort {
    /** 七天命名空间幂等创建本人会话。 */
    SessionSnapshot create(UserContext actor, String title, String idempotencyKey);
    /** 分页列出本人未删除会话。 */
    List<SessionSnapshot> list(UserContext actor, int offset, int limit);
    /** 读取本人会话元数据。 */
    SessionSnapshot read(UserContext actor, long id);
    /** 分页读取完整原始历史，调用方交付正文前必须复核来源。 */
    List<SessionMessage> messages(UserContext actor, long id, long afterSeq, int limit);
    /** 按版本软删会话并清空正文和摘要，保留幂等事实。 */
    void delete(UserContext actor, long id, long version);
    /** 短事务领取唯一执行权，不在模型调用期间持锁。 */
    SessionLease begin(UserContext actor, long id, long version, ScopeRequest scope);
    /** 返回上下文重置序号之后的最近完整事件。 */
    List<SessionMessage> recent(UserContext actor, SessionLease lease, int limit);
    /** 读取上下文重置序号与摘要覆盖序号之后的事件。 */
    List<SessionMessage> unsummarized(UserContext actor, SessionLease lease, long afterSeq, int limit);
    /** 返回当前范围的摘要，仍须由调用方重新授权来源。 */
    Optional<SessionSummary> summary(UserContext actor, SessionLease lease);
    /** 核验执行权、版本、身份及到期，阻止迟到结果发布。 */
    void assertActive(UserContext actor, SessionLease lease);
    /** 原子保存成功问答对及可选摘要，提交前重核当前范围和来源。 */
    SessionSnapshot complete(UserContext actor, SessionLease lease, String question, AiResult result,
                             List<SourceDependency> sources, List<SessionSource> refs, SessionSummary summary);
    /** 生产在线调用传入原请求截止时间，兼容旧存储实现与合成夹具的七参数入口。 */
    default SessionSnapshot complete(UserContext actor, SessionLease lease, String question, AiResult result,
                                     List<SourceDependency> sources, List<SessionSource> refs, SessionSummary summary,
                                     Instant requestDeadline) {
        return complete(actor, lease, question, result, sources, refs, summary);
    }
    /** S05把已配对工具事件与问答同事务保存；旧存储有工具事件时明确不支持，不能默默丢失。 */
    default SessionSnapshot complete(UserContext actor, SessionLease lease, String question, AiResult result,
            List<SourceDependency> sources, List<SessionSource> refs, SessionSummary summary, Instant deadline,
            List<ToolExchange> exchanges) {
        if (!exchanges.isEmpty()) throw new UnsupportedOperationException("存储没有工具事件提交能力");
        return complete(actor, lease, question, result, sources, refs, summary, deadline);
    }
    /** 提交后交付前短事务复核全部历史派生来源及版本，锁不跨 HTTP 发送。 */
    void verifyDelivery(UserContext actor, long id, long version);
    /** 历史分页交付前在短事务统一复核可见事件，期间撤销来源则拒绝整页。 */
    void verifyHistoryDelivery(UserContext actor, long id, long version, List<SessionMessage> visibleMessages);
    /** 有效执行权释放并推进版本；不保存失败或取消的草稿。 */
    void abort(UserContext actor, SessionLease lease);
}
