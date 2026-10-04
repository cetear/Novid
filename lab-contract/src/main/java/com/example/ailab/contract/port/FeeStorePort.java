package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 可靠账本同步窄端口，不经过观测队列；远程调用始终在这些短事务之外。 */
public interface FeeStorePort {
    /** 原子创建范围并预留；恢复沿用首次上限，价格随本次尝试保存不可变快照。 */
    FeeReservation reserve(FeeScope scope, String operationId, String modelId, String operationType,
                           long input, long output, FeePrice price, String currency, BigDecimal limit, long tokenLimit, boolean simulated);
    /** 发送前标记不确定窗口；只允许 RESERVED 一次转为 SENDING。 */
    void sending(FeeReservation reservation);
    /** 只释放有确定未发送事实的 RESERVED；不退款 SENDING 或 UNKNOWN。 */
    void release(FeeReservation reservation);
    /** 保存已知／未知用量，重复结果幂等；UNKNOWN 可被同一提供方响应补全但不能改已定价事实。 */
    void complete(FeeReservation reservation, Integer input, Integer output, String outcome);
    /** 本人摘要可以独立于观测保留期读取，运行筛选只统计本次真实尝试。 */
    FeeSummary summary(UserContext actor, String kind, String resourceId);
    /** 管理员仅低基数聚合；时间窗口有界，不提供私人钻取。 */
    List<FeeAggregate> aggregate(UserContext actor, Instant since);
    /** 有界把崩溃遗留发送标为UNKNOWN，保持全部预留，不自动退款或重新购买。 */
    int markUnknownBefore(Instant before, int maximum);
}
