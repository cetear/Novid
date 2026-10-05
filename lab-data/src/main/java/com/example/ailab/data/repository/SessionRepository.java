package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.SessionMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.SessionStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.ailab.data.persistence.po.SqlParameters;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 私有完整会话存储；执行租约跨远程调用，数据库锁只存在于短写事务。 */
@Repository
public class SessionRepository implements SessionStorePort {
    private final SessionMapper mapper;
    private final SqlSupport sql;
    private final DocumentSqlRepository documents;
    private final ObjectMapper json = new ObjectMapper();

    /** 使用数据模块内部组件，其他模块仅依赖框架无关的窄端口。 */
    public SessionRepository(SqlSupport sql, DocumentSqlRepository documents) {
        this.sql = sql; this.mapper = sql.mapper(SessionMapper.class);
        this.documents = documents;
    }

    /** actor 全局／用户锁串行化配额与七天去重，已删资源不会同键重新创建。 */
    @Override
    @Transactional
    public SessionSnapshot create(UserContext actor, String title, String idempotencyKey) {
        validateCreate(title, idempotencyKey);
        sql.actor(actor, true);
        String hash = SqlSupport.hash(title.length() + ":" + title);
        var existing = sql.project(mapper.createRequestDeduplicationsSelect(new Object[]{actor.userId(), idempotencyKey}), (r, n) -> Map.entry(r.string(1), r.longValue(2)));
        if (!existing.isEmpty()) {
            if (!existing.get(0).getKey().equals(hash)) throw new LabException("OPERATION_CONFLICT", "相同会话去重键的参数不同");
            return load(actor, existing.get(0).getValue(), false).snapshot();
        }
        long count = sql.scalar(mapper.createSessionsSelect(new Object[]{actor.userId()}), Long.class);
        if (count >= 100) throw new LabException("RATE_LIMITED", "每人最多 100 个未删除会话");
        long id = sql.insert(command -> mapper.createSessionsInsert(command), actor.userId(), title, encode(ScopeRequest.self()));
        mapper.createRequestDeduplicationsWrite(new Object[]{actor.userId(), idempotencyKey, hash, id});
        return load(actor, id, false).snapshot();
    }

    /** 列表 SQL 固定本人条件，管理员没有私有数据全局旁路。 */
    @Override
    public List<SessionSnapshot> list(UserContext actor, int offset, int limit) {
        page(offset, limit);
        sql.actor(actor, false);
        return sql.project(mapper.listSessionsSelect(new Object[]{actor.userId(), limit, offset}), this::snapshot);
    }

    /** 直接 ID 读取也要求当前身份及本人归属。 */
    @Override
    public SessionSnapshot read(UserContext actor, long id) {
        sql.actor(actor, false);
        return sql.project(mapper.readSessionsSelect(new Object[]{id, actor.userId()}), this::snapshot).stream().findFirst().orElseThrow(LabException::denied);
    }

    /** 返回完整原始历史；business 交付正文前再按该事件范围及来源过滤。 */
    @Override
    public List<SessionMessage> messages(UserContext actor, long id, long afterSeq, int limit) {
        messagePage(afterSeq, limit);
        sql.actor(actor, false);
        load(actor, id, false);
        return queryMessages(actor, id, afterSeq, limit, false);
    }

    /** 删除仅读取元数据，因此撤销来源不会阻止用户清除自己的会话。 */
    @Override
    @Transactional
    public void delete(UserContext actor, long id, long version) {
        sql.actor(actor, true);
        var versions = sql.project(mapper.deleteSessionsSelect(new Object[]{id, actor.userId()}), (r, n) -> r.longValue(1));
        if (versions.isEmpty()) throw LabException.denied();
        if (version < 1 || versions.get(0) != version) throw conflict("会话版本已变化");
        mapper.deleteMessagesWrite(new Object[]{id, actor.userId()});
        // 不删 sessions 和 request_deduplications，七天去重事实不能复活已删内容。
        mapper.deleteSessionsWrite(new Object[]{encode(ScopeRequest.self()), id, actor.userId()});
    }

