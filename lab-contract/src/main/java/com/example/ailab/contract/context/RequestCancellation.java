package com.example.ailab.contract.context;

import com.example.ailab.contract.error.LabException;

import java.util.function.Supplier;

/**
 * 请求生命周期信号；本地取消与短提交按同一监视器排序，不伪装供应商已停止计费。
 */
public final class RequestCancellation {
    private boolean cancelled;

    /**
     * 断线或等待超时后拒绝后续模型步骤与尚未开始的本地提交。
     */
    public synchronized void cancel() {
        cancelled = true;
    }

    /**
     * 共享预算在每个步骤边界检查请求是否仍允许继续。
     */
    public synchronized void check() {
        if (cancelled || Thread.currentThread().isInterrupted())
            throw new LabException("REQUEST_CANCELLED", "请求已停止，未开始的答案提交已取消");
    }

    /**
     * 取消先到则零提交；提交先开始则保留真实数据库事实，不宣称客户端已收到。
     */
    public synchronized <T> T commit(Supplier<T> action) {
        check();
        return action.get();
    }
}
