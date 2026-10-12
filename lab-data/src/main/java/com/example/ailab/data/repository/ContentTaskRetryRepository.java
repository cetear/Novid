package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.ContentTaskRetryPort;
import com.example.ailab.data.persistence.mapper.TaskMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/** 同一任务的短事务续跑；用户锁与费用调用保持同一串行锁顺序。 */
@Repository
public class ContentTaskRetryRepository implements ContentTaskRetryPort {
    private final SqlSupport sql;
    private final TaskMapper mapper;
    private final TaskRepository tasks;
    private final ObjectMapper json=new ObjectMapper();
    @Value("${lab.fees.currency:CNY}") private String currency="CNY";
    @Value("${lab.fees.task-limit:30}") private BigDecimal amount=new BigDecimal("30");
    @Value("${lab.fees.task-tokens:300000}") private long tokens=300000;
    private static final Set<String> NON_CONTINUABLE=Set.of("WORKFLOW_REVIEW_REJECTED","MODEL_REPAIR_EXHAUSTED",
            "CONTEXT_VERSION_CONFLICT","WORKFLOW_STATE_CONFLICT","WORKFLOW_RETIRED","WORKFLOW_BINDING_MISSING",
            "WORKFLOW_EXECUTOR_UNAVAILABLE","WORKFLOW_ARCHITECTURE_MISMATCH","WORKFLOW_UNIT_TOO_LARGE");
    public ContentTaskRetryRepository(SqlSupport sql,TaskRepository tasks) {
        this.sql=sql;this.mapper=sql.mapper(TaskMapper.class);this.tasks=tasks;
    }
    @Override @Transactional(readOnly=true)
    public ContentTaskRetry.Plan retryPlan(UserContext actor,long id) {
        sql.actor(actor,false);return plan(task(actor,id,false),actor,id);
    }
    @Override @Transactional
    public TaskSnapshot retry(UserContext actor,long id,ContentTaskRetry.Request request,String key) {
        if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw LabException.invalid("需要有效 Idempotency-Key");
        sql.actor(actor,true);
        var row=task(actor,id,true);String encoded=encode(request),hash=SqlSupport.hash(encoded);
        var prior=mapper.retryEvent(new Object[]{id,key});
        if(!prior.isEmpty()) {
            if(!hash.equals(prior.get(0).string("request_hash")))throw new LabException("OPERATION_CONFLICT","相同重跑键的参数不同");
            return tasks.read(actor,id);
        }
        if(row.longValue("state_version")!=request.expectedStateVersion())throw new LabException("OPERATION_CONFLICT","任务状态已变化，请刷新后重跑");
        var before=plan(row,actor,id);
        if(!before.canContinue())throw new LabException("TASK_RETRY_UNAVAILABLE",String.join("；",before.blockers()));
        ContentExecutionLimits limits;
        try {limits=new ContentExecutionLimits(row.intValue("model_turn_limit")+request.additionalTurns(),
                row.intValue("model_attempt_limit")+request.additionalAttempts(),row.intValue("tool_call_limit")+request.additionalTools(),
                row.intValue("model_repair_limit")+request.additionalRepairs(),row.intValue("execution_seconds_limit")+request.additionalExecutionSeconds());}
        catch(IllegalArgumentException error){throw LabException.invalid("追加后的执行额度超出允许范围");}
        long elapsed=elapsed(row);
        boolean publishOnly=mapper.retryStats(new Object[]{id}).get(0).longValue("ready_result_count")>0;
        if(limits.executionSeconds()<=elapsed||!publishOnly&&(limits.turns()<=row.intValue("model_turns")||limits.attempts()<=row.intValue("model_attempts")
                ||limits.tools()<=row.intValue("tool_calls")||limits.repairs()<=row.intValue("model_repairs")))
            throw new LabException("RETRY_BUDGET_INSUFFICIENT","剩余执行时间、模型或工具额度不足，请追加对应额度");
        var feeRows=mapper.retryFees(new Object[]{Long.toString(id),actor.userId()});
        var fee=feeRows.isEmpty()?null:feeRows.get(0);
        BigDecimal effectiveAmount=(fee==null?amount.add(row.decimal("additional_fee_amount")):fee.decimal("limit_amount")).add(request.additionalAmount());
        long effectiveTokens=(fee==null?tokens+row.longValue("additional_token_limit"):fee.longValue("token_limit"))+request.additionalTokens();
        if(effectiveAmount.compareTo(new BigDecimal("1000000"))>0||effectiveTokens>100000000)
            throw LabException.invalid("追加后的费用或词元额度超出允许范围");
        if(!publishOnly&&fee!=null&&(effectiveAmount.compareTo(fee.decimal("used_amount"))<=0||effectiveTokens<=fee.longValue("used_tokens")))
            throw new LabException("RETRY_BUDGET_INSUFFICIENT","累计费用或词元额度不足，请追加对应额度");
        int changed=mapper.retryQueue(new Object[]{id,request.expectedStateVersion(),elapsed,request.additionalExecutionSeconds(),
                request.additionalTurns(),request.additionalAttempts(),request.additionalTools(),request.additionalRepairs(),request.additionalTokens(),request.additionalAmount()});
        if(changed!=1)throw new LabException("OPERATION_CONFLICT","任务状态已变化");
        mapper.retrySteps(new Object[]{id});mapper.retryPackages(new Object[]{id});
        if(fee!=null&&(request.additionalAmount().signum()>0||request.additionalTokens()>0)
                &&mapper.retryAddFees(new Object[]{Long.toString(id),actor.userId(),request.additionalAmount(),request.additionalTokens()})!=1)
            throw new LabException("OPERATION_CONFLICT","费用追加失败");
        if(mapper.retrySaveEvent(new Object[]{id,key,hash,row.intValue("retry_sequence")+1,row.string("error_code"),request.expectedStateVersion(),encoded,row.intValue("attempt")})!=1)
            throw new LabException("OPERATION_CONFLICT","重跑登记失败");
        return tasks.read(actor,id);
    }
    private SqlRow task(UserContext actor,long id,boolean lock) {
        return mapper.retryTask(new Object[]{id,actor.userId()},lock).stream().findFirst().orElseThrow(LabException::denied);
    }
    private ContentTaskRetry.Plan plan(SqlRow row,UserContext actor,long id) {
        var blockers=new ArrayList<String>();
        if(!"KNOWLEDGE_COMPILATION".equals(row.string("task_type")))blockers.add("本轮仅支持资料整编任务");
        if(!"FAILED".equals(row.string("status")))blockers.add("当前状态不是失败");
        if(row.get("artifact_id")!=null)blockers.add("任务已有发布产物");
        String code=Objects.toString(row.string("error_code"),"");
        boolean repairBudget=code.equals("MODEL_REPAIR_EXHAUSTED")&&row.intValue("model_repairs")>=row.intValue("model_repair_limit");
        if(NON_CONTINUABLE.contains(code)&&!repairBudget)blockers.add("需要质量重做或修复来源及执行基线，普通续跑无法解决");
        var stats=mapper.retryStats(new Object[]{id}).get(0);
        var fees=mapper.retryFees(new Object[]{Long.toString(id),actor.userId()});var fee=fees.isEmpty()?null:fees.get(0);
        return new ContentTaskRetry.Plan(id,row.longValue("state_version"),blockers.isEmpty(),row.string("error_code"),List.copyOf(blockers),
                row.intValue("execution_seconds_limit")-elapsed(row),row.intValue("model_turn_limit")-row.intValue("model_turns"),
                row.intValue("model_attempt_limit")-row.intValue("model_attempts"),row.intValue("tool_call_limit")-row.intValue("tool_calls"),
                row.intValue("model_repair_limit")-row.intValue("model_repairs"),stats.longValue("completed_nodes"),stats.longValue("pending_nodes"),
                stats.longValue("accepted_packages"),stats.longValue("pending_packages"),fee==null?currency:fee.string("currency"),
                fee==null?amount.add(row.decimal("additional_fee_amount")):fee.decimal("limit_amount").subtract(fee.decimal("used_amount")),
                fee==null?tokens+row.longValue("additional_token_limit"):fee.longValue("token_limit")-fee.longValue("used_tokens"));
    }
    private static long elapsed(SqlRow row) {
        var claimed=row.timestamp("claimed_at");var at=row.timestamp("updated_at");
        return row.longValue("total_execution_seconds")+(claimed==null||at==null?0:Math.max(0,Duration.between(claimed.toInstant(),at.toInstant()).getSeconds()));
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw LabException.invalid("重跑参数无效");}}
}
