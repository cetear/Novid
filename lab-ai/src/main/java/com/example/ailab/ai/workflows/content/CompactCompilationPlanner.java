package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import java.util.*;
import java.util.stream.Collectors;
import static com.example.ailab.contract.dto.LearningContent.*;
import static com.example.ailab.ai.model.RecordSchema.*;
import static com.example.ailab.ai.model.RecordSchema.FieldRule.*;

/** 在同一完整知识视图中制定目录、语义归并和小节职责，程序补齐独立知识。 */
final class CompactCompilationPlanner {
    record Group(String relation,String summary,List<String> itemIds,List<String> conditions) { }
    record Section(String chapterId,String title,String purpose,List<String> itemIds,List<String> contextItemIds,int outputBytes) { }
    record Draft(Directory directory,List<Section> sections,List<Group> merges) { }
    record Fallback(String reason) { }
    private final LearningRuntime r;
    CompactCompilationPlanner(LearningRuntime runtime){r=runtime;}
    Optional<CompilationPlan> plan() {
        if(r.store().completedNode(r.lease(),"compact_plan_fallback").isPresent())return Optional.empty();
        var input=new LinkedHashMap<String,Object>();input.put("remarks",r.lease().request().instructions());input.put("options",r.lease().request().compilationOptions());input.putAll(knowledgeView(r));
        int maximum=r.lease().request().compilationOptions().maximumChapters();if(maximum==0)maximum=LearningPlanRules.MAX_CHAPTERS;
        var schema=schema(Math.min(maximum,LearningPlanRules.MAX_CHAPTERS));
        // 编号清单为主要返回量，独立知识正文由程序复用；预检包含实际Schema。
        int estimate=r.facts().keySet().stream().mapToInt(id->TextWindow.count(id)+4).sum()*2+12000;
        input.put("estimatedOutputBytes",estimate);
        if(TextWindow.count(ContentJson.encode(input))>r.policy().unitInputBytes()||!r.preflight("joint-plan",input,schema))return Optional.empty();
        try{return Optional.of(assemble(r.call("compact_directory","organize","joint-plan",input,schema)));}
        catch(com.example.ailab.contract.error.LabException error){if(Set.of("MODEL_TRUNCATED","MODEL_CONTEXT_INSUFFICIENT").contains(error.code())){
            if(error.code().equals("MODEL_TRUNCATED"))r.checkpoint("compact_plan_fallback","organize",Map.of("inputHash",ContentJson.hash(input)),new RecordSchema<>(Fallback.class,"已确认联合回包截断，后续沿用有界分批规划",v->{}),()->new Fallback(error.code()));
            return Optional.empty();
        }throw error;}
    }
    static Map<String,Object> knowledgeView(LearningRuntime r) {
        var topics=new LinkedHashMap<String,Object>();var owner=new HashMap<String,String>();
        for(var card:r.cards()){topics.put(card.id(),card.topic().conditions());for(String id:card.topic().itemIds())owner.put(id,card.id());}
        var facts=r.facts().values().stream().map(f->Map.of("id",f.id(),"category",f.category(),"content",f.content(),"topicId",Objects.requireNonNull(owner.get(f.id())))).toList();
        return Map.of("facts",facts,"topicConditions",topics);
    }
    static List<?> themeView(List<ContentReading.TopicCard> cards) {
        return cards.stream().map(c->Map.of("id",c.id(),"title",c.topic().title(),"summary",c.topic().summary(),"conditions",c.topic().conditions())).toList();
    }
    RecordSchema<Draft> schema(int maximum) {
        return new RecordSchema<>(Draft.class,"一次制定目录、知识关系和小节职责。directory按全范围知识组织，不能按技术批次分章。"
                +"sections列出所属chapterId、标题、职责、主要itemIds、可选contextItemIds和500至12000的outputBytes。每条输入事实恰好属于一个主要小节，互补内容独立保留。"
                +"merges只返回至少两条知识相同且条件一致的SAME组，或同主题相互冲突的CONFLICT组；不能把职责不同的事实合并。"
                +"未列入merges的事实由程序作为SINGLE保留，不要重写其摘要。合并组所有事实必须属于同一主要小节；summary精确概括共同职责，CONFLICT保留全部版本条件。"
                +"只引用输入事实编号，条件字典由topicId查询。sections顺序即输出顺序，目录章节编号c1起连续；仅确实需要另一章实际写作结果才声明resultDependencies。",
                List.of(items("sections",r.policy().maximumUnits()),items("merges",r.facts().size()),utf8("sections[].title",200),utf8("sections[].purpose",600),utf8("merges[].summary",500)),v->{
            nested("directory",()->LearningBusinessSchemas.directory(maximum).relaxed().validate(ContentJson.encode(v.directory()),Set.of()));
            validate(v);
        });
    }
    private static void check(boolean condition,Reason reason,String path,String expected){
        RecordSchema.require(condition,reason,path,expected);if(!condition)throw new com.example.ailab.contract.error.LabException("MODEL_STRUCTURED_INVALID","联合规划未满足事实职责约束");
    }
    private void validate(Draft draft) {
        var chapters=draft.directory().chapters().stream().map(DirectoryChapter::id).collect(Collectors.toSet());
        var owner=new LinkedHashMap<String,Integer>();int index=0;
        for(var section:draft.sections()) {
            String path="sections["+index+"]";check(chapters.contains(section.chapterId()),Reason.UNKNOWN_REFERENCE,path+".chapterId","目录章节");
            check(!section.itemIds().isEmpty(),Reason.NON_EMPTY,path+".itemIds","至少一条主要事实");
            check(section.outputBytes()>=500&&section.outputBytes()<=Math.min(12000,ContentLimits.current().section()),Reason.CONTENT_CONSTRAINT,path+".outputBytes","500至小节正文上限");
            for(String id:section.itemIds()){check(r.facts().containsKey(id),Reason.UNKNOWN_REFERENCE,path+".itemIds","输入事实编号");check(owner.putIfAbsent(id,index)==null,Reason.DUPLICATE_ID,path+".itemIds","唯一主要小节");}
            check(new HashSet<>(section.contextItemIds()).size()==section.contextItemIds().size()&&r.facts().keySet().containsAll(section.contextItemIds())&&Collections.disjoint(section.itemIds(),section.contextItemIds()),Reason.CONTENT_CONSTRAINT,path+".contextItemIds","已有且不重复的背景事实");index++;
        }
        check(owner.keySet().equals(r.facts().keySet()),Reason.MISSING_COVERAGE,"sections","全部事实有唯一主要归属");
        var groupByFact=new HashMap<String,Group>();
        for(var group:draft.merges()) {
            check(Set.of("SAME","CONFLICT").contains(group.relation())&&group.itemIds().size()>=2&&!group.summary().isBlank(),Reason.CONTENT_CONSTRAINT,"merges","至少两条事实的SAME或CONFLICT关系及摘要");
            check(!group.relation().equals("CONFLICT")||!group.conditions().isEmpty(),Reason.NON_EMPTY,"merges.conditions","冲突必须保留不同条件");
            Integer first=owner.get(group.itemIds().get(0));
            for(String id:group.itemIds()){check(r.facts().containsKey(id),Reason.UNKNOWN_REFERENCE,"merges.itemIds","输入事实编号");check(groupByFact.putIfAbsent(id,group)==null,Reason.DUPLICATE_ID,"merges.itemIds","事实只属于一个知识组");check(Objects.equals(first,owner.get(id)),Reason.CONTENT_CONSTRAINT,"merges.itemIds","合并组同属一个主要小节");}
        }
    }
    /** 草稿已通过Schema及事实职责校验，只在这里构造最终业务计划。 */
    CompilationPlan assemble(Draft draft) {
        var groupByFact=new HashMap<String,Group>();
        for(var group:draft.merges())for(String id:group.itemIds())groupByFact.put(id,group);
        var conditions=new HashMap<String,List<String>>();for(var card:r.cards())for(String id:card.topic().itemIds())conditions.put(id,card.topic().conditions());
        var knowledge=new ArrayList<Knowledge>();var knowledgeByFact=new LinkedHashMap<String,String>();var seen=new HashSet<Group>();
        for(var fact:r.facts().values()) {
            var group=groupByFact.get(fact.id());if(group!=null&&!seen.add(group))continue;
            var ids=group==null?List.of(fact.id()):r.facts().keySet().stream().filter(group.itemIds()::contains).toList();
            var all=new LinkedHashSet<String>();for(String id:ids)all.addAll(conditions.getOrDefault(id,List.of()));if(group!=null)all.addAll(group.conditions());
            String id=String.format(Locale.ROOT,"k%05d",knowledge.size()+1);knowledge.add(new Knowledge(id,group==null?"SINGLE":group.relation(),group==null?fact.content():group.summary(),ids,List.copyOf(all)));ids.forEach(f->knowledgeByFact.put(f,id));
        }
        var sections=new ArrayList<SectionDuty>();
        for(var section:draft.sections()) {
            var owned=section.itemIds().stream().map(knowledgeByFact::get).distinct().toList();var context=section.contextItemIds().stream().map(knowledgeByFact::get).distinct().filter(id->!owned.contains(id)).toList();
            sections.add(new SectionDuty(String.format(Locale.ROOT,"s%05d",sections.size()+1),section.chapterId(),section.title(),section.purpose(),owned,context,section.outputBytes()));
        }
        var parents=draft.directory().chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId));
        var used=LearningHierarchy.ancestors(sections.stream().map(SectionDuty::chapterId).collect(Collectors.toSet()),parents);
        var chapterIds=new LinkedHashMap<String,String>();
        draft.directory().chapters().stream().filter(c->used.contains(c.id())).forEach(c->chapterIds.put(c.id(),"c"+(chapterIds.size()+1)));
        var retained=draft.directory().chapters().stream().filter(c->used.contains(c.id())).map(c->new DirectoryChapter(chapterIds.get(c.id()),c.parentId().isEmpty()?"":chapterIds.get(c.parentId()),c.title(),c.purpose(),c.exclusions(),c.resultDependencies().stream().filter(used::contains).map(chapterIds::get).toList(),c.relatedChapterIds().stream().filter(used::contains).map(chapterIds::get).toList(),c.terms())).toList();
        sections=sections.stream().map(s->new SectionDuty(s.id(),chapterIds.get(s.chapterId()),s.title(),s.purpose(),s.ownedKnowledgeIds(),s.contextKnowledgeIds(),s.outputBytes())).collect(Collectors.toCollection(ArrayList::new));
        var directory=draft.directory();return new CompilationPlan(new Directory(directory.title(),directory.audience(),directory.requirements(),directory.reason(),retained,directory.terms()),knowledge,sections);
    }
}
