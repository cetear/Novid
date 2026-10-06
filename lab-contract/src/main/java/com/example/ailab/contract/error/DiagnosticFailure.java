package com.example.ailab.contract.error;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 日志只保留异常类型与调用位置；原异常消息可能携带SQL、凭证或用户正文。 */
public final class DiagnosticFailure {
    private DiagnosticFailure() { }

    public static String code(Throwable failure) {
        return failure instanceof LabException lab && lab.code().matches("[A-Z0-9_]{1,80}")
                ? lab.code() : "INTERNAL_ERROR";
    }

    /** 有界复制原因链，不保留原异常实例、消息或 suppressed 内容。 */
    public static Throwable sanitized(Throwable failure) {
        return copy(failure, Collections.newSetFromMap(new IdentityHashMap<>()), 0);
    }

    private static Throwable copy(Throwable failure, Set<Throwable> seen, int depth) {
        if (failure == null || depth >= 8 || !seen.add(failure)) return null;
        var safe = new Throwable(failure.getClass().getName() + " code=" + code(failure));
        safe.setStackTrace(Arrays.copyOf(failure.getStackTrace(), Math.min(64, failure.getStackTrace().length)));
        var cause = copy(failure.getCause(), seen, depth + 1);
        if (cause != null) safe.initCause(cause);
        return safe;
    }
}