    /** 领取 UUID 执行权，版本只在成功／失败释放时递增，远程期间不持任何行锁。 */
    @Override
    @Transactional
    public SessionLease begin(UserContext actor, long id, long version, ScopeRequest requestedScope) {
        ScopeRequest scope = normalize(actor, requestedScope);
        sql.actor(actor, true);
        validateScope(actor, scope);
        var row = load(actor, id, true);
        version(row, version);
        if (row.executionId() != null && row.leaseUntil().isAfter(row.serverNow())) throw conflict("会话正在处理另一个请求");
        String execution = UUID.randomUUID().toString();
        if (!row.snapshot().scope().equals(scope)) {
            // 无来源的统计也可能含旧范围信息，范围改变时保守舍弃整个派生窗口。
            mapper.beginSessionsWrite(new Object[]{encode(scope), id});
        }
        int changed = mapper.beginSessionsWrite2(new Object[]{execution, id, actor.userId(), version});
        if (changed != 1) throw conflict("会话执行权已变化");
        var claimed = load(actor, id, false);
        return new SessionLease(id, execution, version, claimed.leaseUntil(), scope, claimed.contextFloorSeq());
    }

    /** 有界读取最近事件，过滤窗口重置之前的所有内容。 */
    @Override
    public List<SessionMessage> recent(UserContext actor, SessionLease lease, int limit) {
        messagePage(0, limit);
        assertActive(actor, lease);
        var reverse = queryMessages(actor, lease.sessionId(), lease.contextFloorSeq(), limit, true);
        var ordered = new ArrayList<>(reverse);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }

    /** 摘要批次只读覆盖和重置序号之后的有界事件。 */
    @Override
    public List<SessionMessage> unsummarized(UserContext actor, SessionLease lease, long afterSeq, int limit) {
        messagePage(afterSeq, limit);
        assertActive(actor, lease);
        return queryMessages(actor, lease.sessionId(), Math.max(afterSeq, lease.contextFloorSeq()), limit, false);
    }

    /** 摘要是派生上下文，调用方仍需授权所有来源，不当作用户长期偏好。 */
    @Override
    public Optional<SessionSummary> summary(UserContext actor, SessionLease lease) {
        var row = active(actor, lease, false);
        return Optional.ofNullable(row.summary());
    }

    /** 只读复核不给远程调用保留事务；提交前会在短写事务中重复核验。 */
    @Override
    public void assertActive(UserContext actor, SessionLease lease) {
        active(actor, lease, false);
    }

    /** 成功问答对、序号、摘要和版本在同一事务提交，任一复核失败全部回滚。 */
    @Override
    @Transactional
    public SessionSnapshot complete(UserContext actor, SessionLease lease, String question, AiResult result,
                                    List<SourceDependency> sources, List<SessionSource> refs, SessionSummary summary) {
        // 旧夹具没有独立在线预算，75 秒租约减去 15 秒提交余量就是最保守的旧入口期限。
        return complete(actor, lease, question, result, sources, refs, summary, lease.leaseUntil().minusSeconds(15));
    }

    /** 提交时间独立于租约到期，等待全局锁不会把在线 60 秒期限延长至 75 秒。 */
    @Override
    @Transactional
    public SessionSnapshot complete(UserContext actor, SessionLease lease, String question, AiResult result,
                                    List<SourceDependency> sources, List<SessionSource> refs, SessionSummary summary,
                                    Instant requestDeadline) {
        return complete(actor, lease, question, result, sources, refs, summary, requestDeadline, List.of());
    }

