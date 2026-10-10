package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.ai.workflows.support.LearningSchemas;
import com.example.ailab.ai.workflows.media.MediaSchemas;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ContentWorkflow.*;
import java.util.*;
import static com.example.ailab.ai.workflows.support.LearningSchemas.require;
import static com.example.ailab.ai.model.RecordSchema.Reason.*;

/** 模型只提议内容，数量、引用、遗漏及资源边界由本地协议核验。 */
public final class ContentSchemas {
    private ContentSchemas() { }
    static void text(String v,int bytes){require(v!=null&&!v.isBlank()&&TextWindow.count(v)<=bytes);}
    public static RecordSchema<Facts> facts(String prefix,String sourceId,String raw,int maximum,boolean merged) {
        return new RecordSchema<>(Facts.class,"输出items及status。status为COMPLETE或OVERFLOW；全部事实超过"+maximum+"项时返回OVERFLOW，程序会继续分批。"
                +"id使用"+prefix+"_i1起连续编号，sourceId="+sourceId+"。category按内容归纳；content最多1000 UTF-8字节，复杂知识拆为相关条目；quote为连续原文，最多600 UTF-8字节。", value->{
            RecordSchema.require(Set.of("COMPLETE","OVERFLOW").contains(value.status()),FACT_STATUS);
            RecordSchema.require(value.items().size()<=maximum,FACT_COUNT);
            var ids=new HashSet<String>();int index=0;
            for(var item:value.items()) {
                RecordSchema.require(ids.add(item.id()),FACT_ID);
                RecordSchema.require(item.sourceId().equals(sourceId),FACT_SOURCE);
                RecordSchema.require(raw.contains(item.quote()),FACT_QUOTE);
                if(!merged)RecordSchema.require(item.id().equals(prefix+"_i"+(++index)),FACT_ID);
                RecordSchema.require(!item.category().isBlank()&&TextWindow.count(item.category())<=200
                        &&!item.content().isBlank()&&TextWindow.count(item.content())<=1000
                        &&!item.quote().isBlank()&&TextWindow.count(item.quote())<=600,FACT_TEXT_SIZE);
            }
        });
    }
    public static RecordSchema<Summary> summary() {
        return new RecordSchema<>(Summary.class,"输出title和summary。保留主题、条件、例外、重复和冲突。title最多200 UTF-8字节，summary最多600 UTF-8字节，明细由程序保留。",v->{text(v.title(),200);text(v.summary(),600);});
    }
    public static RecordSchema<Intent> intent(int maximumThemes) {
        return new RecordSchema<>(Intent.class,"输出title、themes(id/title/purpose)、requirements、reason、requestedUnits。主题根据资料归纳，theme id为t1起连续编号；"
                +"仅用户明确要求精确题数、整编章数或PPT总页数时requestedUnits为十进制字符串，否则AUTO。章节由资料主题组织决定。requirements说明采用的备注要求及默认偏好。",v->{
            text(v.title(),400);text(v.requirements(),1600);text(v.reason(),1000);require(!v.themes().isEmpty()&&v.themes().size()<=maximumThemes);
            for(int i=0;i<v.themes().size();i++){var t=v.themes().get(i);require(t.id().equals("t"+(i+1)));text(t.title(),200);text(t.purpose(),400);}
            require(v.requestedUnits().equals("AUTO")||v.requestedUnits().matches("[1-9][0-9]{0,2}"));
        });
    }
    public static RecordSchema<UnitBatch> units(String type,Intent intent,List<Learning.Item> items,TaskRequest request,int maxUnits,int images) {
        return units(type,intent,items,request,maxUnits,images,7000);
    }
    public static RecordSchema<UnitBatch> units(String type,Intent intent,List<Learning.Item> items,TaskRequest request,int maxUnits,int images,int evidenceBytes) {
        var allowed=items.stream().map(Learning.Item::id).collect(java.util.stream.Collectors.toSet());
        var themes=intent.themes().stream().map(Theme::id).collect(java.util.stream.Collectors.toSet());
        Set<String> kinds=type.equals("QUIZ_GENERATION")?new HashSet<>(request.quizOptions().questionTypes()):Set.of(type.equals("KNOWLEDGE_COMPILATION")?"SECTION":"SLIDE");
        return new RecordSchema<>(UnitBatch.class,"输出units及omittedItemIds、omissionReason。每目标kind为"+kinds+"，themeId引用统一主题，title/purpose描述目标，relation为MERGE/COMPLEMENT/CONFLICT。"
                +"itemIds引用本批事实；目标数量按有效知识决定，最多"+maxUnits+"个。整编全部条目恰好分配一次；自测与PPT未选条目须列入遗漏并说明选材理由。"
                +"imageMode为NONE/GENERATED/WEB_SEARCH，imagePrompt为配图需求；学习任务用NONE和空提示，PPT本批最多"+images+"个配图需求。",v->{
            require(v.units().size()<=maxUnits);var assigned=new HashSet<String>();int count=0;
            for(var u:v.units()) {
                require(kinds.contains(u.kind())&&themes.contains(u.themeId())&&Set.of("MERGE","COMPLEMENT","CONFLICT").contains(u.relation())
                        &&!u.itemIds().isEmpty()&&new HashSet<>(u.itemIds()).size()==u.itemIds().size()&&allowed.containsAll(u.itemIds()));
                text(u.title(),300);text(u.purpose(),500);
                require(TextWindow.count(ContentJson.encode(items.stream().filter(i->u.itemIds().contains(i.id())).toList()))<=evidenceBytes);
                require(Set.of("NONE","GENERATED","WEB_SEARCH").contains(u.imageMode()));
                if(!u.imageMode().equals("NONE")){count++;text(u.imagePrompt(),1200);}
                require(type.equals("NOTES_PPT")||u.imageMode().equals("NONE"));
                if(type.equals("KNOWLEDGE_COMPILATION"))for(String id:u.itemIds())require(assigned.add(id));else assigned.addAll(u.itemIds());
            }
            require(count<=images&&allowed.containsAll(v.omittedItemIds())&&Collections.disjoint(assigned,v.omittedItemIds()));
            if(!v.omittedItemIds().isEmpty())text(v.omissionReason(),1000);
            if(type.equals("KNOWLEDGE_COMPILATION"))require(v.omittedItemIds().isEmpty());
            assigned.addAll(v.omittedItemIds());require(assigned.equals(allowed));
        });
    }
    public static RecordSchema<Media.Unit> slide(UnitPlan target,Set<String> references) {
        String id="slide-"+Integer.parseInt(target.id().substring(1));
        return new RecordSchema<>(Media.Unit.class,"输出单页Media.Unit，unitId="+id+"，title="+target.title()+"。layout为TITLE/TEXT/TWO_COLUMN/IMAGE_TEXT。"
                +"text为适合投影的正文，notes为讲解内容，seconds=0；references引用提供的文档标签。imageMode/imagePrompt保持目标计划，整体最多4000 UTF-8字节。",v->{
            require(v.unitId().equals(id)&&v.title().equals(target.title())&&v.seconds()==0&&Set.of("TITLE","TEXT","TWO_COLUMN","IMAGE_TEXT").contains(v.layout())
                    &&v.imageMode().equals(target.imageMode())&&v.imagePrompt().equals(target.imagePrompt()));
            MediaSchemas.validateUnits(List.of(v),"NOTES_PPT",references,1);
            require(TextWindow.count(ContentJson.encode(v))<=4000);
        });
    }
}
