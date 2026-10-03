package com.example.ailab.contract.port;
import java.util.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
/** KnowledgeIndexPort，所有状态／执行权由权威数据库复核。 */
public interface KnowledgeIndexPort {
    /** 受控初始化索引，不销毁已存在索引。 */ void initialize();
    /** 同批次 ID 幂等写入，返回前等待批次可搜索。 */ void index(List<IndexedChunk> chunks);
    /** 校验每项 ID、hash、模型版本和向量维度。 */ void verify(List<IndexedChunk> chunks);
    /** 删除至多 256 项；确认目标范围内零剩余时才返回 true。 */ boolean cleanup(IndexCleanupLease lease);
}