    /** 工具事件严格配对且与答案共用执行权、来源与截止CAS，失败整轮回滚。 */
    @Override
    @Transactional
    public SessionSnapshot complete(UserContext actor, SessionLease lease, String question, AiResult result,
            List<SourceDependency> sources, List<SessionSource> refs, SessionSummary summary,
            Instant requestDeadline, List<ToolExchange> exchanges) {
        if (exchanges == null || exchanges.size() > 8) throw LabException.invalid("工具事件超限");
        var ids = new HashSet<String>();
        int toolBytes = 0;
        for (var event : exchanges) {
            if (event == null || event.toolCallId() == null || !event.toolCallId().matches("[A-Za-z0-9_.:-]{1,128}")
                    || !ids.add(event.toolCallId()) || event.toolName() == null || !event.toolName().matches("[a-z_]{1,64}")
                    || event.arguments() == null || event.result() == null || event.version() == null)
                throw LabException.invalid("工具事件配对不合法");
            toolBytes += bytes(event.arguments()) + bytes(event.result());
        }
        if (toolBytes > 65536) throw new LabException("BUDGET_EXCEEDED", "工具历史超过64KB");
        var row = active(actor, lease, true);
        if (requestDeadline == null || !row.serverNow().isBefore(requestDeadline)) throw deadlineExceeded();
        if (question == null || question.isBlank() || question.length() > 2000) throw LabException.invalid("问题须为 1～2000 字符");
        if (result == null || !Set.of("SUCCESS", "NEEDS_INPUT").contains(result.status()) || result.answer() == null || result.answer().isBlank())
            throw LabException.invalid("会话只能保存正常业务结果");
        if (bytes(result.answer()) > 65536) throw new LabException("BUDGET_EXCEEDED", "会话答案超过 64 KB");
        if (row.nextSeq() + 1 + exchanges.size() * 2 > 10000) throw new LabException("BUDGET_EXCEEDED", "每会话最多 10000 条消息");
        var referenceSet = new LinkedHashSet<SessionSource>();
        if (refs != null) referenceSet.addAll(refs);
        for (var citation : result.citations()) referenceSet.add(new SessionSource(
                new SourceDependency(citation.document().knowledgeBaseId(), citation.document().id(), citation.document().documentVersion()),
                citation.processingRevision(), citation.sectionId(), citation.startOffset(), citation.endOffset()));
        var references = List.copyOf(referenceSet);
        if (references.size() > 32) throw new LabException("CONTEXT_MAPPING_INVALID", "引用位置超过 32 项");
        var inputs = new ArrayList<SourceDependency>();
        if (sources != null) inputs.addAll(sources);
        for (var reference : references) {
            if (reference == null || reference.dependency() == null) throw LabException.invalid("引用来源不能为空");
            inputs.add(reference.dependency());
        }
        for (var citation : result.citations()) inputs.add(new SourceDependency(citation.document().knowledgeBaseId(), citation.document().id(), citation.document().documentVersion()));
        if (summary != null) {
            validateSummary(summary, row);
            inputs.addAll(summary.sourceDependencies());
        }
        var flattened = verifySources(actor, lease.scope(), inputs);
        for (var reference : references) verifyReference(reference, null);
        // 远程检索的正文不能仅凭此前 tools.verify 成功发布，事务内核对当前激活代次及原文。
        for (var citation : result.citations()) verifyReference(new SessionSource(
                new SourceDependency(citation.document().knowledgeBaseId(), citation.document().id(), citation.document().documentVersion()),
                citation.processingRevision(), citation.sectionId(), citation.startOffset(), citation.endOffset()), citation.text());
        String sourceJson = encode(flattened), referenceJson = encode(references), scopeJson = encode(lease.scope());
        // 问题也携带完整上下文来源，防止用户复述受限资料后绕过后续历史读取过滤。
        insertMessage(actor, lease, row.nextSeq(), "USER", result.status(), question, sourceJson, referenceJson, scopeJson);
        long seq = row.nextSeq() + 1;
        for (var event : exchanges) {
            for (String role : List.of("TOOL_REQUEST", "TOOL_RESULT")) {
                mapper.completeMessagesWrite(new Object[]{lease.sessionId(), actor.userId(), seq++, role, result.status(), role.equals("TOOL_REQUEST") ? event.arguments() : event.result(), sourceJson, referenceJson, scopeJson, event.toolCallId(), event.toolName()});
            }
        }
        insertMessage(actor, lease, seq, "ASSISTANT", result.status(), result.answer(), sourceJson, referenceJson, scopeJson);
        if (summary != null) {
            var summarySources = verifySources(actor, lease.scope(), summary.sourceDependencies());
            mapper.completeSessionsWrite(new Object[]{summary.content(), summary.coveredThroughSeq(), encode(summarySources), lease.sessionId()});
        } else {
            // AI 已过滤失效旧摘要或暂无摘要，彻底清除旧派生内容，下一轮不能反复复用。
            mapper.completeSessionsWrite2(new Object[]{lease.sessionId()});
        }
        int changed = mapper.completeSessionsWrite3(new Object[]{2 + exchanges.size() * 2, lease.sessionId(), actor.userId(), lease.version(), lease.executionId(), Timestamp.from(requestDeadline)});
        if (changed != 1) {
            // 后续来源查询／消息写入也可能耗时；最终 CAS 失败会回滚已插入的整对事件。
            Instant serverNow = sql.scalar(mapper.completeRowsSelect(new Object[]{}), Timestamp.class).toInstant();
            if (!serverNow.isBefore(requestDeadline)) throw deadlineExceeded();
            throw stale();
        }
        return load(actor, lease.sessionId(), false).snapshot();
    }

