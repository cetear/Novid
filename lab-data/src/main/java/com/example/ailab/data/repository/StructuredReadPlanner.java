package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ContentReading.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;

/** 复用已激活章节及块映射；不重解析文档，也不采用检索片的重叠作为阅读覆盖。 */
public final class StructuredReadPlanner {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON=new com.fasterxml.jackson.databind.ObjectMapper();
    private static final int PACK_OVERHEAD=TextWindow.count("{\"protocol\":\"structure-read-v1\",\"fragments\":[]}");
    private static final int COMPACT_FRAGMENT_BYTES=2400;
    private StructuredReadPlanner() { }
    private record Block(String id,String type,int start,int end) { }
    public record Input(DocumentContent content,List<SectionSnapshot> sections,List<TextMapping> mappings) { }
    public static Plan build(List<Input> inputs,ContentWorkflow.Policy policy) {
        return build(inputs,policy,false);
    }
    public static Plan build(List<Input> inputs,ContentWorkflow.Policy policy,boolean compact) {
        return build(inputs,policy,compact?ContentReading.COMPACT_PROTOCOL:ContentReading.PROTOCOL);
    }
    public static Plan progressive(List<Input> inputs,ContentWorkflow.Policy policy) {
        return build(inputs,policy,ContentReading.PROGRESS_PROTOCOL);
    }
    private static Plan build(List<Input> inputs,ContentWorkflow.Policy policy,String protocol) {
        boolean compact=ContentReading.compact(protocol);
        int extra=protocol.length()-ContentReading.PROTOCOL.length();int overhead=PACK_OVERHEAD+extra;
        var documents=new ArrayList<Document>();var fragments=new ArrayList<Fragment>();var packs=new ArrayList<Pack>();
        var sources=new LinkedHashSet<SourceDependency>();
        int totalBytes=0;
        for(var input:inputs) {
            var content=input.content();var d=content.document();String text=content.text();
            sources.add(new SourceDependency(d.knowledgeBaseId(),d.id(),d.documentVersion()));sources.addAll(content.sourceDependencies());
            if(d.activeProcessingRevision()==null)throw new LabException("INDEX_NOT_READY","资料须先完成结构入库");
            if(text.isBlank())throw new LabException("WORKFLOW_NEEDS_INPUT","所选资料正文为空");
            totalBytes=Math.addExact(totalBytes,TextWindow.count(text));
            if(totalBytes>policy.sourceBytes())throw new LabException("WORKFLOW_INPUT_TOO_LARGE","资料超过创建时原文容量");
            var sections=input.sections().stream().sorted(Comparator.comparingInt(SectionSnapshot::ordinal)).toList();
            validateSections(sections,text);
            documents.add(new Document(d.knowledgeBaseId(),d.id(),d.documentVersion(),d.activeProcessingRevision(),d.title(),d.format(),Learning.digest(text),text.length(),sections));
            var blockMap=new LinkedHashMap<String,Block>();
            for(var m:input.mappings()) {
                if(m.repeatedHeader())continue;
                if(m.sourceStartOffset()<0||m.sourceEndOffset()>text.length()||m.sourceEndOffset()<=m.sourceStartOffset()
                        ||!TextWindow.boundary(text,m.sourceStartOffset())||!TextWindow.boundary(text,m.sourceEndOffset()))throw ContentReading.invalid();
                var b=new Block(m.blockId(),m.blockType(),m.sourceStartOffset(),m.sourceEndOffset());
                blockMap.merge(b.id(),b,(old,next)->{
                    if(!old.type().equals(next.type())||next.start()>old.end()||old.start()>next.end())throw ContentReading.invalid();
                    return new Block(old.id(),old.type(),Math.min(old.start(),next.start()),Math.max(old.end(),next.end()));
                });
            }
            // TXT及旧映射批次按原文空行固定自然段，不将整个章节当作一个自然块。
            if(blockMap.isEmpty()) {
                var paragraphs=java.util.regex.Pattern.compile("(?:\\r?\\n)[\\t ]*(?:\\r?\\n)+").matcher(text);int from=0,index=0;
                while(paragraphs.find()) {int to=paragraphs.end();blockMap.put("paragraph_"+index,new Block("paragraph_"+(index++),"PARAGRAPH",from,to));from=to;}
                if(from<text.length())blockMap.put("paragraph_"+index,new Block("paragraph_"+index,"PARAGRAPH",from,text.length()));
            }
            else if("txt".equalsIgnoreCase(d.format())||"TEXT".equalsIgnoreCase(d.format())) {
                var natural=new LinkedHashMap<String,Block>();
                for(var block:blockMap.values()) {
                    if(!"PARAGRAPH".equals(block.type())) {natural.put(block.id(),block);continue;}
                    var paragraphs=java.util.regex.Pattern.compile("(?:\\r?\\n)[\\t ]*(?:\\r?\\n)+").matcher(text).region(block.start(),block.end());
                    int from=block.start(),index=0;
                    while(paragraphs.find()) {int to=paragraphs.end();String id=block.id()+"_p"+(index++);natural.put(id,new Block(id,block.type(),from,to));from=to;}
                    if(from<block.end()){String id=block.id()+"_p"+index;natural.put(id,new Block(id,block.type(),from,block.end()));}
                }
                blockMap=natural;
            }
            var blocks=blockMap.values().stream().sorted(Comparator.comparingInt(Block::start)).toList();
            int previous=0;for(var b:blocks){if(b.start()<previous)throw ContentReading.invalid();previous=b.end();}
            var documentFragments=new ArrayList<Fragment>();
            for(int i=0;i<sections.size();i++) {
                var section=sections.get(i);int ownStart=section.startOffset(),ownEnd=i+1<sections.size()?sections.get(i+1).startOffset():text.length();
                int cursor=ownStart;
                for(var b:blocks) {
                    int start=Math.max(ownStart,b.start()),end=Math.min(ownEnd,b.end());if(start>=end)continue;
                    if(cursor<start)add(text,d,section,cursor,start,"GAP","TEXT",policy.pageBytes()-extra,compact,fragments,documentFragments);
                    add(text,d,section,start,end,b.id(),b.type(),policy.pageBytes()-extra,compact,fragments,documentFragments);cursor=end;
                }
                if(cursor<ownEnd)add(text,d,section,cursor,ownEnd,"TEXT_"+cursor,"TEXT",policy.pageBytes()-extra,compact,fragments,documentFragments);
            }
            if(compact){var merged=merge(text,documentFragments,policy.pageBytes(),overhead);fragments.subList(fragments.size()-documentFragments.size(),fragments.size()).clear();documentFragments=new ArrayList<>();
                for(var f:merged){var old=f.source();var slice=new Learning.SourceSlice(String.format(Locale.ROOT,"p%05d",fragments.size()+1),old.knowledgeBaseId(),old.documentId(),old.documentVersion(),old.processingRevision(),old.title(),old.startOffset(),old.endOffset(),old.textHash());
                    var renamed=new Fragment(slice,f.naturalId(),f.sectionId(),f.headingPath(),f.blockType(),f.partIndex(),f.contexts(),f.splitReason(),f.naturalBlocks());fragments.add(renamed);documentFragments.add(renamed);}
            }
            var group=new ArrayList<String>();int bytes=overhead,output=0;
            for(var f:documentFragments) {
                int size=payloadBytes(text,f.source().id(),f.headingPath(),f.blockType(),f.source().startOffset(),f.source().endOffset(),f.contexts());
                int expected=TextWindow.count(text.substring(f.source().startOffset(),f.source().endOffset()))*2+200;
                if(!group.isEmpty()&&(bytes+size+1>policy.readingPackBytes()||group.size()>=ContentReading.MAX_PACK_FRAGMENTS||compact&&output+expected>60000)) {
                    packs.add(new Pack(String.format(Locale.ROOT,"rp%05d",packs.size()+1),d.id(),group,bytes));group=new ArrayList<>();bytes=overhead;output=0;
                }
                if(!group.isEmpty())bytes++;group.add(f.source().id());bytes+=size;output+=expected;
            }
            if(!group.isEmpty())packs.add(new Pack(String.format(Locale.ROOT,"rp%05d",packs.size()+1),d.id(),group,bytes));
        }
        var plan=new Plan(protocol,documents,fragments,packs,List.copyOf(sources));plan.validate(policy.readingPackBytes());
        if(packs.size()>policy.turns())throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","固定阅读包数量超过父任务调用额度");
        return plan;
    }
    private static ArrayList<Fragment> merge(String text,List<Fragment> input,int pageBudget,int overhead) {
        var out=new ArrayList<Fragment>();
        for(var current:input) {
            var blocks=List.of(new Context(current.source().startOffset(),current.source().endOffset(),current.blockType()));
            var next=new Fragment(current.source(),current.naturalId(),current.sectionId(),current.headingPath(),current.blockType(),current.partIndex(),current.contexts(),current.splitReason(),blocks);
            if(!out.isEmpty()) {
                var previous=out.get(out.size()-1);var a=previous.source();var b=next.source();
                if(previous.sectionId().equals(next.sectionId())&&a.endOffset()==b.startOffset()&&previous.contexts().isEmpty()&&next.contexts().isEmpty()
                        &&Set.of("PARAGRAPH","TEXT","LIST","WHITESPACE").contains(previous.blockType())&&Set.of("PARAGRAPH","TEXT","LIST","WHITESPACE").contains(next.blockType())
                        &&TextWindow.count(text.substring(a.startOffset(),b.endOffset()))<=COMPACT_FRAGMENT_BYTES&&payloadBytes(text,a.id(),previous.headingPath(),"TEXT",a.startOffset(),b.endOffset(),List.of())+overhead<=pageBudget) {
                    var ranges=new ArrayList<>(previous.naturalBlocks());ranges.addAll(next.naturalBlocks());
                    var slice=new Learning.SourceSlice(a.id(),a.knowledgeBaseId(),a.documentId(),a.documentVersion(),a.processingRevision(),a.title(),a.startOffset(),b.endOffset(),Learning.digest(text.substring(a.startOffset(),b.endOffset())));
                    out.set(out.size()-1,new Fragment(slice,previous.naturalId(),previous.sectionId(),previous.headingPath(),"TEXT",0,List.of(),"SEMANTIC_BLOCK",ranges));continue;
                }
            }
            out.add(next);
        }return out;
    }
    private static void add(String text,DocumentSnapshot doc,SectionSnapshot section,int start,int end,String natural,String type,int budget,boolean compact,
                            List<Fragment> all,List<Fragment> document) {
        int headerEnd=start;
        if("TABLE".equals(type)) {
            int first=text.indexOf('\n',start),second=first<0?-1:text.indexOf('\n',first+1);
            headerEnd=second>=0&&second<end?second+1:end;
            if(TextWindow.count(text.substring(start,headerEnd))+100>budget/2 && TextWindow.count(text.substring(start,end))+96>budget)
                throw new LabException("WORKFLOW_UNIT_TOO_LARGE","表头超过阅读包容量，无法保留必要上下文");
        }
        else if("CODE".equals(type)||"LIST".equals(type)) {
            int line=text.indexOf('\n',start);if(line>=start&&line<end&&TextWindow.count(text.substring(start,line+1))<=budget/4)headerEnd=line+1;
        }
        int headerBytes=TextWindow.count(text.substring(start,headerEnd));
        int cursor=start,part=0;
        while(cursor<end) {
            var contexts=cursor>=headerEnd&&headerEnd>start?List.of(new Context(start,headerEnd,"TABLE".equals(type)?"TABLE_HEADER":"BLOCK_OPENING")):List.<Context>of();
            int background=contexts.isEmpty()?0:TextWindow.count(text.substring(start,headerEnd));
            int bodyBudget=budget-96-background;
            // 正文目标独立于路径／必要背景容量；表头完整性仍按原服务器容量检查。
            if(compact)bodyBudget=Math.min(bodyBudget,Math.max(COMPACT_FRAGMENT_BYTES,cursor==start&&"TABLE".equals(type)?headerBytes:0));
            int next=cut(text,cursor,end,bodyBudget,"CODE".equals(type)||"TABLE".equals(type)||"LIST".equals(type));
            String id=String.format(Locale.ROOT,"p%05d",all.size()+1);
            int maximum=TextWindow.count(text.substring(cursor,next));
            while(PACK_OVERHEAD+payloadBytes(text,id,section.headingPath(),type,cursor,next,contexts)>budget) {
                maximum-=Math.max(1,PACK_OVERHEAD+payloadBytes(text,id,section.headingPath(),type,cursor,next,contexts)-budget);
                if(maximum<=0)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","片段路径和必要背景超过阅读包容量");
                next=cut(text,cursor,next,maximum,"CODE".equals(type)||"TABLE".equals(type)||"LIST".equals(type));
            }
            if("TABLE".equals(type)&&cursor==start&&next<headerEnd)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","表头及章节路径超过阅读包容量");
            var slice=new Learning.SourceSlice(id,doc.knowledgeBaseId(),doc.id(),doc.documentVersion(),
                    doc.activeProcessingRevision(),doc.title(),cursor,next,Learning.digest(text.substring(cursor,next)));
            var f=new Fragment(slice,natural,section.sectionId(),section.headingPath(),type,part++,contexts,
                    cursor==start&&next==end?"NATURAL_BLOCK":"INPUT_BUDGET");
            all.add(f);document.add(f);cursor=next;
            if(all.size()>20000)throw new LabException("WORKFLOW_INPUT_TOO_LARGE","自然阅读片段超过独立上限");
        }
    }
    private static int payloadBytes(String text,String id,String path,String type,int start,int end,List<Context> contexts) {
        try{return TextWindow.count(JSON.writeValueAsString(Map.of("fragmentId",id,"headingPath",path,"blockType",type,"ownedText",text.substring(start,end),
                "background",contexts.stream().map(c->Map.of("role",c.role(),"text",text.substring(c.startOffset(),c.endOffset()))).toList())));}
        catch(Exception invalid){throw ContentReading.invalid();}
    }
    static int cut(String text,int start,int end,int maximum,boolean structured) {
        int hard=TextWindow.end(text,start,end,maximum);if(hard<=start)throw ContentReading.invalid();if(hard==end)return end;
        for(int i=hard-1;i>start;i--) {
            char c=text.charAt(i);
            if(c=='\n'||!structured&&"。！？.!?;；".indexOf(c)>=0) {
                int candidate=i+1;if(TextWindow.boundary(text,candidate))return candidate;
            }
        }
        return hard;
    }
    private static void validateSections(List<SectionSnapshot> sections,String text) {
        if(sections.isEmpty()||sections.size()>5001||sections.get(0).ordinal()!=0||sections.get(0).startOffset()!=0
                ||sections.get(0).endOffset()!=text.length())throw new LabException("INDEX_NOT_READY","缺少完整章节结构，请重新入库");
        var seen=new HashMap<String,SectionSnapshot>();int start=0,lastOrdinal=-1;
        for(var s:sections) {
            if(s.ordinal()<=lastOrdinal||s.startOffset()<start||s.endOffset()<s.startOffset()||s.endOffset()>text.length()
                    ||!TextWindow.boundary(text,s.startOffset())||!TextWindow.boundary(text,s.endOffset())||seen.containsKey(s.sectionId()))throw ContentReading.invalid();
            if(s.parentSectionId()!=null) {
                var p=seen.get(s.parentSectionId());if(p==null||s.startOffset()<p.startOffset()||s.endOffset()>p.endOffset())throw ContentReading.invalid();
            }
            seen.put(s.sectionId(),s);start=s.startOffset();lastOrdinal=s.ordinal();
        }
    }
}
