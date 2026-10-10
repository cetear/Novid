package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ContentReading.*;
import java.util.*;
import static com.example.ailab.ai.model.RecordSchema.*;
import static com.example.ailab.ai.model.RecordSchema.FieldRule.*;
import static com.example.ailab.ai.model.RecordSchema.Reason.*;

/** 阅读包输出按自然片段核验；背景不能取得事实归属或覆盖。 */
public final class ReadingSchemas {
    private ReadingSchemas() { }
    public static RecordSchema<Batch> batch(List<Fragment> fragments,Map<String,String> raw,ContentLimits limits) {
        return new RecordSchema<>(Batch.class,
                "逐片段输出fragments，顺序与输入相同，每片fragmentId固定。只从ownedText提取items，背景仅帮助理解。"
                +"事实id为fragmentId_i1起连续编号，sourceId为fragmentId。quote必须为ownedText连续原文。每片最多32条；容量不足status=OVERFLOW，完整status=COMPLETE。"
                +"topics归纳主题标题、摘要、关键词、实体、角色、条件、关系及依赖；itemIds覆盖本片全部事实，dependencyItemIds仅引用本片事实。空白/标题可返回空事实和空主题。",
                List.of(items("fragments",fragments.size()),choices("status",Set.of("COMPLETE","OVERFLOW")),
                        items("fragments[].items",32),items("fragments[].topics",32),utf8("fragments[].items[].category",limits.factCategory()),
                        utf8("fragments[].items[].content",limits.factContent()),utf8("fragments[].items[].quote",limits.factQuote()),
                        utf8("fragments[].topics[].title",160),utf8("fragments[].topics[].summary",600)),v->{
            require(v.fragments().size()==fragments.size(),CONTENT_CONSTRAINT);
            for(int i=0;i<Math.min(v.fragments().size(),fragments.size());i++) {
                String id=fragments.get(i).source().id();var observation=v.fragments().get(i);
                require(id.equals(observation.fragmentId()),FACT_SOURCE);
                ContentSchemas.facts(id,id,raw.get(id),32,false,limits).validate(ContentJson.encode(new ContentWorkflow.Facts(observation.items(),v.status())),Set.of());
                topics(id,observation.items(),observation.topics());
            }
        });
    }
    public static RecordSchema<Topics> topics(String prefix,List<Learning.Item> facts) {
        return new RecordSchema<>(Topics.class,"按本批事实归纳主题卡。id使用"+prefix+"_t1起连续编号；itemIds覆盖本批全部事实，dependencyItemIds仅引用本批事实。保留条件和矛盾关系。",
                List.of(items("topics",32),utf8("topics[].title",160),utf8("topics[].summary",600)),v->topics(prefix,facts,v.topics()));
    }
    private static void topics(String prefix,List<Learning.Item> facts,List<TopicDraft> topics) {
        var allowed=new HashSet<String>();facts.forEach(i->allowed.add(i.id()));var covered=new HashSet<String>();int index=0;
        require(facts.isEmpty()==topics.isEmpty(),CONTENT_CONSTRAINT);
        for(var t:topics) {
            require(t.id().equals(prefix+"_t"+(++index)),CONTENT_CONSTRAINT);
            require(!t.itemIds().isEmpty()&&new HashSet<>(t.itemIds()).size()==t.itemIds().size()&&allowed.containsAll(t.itemIds())
                    &&allowed.containsAll(t.dependencyItemIds())&&new HashSet<>(t.dependencyItemIds()).size()==t.dependencyItemIds().size()
                    &&Collections.disjoint(covered,t.itemIds()),CONTENT_CONSTRAINT);
            require(!t.contentRole().isBlank()&&TextWindow.count(t.contentRole())<=120,CONTENT_CONSTRAINT);
            for(var list:List.of(t.keywords(),t.entities(),t.conditions(),t.relations())) {
                require(list.size()<=12,CONTENT_CONSTRAINT);for(String s:list)require(!s.isBlank()&&TextWindow.count(s)<=200,CONTENT_CONSTRAINT);
            }
            covered.addAll(t.itemIds());
        }
        require(covered.equals(allowed),CONTENT_CONSTRAINT);
    }
}
