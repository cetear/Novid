package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * TraceRecordPort 窄端口，实现由运行入口装配。
 */
public interface TraceRecordPort {
    /**
     * 保存脱敏调用事实，失败不改变业务结果。
     */
    void record(TraceSnapshot trace);

    /**
     * 本人运行列表，ADMIN 同样不能读取他人。
     */
    List<TraceSnapshot> list(UserContext actor, int offset, int limit);

    /**
     * 本人运行详情。
     */
    TraceSnapshot read(UserContext actor, String traceId);
}