    /** 答案落库后仍以当前身份、版本、原始范围和全部派生来源复核，事务结束才允许发送。 */
    @Override
    @Transactional
    public void verifyDelivery(UserContext actor, long id, long version) {
        sql.actor(actor, true);
        var row = load(actor, id, true);
        version(row, version);
        validateScope(actor, normalize(actor, row.snapshot().scope()));
        var last = sql.project(mapper.verifyDeliveryMessagesSelect(new Object[]{id, actor.userId()}), this::message).stream().findFirst().orElseThrow(() -> conflict("会话尚无可交付答案"));
        if (!last.scope().equals(row.snapshot().scope())) throw conflict("会话范围已变化");
        verifySources(actor, row.snapshot().scope(), last.sourceDependencies());
        for (var reference : last.sourceReferences()) verifyReference(reference, null);
    }

    /** 统一锁下重读每条可见事件的权威依赖，防止分页中途撤销资料仍返回前面已校验的正文。 */
    @Override
    @Transactional
    public void verifyHistoryDelivery(UserContext actor, long id, long version, List<SessionMessage> visibleMessages) {
        if (visibleMessages == null || visibleMessages.size() > 100) throw LabException.invalid("历史交付页无效");
        sql.actor(actor, true);
        var row = load(actor, id, true);
        version(row, version);
        // 全页占位符没有受限正文或来源，仍允许本人查看删除／降级后的历史序号。
        if (visibleMessages.stream().allMatch(message -> message != null && "RESTRICTED".equals(message.status()))) return;
        ScopeRequest currentScope = normalize(actor, row.snapshot().scope());
        validateScope(actor, currentScope);
        for (var visible : visibleMessages) {
            if (visible == null || visible.seq() <= 0) throw LabException.invalid("历史交付序号无效");
            if ("RESTRICTED".equals(visible.status())) continue;
            // 可见集合由 business 过滤，但来源仍从 SQL 重读，不能相信转交对象已经携带完整依赖。
            var message = sql.project(mapper.verifyHistoryDeliveryMessagesSelect(new Object[]{id, actor.userId(), visible.seq()}), this::message).stream().findFirst().orElseThrow(LabException::denied);
            validateScope(actor, normalize(actor, message.scope()));
            verifySources(actor, currentScope, message.sourceDependencies());
            for (var reference : message.sourceReferences()) verifyReference(reference, null);
        }
    }

    /** 只释放仍属于本执行者的租约，旧执行者无法覆盖新请求，也不留下失败草稿。 */
    @Override
    @Transactional
    public void abort(UserContext actor, SessionLease lease) {
        sql.actor(actor, true);
        // 外人即使掌握完整租约对象也必须统一拒绝，旧本人执行者只会零行释放。
        var owners = sql.project(mapper.abortSessionsSelect(new Object[]{lease.sessionId(), actor.userId()}), (r, n) -> r.longValue(1));
        if (owners.isEmpty()) throw LabException.denied();
        // 到期也可清理自己的 UUID；一旦新请求取得 UUID，此条件就不会再匹配。
        mapper.abortSessionsWrite(new Object[]{lease.sessionId(), actor.userId(), lease.version(), lease.executionId()});
    }

    /** 固定全局／用户／会话锁顺序，并以数据库时间验证执行权，拒绝过期结果。 */
    private SessionRow active(UserContext actor, SessionLease lease, boolean lock) {
        if (lease == null) throw stale();
        sql.actor(actor, lock);
        var row = load(actor, lease.sessionId(), lock);
        if (row.snapshot().version() != lease.version() || !Objects.equals(row.executionId(), lease.executionId())
                || row.leaseUntil() == null || !row.leaseUntil().isAfter(row.serverNow())
                || !row.snapshot().scope().equals(lease.scope()) || row.contextFloorSeq() != lease.contextFloorSeq()) throw stale();
        validateScope(actor, normalize(actor, lease.scope()));
        return row;
    }

