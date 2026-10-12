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
        var rules=new ArrayList<>(List.of(items("fragments",fragments.size()),choices("status",Set.of("COMPLETE","OVERFLOW")),
                items("fragments[].items",32),utf8("fragments[].items[].category",limits.factCategory()),
                utf8("fragments[].items[].content",limits.factContent()),utf8("fragments[].items[].quote",limits.factQuote())));
        rules.addAll(topicRules("fragments[].topics"));
        return new RecordSchema<>(Batch.class,
                "只返回一个业务结果对象，根对象仅含fragments数组和status字符串；全部片段必须位于同一个fragments数组内，不得在闭合的根对象之后追加字段。"
                +"以下JSON Schema仅用于定义校验约束，不是回答内容，禁止回显type/properties/required/additionalProperties等Schema定义字段。"
                +"逐片段输出fragments，顺序与输入相同，每片fragmentId固定。只从ownedText提取items，背景仅帮助理解。"
                +"事实id为fragmentId_i1起连续编号，sourceId为fragmentId。quote必须为ownedText连续原文。每片最多32条；容量不足status=OVERFLOW，完整status=COMPLETE。"
                +"topics归纳主题标题、摘要、关键词、实体、角色、条件、关系及依赖，id为fragmentId_t1起连续编号。"
                +"每张卡的itemIds非空且不重复，每条事实恰好归属一张卡；dependencyItemIds不重复且仅引用本片事实。无事实时topics为空，有事实时不得为空。",rules,v->{
            require(v.fragments().size()==fragments.size(),CONTENT_CONSTRAINT,"fragments","按输入顺序返回全部"+fragments.size()+"个片段");
            for(int i=0;i<Math.min(v.fragments().size(),fragments.size());i++) {
                String id=fragments.get(i).source().id();var observation=v.fragments().get(i);
                String path="fragments["+i+"]";
                require(id.equals(observation.fragmentId()),FACT_SOURCE,path+".fragmentId",id);
                nested(path,()->ContentSchemas.facts(id,id,raw.get(id),32,false,limits).validate(ContentJson.encode(new ContentWorkflow.Facts(observation.items(),v.status())),Set.of()));
                topics(id,observation.items(),observation.topics(),path+".topics");
            }
        });
    }
    public static RecordSchema<Topics> topics(String prefix,List<Learning.Item> facts) {
        return new RecordSchema<>(Topics.class,"按本批事实归纳主题卡。id使用"+prefix+"_t1起连续编号；每张卡的itemIds非空且不重复，每条事实恰好归属一张卡；"
                +"dependencyItemIds不重复且仅引用本批事实。无事实时topics为空，有事实时不得为空。保留条件和矛盾关系。",
                topicRules("topics"),v->topics(prefix,facts,v.topics(),"topics"));
    }
    private static List<FieldRule> topicRules(String path) {
        var rules=new ArrayList<>(List.of(items(path,32),utf8(path+"[].title",160),utf8(path+"[].summary",600),utf8(path+"[].contentRole",120)));
        for(String field:List.of("keywords","entities","conditions","relations")) {
            rules.add(items(path+"[]."+field,12));rules.add(utf8(path+"[]."+field+"[]",200));
        }
        return rules;
    }
    private static void topics(String prefix,List<Learning.Item> facts,List<TopicDraft> topics,String path) {
        var allowed=new HashSet<String>();facts.forEach(i->allowed.add(i.id()));var covered=new HashSet<String>();int index=0;
        require(facts.isEmpty()==topics.isEmpty(),CONTENT_CONSTRAINT,path,"无事实时为空，有事实时至少一张主题卡");
        for(var t:topics) {
            String card=path+"["+(index++)+"]";
            require(t.id().equals(prefix+"_t"+index),CONTENT_CONSTRAINT,card+".id",prefix+"_t"+index);
            require(!t.itemIds().isEmpty(),NON_EMPTY,card+".itemIds","至少一个本片事实编号");
            require(new HashSet<>(t.itemIds()).size()==t.itemIds().size()&&Collections.disjoint(covered,t.itemIds()),DUPLICATE_ID,card+".itemIds","事实编号在卡片内和卡片间均不得重复");
            require(allowed.containsAll(t.itemIds()),UNKNOWN_REFERENCE,card+".itemIds","仅引用本片事实编号");
            require(allowed.containsAll(t.dependencyItemIds()),UNKNOWN_REFERENCE,card+".dependencyItemIds","仅引用本片事实编号");
            require(new HashSet<>(t.dependencyItemIds()).size()==t.dependencyItemIds().size(),DUPLICATE_ID,card+".dependencyItemIds","编号不得重复");
            covered.addAll(t.itemIds());
        }
        require(covered.containsAll(allowed),MISSING_COVERAGE,path,"每条本片事实恰好归属一张卡，不得遗漏");
    }
}
