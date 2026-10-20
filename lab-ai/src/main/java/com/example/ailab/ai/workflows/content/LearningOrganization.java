package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.LearningContent.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;
import java.util.stream.Collectors;
import static com.example.ailab.ai.model.RecordSchema.*;

/** 全局职责先固定，事实逐批归属；混合主题卡不是不可拆分的业务单位。 */
final class LearningOrganization {
    record FactAssignment(String itemId,String chapterId,List<String> relatedChapterIds,String reason) { }
    record FactAssignments(List<FactAssignment> assignments) { }
    record ChapterExclusions(String chapterId,List<String> exclusions) { }
    record ExclusionRepair(List<ChapterExclusions> patches) { }
    static final String EXCLUSION_CONFLICT="DIRECTORY_EXCLUSION_CONFLICT";
    private LearningOrganization() { }

    static List<Map<String,Object>> facts(LearningRuntime r,List<ContentReading.TopicCard> cards) {
        var owners=new LinkedHashMap<String,ContentReading.TopicCard>();
        for(var card:cards)for(String id:card.topic().itemIds())
            if(!r.facts().containsKey(id)||owners.putIfAbsent(id,card)!=null)throw invalid();
        if(!owners.keySet().equals(r.facts().keySet()))throw invalid();
        return r.facts().values().stream().map(f->{
            var card=owners.get(f.id());var topic=card.topic();
            return Map.<String,Object>of("item",f,"topicId",card.id(),"topicTitle",topic.title(),
                    "contentRole",topic.contentRole(),"conditions",topic.conditions(),"relations",topic.relations(),
                    "dependencyItemIds",topic.dependencyItemIds(),"sourceSectionId",card.sectionId());
        }).toList();
    }
    static RecordSchema<FactAssignments> assignments(Set<String> facts,Directory directory) {
        var chapters=directory.chapters().stream().map(DirectoryChapter::id).collect(Collectors.toSet());
        return new RecordSchema<>(FactAssignments.class,"按已固定的全局章节职责逐事实分配。每个itemId恰好一个主要chapterId、必要的relatedChapterIds和具体reason。"
                +"主题卡提供条件与关系；同卡异质知识可以分别归属，不凭共同关键词或技术批次归类。孤立知识不能遗漏，元信息也保留归属；无法合理归属时在reason说明不确定，交组织复核处理。",
                List.of(FieldRule.items("assignments",facts.size()),FieldRule.targetUtf8("assignments[].reason",180,400)),v->{
            var seen=new HashSet<String>();int index=0;
            for(var a:v.assignments()) {
                String path="assignments["+index+++"]";
                require(facts.contains(a.itemId()),Reason.UNKNOWN_REFERENCE,path+".itemId","本批事实编号");
                require(seen.add(a.itemId()),Reason.DUPLICATE_ID,path+".itemId","每条事实只有一个主要归属");
                require(chapters.contains(a.chapterId()),Reason.UNKNOWN_REFERENCE,path+".chapterId","固定全局章节");
                require(chapters.containsAll(a.relatedChapterIds())&&!a.relatedChapterIds().contains(a.chapterId())
                        &&new HashSet<>(a.relatedChapterIds()).size()==a.relatedChapterIds().size(),Reason.CONTENT_CONSTRAINT,path+".relatedChapterIds","有效且不重复的背景章节，不含主章");
            }
            require(seen.equals(facts),Reason.MISSING_COVERAGE,"assignments","本批全部事实恰好一次");
        });
    }
    static List<FactAssignment> assign(LearningRuntime r,Directory directory,List<ContentReading.TopicCard> cards,String prefix) {
        var result=new LinkedHashMap<String,FactAssignment>();
        var base=Map.of("directory",directory);
        int room=r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(base))-256;
        var batches=new ArrayList<List<Map<String,Object>>>();
        int maximum=Math.max(1,Math.min(64,(r.policy().packageOutputBytes()-512)/700));
        for(var batch:LearningPlanner.partition(facts(r,cards),room,ContentJson::encode))
            for(int from=0;from<batch.size();from+=maximum)batches.add(List.copyOf(batch.subList(from,Math.min(from+maximum,batch.size()))));
        var completed=r.parallel(java.util.stream.IntStream.range(0,batches.size()).boxed().toList(),i->assignBatch(r,directory,batches.get(i),prefix+"_"+i));
        for(var assigned:completed)for(var a:assigned)if(result.putIfAbsent(a.itemId(),a)!=null)throw invalid();
        if(!result.keySet().equals(r.facts().keySet()))throw invalid();
        return r.facts().keySet().stream().map(result::get).toList();
    }
    private static List<FactAssignment> assignBatch(LearningRuntime r,Directory directory,List<Map<String,Object>> batch,String id) {
        var ids=batch.stream().map(row->((Learning.Item)row.get("item")).id()).collect(Collectors.toSet());
        var input=Map.of("directory",directory,"facts",batch,"estimatedOutputBytes",512+batch.size()*700);r.bounded(input);
        var schema=assignments(ids,directory);
        try {
            if(r.preflight("assign",input,schema))return r.call(id,"organize","assign",input,schema).assignments();
        }catch(LabException failure) {
            if(!Set.of("MODEL_CONTEXT_INSUFFICIENT","MODEL_TRUNCATED").contains(failure.code()))throw failure;
        }
        if(batch.size()<2)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","单条事实的完整归属及必要上下文超出模型容量");
        int mid=batch.size()/2;
        var result=new ArrayList<>(assignBatch(r,directory,batch.subList(0,mid),id+"_a"));
        result.addAll(assignBatch(r,directory,batch.subList(mid,batch.size()),id+"_b"));return List.copyOf(result);
    }
    static List<FactAssignment> assignments(CompilationPlan plan) {
        var knowledge=plan.knowledge().stream().collect(Collectors.toMap(Knowledge::id,k->k));
        var contexts=new HashMap<String,Set<String>>();
        for(var section:plan.sections())for(String id:section.contextKnowledgeIds())
            contexts.computeIfAbsent(id,k->new LinkedHashSet<>()).add(section.chapterId());
        var result=new ArrayList<FactAssignment>();
        for(var section:plan.sections())for(String id:section.ownedKnowledgeIds()) {
            var related=contexts.getOrDefault(id,Set.of()).stream().filter(c->!c.equals(section.chapterId())).sorted().toList();
            for(String fact:knowledge.get(id).sourceItemIds())result.add(new FactAssignment(fact,section.chapterId(),related,section.purpose()));
        }
        return List.copyOf(result);
    }
    static Map<String,Object> correctionContext(CompilationPlan plan,List<Learning.Issue> issues) {
        var targets=new ArrayList<Map<String,Object>>();
        for(int i=0;i<issues.size();i++) {
            var issue=issues.get(i);String path="knowledge";
            for(int j=0;j<plan.directory().chapters().size();j++)if(plan.directory().chapters().get(j).id().equals(issue.unitId()))
                path="directory.chapters["+j+"]"+(issue.code().equals(EXCLUSION_CONFLICT)?".exclusions":"");
            for(int j=0;j<plan.knowledge().size();j++)if(plan.knowledge().get(j).sourceItemIds().contains(issue.unitId()))path="knowledge["+j+"]";
            targets.add(Map.of("issueIndex",i,"unitId",issue.unitId(),"path",path));
        }
        return Map.of("reviewedPlan",plan,"reviewedPlanHash",ContentJson.hash(plan),"organizationIssues",issues,"issueTargets",targets);
    }
    static boolean exclusionsOnly(CompilationPlan plan,List<Learning.Issue> issues) {
        var chapters=plan.directory().chapters().stream().map(DirectoryChapter::id).collect(Collectors.toSet());
        return !issues.isEmpty()&&issues.stream().allMatch(i->i.code().equals(EXCLUSION_CONFLICT)&&chapters.contains(i.unitId()));
    }
    static RecordSchema<ExclusionRepair> exclusionRepair(Set<String> targets) {
        return new RecordSchema<>(ExclusionRepair.class,"针对reviewedPlanHash绑定的受审计划，逐项解决organizationIssues。issueTargets以受审计划中的字段路径定位。"
                +"只返回目标章节chapterId及修正后的完整exclusions列表；删除或收窄否定本章purpose、已归属知识或小节职责的排除项。"
                +LearningBusinessSchemas.EXCLUSION_RULES+"每个目标章节恰好一个patch，不能修改其他字段、章节或事实归属。",
                List.of(FieldRule.items("patches",targets.size())),v->{
            var seen=new HashSet<String>();
            for(var patch:v.patches()) {
                require(targets.contains(patch.chapterId()),Reason.UNKNOWN_REFERENCE,"patches.chapterId","仅问题定位的受审章节");
                require(seen.add(patch.chapterId()),Reason.DUPLICATE_ID,"patches.chapterId","每个目标章节恰好一次");
                require(patch.exclusions().stream().noneMatch(String::isBlank),Reason.NON_EMPTY,"patches.exclusions","空列表或具体的外部范围");
            }
            require(seen.equals(targets),Reason.MISSING_COVERAGE,"patches","覆盖全部目标章节");
        });
    }
    static CompilationPlan repairExclusions(LearningRuntime r,CompilationPlan plan,List<Learning.Issue> issues,String originalId) {
        var targets=issues.stream().map(Learning.Issue::unitId).collect(Collectors.toCollection(LinkedHashSet::new));
        var input=new LinkedHashMap<String,Object>(correctionContext(plan,issues));
        input.put("remarks",r.lease().request().instructions());input.put("estimatedOutputBytes",512+targets.size()*700);r.bounded(input);
        var fixed=r.correct("directory_exclusion_repair_v1","organize","directory",input,exclusionRepair(targets),originalId);
        var replacements=fixed.patches().stream().collect(Collectors.toMap(ChapterExclusions::chapterId,ChapterExclusions::exclusions));
        var old=plan.directory();var chapters=old.chapters().stream().map(c->replacements.containsKey(c.id())
                ?new DirectoryChapter(c.id(),c.parentId(),c.title(),c.purpose(),replacements.get(c.id()),c.resultDependencies(),c.relatedChapterIds(),c.terms()):c).toList();
        var directory=new Directory(old.title(),old.audience(),old.requirements(),old.reason(),chapters,old.terms());
        LearningBusinessSchemas.directory(r.lease().request().compilationOptions().maximumChapters()).relaxed().validate(ContentJson.encode(directory),Set.of());
        return new CompilationPlan(directory,plan.knowledge(),plan.sections());
    }
    static List<Learning.Issue> review(LearningRuntime r,Directory directory,List<FactAssignment> assignments,
                                     List<ContentReading.TopicCard> cards,String prefix) {
        var byFact=new LinkedHashMap<String,FactAssignment>();
        for(var a:assignments)if(byFact.putIfAbsent(a.itemId(),a)!=null)throw invalid();
        if(!byFact.keySet().equals(r.facts().keySet()))throw invalid();
        var rows=facts(r,cards).stream().map(row->{var value=new LinkedHashMap<String,Object>(row);
            value.put("assignment",byFact.get(((Learning.Item)row.get("item")).id()));return value;}).toList();
        var counts=assignments.stream().collect(Collectors.groupingBy(FactAssignment::chapterId,LinkedHashMap::new,Collectors.counting()));
        var base=Map.of("directory",directory,"chapterFactCounts",counts);
        int room=r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(base))-256;
        var issues=new ArrayList<Learning.Issue>();int group=0,inspected=0;
        for(var batch:LearningPlanner.partition(rows,room,ContentJson::encode)) {
            var ids=batch.stream().map(row->((Learning.Item)row.get("item")).id()).collect(Collectors.toSet());
            var allowed=new HashSet<>(ids);directory.chapters().forEach(c->allowed.add(c.id()));
            var schema=new RecordSchema<>(GlobalReview.class,"冻结计划前复核全局目录职责及本批事实的归属、条件与依据，检查重叠、异质混杂和未解决归属。"
                    +LearningBusinessSchemas.EXCLUSION_RULES+"仅当章节purpose及事实归属正确、问题可完全通过删除或收窄exclusions解决时，code="+EXCLUSION_CONFLICT+"，unitId使用受审目录章节编号。涉及职责、章节结构或事实归属调整的问题必须使用其他code；两类问题同时存在时分别列出。"
                    +"不因风格或章数偏好重组；孤立知识也须保留合理职责。ACCEPT无issues，REPAIR用事实或章节编号定位问题；必要上下文不足才INSPECT本批最多四条事实。",
                    List.of(FieldRule.choices("decision",Set.of("ACCEPT","REPAIR","INSPECT")),FieldRule.items("issues",16),FieldRule.items("inspectedUnitIds",4),
                            FieldRule.utf8("issues[].evidence",600),FieldRule.utf8("issues[].suggestion",600)),v->{
                nested("$",()->LearningBusinessSchemas.global(allowed).validate(ContentJson.encode(v),Set.of()));
                require(ids.containsAll(v.inspectedUnitIds()),Reason.UNKNOWN_REFERENCE,"inspectedUnitIds","本批事实编号");
            });
            var input=new LinkedHashMap<String,Object>(base);input.put("facts",batch);r.bounded(input);
            String id=prefix+"_"+group++;
            var checked=r.call(id,"organize","organization-review",input,schema);
            if(checked.decision().equals("INSPECT")) {
                inspected+=checked.inspectedUnitIds().size();
                if(inspected>4)throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","组织复核的必要上下文超过四条事实检查额度");
                var contexts=new LinkedHashMap<String,String>();
                for(String fact:checked.inspectedUnitIds())contexts.put(fact,r.fragmentText(r.facts().get(fact).sourceId()));
                input.put("requestedContext",contexts);r.bounded(input);
                checked=r.call(id+"_context","organize","organization-review",input,schema.withChecks(v->require(!v.decision().equals("INSPECT"),Reason.CONTENT_CONSTRAINT,"decision","补充上下文后必须ACCEPT或REPAIR")));
            }
            issues.addAll(checked.issues());
        }
        return List.copyOf(issues);
    }
    private static LabException invalid(){return new LabException("WORKFLOW_STATE_CONFLICT","主题组织缺少完整且唯一的事实归属");}
}
