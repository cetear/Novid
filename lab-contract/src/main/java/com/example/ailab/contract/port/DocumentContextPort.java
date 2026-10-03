package com.example.ailab.contract.port;
import java.util.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
/** DocumentContextPort，所有状态／执行权由权威数据库复核。 */
public interface DocumentContextPort {
    /** 当前文档目录，资料授权与处理批次复核。 */ List<SectionSnapshot> sections(AuthorizedKnowledgeScope scope,long documentId,int offset,int limit);
    /** 当前文档小片，保留真实父段与位置。 */ List<ChunkSnapshot> chunks(AuthorizedKnowledgeScope scope,long documentId,int offset,int limit);
}
