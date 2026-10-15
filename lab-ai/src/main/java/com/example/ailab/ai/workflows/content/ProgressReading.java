package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import java.util.*;
import static com.example.ailab.ai.model.RecordSchema.*;
import static com.example.ailab.ai.model.RecordSchema.FieldRule.*;

/** 逐片确认完整性；容量不足只返回轻量标记，部分事实永不冒充完整阅读。 */
final class ProgressReading {
    record Block(String fragmentId,String status,List<CompactReading.Fact> items,List<CompactReading.Topic> topics) { }
    record ReadingBatch(List<Block> fragments) { }
    static RecordSchema<ReadingBatch> schema(List<ContentReading.Fragment> fragments,Map<String,String> raw,ContentLimits limits) {
        return new RecordSchema<>(ReadingBatch.class,
                "按输入顺序逐片阅读，根对象仅含fragments。每片返回fragmentId、status、items、topics。"
                +"status=COMPLETE表示已完整提取本片，最多32条事实；预计超过容量或无法完整处理时立即返回status=OVERFLOW、items=[]、topics=[]，无需生成部分事实。"
                +"事实仅含category/content/quote，quote必须是ownedText连续原文。主题仅含title/summary/conditions/itemIndices，序号从1起且每条事实恰好属于一个主题。"
                +"不输出事实或主题编号、来源编号、关键词、实体、角色及依赖；这些字段由程序补齐。保留数字、否定、例外及冲突的全部条件。背景不能作为本片事实。",
                List.of(items("fragments",fragments.size()),choices("fragments[].status",Set.of("COMPLETE","OVERFLOW")),
                        items("fragments[].items",32),utf8("fragments[].items[].category",limits.factCategory()),
                        utf8("fragments[].items[].content",limits.factContent()),utf8("fragments[].items[].quote",limits.factQuote()),
                        items("fragments[].topics",32),utf8("fragments[].topics[].title",160),targetUtf8("fragments[].topics[].summary",420,600),
                        items("fragments[].topics[].conditions",12),utf8("fragments[].topics[].conditions[]",200)),v->{
            require(v.fragments().size()==fragments.size(),Reason.MISSING_COVERAGE,"fragments","全部输入片段按原顺序返回");
            for(int i=0;i<Math.min(v.fragments().size(),fragments.size());i++) {
                var f=fragments.get(i);var block=v.fragments().get(i);String path="fragments["+i+"]";
                require(f.source().id().equals(block.fragmentId()),Reason.FACT_SOURCE,path+".fragmentId","当前输入片段编号");
                if("OVERFLOW".equals(block.status()))
                    require(block.items().isEmpty()&&block.topics().isEmpty(),Reason.CONTENT_CONSTRAINT,path,"容量不足时只返回空数组和OVERFLOW标记");
                else {
                    require("COMPLETE".equals(block.status()),Reason.FACT_STATUS,path+".status","COMPLETE或OVERFLOW");
                    nested(path,()->ReadingSchemas.observation(f,raw.get(block.fragmentId()),32,limits).relaxed().validate(ContentJson.encode(observation(block)),Set.of()));
                }
            }
        }).withSummaryCompression("fragments[].topics[].summary");
    }
    static ContentReading.Observation observation(Block block) {
        if(!"COMPLETE".equals(block.status()))throw new com.example.ailab.contract.error.LabException("WORKFLOW_STATE_CONFLICT","仅完整阅读片段可保存");
        return CompactReading.assemble(new CompactReading.Batch(List.of(new CompactReading.Block(block.fragmentId(),block.items(),block.topics())),"COMPLETE")).fragments().get(0);
    }
}
