package com.example.ailab.contract.dto;

import java.util.List;
import java.time.Instant;

/** 整编业务来源与正文位置；权限依赖另外保留，不从展示边推导。 */
public final class Compilation {
    private Compilation() { }
    public record ChapterIdentity(String id,String parentId,String title) { }
    public record SectionMapping(String chapterId, String sectionId, int startOffset, int endOffset,
                                 List<Learning.Citation> evidence,List<ChapterIdentity> chapterPath) {
        public SectionMapping { evidence = List.copyOf(evidence); chapterPath = List.copyOf(chapterPath); }
        public SectionMapping(String chapterId,String sectionId,int start,int end,List<Learning.Citation> evidence) {
            this(chapterId,sectionId,start,end,evidence,List.of());
        }
    }
    public record Source(long knowledgeBaseId, long documentId, int documentVersion, int sourceOrder, String title) { }
    public record ParsedBinding(String chapterId,String logicalSectionId,String parsedSectionId,long processingRevision,
                                int startOffset,int endOffset,String mappingHash) { }
    public record Origin(String originType, int generatedVersion, Long taskId, String resultHash,
                         Instant generatedAt, boolean mappingValid, List<Source> directSources,
                         List<SectionMapping> sections, String bindingStatus, List<ParsedBinding> parsedBindings) {
        public Origin { directSources = List.copyOf(directSources); sections = List.copyOf(sections); parsedBindings=List.copyOf(parsedBindings); }
        public Origin(String type,int version,Long task,String hash,Instant time,boolean valid,List<Source> sources,List<SectionMapping> sections) {
            this(type,version,task,hash,time,valid,sources,sections,valid?"PENDING_PARSE":"NOT_APPLICABLE",List.of());
        }
    }
    public record Participation(long documentId, long knowledgeBaseId, String title, int currentVersion,
                                List<Integer> sourceVersions) {
        public Participation { sourceVersions = List.copyOf(sourceVersions); }
    }
    public record SaveStatus(ApprovalSnapshot approval, DocumentSnapshot document) { }
}
