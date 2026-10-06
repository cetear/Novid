package com.example.ailab.app;

import com.example.ailab.ai.model.ExecutionBudget;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.DiagnosticFailure;
import com.example.ailab.contract.error.LabException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.*;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 统一覆盖实际业务入口和AI边界，不打印参数对象或返回正文。 */
@Aspect
@Component
public class OperationLoggingAspect {
    private static final Logger LOG = LoggerFactory.getLogger(OperationLoggingAspect.class);

    @Around("execution(public * com.example.ailab.business.application.*ApplicationService.*(..))"
            + " || execution(public * com.example.ailab.ai.model.ModelGateway.chat*(..))"
            + " || execution(public * com.example.ailab.ai.model.ModelGateway.structured(..))"
            + " || execution(public * com.example.ailab.ai.model.ModelGateway.toolTurn(..))"
            + " || execution(public * com.example.ailab.ai.model.ModelGateway.finishToolTurn(..))"
            + " || execution(public * com.example.ailab.ai.model.ModelGateway.embed(..))"
            + " || execution(public * com.example.ailab.ai.model.MediaModelGateway.submit(..))"
            + " || execution(public * com.example.ailab.ai.model.MediaModelGateway.query(..))"
            + " || execution(public * com.example.ailab.ai.tools.ToolExecutionService.execute(..))"
            + " || execution(public * com.example.ailab.ai.orchestration.rag.DocumentIngestionPipeline.execute(..))"
            + " || execution(public * com.example.ailab.app.GovernanceMaintenance.execute(..))")
    public Object record(ProceedingJoinPoint invocation) throws Throwable {
        var previous = MDC.getCopyOfContextMap();
        if (MDC.get("operationId") == null) MDC.put("operationId", UUID.randomUUID().toString());
        for (Object argument : invocation.getArgs()) {
            if (argument instanceof UserContext actor) MDC.put("actorId", Long.toString(actor.userId()));
            if (argument instanceof AiRequest request && request.sessionId() != null)
                MDC.put("sessionId", Long.toString(request.sessionId()));
            if (argument instanceof IngestionLease lease) {
                MDC.put("actorId", Long.toString(lease.actor().userId()));
                MDC.put("ingestionId", Long.toString(lease.ingestionId()));
                MDC.put("documentId", Long.toString(lease.documentId()));
            }
            if (argument instanceof ExecutionBudget budget && budget.feeScope() != null) {
                var scope = budget.feeScope();
                MDC.put("traceId", scope.runId());
                MDC.put("actorId", Long.toString(scope.actor().userId()));
                if ("TASK".equals(scope.kind())) MDC.put("taskId", scope.resourceId());
                if ("INGESTION".equals(scope.kind())) MDC.put("ingestionId", scope.resourceId());
            }
        }
        // 只白名单记录类型化数字资源编号，字符串和DTO永不展开。
        var names = ((MethodSignature) invocation.getSignature()).getParameterNames();
        var arguments = invocation.getArgs();
        if (names != null) for (int index = 0; index < names.length; index++) {
            if (arguments[index] instanceof Number number
                    && java.util.Set.of("id", "baseId", "taskId", "version").contains(names[index]))
                MDC.put("resource_" + names[index], number.toString());
        }
        String operation = invocation.getSignature().toShortString();
        long started = System.nanoTime();
        LOG.info("event=operation.start operation={}", operation);
        try {
            Object result = invocation.proceed();
            String outcome = "SUCCESS";
            if (result instanceof com.example.ailab.ai.tools.ToolExecutionService.Outcome tool) outcome = safeStatus(tool.status());
            if (result instanceof Media.ProviderResult provider) outcome = safeStatus(provider.status());
            if (result instanceof TaskSnapshot task) MDC.put("taskId", Long.toString(task.taskId()));
            if (result instanceof DocumentSnapshot document) MDC.put("documentId", Long.toString(document.id()));
            if (result instanceof LoginResult login) MDC.put("actorId", Long.toString(login.user().id()));
            LOG.info("event=operation.complete operation={} outcome={} resourceId={} version={} durationMs={}",
                    operation, outcome, MDC.get("resource_id"), MDC.get("resource_version"), elapsed(started));
            return result;
        } catch (Throwable failure) {
            if (failure instanceof LabException)
                LOG.warn("event=operation.failed operation={} code={} durationMs={}", operation, DiagnosticFailure.code(failure), elapsed(started));
            else
                LOG.error("event=operation.failed operation={} code={} durationMs={}", operation, DiagnosticFailure.code(failure), elapsed(started), DiagnosticFailure.sanitized(failure));
            throw failure;
        } finally {
            if (previous == null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }

    private static long elapsed(long started) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started); }
    private static String safeStatus(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,80}") ? value : "UNKNOWN";
    }
}
