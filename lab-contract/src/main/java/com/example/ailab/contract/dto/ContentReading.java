package com.example.ailab.contract.dto;

import java.util.*;
import com.example.ailab.contract.error.LabException;

/** 结构阅读v1；自然正文拥有区间与调用打包独立，背景不计入覆盖。 */
public final class ContentReading {
    private ContentReading() { }
    public static final String PROTOCOL = "structure-read-v1";
    public static final String COMPACT_PROTOCOL = "structure-read-v2-compact";
    public static boolean supported(String protocol){return PROTOCOL.equals(protocol)||COMPACT_PROTOCOL.equals(protocol);}
    public static final int MAX_PACK_FRAGMENTS = 64;
    public record Document(long knowledgeBaseId, long documentId, int documentVersion, long processingRevision,
                           String title, String format, String textHash, int textLength, List<SectionSnapshot> sections) {
        public Document { sections = List.copyOf(sections); }
    }
    public record Context(int startOffset, int endOffset, String role) { }
    public record Fragment(Learning.SourceSlice source, String naturalId, String sectionId, String headingPath,
                           String blockType, int partIndex, List<Context> contexts, String splitReason,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) List<Context> naturalBlocks) {
        public Fragment { contexts = List.copyOf(contexts);naturalBlocks=naturalBlocks==null?List.of():List.copyOf(naturalBlocks); }
        public Fragment(Learning.SourceSlice source,String naturalId,String sectionId,String headingPath,String blockType,int partIndex,List<Context> contexts,String splitReason){this(source,naturalId,sectionId,headingPath,blockType,partIndex,contexts,splitReason,List.of());}
    }
    public record Pack(String packId, long documentId, List<String> fragmentIds, int inputBytes) {
        public Pack { fragmentIds = List.copyOf(fragmentIds); }
    }
    public record Plan(String protocol, List<Document> documents, List<Fragment> fragments, List<Pack> packs, List<SourceDependency> sources) {
        public Plan { documents = List.copyOf(documents); fragments = List.copyOf(fragments); packs = List.copyOf(packs); sources=List.copyOf(sources); }
        /** 校验区间并集及每片唯一负责包，不能用ID齐全冒充完整阅读。 */
        public void validate(int readingPackBytes) {
            if (!supported(protocol) || documents.isEmpty() || fragments.isEmpty() || packs.isEmpty()
                    || fragments.size() > 20000 || packs.size() > 10000) throw invalid();
            var docs = new HashMap<Long,Document>();var cursors = new HashMap<Long,Integer>();var slices = new HashMap<String,Fragment>();
            var sectionOwners=new HashMap<Long,Map<String,SectionSnapshot>>();var sectionEnds=new HashMap<Long,Map<String,Integer>>();
            for (var d : documents) if (d.documentVersion() <= 0 || d.processingRevision() <= 0 || d.textLength() <= 0
                    || docs.putIfAbsent(d.documentId(),d) != null) throw invalid();
            if(sources.size()>32 || new HashSet<>(sources).size()!=sources.size())throw invalid();
            for(var d:documents)if(!sources.contains(new SourceDependency(d.knowledgeBaseId(),d.documentId(),d.documentVersion())))throw invalid();
            for(var d:documents) {
                var owners=new HashMap<String,SectionSnapshot>();var ends=new HashMap<String,Integer>();int previous=0,ordinal=-1;
                if(d.sections().isEmpty()||d.sections().get(0).startOffset()!=0||d.sections().get(0).endOffset()!=d.textLength())throw invalid();
                for(int i=0;i<d.sections().size();i++) {
                    var section=d.sections().get(i);int end=i+1<d.sections().size()?d.sections().get(i+1).startOffset():d.textLength();
                    if(section.startOffset()<previous||section.ordinal()<=ordinal||end<section.startOffset()||end>section.endOffset()
                            ||section.endOffset()>d.textLength()||owners.putIfAbsent(section.sectionId(),section)!=null)throw invalid();
                    ends.put(section.sectionId(),end);previous=section.startOffset();ordinal=section.ordinal();
                }
                sectionOwners.put(d.documentId(),owners);sectionEnds.put(d.documentId(),ends);
            }
            for (var fragment : fragments) {
                var s=fragment.source();var d=docs.get(s.documentId());
                if (d==null || d.documentVersion()!=s.documentVersion() || d.processingRevision()!=s.processingRevision()
                        || d.knowledgeBaseId()!=s.knowledgeBaseId() || s.startOffset()!=cursors.getOrDefault(s.documentId(),0)
                        || s.endOffset()<=s.startOffset() || s.endOffset()>d.textLength() || slices.putIfAbsent(s.id(),fragment)!=null) throw invalid();
                var section=sectionOwners.get(d.documentId()).get(fragment.sectionId());
                if(section==null||!section.headingPath().equals(fragment.headingPath())||s.startOffset()<section.startOffset()
                        ||s.endOffset()>sectionEnds.get(d.documentId()).get(fragment.sectionId()))throw invalid();
                for(var c:fragment.contexts()) if(c.startOffset()<0||c.endOffset()<=c.startOffset()||c.endOffset()>d.textLength())throw invalid();
                if(COMPACT_PROTOCOL.equals(protocol)) {
                    int cursor=s.startOffset();for(var block:fragment.naturalBlocks()){if(block.startOffset()!=cursor||block.endOffset()<=cursor||block.endOffset()>s.endOffset())throw invalid();cursor=block.endOffset();}
                    if(cursor!=s.endOffset())throw invalid();
                }
                cursors.put(s.documentId(),s.endOffset());
            }
            for(var d:documents)if(cursors.getOrDefault(d.documentId(),0)!=d.textLength())throw invalid();
            var assigned=new HashSet<String>();var packIds=new HashSet<String>();
            for(var pack:packs) {
                if(!packIds.add(pack.packId())||pack.fragmentIds().isEmpty()||pack.fragmentIds().size()>MAX_PACK_FRAGMENTS||pack.inputBytes()>readingPackBytes||pack.inputBytes()<=0)throw invalid();
                int last=-1;
                for(String id:pack.fragmentIds()) {
                    var f=slices.get(id);if(f==null||f.source().documentId()!=pack.documentId()||!assigned.add(id)
                            ||last>=0&&last!=f.source().startOffset())throw invalid();last=f.source().endOffset();
                }
            }
            if(!assigned.equals(slices.keySet()))throw invalid();
        }
    }
    /** 模型只提出语义卡；来源、章节路径、计数由程序补充。 */
    public record TopicDraft(String id, String title, String summary, List<String> keywords, List<String> entities,
                             String contentRole, List<String> conditions, List<String> relations,
                             List<String> dependencyItemIds, List<String> itemIds) {
        public TopicDraft { keywords=List.copyOf(keywords);entities=List.copyOf(entities);conditions=List.copyOf(conditions);
            relations=List.copyOf(relations);dependencyItemIds=List.copyOf(dependencyItemIds);itemIds=List.copyOf(itemIds); }
    }
    public record Observation(String fragmentId, List<Learning.Item> items, List<TopicDraft> topics) {
        public Observation { items=List.copyOf(items);topics=List.copyOf(topics); }
    }
    public record Batch(List<Observation> fragments, String status) { public Batch { fragments=List.copyOf(fragments); } }
    public record Topics(List<TopicDraft> topics) { public Topics { topics=List.copyOf(topics); } }
    public record TopicCard(String id, String fragmentId, String sectionId, String headingPath, TopicDraft topic,
                            int factCount, int sourceBytes) { }
    public record Evidence(String itemId, String fragmentId, int quoteStartOffset, int quoteEndOffset,
                           String quoteHash) { }
    public record Cards(List<TopicCard> cards) { public Cards { cards=List.copyOf(cards); } }
    public record Evidences(List<Evidence> evidence) { public Evidences { evidence=List.copyOf(evidence); } }
    public record Snapshot(Plan plan, String planHash, int completedFragments, int completedPacks,
                           boolean fullSourceRead, List<TopicCard> topicCards) {
        public Snapshot { topicCards=List.copyOf(topicCards); }
    }
    public static LabException invalid() { return new LabException("WORKFLOW_STATE_CONFLICT","结构阅读计划、范围或覆盖证明不一致"); }
}