    /** 本人过滤写在 SQL 里，知道 ID 也不能推断他人的会话是否存在。 */
    private SessionRow load(UserContext actor, long id, boolean lock) {
        return sql.project(mapper.loadSessionsSelect(new Object[]{id, actor.userId()}, lock), this::row).stream().findFirst().orElseThrow(LabException::denied);
    }

    /** 窗口查询始终带本人字段与序号下界，倒序读取由 recent 恢复自然顺序。 */
    private List<SessionMessage> queryMessages(UserContext actor, long id, long afterSeq, int limit, boolean reverse) {
        return sql.project(mapper.queryMessagesMessagesSelect(new Object[]{id, actor.userId(), afterSeq, limit}, reverse), this::message);
    }

    /** 每条事件写入服务器来源、原始范围及确定成功状态，工具字段只预留。 */
    private void insertMessage(UserContext actor, SessionLease lease, long seq, String role, String status, String content,
                               String sources, String references, String scope) {
        mapper.insertMessageMessagesWrite(new Object[]{lease.sessionId(), actor.userId(), seq, role, status, content, sources, references, scope});
    }

    /** 来源递归去重、当前内容版本与当前范围都由权威 SQL 重核，global 锁阻止并发撤销。 */
    private List<SourceDependency> verifySources(UserContext actor, ScopeRequest scope, List<SourceDependency> inputs) {
        var flattened = new LinkedHashSet<SourceDependency>();
        walkSources(actor, scope, inputs, flattened, new HashSet<>(), 0);
        return flattened.stream().sorted(Comparator.comparingLong(SourceDependency::knowledgeBaseId)
                .thenComparingLong(SourceDependency::documentId).thenComparingInt(SourceDependency::documentVersion)).toList();
    }

    /** 拒绝环及无界派生链；逐个来源都必须落在会话当前选择范围内。 */
    private void walkSources(UserContext actor, ScopeRequest scope, List<SourceDependency> inputs,
                             Set<SourceDependency> flattened, Set<String> path, int depth) {
        if (depth > 8 || inputs.size() > 128) throw mapping("来源依赖超过限制");
        for (var source : inputs) {
            if (source == null || source.documentVersion() < 1) throw mapping("来源记录无效");
            String key = source.documentId() + ":" + source.documentVersion();
            if (path.contains(key)) throw mapping("来源依赖环");
            if (flattened.contains(source)) continue;
            if (flattened.size() >= 32) throw mapping("来源总数超过 32 项");
            var parameters = new SqlParameters().addValue("document", source.documentId())
                    .addValue("base", source.knowledgeBaseId()).addValue("version", source.documentVersion());
            var authorized = new AuthorizedKnowledgeScope(actor, scope.mode(), scope.knowledgeBaseIds(), scope.ownerUserId(), Instant.now());
            sql.scope(authorized, parameters);
            int count = sql.scalar(mapper.walkSourcesDocumentsSelect(parameters), Integer.class);
            if (count != 1) throw LabException.denied();
            flattened.add(source);
            path.add(key);
            walkSources(actor, scope, documents.dependencies(source.documentId(), source.documentVersion()), flattened, path, depth + 1);
            path.remove(key);
        }
    }

    /** 范围排序去重稳定 JSON 与等价比较，不接受角色越权的 ALL 或 owner。 */
    private ScopeRequest normalize(UserContext actor, ScopeRequest requested) {
        ScopeRequest scope = requested == null ? ScopeRequest.self() : requested;
        if (scope.mode() == ScopeRequest.Mode.ALL && actor.role() != UserContext.Role.ADMIN
                || scope.ownerUserId() != null && actor.role() != UserContext.Role.ADMIN && scope.ownerUserId() != actor.userId()) throw LabException.denied();
        if (scope.mode() == ScopeRequest.Mode.SELF && scope.ownerUserId() != null && scope.ownerUserId() != actor.userId()) throw LabException.denied();
        if (scope.mode() != ScopeRequest.Mode.SELECTED && !scope.knowledgeBaseIds().isEmpty()) throw LabException.invalid("仅 SELECTED 接受知识库 ID");
        if (scope.knowledgeBaseIds().size() > 100 || scope.knowledgeBaseIds().stream().anyMatch(id -> id == null || id < 1)) throw LabException.invalid("知识库范围无效");
        return new ScopeRequest(scope.mode(), scope.knowledgeBaseIds().stream().distinct().sorted().toList(), scope.ownerUserId());
    }

