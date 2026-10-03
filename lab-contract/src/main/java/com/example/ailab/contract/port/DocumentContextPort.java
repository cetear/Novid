package com.example.ailab.contract.port;

import java.util.*;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;

/**
 * DocumentContextPort，所有状态／执行权由权威数据库复核。
 */
public interface DocumentContextPort {
    /**
     * 当前文档目录，资料授权与处理批次复核。
     */
    List<SectionSnapshot> sections(AuthorizedKnowledgeScope scope, long documentId, int offset, int limit);

    /**
     * 当前文档小片，保留真实父段与位置。
     */
    List<ChunkSnapshot> chunks(AuthorizedKnowledgeScope scope, long documentId, int offset, int limit);

    /** 绝对游标分页，显式绑定内容版本及激活代次；null sectionId 读取虚拟根全文。 */
    SectionPage sectionPage(AuthorizedKnowledgeScope scope, long documentId, int documentVersion,
                            long processingRevision, String sectionId, Integer afterOffset, int maxTokens);

    /** 当前内容的最新入库元数据，经原文及派生来源授权；失败可查，未激活不读结构。 */
    IngestionMetadata ingestion(AuthorizedKnowledgeScope scope, long documentId);

    /** 全文覆盖按目录正文顺序推进，遇到下一标题结束当前页，不重复父章节的子树。 */
    default SectionPage documentPage(AuthorizedKnowledgeScope scope,long documentId,int version,long revision,int after,int maxTokens) {
        return sectionPage(scope,documentId,version,revision,null,after,maxTokens);
    }
}
