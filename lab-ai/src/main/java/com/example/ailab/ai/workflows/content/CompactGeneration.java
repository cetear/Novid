package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import java.util.*;
import static com.example.ailab.contract.dto.LearningContent.*;

/** 导航与正文同次返回，正文仍按原单元协议验收和保存。 */
final class CompactGeneration {
    record Part(String partId,String body,List<String> itemIds,String summary) {
        Part{summary=summary==null?"":summary;}
        SectionPart section(){return new SectionPart(partId,body,itemIds);}
    }
    record Batch(List<Part> parts){ }
    static RecordSchema<Batch> schema(List<LearningContent.Part> parts){
        return new RecordSchema<>(Batch.class,"为全部固定片段生成正文，并在同一parts项返回简短summary用于导航。summary概括实际正文的主体、条件及冲突，不替代正文验收。partId与itemIds保持固定顺序，职责及原文依据不变。",
                List.of(RecordSchema.FieldRule.items("parts",parts.size()),RecordSchema.FieldRule.utf8("parts[].body",ContentLimits.current().section())),v->
                LearningBusinessSchemas.sectionBatch(parts).relaxed().validate(ContentJson.encode(new SectionBatch(v.parts().stream().map(Part::section).toList())),Set.of()));
    }
    static String navigationId(String partId,Object section){return "nav_"+partId+"_"+ContentJson.hash(section).substring(0,24);}
    static RecordSchema<ContentWorkflow.Summary> navigationSchema(){return new RecordSchema<>(ContentWorkflow.Summary.class,"实际正文的导航摘要，不以固定短字节限制触发模型纠正",v->{});}
}
