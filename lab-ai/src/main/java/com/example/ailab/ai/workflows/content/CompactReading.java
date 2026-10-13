package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import java.util.*;
import static com.example.ailab.ai.model.RecordSchema.*;
import static com.example.ailab.ai.model.RecordSchema.FieldRule.*;

/** 模型提出事实和条件，编号、来源身份与索引字段由程序补齐。 */
final class CompactReading {
    record Fact(String category,String content,String quote) { }
    record Topic(String title,String summary,List<String> conditions,List<Integer> itemIndices) { }
    record Block(String fragmentId,List<Fact> items,List<Topic> topics) { }
    record Batch(List<Block> fragments,String status) { }
    static RecordSchema<Batch> schema(List<ContentReading.Fragment> fragments,Map<String,String> raw,ContentLimits limits) {
        return new RecordSchema<>(Batch.class,"按输入顺序完整阅读每个语义块，输出fragments与status=COMPLETE/OVERFLOW。"
                +"只从ownedText提取事实，quote为连续原文；每块最多32条。topics按知识归纳简短主题和全部条件，itemIndices使用本块事实1起序号，每条事实恰好属于一个主题。"
                +"不输出来源、事实编号、关键词、实体、角色和依赖等可由程序管理的重复字段。保留数字、否定、例外及相互冲突的条件。",
                List.of(items("fragments",fragments.size()),items("fragments[].items",32),utf8("fragments[].items[].category",limits.factCategory()),utf8("fragments[].items[].content",limits.factContent()),utf8("fragments[].items[].quote",limits.factQuote()),utf8("fragments[].topics[].title",160),utf8("fragments[].topics[].summary",600),items("fragments[].topics[].conditions",12)),v->{
            var full=assemble(v);ReadingSchemas.batch(fragments,raw,limits).relaxed().validate(ContentJson.encode(full),Set.of());
        });
    }
    static ContentReading.Batch assemble(Batch batch) {
        var observations=new ArrayList<ContentReading.Observation>();
        for(var block:batch.fragments()) {
            var facts=new ArrayList<Learning.Item>();int i=0;
            for(var fact:block.items())facts.add(new Learning.Item(block.fragmentId()+"_i"+(++i),block.fragmentId(),fact.category(),fact.content(),fact.quote()));
            var topics=new ArrayList<ContentReading.TopicDraft>();int t=0;
            for(var topic:block.topics()) {
                var ids=topic.itemIndices().stream().map(index->{require(index!=null&&index>0&&index<=facts.size(),Reason.UNKNOWN_REFERENCE,"topics.itemIndices","本块1起事实序号");return block.fragmentId()+"_i"+index;}).toList();
                var keywords=facts.stream().filter(f->ids.contains(f.id())).map(Learning.Item::category).distinct().limit(12).toList();
                topics.add(new ContentReading.TopicDraft(block.fragmentId()+"_t"+(++t),topic.title(),topic.summary(),keywords,List.of(),"KNOWLEDGE",topic.conditions(),List.of(),List.of(),ids));
            }
            observations.add(new ContentReading.Observation(block.fragmentId(),facts,topics));
        }return new ContentReading.Batch(observations,batch.status());
    }
}
