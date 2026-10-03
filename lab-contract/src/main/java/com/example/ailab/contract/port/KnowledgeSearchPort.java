package com.example.ailab.contract.port;
import java.util.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
/** KnowledgeSearchPort，所有状态／执行权由权威数据库复核。 */
public interface KnowledgeSearchPort {
    /** BM25 和 kNN 均预过滤可信范围，由 Java 做 RRF。 */ List<ChunkCandidate> search(AuthorizedKnowledgeScope scope,String query,List<Float> vector,String embeddingModelVersion);
    /** 本批次可信匹配小片与父段扩展，经 MySQL 权限及版本复核。 */ List<EvidenceBundle> expand(AuthorizedKnowledgeScope scope,List<ChunkCandidate> candidates,int maxEvidenceBytes);
}
