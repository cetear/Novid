package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ContentWorkflow.*;
import java.util.*;
import static com.example.ailab.ai.model.RecordSchema.*;
import static com.example.ailab.ai.model.RecordSchema.FieldRule.*;
import static com.example.ailab.ai.model.RecordSchema.Reason.*;

/** 统一字段规则用于提示、本地验证与工作流恢复。 */
public final class ContentSchemas {
    private ContentSchemas() { }
    public static RecordSchema<Facts> facts(String prefix,String sourceId,String raw,int maximum,boolean merged) {
        return facts(prefix,sourceId,raw,maximum,merged,ContentLimits.current());
    }
    public static RecordSchema<Facts> facts(String prefix,String sourceId,String raw,int maximum,boolean merged,ContentLimits limits) {
        return new RecordSchema<>(Facts.class,"输出items及status。status为COMPLETE或OVERFLOW；全部事实超过"+maximum+"项时返回OVERFLOW，程序会继续分批。"
                +"id使用"+prefix+"_i1起连续编号，sourceId="+sourceId+"。category按内容归纳；复杂知识拆为相关条目；quote必须是连续原文。",
                List.of(items("items",maximum),choices("status",Set.of("COMPLETE","OVERFLOW")),utf8("items[].category",limits.factCategory()),
                        utf8("items[].content",limits.factContent()),utf8("items[].quote",limits.factQuote())),value->{
            require(Set.of("COMPLETE","OVERFLOW").contains(value.status()),FACT_STATUS,"status","COMPLETE/OVERFLOW");
            maximum(value.items().size()<=maximum,FACT_COUNT,"items",value.items().size(),maximum,"ITEMS");
            var ids=new HashSet<String>();int index=0;
            for(var item:value.items()){
                String path="items["+(index++)+"]";
                require(ids.add(item.id()),FACT_ID,path+".id","本批唯一事实编号");
                require(item.sourceId().equals(sourceId),FACT_SOURCE,path+".sourceId",sourceId);
                require(!item.quote().isBlank()&&raw.contains(item.quote()),FACT_QUOTE,path+".quote","本批输入中的连续原文，保留空格和换行");
                if(!merged)require(item.id().equals(prefix+"_i"+index),FACT_ID,path+".id",prefix+"_i"+index);
            }
        });
    }
    public static RecordSchema<Summary> summary(){return summary(ContentLimits.current());}
    public static RecordSchema<Summary> summary(ContentLimits limits) {
        return new RecordSchema<>(Summary.class,"输出title和summary。保留主题、条件、例外、重复和冲突；明细由程序保留。",
                List.of(utf8("title",limits.summaryTitle()),utf8("summary",limits.summary())),v->{});
    }
    public static RecordSchema<Intent> intent(int maximumThemes){return intent(maximumThemes,ContentLimits.current());}
    public static RecordSchema<Intent> intent(int maximumThemes,ContentLimits limits) {
        return new RecordSchema<>(Intent.class,"输出title、themes(id/title/purpose)、requirements、reason、requestedUnits。主题根据资料归纳，theme id为t1起连续编号；"
                +"仅用户明确要求精确题数、整编章数或PPT总页数时requestedUnits为十进制字符串，否则AUTO。requirements说明采用的备注要求及默认偏好。",
                List.of(utf8("title",limits.title()),utf8("requirements",limits.requirements()),utf8("reason",limits.reason()),
                        items("themes",maximumThemes),utf8("themes[].title",limits.title()),utf8("themes[].purpose",limits.purpose())),v->{
            require(!v.themes().isEmpty(),CONTENT_CONSTRAINT,"themes","至少一个主题");
            for(int i=0;i<v.themes().size();i++)require(v.themes().get(i).id().equals("t"+(i+1)),CONTENT_CONSTRAINT,"themes["+i+"].id","t"+(i+1));
            require(v.requestedUnits().equals("AUTO")||v.requestedUnits().matches("[1-9][0-9]{0,2}"),CONTENT_CONSTRAINT,"requestedUnits","AUTO或1至999的十进制字符串");
        });
    }
    public static RecordSchema<UnitBatch> units(String type,Intent intent,List<Learning.Item> facts,TaskRequest request,int maxUnits,int images){
        return units(type,intent,facts,request,maxUnits,images,Policy.defaults().unitInputBytes(),ContentLimits.current());
    }
    public static RecordSchema<UnitBatch> units(String type,Intent intent,List<Learning.Item> facts,TaskRequest request,int maxUnits,int images,int evidenceBytes){
        return units(type,intent,facts,request,maxUnits,images,evidenceBytes,ContentLimits.current());
    }
    public static RecordSchema<UnitBatch> units(String type,Intent intent,List<Learning.Item> facts,TaskRequest request,int maxUnits,int images,int evidenceBytes,ContentLimits limits) {
        var allowed=facts.stream().map(Learning.Item::id).collect(java.util.stream.Collectors.toSet());
        var themes=intent.themes().stream().map(Theme::id).collect(java.util.stream.Collectors.toSet());
        Set<String> kinds=type.equals("QUIZ_GENERATION")?new HashSet<>(request.quizOptions().questionTypes()):Set.of(type.equals("KNOWLEDGE_COMPILATION")?"SECTION":"SLIDE");
        Set<String> imageModes=!type.equals("NOTES_PPT")?Set.of("NONE"):switch(request.presentationOptions().imagePolicy()){case "CONCEPT"->Set.of("NONE","GENERATED");case "FACTUAL"->Set.of("NONE","WEB_SEARCH");default->Set.of("NONE","GENERATED","WEB_SEARCH");};
        return new RecordSchema<>(UnitBatch.class,"输出units及omittedItemIds、omissionReason。themeId引用统一主题，title/purpose描述目标。"
                +"itemIds引用本批事实；目标数量按有效知识决定。整编全部条目恰好分配一次；自测与PPT未选条目须列入遗漏并说明选材理由。"
                +"PPT本批最多"+images+"个配图需求；imagePrompt为配图需求，NONE时为空。每个目标的事实JSON最多"+evidenceBytes+" UTF-8字节。",
                List.of(items("units",maxUnits),choices("units[].kind",kinds),choices("units[].themeId",themes),
                        choices("units[].relation",Set.of("MERGE","COMPLEMENT","CONFLICT")),utf8("units[].title",limits.title()),utf8("units[].purpose",limits.purpose()),
                        choices("units[].imageMode",imageModes),optionalUtf8("units[].imagePrompt",limits.imagePrompt()),optionalUtf8("omissionReason",limits.reason())),v->{
            var assigned=new HashSet<String>();int count=0,index=0;
            for(var u:v.units()){
                String path="units["+(index++)+"]";
                require(!u.itemIds().isEmpty()&&new HashSet<>(u.itemIds()).size()==u.itemIds().size()&&allowed.containsAll(u.itemIds()),CONTENT_CONSTRAINT,path+".itemIds","不重复的本批事实编号");
                int bytes=TextWindow.count(ContentJson.encode(facts.stream().filter(i->u.itemIds().contains(i.id())).toList()));
                maximum(bytes<=evidenceBytes,CONTENT_CONSTRAINT,path+".itemIds",bytes,evidenceBytes,"UTF-8_BYTES");
                if(!u.imageMode().equals("NONE")){count++;require(!u.imagePrompt().isBlank(),CONTENT_CONSTRAINT,path+".imagePrompt","非空配图需求");}
                if(type.equals("KNOWLEDGE_COMPILATION"))for(String id:u.itemIds())require(assigned.add(id),CONTENT_CONSTRAINT,path+".itemIds","每条事实只分配一次");else assigned.addAll(u.itemIds());
            }
            maximum(count<=images,CONTENT_CONSTRAINT,"units.imageMode",count,images,"ITEMS");
            require(allowed.containsAll(v.omittedItemIds())&&Collections.disjoint(assigned,v.omittedItemIds()),CONTENT_CONSTRAINT,"omittedItemIds","本批未选且不重复分配的事实编号");
            if(!v.omittedItemIds().isEmpty())require(!v.omissionReason().isBlank(),CONTENT_CONSTRAINT,"omissionReason","非空选材理由");
            if(type.equals("KNOWLEDGE_COMPILATION"))require(v.omittedItemIds().isEmpty(),CONTENT_CONSTRAINT,"omittedItemIds","整编不允许遗漏事实");
            assigned.addAll(v.omittedItemIds());require(assigned.equals(allowed),CONTENT_CONSTRAINT,"units.itemIds","全部事实须被分配或明确列入遗漏");
        });
    }
    public static RecordSchema<Media.Unit> slide(UnitPlan target,Set<String> references){return slide(target,references,ContentLimits.current());}
    public static RecordSchema<Media.Unit> slide(UnitPlan target,Set<String> references,ContentLimits limits) {
        String id="slide-"+Integer.parseInt(target.id().substring(1));
        return new RecordSchema<>(Media.Unit.class,"输出单页Media.Unit，unitId="+id+"，title保持目标标题。text为适合投影的正文，notes为讲解内容，seconds=0；references引用提供的文档标签。imageMode/imagePrompt保持目标计划。"
                +"正文适合投影，详细解释放notes，完整结果最多"+limits.slide()+" UTF-8字节。",
                List.of(optionalUtf8("$",limits.slide()),utf8("title",limits.title()),utf8("text",limits.slideText()),
                        optionalUtf8("notes",limits.slideNotes()),optionalUtf8("imagePrompt",limits.imagePrompt()),choices("layout",Set.of("TITLE","TEXT","TWO_COLUMN","IMAGE_TEXT")),
                        choices("imageMode",Set.of(target.imageMode()))),v->{
            require(v.unitId().equals(id),CONTENT_CONSTRAINT,"unitId",id);
            require(v.title().equals(target.title()),CONTENT_CONSTRAINT,"title","保持已接受目标标题");
            require(v.seconds()==0,CONTENT_CONSTRAINT,"seconds","0");
            require(v.imageMode().equals(target.imageMode())&&v.imagePrompt().equals(target.imagePrompt()),CONTENT_CONSTRAINT,"imageMode/imagePrompt","保持已接受配图方案");
            require(!v.references().isEmpty()&&references.containsAll(v.references()),CONTENT_CONSTRAINT,"references","仅引用本目标提供的文档标签");
        });
    }
}
