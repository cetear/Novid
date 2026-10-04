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

    /** 脱敏节点批次写入；旧测试替身不伪装已持久化节点。 */
    default void recordGraph(TraceSnapshot run, List<TraceNode> nodes) { record(run); }
    /** 私人图在端口再次校验所有者，缺节点时明确不完整。 */
    default TraceGraph graph(UserContext actor, String traceId) { return TraceGraph.from(read(actor,traceId), List.of()); }
    /** 恢复只查询同一所有者与逻辑任务，返回上一实际执行。 */
    default String previous(long actorUserId, Long taskId, Long ingestionId) { return null; }
    /** 清理超过保留期的观测，包括崩溃遗留运行；不触碰业务账本。 */
    default int purge(Instant before, int maximum) { return 0; }
    /** 仅记录服务器首次交付边界，不冒充浏览器实际收到或模型TTFT。 */
    default void delivered(UserContext actor,String id,Instant time) { }
}