    /** 即使无来源的统计／澄清结果也要重核当前完整 SELECTED；禁用其中任一库则整体拒绝。 */
    private void validateScope(UserContext actor, ScopeRequest scope) {
        if (scope.mode() != ScopeRequest.Mode.SELECTED || scope.knowledgeBaseIds().isEmpty()) return;
        var parameters = new SqlParameters();
        sql.scope(new AuthorizedKnowledgeScope(actor, scope.mode(), scope.knowledgeBaseIds(), scope.ownerUserId(), Instant.now()), parameters);
        int count = sql.scalar(mapper.validateScopeKnowledgeBasesSelect(parameters), Integer.class);
        if (count != scope.knowledgeBaseIds().size()) throw LabException.denied();
    }

    /** 原文版本、激活代次、章节归属和 UTF-16 范围事务内复核，拒绝处理重启后的迟到证据。 */
    private void verifyReference(SessionSource reference, String expectedText) {
        var dependency = reference.dependency();
        var versions = sql.project(mapper.verifyReferenceDocumentsSelect(new Object[]{dependency.documentId(), dependency.knowledgeBaseId(), dependency.documentVersion()}), (r, n) -> new CurrentDocument(r.string("raw_text"), (Long) r.value("active_processing_revision")));
        if (versions.isEmpty()) throw LabException.denied();
        var current = versions.get(0);
        if (reference.processingRevision() != null && (reference.processingRevision() < 1
                || !Objects.equals(reference.processingRevision(), current.processingRevision()))) throw mapping("引用处理代次已变化");
        if ((reference.startOffset() == null) != (reference.endOffset() == null)) throw mapping("引用位置不完整");
        if (reference.startOffset() != null) {
            int start = reference.startOffset(), end = reference.endOffset();
            if (start < 0 || end <= start || end > current.text().length()
                    || !unicodeBoundary(current.text(), start) || !unicodeBoundary(current.text(), end)) throw mapping("引用原文范围无效");
            if (expectedText != null && !current.text().substring(start, end).equals(expectedText)) throw mapping("引用正文与当前原文不一致");
        } else if (expectedText != null) throw mapping("引用正文缺少原文位置");
        if (reference.sectionId() != null) {
            if (reference.processingRevision() == null) throw mapping("引用章节缺少处理代次");
            int sections = sql.scalar(mapper.verifyReferenceDocumentSectionsSelect(new Object[]{reference.sectionId(), dependency.documentId(), dependency.documentVersion(), reference.processingRevision()}), Integer.class);
            if (sections != 1) throw mapping("引用章节不属于当前资料");
        }
    }

    /** UTF-16 位置不得切断代理对，避免历史引用指向半个 Unicode 字符。 */
    private boolean unicodeBoundary(String text, int offset) {
        return offset <= 0 || offset >= text.length() || !Character.isHighSurrogate(text.charAt(offset - 1)) || !Character.isLowSurrogate(text.charAt(offset));
    }

    /** 摘要最多 2000 UTF-8 字节并覆盖已存在完整问答对；失效旧摘要允许从合法历史重建。 */
    private void validateSummary(SessionSummary summary, SessionRow row) {
        if (summary.content() == null || summary.content().isBlank() || bytes(summary.content()) > 2000
                || summary.coveredThroughSeq() <= row.contextFloorSeq() || summary.coveredThroughSeq() >= row.nextSeq()
                || summary.coveredThroughSeq() % 2 != 0) throw mapping("摘要内容或覆盖序号无效");
    }

    /** 会话行只在 data 内保留租约与序号，快照交付时不泄露执行 UUID。 */
    private SessionRow row(SqlRow r, int n) {
        var snapshot = snapshot(r, n);
        String summaryContent = r.string("summary_content");
        var summary = summaryContent == null ? null : new SessionSummary(summaryContent, r.longValue("summary_covered_through_seq"),
                decode(r.string("summary_source_json"), new TypeReference<List<SourceDependency>>() {}));
        return new SessionRow(snapshot, r.longValue("next_seq"), r.longValue("context_floor_seq"), r.string("execution_id"),
                instant(r, "lease_until"), instant(r, "server_now"), summary);
    }

