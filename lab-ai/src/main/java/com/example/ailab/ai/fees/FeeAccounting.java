package com.example.ailab.ai.fees;

import com.example.ailab.ai.runtime.ExecutionBudget;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.FeeStorePort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 同步费用协调，先预留再消费执行权，发送和响应分别持久化；不得降级为无账本调用。
 */
@Component
public class FeeAccounting {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FeeAccounting.class);
    private final FeeStorePort store;
    private final FeeProperties properties;

    /**
     * AI只持有框架无关费用端口，不访问SQL或观测节点。
     */
    public FeeAccounting(FeeStorePort store, FeeProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    /**
     * 缺价不造金额，未知引用保留用量；费用归属必须由正式在线／后台入口绑定。
     */
    public FeeReservation reserve(ExecutionBudget budget, String modelId, String type, long input, long output,
                                  String priceRef, boolean simulated) {
        var scope = budget.feeScope();
        if (scope == null) throw new LabException("FEE_SCOPE_REQUIRED", "模型调用缺少服务端费用归属");
        var price = properties.prices().get(priceRef);
        if (price != null && price.effectiveAt().isAfter(Instant.now()))
            throw new LabException("FEE_PRICE_UNAVAILABLE", "价格尚未生效");
        var limit = switch (scope.kind()) {
            case "TASK" -> properties.taskLimit();
            case "INGESTION" -> properties.ingestionLimit();
            default -> properties.onlineLimit();
        };
        long tokens = switch (scope.kind()) {
            case "TASK" -> properties.taskTokens();
            case "INGESTION" -> properties.ingestionTokens();
            default -> properties.onlineTokens();
        };
        try {
            return store.reserve(scope, UUID.randomUUID().toString(), modelId, type, input, output, price, properties.currency(), limit, tokens, simulated);
        } catch (LabException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable("RESERVE", e);
        }
    }

    /**
     * 发送前已回滚的锁冲突可在新事务中补登记一次；不重新预留或消费模型执行权。
     */
    public void sending(FeeReservation reservation) {
        boolean independent = !org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive();
        for (int retry = 0; retry < 2; retry++) {
            try {
                store.sending(reservation);
                return;
            } catch (LabException e) {
                throw e;
            } catch (RuntimeException e) {
                // 无外层事务时，仓库代理已完成回滚；下一次调用才会获得全新的事务。
                if (retry == 0 && independent && rolledBackLock(e))
                    LOG.warn("event=fee.sending_lock_retry retry=1");
                else throw unavailable("SENDING", e);
            }
        }
    }

    private boolean rolledBackLock(RuntimeException failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 16; depth++, cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLTransactionRollbackException sql
                    && (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213)
                    && ("40001".equals(sql.getSQLState()) || "HY000".equals(sql.getSQLState()))) return true;
        }
        return false;
    }

    /**
     * 仅确定未发送释放；数据库失败保留未知窗口，不能吞错当退款。
     */
    public void release(FeeReservation reservation) {
        guarded("RELEASE", () -> store.release(reservation));
    }

    /**
     * 响应持久化至多两次，同键重写无需再次请求模型；失败不宣称已结算。
     */
    public void complete(FeeReservation reservation, Integer input, Integer output, String outcome) {
        for (int retry = 0; retry < 2; retry++) {
            try {
                store.complete(reservation, input, output, outcome);
                return;
            } catch (LabException e) {
                throw e;
            } catch (RuntimeException e) {
                if (retry == 1) throw unavailable("COMPLETE", e);
            }
        }
    }

    /**
     * 稳定错误不带连接地址和供应商正文。
     */
    private void guarded(String stage, Runnable action) {
        try {
            action.run();
        } catch (LabException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable(stage, e);
        }
    }

    /**
     * 可靠账本失效必须停止新付费调用，与可丢追踪的降级不同。
     */
    private LabException unavailable(String stage, RuntimeException failure) {
        String state = "UNKNOWN"; Integer errorCode = null;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql) {
                if (sql.getSQLState() != null && sql.getSQLState().matches("[A-Z0-9]{5}")) state = sql.getSQLState();
                errorCode = sql.getErrorCode();
                break;
            }
        }
        LOG.warn("event=fee.ledger_failed stage={} sqlState={} sqlErrorCode={}", stage, state, errorCode,
                com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));
        return new LabException("FEE_LEDGER_UNAVAILABLE", "可靠费用记录暂不可用，请核对原操作");
    }
}
