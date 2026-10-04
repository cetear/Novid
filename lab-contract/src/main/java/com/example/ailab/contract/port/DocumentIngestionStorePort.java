package com.example.ailab.contract.port;

import java.util.*;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;

/**
 * DocumentIngestionStorePort，所有状态／执行权由权威数据库复核。
 */
public interface DocumentIngestionStorePort {
    /**
     * 有界扫描并领取或恢复过期租约，fencing 单调增加。
     */
    Optional<IngestionLease> claim(String workerId);

    /**
     * 续租必须匹配执行者及 fencing。
     */
    boolean renew(IngestionLease lease);

    /**
     * 版本化结构原子保存，重试覆盖本批次，不影响已激活批次。
     */
    void saveStructure(IngestionLease lease, ParsedDocument parsed);

    /** 原子保存稳定批次计划，已有计划只接受完全相同的输入。 */
    void plan(IngestionLease lease, List<IngestionBatchPlan> batches);

    /** 仅读取单批向量，避免恢复时加载全文向量。 */
    IngestionBatch batch(IngestionLease lease, int ordinal);

    /** 返回数据库累计预算及首次执行期限。 */
    IngestionBudget budget(IngestionLease lease);

    /** 每次真实远程尝试之前持久预留额度和未知结果意图。 */
    void beginEmbedding(IngestionLease lease, int ordinal);

    /** 保存完整向量及提供方用量；缺用量保持未知，不当作零费用。 */
    void completeEmbedding(IngestionLease lease, int ordinal, List<List<Float>> vectors, String model, Integer actualTokens);

    /** 阶段切换在短事务内复核执行权。 */
    void phase(IngestionLease lease, String phase);

    /** 搜索可见且每项匹配后保存批次索引成功事实。 */
    void indexed(IngestionLease lease, int ordinal);

    /**
     * 远程成功后 CAS 激活；迟到执行者不能提交。
     */
    void activate(IngestionLease lease, int expectedChunks);

    /**
     * 持久化真实失败码，有限重试，不能标为 READY。
     */
    void fail(IngestionLease lease, String code);

    /**
     * 请求 owner 技术重处理，不伪造内容版本。
     */
    void reprocess(UserContext actor, long documentId);

    /** 本人仅提前恢复指定失败代次；不重置次数、期限、向量或未知结果。 */
    void recover(UserContext actor, long documentId, long processingRevision);
}