    /** 元数据映射不读取任何消息或摘要正文，列表和直接读取只返回本人会话信息。 */
    private SessionSnapshot snapshot(SqlRow r, int n) {
        return new SessionSnapshot(r.longValue("id"), r.string("title"), r.longValue("version"),
                decode(r.string("scope_json"), ScopeRequest.class), instant(r, "created_at"), instant(r, "updated_at"));
    }

    /** 固定消息类型解析，不允许任意 JSON 多态或执行客户端内容。 */
    private SessionMessage message(SqlRow r, int n) {
        return new SessionMessage(r.longValue("seq"), r.string("role"), r.string("status"), r.string("content"),
                decode(r.string("source_json"), new TypeReference<List<SourceDependency>>() {}),
                decode(r.string("source_reference_json"), new TypeReference<List<SessionSource>>() {}),
                r.string("tool_call_id"), r.string("tool_name"), instant(r, "created_at"), decode(r.string("scope_json"), ScopeRequest.class));
    }

    /** UTC JDBC 时间映射保留可空租约，不依赖应用机器时钟判断执行权。 */
    private Instant instant(SqlRow r, String column) {
        Timestamp value = r.timestamp(column);
        return value == null ? null : value.toInstant();
    }

    /** 创建参数在数据边界也校验，防止绕过业务端口造成无界持久化。 */
    private void validateCreate(String title, String key) {
        if (title == null || title.isBlank() || title.length() > 200 || key == null || !key.matches("[A-Za-z0-9_.:-]{1,128}")) throw LabException.invalid("标题或 Idempotency-Key 无效");
    }

    /** 分页硬限额与公开接口一致，不能用内部端口发起无界扫描。 */
    private void page(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 100) throw LabException.invalid("分页参数无效");
    }

    /** 消息页按序号续读，最多一次读取 100 条。 */
    private void messagePage(long afterSeq, int limit) {
        if (afterSeq < 0) throw LabException.invalid("消息序号无效");
        page(0, limit);
    }

    /** 客户端版本不能覆盖已经成功／失败释放的新事实。 */
    private void version(SessionRow row, long expected) {
        if (expected < 1 || row.snapshot().version() != expected) throw conflict("会话版本已变化");
    }

    /** 固定 JSON 编码，不包含 Instant 和内部执行对象。 */
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("会话记录序列化失败", error); }
    }

    /** 已知契约类型解析损坏即拒绝，不返回部分正文。 */
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception error) { throw mapping("会话记录损坏"); }
    }

    /** 来源数组只解析固定元素类型，禁止猜测或跳过损坏依赖。 */
    private <T> T decode(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception error) { throw mapping("会话来源记录损坏"); }
    }

    /** 以 UTF-8 字节硬限额约束模型产物持久化。 */
    private int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }

    /** 统一版本与并发冲突码，web 映射为 409。 */
    private LabException conflict(String message) { return new LabException("SESSION_CONFLICT", message); }

    /** 执行权失效不泄露内部 UUID 或会话内容。 */
    private LabException stale() { return new LabException("STALE_EXECUTION", "会话执行权已失效"); }

    /** 在线截止时间耗尽不能保存成功事件，数据库租约尚有余量也必须拒绝。 */
    private LabException deadlineExceeded() { return new LabException("BUDGET_EXCEEDED", "会话在线请求期限已耗尽"); }

    /** 来源或摘要损坏不能以无来源内容继续生成。 */
    private LabException mapping(String message) { return new LabException("CONTEXT_MAPPING_INVALID", message); }

    /** 私有数据行模型，与公开会话快照严格分开。 */
    private record SessionRow(SessionSnapshot snapshot, long nextSeq, long contextFloorSeq,
                              String executionId, Instant leaseUntil, Instant serverNow, SessionSummary summary) {}

    /** 只在锁内核验引用的当前原文与处理代次，不跨模块交付内部记录。 */
    private record CurrentDocument(String text, Long processingRevision) {}
}
