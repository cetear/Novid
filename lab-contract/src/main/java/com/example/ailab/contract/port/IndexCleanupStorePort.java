package com.example.ailab.contract.port;

import com.example.ailab.contract.dto.IndexCleanupLease;
import java.util.Optional;

/** 清理意图与租约的权威数据库端口，远程调用在事务外执行。 */
public interface IndexCleanupStorePort {
    /** 有界领取已确认删除或成功激活产生的事件。 */
    Optional<IndexCleanupLease> claim(String workerId);
    /** 单批未完成则重新排队，确认不存在剩余索引项后才置 DONE。 */
    void finish(IndexCleanupLease lease,boolean complete);
    /** 故障保留意图并延迟重试，最多三次异常尝试。 */
    void fail(IndexCleanupLease lease);
}
