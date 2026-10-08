package com.example.ailab.ai.model;

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
            throw unavailable();
        }
    }

    /**
     * 发送许可写失败时绝不调用远程；已有预留保持可对账。
     */
    public void sending(FeeReservation reservation) {
        guarded(() -> store.sending(reservation));
    }

    /**
     * 仅确定未发送释放；数据库失败保留未知窗口，不能吞错当退款。
     */
    public void release(FeeReservation reservation) {
        guarded(() -> store.release(reservation));
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
                if (retry == 1) throw unavailable();
            }
        }
    }

    /**
     * 稳定错误不带连接地址和供应商正文。
     */
    private void guarded(Runnable action) {
        try {
            action.run();
        } catch (LabException e) {
            throw e;
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    /**
     * 可靠账本失效必须停止新付费调用，与可丢追踪的降级不同。
     */
    private LabException unavailable() {
        return new LabException("FEE_LEDGER_UNAVAILABLE", "可靠费用记录暂不可用，请核对原操作");
    }
}
