package com.example.ailab.data.repository;

import com.example.ailab.data.persistence.mapper.FeeMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.FeeStorePort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** 短事务可靠账本；锁顺序为system_control→actor→scope→attempt，绝不持锁调用模型。 */
@Repository
public class FeeRepository implements FeeStorePort {
    private final FeeMapper mapper;
    private final SqlSupport sql;
    private static final BigDecimal ZERO = new BigDecimal("0.00000000");
    /** 使用正式数据源和Spring事务代理，不复用可丢追踪存储。 */
    public FeeRepository(SqlSupport sql) { this.sql = sql; this.mapper = sql.mapper(FeeMapper.class); }

    /** 同一范围并发预留串行核对累计金额及词元；既有scope上限不因配置变更重置。 */
    @Override @Transactional
    public FeeReservation reserve(FeeScope scope, String operationId, String modelId, String operationType,
            long input, long output, FeePrice price, String currency, BigDecimal limit, long tokenLimit, boolean simulated) {
        if (input < 0 || output < 0 || input > 10000000 || output > 10000000 || tokenLimit < 1
                || limit == null || limit.signum() <= 0 || !currency.matches("[A-Z]{3}")
                || !operationId.matches("[0-9a-fA-F-]{36}") || !modelId.matches("[A-Za-z0-9_.-]{1,128}")
                || !Set.of("CHAT", "EMBEDDING","IMAGE_GENERATION","VIDEO_GENERATION","AUDIO_GENERATION").contains(operationType)) throw LabException.invalid("费用预留参数无效");
        boolean media = Set.of("IMAGE_GENERATION","VIDEO_GENERATION","AUDIO_GENERATION").contains(operationType);
        // 缺价媒体不得付费；普通文本缺价仍沿用S07 UNKNOWN语义。
        if (media && (price==null || price.unit().equals("PER_MILLION_TOKENS") || output!=0))
            throw new LabException("FEE_PRICE_UNAVAILABLE","媒体需要明确单位和真实报价");
        if (!media && price!=null && !price.unit().equals("PER_MILLION_TOKENS")) throw conflict();
        if (price != null && !price.currency().equals(currency)) throw conflict();
        sql.actor(scope.actor(), true);
        String taskType = "";
        SqlRow task = null;
        if (scope.kind().equals("TASK")) {
            var rows = mapper.reserveAiTasksSelect(new Object[]{scope.resourceId(), scope.actor().userId()});
            if (rows.isEmpty()) throw LabException.denied();
            task = sql.one(rows);
            taskType = (String) task.get("task_type");
        }
        if ("NOTES_PPT".equals(taskType) || "NOTES_VIDEO".equals(taskType)) {
            // 媒体首次规划就使用本人缩小后的金额上限，不能先创建30元scope再忽略批准上限。
            try{
                var request=new com.fasterxml.jackson.databind.ObjectMapper().readValue((String) task.get("request_json"),TaskRequest.class);
                BigDecimal requested=request.taskType().equals("NOTES_VIDEO")?request.videoOptions().maximumAmount():request.presentationOptions().maximumAmount();limit=limit.min(requested);
            }catch(Exception e){throw conflict();}
        }
        String id = SqlSupport.hash(scope.actor().userId() + ":" + scope.kind() + ":" + scope.resourceId());
        var scopes = mapper.reserveFeeScopesSelect(new Object[]{id});
        if (scopes.isEmpty()) {
            int legacy = legacy(scope.actor(), scope.kind(), scope.resourceId());
            mapper.reserveFeeScopesWrite(new Object[]{id, scope.actor().userId(), scope.kind(), scope.resourceId(), currency, limit, tokenLimit, legacy});
            scopes = mapper.reserveFeeScopesSelect2(new Object[]{id});
        }
        var savedScope = scopes.get(0);
        if (!currency.equals(savedScope.get("currency"))) throw conflict();
        String hash = SqlSupport.hash(scope.runId() + ":" + modelId + ":" + operationType + ":" + input + ":" + output + ":" + price + ":" + simulated);
        var existing = mapper.reserveFeeAttemptsSelect(new Object[]{operationId});
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.get(0).get("request_hash"))
                    || sql.scalar(mapper.reserveFeeAttemptsSelect2(new Object[]{operationId, id}), Integer.class) != 1) throw conflict();
            return new FeeReservation(operationId, id);
        }
        var used = totals(id, null);
        BigDecimal reserved = simulated || price == null ? null : price.amount(input, output);
        long consumed = sql.scalar(mapper.reserveFeeAttemptsSelect3(new Object[]{id}), Long.class);
        // 尝试额度来自服务端任务类型，与执行预算一致，不能由费用调用方自行放宽。
        int attemptLimit = scope.kind().equals("INGESTION") ? 160 : Learning.Limits.forType(taskType).attempts();
        if (used.attempts() >= attemptLimit
                || consumed + (media?0:input + output) > ((Number)savedScope.get("token_limit")).longValue()
                || used.overLimit() || reserved != null && used.estimatedAmount().add(used.reservedAmount()).add(reserved)
                    .compareTo((BigDecimal)savedScope.get("limit_amount")) > 0)
            throw new LabException("BUDGET_EXCEEDED", "累计费用或词元额度不足");
        // 历史调用无金额或未知价格时不能宣称金额上限已受控，继续仅受硬词元／尝试限制。
        mapper.reserveFeeAttemptsWrite(new Object[]{operationId, id, scope.runId(), modelId, operationType, simulated, media?0:input, media?0:output, price == null ? null : price.ref(), price == null ? null : price.version(), currency, price == null ? null : price.unit(), price == null ? null : Timestamp.from(price.effectiveAt()), price == null ? null : price.inputRate(), price == null ? null : price.outputRate(), reserved, hash});
        if (media) mapper.reserveFeeAttemptsWrite2(new Object[]{input, operationId});
        return new FeeReservation(operationId, id);
    }

    /** 确定执行额度已消费后登记发送许可；重复调用不能再次发出同一操作。 */
    @Override @Transactional
    public void sending(FeeReservation reservation) {
        lock(reservation);
        if (mapper.sendingFeeAttemptsWrite(new Object[]{reservation.operationId(), reservation.scopeId()}) != 1) throw conflict();
    }

    /** 仅在远程发送前的失败释放；取消、超时及崩溃不是退款证据。 */
    @Override @Transactional
    public void release(FeeReservation reservation) {
        lock(reservation);
        mapper.releaseFeeAttemptsWrite(new Object[]{reservation.operationId(), reservation.scopeId()});
    }

    /** 提供方响应事实不因账户撤销丢失；该内部能力只补原键费用，不能发布业务结果。 */
    @Override @Transactional
    public void complete(FeeReservation reservation, Integer input, Integer output, String outcome) {
        if (input != null && input < 0 || output != null && output < 0 || outcome == null || !outcome.matches("[A-Z_]{1,64}")) throw LabException.invalid("费用响应无效");
        lock(reservation);
        var rows = mapper.completeFeeAttemptsSelect(new Object[]{reservation.operationId(), reservation.scopeId()});
        if (rows.isEmpty()) throw conflict();
        var row = rows.get(0); String state = (String)row.get("state");
        Integer oldInput = integer(row.get("input_tokens")), oldOutput = integer(row.get("output_tokens"));
        // 已知事实只能补空值，不能用重复或乱序回调改费率／用量。
        if (oldInput != null && input != null && !oldInput.equals(input) || oldOutput != null && output != null && !oldOutput.equals(output)) throw conflict();
        if (input == null) input = oldInput; if (output == null) output = oldOutput;
        String responseHash = SqlSupport.hash(input + ":" + output + ":" + outcome);
        if (row.get("reserved_units")!=null) throw conflict();
        if (Set.of("SETTLED", "SIMULATED").contains(state)) {
            if (!responseHash.equals(row.get("response_hash"))) throw conflict();
            return;
        }
        if (!Set.of("SENDING", "UNKNOWN").contains(state)) throw conflict();
        boolean simulated = Boolean.TRUE.equals(row.get("simulated"));
        BigDecimal amount = null;
        if (!simulated && input != null && output != null && row.get("price_ref") != null) {
            var price = new FeePrice((String)row.get("price_ref"), (String)row.get("price_version"), (String)row.get("currency"),
                    (String)row.get("price_unit"), ((Timestamp)row.get("price_effective_at")).toInstant(),
                    (BigDecimal)row.get("input_rate"), (BigDecimal)row.get("output_rate"));
            amount = price.amount(input, output);
        }
        String next = simulated ? "SIMULATED" : amount != null ? "SETTLED" : "UNKNOWN";
        // 超出估算也如实结算，不拒绝保存真实费用；后续预留将被累计预算拦截。
        mapper.completeFeeAttemptsWrite(new Object[]{next, outcome, input, output, simulated ? "SIMULATED" : input != null && output != null ? "PROVIDER_REPORTED" : "UNKNOWN", amount, responseHash, reservation.operationId()});
    }

    /** 媒体账本单独保存真实单位，不把预留当已知usage，重复结果只补原键。 */
    @Override @Transactional
    public void completeMedia(FeeReservation reservation,Long units,String outcome) {
        if (units!=null && (units<0 || units>10000000) || outcome==null || !outcome.matches("[A-Z_]{1,64}"))
            throw LabException.invalid("媒体用量无效");
        lock(reservation);
        var row=sql.one(mapper.completeMediaFeeAttemptsSelect(new Object[]{reservation.operationId(), reservation.scopeId()}));
        if (row.get("reserved_units")==null) throw conflict();
        Long old=row.get("used_units")==null?null:((Number)row.get("used_units")).longValue();
        if (old!=null && units!=null && !old.equals(units)) throw conflict();
        if (units==null) units=old;
        String hash=SqlSupport.hash(units+":"+outcome);
        if (row.get("state").equals("SETTLED")) {
            if(hash.equals(row.get("response_hash")))return;
            // 用量在处理中已经结算时，终态只补录结果，不再次改变用量或金额。
            if("RESULT_UNKNOWN".equals(row.get("outcome"))&&Set.of("SUCCESS","FAILED").contains(outcome)) {
                mapper.completeMediaOutcome(new Object[]{outcome,hash,reservation.operationId()});
                return;
            }
            if("RESULT_UNKNOWN".equals(outcome)&&Set.of("SUCCESS","FAILED").contains((String)row.get("outcome")))return;
            throw conflict();
        }
        if (!Set.of("SENDING","UNKNOWN").contains(row.get("state"))) throw conflict();
        var price=new FeePrice((String)row.get("price_ref"),(String)row.get("price_version"),(String)row.get("currency"),
                (String)row.get("price_unit"),((Timestamp)row.get("price_effective_at")).toInstant(),(BigDecimal)row.get("input_rate"),(BigDecimal)row.get("output_rate"));
        mapper.completeMediaFeeAttemptsWrite(new Object[]{units==null?"UNKNOWN":"SETTLED", units, units==null?"UNKNOWN":"PROVIDER_REPORTED", units==null?null:price.amount(units,0), outcome, hash, reservation.operationId()});
    }
    /** 统一scope锁先于attempt锁，发送和补账都采用同一锁顺序。 */
    private void lock(FeeReservation reservation) {
        var rows = mapper.lockFeeScopesSelect(new Object[]{reservation.scopeId()});
        if (rows.isEmpty()) throw conflict();
    }

    /** 本人费用读取不依赖可清理的运行行；不存在费用时仍核验资源所有权。 */
    @Override @Transactional(readOnly = true)
    public FeeSummary summary(UserContext actor, String kind, String resourceId) {
        sql.actor(actor, false);
        if (kind.equals("RUN")) {
            var owners = mapper.summaryFeeAttemptsSelect(new Object[]{resourceId});
            if (owners.isEmpty()) {
                var legacy = sql.scalars(mapper.summaryAiRunsSelect(new Object[]{resourceId, actor.userId()}), Integer.class);
                if (legacy.isEmpty()) throw LabException.denied();
                return empty(kind, resourceId, legacy.get(0));
            }
            if (owners.stream().anyMatch(r -> ((Number)r.get("actor_user_id")).longValue() != actor.userId())) throw LabException.denied();
            // 一个run归属一个在线／任务／入库scope，后台scope合计仍由独立接口读取。
            String scopeId = sql.scalar(mapper.summaryFeeAttemptsSelect2(new Object[]{resourceId}), String.class);
            var total = totals(scopeId, resourceId);
            return new FeeSummary(kind, resourceId, total.currency(), total.limitAmount(), total.estimatedAmount(), total.reservedAmount(), total.providerInputTokens(), total.providerOutputTokens(), total.reservedTokens(), total.attempts(), total.settledAttempts(), total.unknownAttempts(), total.pendingAttempts(), total.simulatedAttempts(), total.legacyUntrackedAttempts(), total.overLimit(), total.costStatus());
        }
        int legacy = legacy(actor, kind, resourceId);
        String id = SqlSupport.hash(actor.userId() + ":" + kind + ":" + resourceId);
        if (sql.scalar(mapper.summaryFeeScopesSelect(new Object[]{id}), Integer.class) == 0) return empty(kind, resourceId, legacy);
        return totals(id, null);
    }

    /** 汇总每条叶子一次，未知金额不以零替代；数字小计由costStatus限定含义。 */
    private FeeSummary totals(String id, String runId) {
        var scope = sql.one(mapper.totalsFeeScopesSelect(new Object[]{id}));
        var rows = runId == null ? mapper.totalsFeeAttemptsSelect(new Object[]{id})
                : mapper.totalsFeeAttemptsSelect2(new Object[]{id, runId});
        BigDecimal estimated = ZERO, reserved = ZERO; long in = 0, out = 0, tokens = 0; int settled = 0, unknown = 0, pending = 0, simulated = 0;
        for (var row : rows) {
            String state = (String)row.get("state");
            if (state.equals("RELEASED")) continue;
            if (state.equals("SETTLED")) { settled++; estimated = estimated.add((BigDecimal)row.get("estimated_amount")); }
            else if (state.equals("SIMULATED")) simulated++;
            else {
                if (state.equals("UNKNOWN")) unknown++; else pending++;
                if (row.get("reserved_amount") != null) reserved = reserved.add((BigDecimal)row.get("reserved_amount"));
            }
            // 用量可见小计与预算消耗分开：未知尝试有部分usage仍占全额预留，不能双计。
            if (!state.equals("SIMULATED")) {
                if (row.get("input_tokens") != null) in += ((Number)row.get("input_tokens")).longValue();
                if (row.get("output_tokens") != null) out += ((Number)row.get("output_tokens")).longValue();
            }
            if (!state.equals("SETTLED")) tokens += ((Number)row.get("reserved_input")).longValue() + ((Number)row.get("reserved_output")).longValue();
        }
        var limit = (BigDecimal)scope.get("limit_amount");
        int legacy = ((Number)scope.get("legacy_untracked_attempts")).intValue();
        long budgetTokens=0;
        for(var row:rows) {
            String state=(String)row.get("state");
            if(state.equals("RELEASED")) continue;
            budgetTokens+=state.equals("SETTLED") && row.get("reserved_units")==null?((Number)row.get("input_tokens")).longValue()+((Number)row.get("output_tokens")).longValue()
                    :((Number)row.get("reserved_input")).longValue()+((Number)row.get("reserved_output")).longValue();
        }
        boolean over = estimated.add(reserved).compareTo(limit) > 0 || budgetTokens>((Number)scope.get("token_limit")).longValue();
        return new FeeSummary((String)scope.get("scope_kind"), (String)scope.get("resource_id"), (String)scope.get("currency"), limit,
                estimated, reserved, in, out, tokens, rows.size(), settled, unknown, pending, simulated, legacy, over,
                unknown + pending + legacy > 0 ? "UNKNOWN" : settled > 0 ? "ESTIMATED" : simulated > 0 ? "SIMULATED" : "NO_RECORDED_ATTEMPTS");
    }

    /** 新旧后台资源都复核本人；历史尝试数只标缺账，不伪造价款。 */
    private int legacy(UserContext actor, String kind, String resourceId) {
        if (kind.equals("RUN")) return 0;

        if (!Set.of("TASK","INGESTION").contains(kind) || !resourceId.matches("[0-9]{1,19}")) throw LabException.invalid("费用资源无效");
        var values = sql.scalars(mapper.legacyAiTasksSelect(new Object[]{resourceId, actor.userId()}, kind.equals("TASK")), Integer.class);
        if (values.isEmpty()) throw LabException.denied();
        return values.get(0);
    }

    /** 管理聚合不返回个人标识；最大31天窗口，模拟从真实小计排除。 */
    @Override @Transactional(readOnly = true)
    public List<FeeAggregate> aggregate(UserContext actor, Instant since) {
        sql.actor(actor, false);
        if (actor.role() != UserContext.Role.ADMIN) throw LabException.denied();
        if (since == null || since.isBefore(Instant.now().minusSeconds(31L * 86400)) || since.isAfter(Instant.now())) throw LabException.invalid("聚合窗口须在最近31天内");
        return sql.project(mapper.aggregateFeeAttemptsSelect(new Object[]{Timestamp.from(since)}), (r, n) -> new FeeAggregate(r.string("currency"), r.intValue("attempts"), r.intValue("unknown_count"), r.intValue("pending_count"), r.intValue("simulated_count"), r.decimal("estimated"), r.decimal("reserved")));
    }

    /** 崩溃窗口有界封存而不退款；无需模型网络查询，供人工对账前明确未知。 */
    @Override @Transactional
    public int markUnknownBefore(Instant before, int maximum) {
        if (maximum < 1 || maximum > 100 || before == null || before.isAfter(Instant.now().minusSeconds(120))) throw LabException.invalid("未知封存边界无效");
        var ids = mapper.markUnknownBeforeFeeAttemptsSelect(new Object[]{Timestamp.from(before), maximum});
        int changed = 0;
        for (var row : ids) {
            lock(new FeeReservation((String)row.get("operation_id"), (String)row.get("scope_id")));
            changed += mapper.markUnknownBeforeFeeAttemptsWrite(new Object[]{row.get("operation_id")});
        }
        return changed;
    }

    /** 空账本只有明确无调用或旧事实缺账两种，不能解释为免费。 */
    private FeeSummary empty(String kind, String resourceId, int legacy) {
        return new FeeSummary(kind, resourceId, null, null, ZERO, ZERO, 0, 0, 0, 0, 0, 0, 0, 0, legacy, false, legacy > 0 ? "UNKNOWN" : "NO_RECORDED_ATTEMPTS");
    }
    /** SQL BIGINT统一转换；提供方输入在入口已经限定非负整数。 */
    private Integer integer(Object value) { return value == null ? null : ((Number)value).intValue(); }
    /** 冲突不授权自动重试外部购买，也不泄露账本内部信息。 */
    private LabException conflict() { return new LabException("FEE_CONFLICT", "费用操作或历史事实冲突"); }
}
