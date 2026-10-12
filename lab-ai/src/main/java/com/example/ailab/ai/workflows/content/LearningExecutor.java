package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.ai.workflows.support.*;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.LearningContent.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.ai.tools.ToolSchema;
import java.util.*;
import java.util.stream.Collectors;

/** 父租约下的有界工作包调度、逐项验收、恢复和局部返工。 */
final class LearningExecutor {
    private final LearningRuntime r;private final Plan plan;private int inspectionRemaining;
    private record CachedSummary(String outputHash,UnitSummary summary) { }
    private final Map<String,CachedSummary> summaryCache=new java.util.concurrent.ConcurrentHashMap<>();
    record PartReceipt(String partId,String producerId,String json) { }
    record Completed(Learning.Result result,List<Coverage> coverage) { }
    private static final RecordSchema<PartReceipt> RECEIPT=new RecordSchema<>(PartReceipt.class,"程序保存单元草稿或验收结果与生成回执",v->RecordSchema.require(!v.partId().isBlank()&&!v.producerId().isBlank()&&!v.json().isBlank(),RecordSchema.Reason.CONTENT_CONSTRAINT));
    LearningExecutor(LearningRuntime runtime,Plan value){r=runtime;plan=value;}
    Completed execute(List<Learning.Citation> citations) {
        while(true) {
            var state=r.store().packages(r.lease());var byId=state.stream().collect(Collectors.toMap(p->p.workPackage().id(),p->p));
            var pending=state.stream().filter(p->!Set.of("ACCEPTED","REPLACED").contains(p.status())).toList();if(pending.isEmpty())break;
            var ready=pending.stream().filter(p->p.workPackage().dependencies().stream().allMatch(id->resolved(id,byId,new HashSet<>()))).limit(r.policy().parallelism()).toList();
            if(ready.isEmpty())throw new LabException("WORKFLOW_STATE_CONFLICT","执行包依赖无法推进");
            var failures=r.parallel(ready,p->{try{run(p);return Optional.<LabException>empty();}catch(LabException failure){return Optional.of(failure);}});
            for(var failure:failures)if(failure.isPresent())throw failure.get();
        }
        var leaves=r.store().packages(r.lease()).stream().filter(p->p.status().equals("ACCEPTED")).toList();
        var parts=leaves.stream().flatMap(p->p.parts().stream()).sorted(Comparator.comparing(Part::id)).toList();
        r.store().finish(r.lease(),"generate",parts.stream().map(p->"partaccepted_"+p.id()).toList());
        var outputs=assemble(parts);var coverage=coverage(parts);
        global(outputs,parts,coverage);
        for(var entry:outputs.entrySet()) {
            Object value=entry.getValue();if(value instanceof Learning.Question q)r.checkpoint("accepted_"+entry.getKey(),"review",Map.of("plan",ContentJson.hash(plan),"result",q),questionSchema(entry.getKey()),()->q);
            else {var s=(Learning.Section)value;r.checkpoint("accepted_"+entry.getKey(),"review",Map.of("plan",ContentJson.hash(plan),"result",s),sectionSchema(entry.getKey()),()->s);}
        }
        for(String chapter:chapterIds()) {
            var members=unitsInChapter(chapter,outputs.keySet());
            r.checkpoint("chapter_accepted_"+chapter,"review",Map.of("plan",ContentJson.hash(plan),"members",members,"outputs",ContentJson.hash(members.stream().map(outputs::get).toList())),LearningBusinessSchemas.global(Set.copyOf(members)),()->new GlobalReview("ACCEPT",List.of(),List.of()));
        }
        r.checkpoint("global_accepted","review",Map.of("plan",ContentJson.hash(plan),"outputs",ContentJson.hash(outputs),"coverage",coverage),LearningBusinessSchemas.global(outputs.keySet()),()->new GlobalReview("ACCEPT",List.of(),List.of()));
        r.store().finish(r.lease(),"review",plan.requiredNodes());
        Learning.Result result;
        if(plan.quiz()!=null){var questions=plan.quiz().slots().stream().map(s->(Learning.Question)outputs.get(s.id())).toList();var quiz=LearningSchemas.quiz(plan.quiz().slots().stream().map(s->new Learning.Target(s.id(),s.type(),s.itemIds())).toList()).validate(ContentJson.encode(new Learning.Quiz(plan.title(),questions)),Set.of());result=new Learning.Result("learning-quiz",plan.title(),quiz,null,List.of(),citations,true,"MODEL_REVIEW_PASSED_PENDING_HUMAN");}
        else {
            var chapters=new ArrayList<Learning.Chapter>();var chapterPlans=new ArrayList<Learning.ChapterPlan>();
            for(var chapter:plan.compilation().directory().chapters()) {
                var sections=plan.compilation().sections().stream().filter(s->s.chapterId().equals(chapter.id())).toList();
                var groups=sections.stream().map(s->new Learning.Group(s.id(),s.title(),sectionRelation(s),ownedFacts(s))).toList();chapterPlans.add(new Learning.ChapterPlan(chapter.id(),chapter.title(),chapter.parentId(),groups));
                chapters.add(new Learning.Chapter(chapter.id(),chapter.title(),sections.stream().map(s->(Learning.Section)outputs.get(s.id())).toList()));
            }
            var outline=new Learning.Outline(plan.title(),chapterPlans);result=new Learning.Result("knowledge-compilation",plan.title(),null,outline,chapters,citations,true,"MODEL_REVIEW_PASSED_PENDING_HUMAN");
        }
        return new Completed(result,coverage);
    }
    private void run(PackageStatus status) {
        var work=status.workPackage();r.store().beginPackage(r.lease(),work.id());
        try {
            var active=status.parts().stream().filter(p->r.store().completedNode(r.lease(),"partaccepted_"+p.id()).isEmpty()).toList();
            if(!active.isEmpty()) {
                var drafts=drafts(status,active);
                LabException first=null;
                for(var part:active)try {
                    var draft=drafts.get(part.id());
                    Object value=part.kind().equals("QUESTION")?questionSchema(part.logicalId()).validate(draft.json(),Set.of()):partSchema(part).validate(draft.json(),Set.of());
                    value=acceptUnit(part,value,draft.producerId());
                    var receipt=new PartReceipt(part.id(),draft.producerId(),ContentJson.encode(value));r.checkpoint("partaccepted_"+part.id(),"review",Map.of("plan",ContentJson.hash(plan),"part",part),RECEIPT,()->receipt);
                }catch(LabException failure){if(first==null)first=failure;}
                if(first!=null)throw first;
            }
            String hash=ContentJson.hash(status.parts().stream().map(p->ContentJson.encode(accepted(p.id()))).toList());r.store().completePackage(r.lease(),work.id(),hash);
        }catch(LabException failure) {
            if(capacity(failure)) {
                // 已通过单元的职责保持原样，只拆未验收职责。
                try {
                    var split=LearningPackages.split(r,plan,status);r.store().splitPackage(r.lease(),work.id(),split.parts(),split.children());return;
                }catch(LabException terminal){r.store().failPackage(r.lease(),work.id(),terminal.code());throw terminal;}
            }
            r.store().failPackage(r.lease(),work.id(),failure.code());throw failure;
        }
    }
    private Map<String,PartReceipt> drafts(PackageStatus status,List<Part> active) {
        var drafts=new LinkedHashMap<String,PartReceipt>();
        for(var part:active)r.store().completedNode(r.lease(),"partdraft_"+part.id()).ifPresent(json->{
            var receipt=RECEIPT.validate(json,Set.of());
            if(!receipt.partId().equals(part.id()))throw new LabException("WORKFLOW_STATE_CONFLICT","草稿回执单元身份不一致");
            drafts.put(part.id(),receipt);
        });
        if(drafts.size()==active.size())return drafts;
        // 包身份和完整输入保持不变；中断时可复用整包生成回执，拆包时可复用单元草稿。
        var parts=status.parts();var input=LearningPackages.input(r,parts,plan.compilation(),plan.quiz());
        var dependencies=status.workPackage().dependencies();
        if(!dependencies.isEmpty()) {
            var dependencyOutputs=new ArrayList<PartReceipt>();
            for(var p:r.store().packages(r.lease()))if(dependencies.stream().anyMatch(id->descendant(p.workPackage().id(),id))&&p.status().equals("ACCEPTED"))for(var part:p.parts())dependencyOutputs.add(accepted(part.id()));
            input.put("dependencyResults",dependencyOutputs);
        }
        r.bounded(input);String producer="write_"+status.workPackage().id();var generated=new LinkedHashMap<String,Object>();
        if(plan.quiz()==null){var batch=r.call(producer,"generate","package-write",input,LearningBusinessSchemas.sectionBatch(parts));for(var part:batch.parts())generated.put(part.partId(),part);}
        else {var slots=plan.quiz().slots().stream().collect(Collectors.toMap(Slot::id,s->s));var batch=r.call(producer,"generate","package-write",input,LearningBusinessSchemas.questions(parts,slots));for(int i=0;i<parts.size();i++)generated.put(parts.get(i).id(),batch.questions().get(i));}
        for(var part:parts) {
            var receipt=new PartReceipt(part.id(),producer,ContentJson.encode(generated.get(part.id())));
            var saved=r.checkpoint("partdraft_"+part.id(),"generate",Map.of("plan",ContentJson.hash(plan),"part",part),RECEIPT,()->receipt);
            drafts.put(part.id(),saved);
        }
        return drafts;
    }
    private Object acceptUnit(Part part,Object draft,String producer) {
        var context=LearningPackages.input(r,List.of(part),plan.compilation(),plan.quiz());
        String unit=part.kind().equals("QUESTION")?part.logicalId():part.id();
        var review=r.call("unit_review_"+part.id(),"review","unit-review",Map.of("context",context,"result",draft),LearningSchemas.review(Set.of(unit),ContentLimits.current()));
        Object result=draft;
        if(review.decision().equals("REPAIR")) {
            var input=Map.of("context",context,"result",draft,"issues",review.issues());
            if(part.kind().equals("QUESTION"))result=r.correct("unit_repair_"+part.id(),"package-write",input,questionSchema(part.logicalId()),producer);
            else result=r.correct("unit_repair_"+part.id(),"package-write",input,partSchema(part),producer);
            review=r.call("unit_rereview_"+part.id(),"review","unit-review",Map.of("context",context,"result",result),LearningSchemas.review(Set.of(unit),ContentLimits.current()));
        }
        if(!review.decision().equals("ACCEPT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","执行单元一次修复后仍未通过证据验收");return result;
    }
    private LinkedHashMap<String,Object> assemble(List<Part> parts) {
        var result=new LinkedHashMap<String,Object>();
        if(plan.quiz()!=null)for(var slot:plan.quiz().slots()){var p=parts.stream().filter(x->x.logicalId().equals(slot.id())).findFirst().orElseThrow();result.put(slot.id(),questionSchema(slot.id()).validate(accepted(p.id()).json(),Set.of()));}
        else for(var section:plan.compilation().sections()) {
            var body=parts.stream().filter(p->p.logicalId().equals(section.id())).map(p->partSchema(p).validate(accepted(p.id()).json(),Set.of()).body()).collect(Collectors.joining("\n\n"));
            result.put(section.id(),sectionSchema(section.id()).validate(ContentJson.encode(new Learning.Section(section.id(),body,ownedFacts(section))),Set.of()));
        }return result;
    }
    private void global(Map<String,Object> outputs,List<Part> parts,List<Coverage> coverage) {
        inspectionRemaining=4;var summaries=summaries(outputs,"first");var issues=globalPass(outputs,summaries,coverage,"first");issues.addAll(chapterAndConflictPass(outputs,summaries,"first"));
        if(plan.quiz()!=null){var quiz=new Learning.Quiz(plan.title(),outputs.values().stream().map(v->(Learning.Question)v).toList());issues.addAll(LearningSchemas.withLocalQuizChecks(quiz,new Learning.Review("ACCEPT",List.of())).issues());}
        var targets=issues.stream().map(Learning.Issue::unitId).distinct().toList();
        for(String id:targets) {
            var owned=parts.stream().filter(p->p.logicalId().equals(id)).toList();if(owned.stream().anyMatch(p->r.store().completedNode(r.lease(),"unit_repair_"+p.id()).isPresent()))throw new LabException("MODEL_REPAIR_EXHAUSTED","问题单元已经完成一次局部修复");
            var context=LearningPackages.input(r,owned,plan.compilation(),plan.quiz());var unitIssues=issues.stream().filter(i->i.unitId().equals(id)).toList();
            Object fixed;
            if(plan.quiz()!=null){r.bounded(context);var input=Map.of("context",context,"result",outputs.get(id),"issues",unitIssues,"neighbors",summaries.stream().filter(s->!s.id().equals(id)).limit(12).toList());fixed=r.correct("global_repair_"+id,"package-write",input,questionSchema(id),accepted(owned.get(0).id()).producerId());
                var review=r.call("global_local_review_"+id,"review","unit-review",Map.of("context",context,"result",fixed),LearningSchemas.review(Set.of(id),ContentLimits.current()));
                if(!review.decision().equals("ACCEPT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","全局问题局部修复后仍未通过验收");
            }else {
                // 大小节按既有片段返工并重组同一逻辑身份；相邻有效小节保持原结果。
                var bodies=new ArrayList<String>();for(var part:owned){var local=LearningPackages.input(r,List.of(part),plan.compilation(),null);r.bounded(local);var draft=partSchema(part).validate(accepted(part.id()).json(),Set.of());
                    var input=Map.of("context",local,"result",draft,"issues",unitIssues,"logicalSectionId",id,"neighbors",summaries.stream().filter(s->!s.id().equals(id)).limit(6).toList());
                    var repaired=r.correct("global_part_repair_"+part.id(),"package-write",input,partSchema(part),accepted(part.id()).producerId());
                    var review=r.call("global_part_review_"+part.id(),"review","unit-review",Map.of("context",local,"result",repaired),LearningSchemas.review(Set.of(part.id()),ContentLimits.current()));
                    if(!review.decision().equals("ACCEPT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","问题小节片段返工后未通过验收");bodies.add(repaired.body());
                }
                var section=(Learning.Section)outputs.get(id);fixed=sectionSchema(id).validate(ContentJson.encode(new Learning.Section(id,String.join("\n\n",bodies),section.itemIds())),Set.of());
            }
            outputs.put(id,fixed);
        }
        if(!targets.isEmpty()) {inspectionRemaining=4;var repaired=summaries(outputs,"repaired");var remaining=globalPass(outputs,repaired,coverage,"repaired");remaining.addAll(chapterAndConflictPass(outputs,repaired,"repaired"));if(!remaining.isEmpty())throw new LabException("WORKFLOW_REVIEW_REJECTED","一次局部修复后全局复核仍发现问题");}
        if(plan.quiz()!=null&&!LearningSchemas.withLocalQuizChecks(new Learning.Quiz(plan.title(),outputs.values().stream().map(v->(Learning.Question)v).toList()),new Learning.Review("ACCEPT",List.of())).decision().equals("ACCEPT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","题位存在重复题干或选项");
    }
    record UnitSummary(String id,String chapterId,String cognition,String title,String summary,List<String> itemIds) { }
    private List<UnitSummary> summaries(Map<String,Object> outputs,String round) {
        return r.parallel(List.copyOf(outputs.entrySet()),entry->{
            String outputHash=ContentJson.hash(entry.getValue());var cached=summaryCache.get(entry.getKey());
            if(cached!=null&&cached.outputHash().equals(outputHash))return cached.summary();
            var encoded=ContentJson.encode(entry.getValue());var chunks=new ArrayList<ContentWorkflow.Summary>();int from=0,index=0;
            while(from<encoded.length()){int end=TextWindow.end(encoded,from,encoded.length(),r.policy().unitInputBytes()-1500);String text=encoded.substring(from,end);chunks.add(r.call("summary_"+round+"_"+entry.getKey()+"_"+index++,"review","index",Map.of("unitId",entry.getKey(),"actualOutput",text),ContentSchemas.summary(ContentLimits.current())));from=end;}
            ContentWorkflow.Summary summary=chunks.get(0);if(chunks.size()>1)summary=r.call("summary_"+round+"_"+entry.getKey()+"_merged","review","index",Map.of("unitId",entry.getKey(),"parts",chunks),ContentSchemas.summary(ContentLimits.current()));
            UnitSummary result;
            if(plan.quiz()!=null){var slot=plan.quiz().slots().stream().filter(s->s.id().equals(entry.getKey())).findFirst().orElseThrow();result=new UnitSummary(slot.id(),slot.primaryChapterId(),slot.cognition(),summary.title(),summary.summary(),slot.itemIds());}
            else {var section=plan.compilation().sections().stream().filter(s->s.id().equals(entry.getKey())).findFirst().orElseThrow();result=new UnitSummary(section.id(),section.chapterId(),"",section.title(),summary.summary(),ownedFacts(section));}
            summaryCache.put(entry.getKey(),new CachedSummary(outputHash,result));return result;
        });
    }
    private ArrayList<Learning.Issue> globalPass(Map<String,Object> outputs,List<UnitSummary> summaries,List<Coverage> coverage,String round) {
        var issues=new ArrayList<Learning.Issue>();int i=0;
        for(var group:LearningPlanner.partition(summaries,r.policy().unitInputBytes()-300,v->ContentJson.encode(globalInput(List.of(v),coverage)))) {
            var input=globalInput(group,coverage);r.bounded(input);
            var allowed=group.stream().map(UnitSummary::id).collect(Collectors.toSet());var review=r.call("global_review_"+round+"_"+i,"review","global-review",input,LearningBusinessSchemas.global(allowed));
            if(review.decision().equals("INSPECT")) {
                inspectionRemaining-=review.inspectedUnitIds().size();if(inspectionRemaining<0)throw new LabException("WORKFLOW_REVIEW_REJECTED","本轮全局检查超过4个正文检查单元");
                var inspected=new ArrayList<Object>();for(String id:review.inspectedUnitIds()) {
                    var evidence=group.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow().itemIds();
                    // 检查输入必须完整；容量不够时明确失败，禁止截掉必要证据。
                    var full=Map.of("unitId",id,"actualOutput",outputs.get(id),"evidence",r.evidence(evidence));r.bounded(full);
                    var checked=r.call("inspect_"+round+"_"+i+"_"+id,"review","unit-review",full,LearningSchemas.review(Set.of(id),ContentLimits.current()));issues.addAll(checked.issues());inspected.add(Map.of("id",id,"review",checked));
                }
                review=r.call("global_after_inspect_"+round+"_"+i,"review","global-review",Map.of("context",input,"inspectionResults",inspected),LearningBusinessSchemas.global(allowed));
                if(review.decision().equals("INSPECT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","全局复核达到有限定点检查次数");
            }
            issues.addAll(review.issues());i++;
        }
        // 全范围协调检查保留所有单元身份，层级摘要仅压缩正文，不丢跨包职责。
        var level=summaries.stream().map(s->new ReviewIndex(List.of(s.id()),new ContentWorkflow.Summary(s.title(),s.summary()))).toList();int depth=0;
        while(TextWindow.count(ContentJson.encode(level))>r.policy().unitInputBytes()-2500) {
            var groups=LearningPlanner.partition(level,r.policy().unitInputBytes()-1000,ContentJson::encode);var next=new ArrayList<ReviewIndex>();int index=0;
            for(var group:groups){var summary=r.call("review_index_"+round+"_"+depth+"_"+index++,"review","index",Map.of("children",group),compactSummary());next.add(new ReviewIndex(group.stream().flatMap(v->v.members().stream()).toList(),summary));}
            if(next.size()>=level.size()||++depth>16)throw new LabException("WORKFLOW_INDEX_CAPACITY_EXCEEDED","全局复核索引无法在固定资源中收敛");level=next;
        }
        var master=new LinkedHashMap<String,Object>();master.put("allUnitIndex",level);master.put("title",plan.title());master.put("requirements",plan.requirements());
        master.put("coverageCounts",Map.of("readFragments",r.reading().fragments().size(),"facts",r.facts().size(),"chapters",plan.counts().chapters(),"questions",plan.counts().questions(),"sections",plan.counts().sections()));
        Object responsibilities=plan.compilation()!=null?plan.compilation().directory().chapters():plan.quiz().quotas();
        var directoryIndex=new ArrayList<ContentWorkflow.Summary>();int directoryPart=0;
        List<?> entries=responsibilities instanceof List<?> list?list:List.of(responsibilities);
        for(var group:LearningPlanner.partition(entries,r.policy().indexBytes()-500,ContentJson::encode))directoryIndex.add(r.call("directory_review_index_"+round+"_"+directoryPart++,"review","index",Map.of("responsibilities",group),compactSummary()));
        int directoryDepth=0;while(TextWindow.count(ContentJson.encode(directoryIndex))>1200){var next=new ArrayList<ContentWorkflow.Summary>();int chunk=0;for(var group:LearningPlanner.partition(directoryIndex,r.policy().indexBytes()-500,ContentJson::encode))next.add(r.call("directory_review_reduce_"+round+"_"+directoryDepth+"_"+chunk++,"review","index",Map.of("children",group),compactSummary()));if(next.size()>=directoryIndex.size()||++directoryDepth>16)throw new LabException("WORKFLOW_INDEX_CAPACITY_EXCEEDED","目录复核索引无法收敛");directoryIndex=next;}
        master.put("terms",plan.compilation()==null?List.of():plan.compilation().directory().terms());
        master.put("directoryIndex",directoryIndex);r.bounded(master);
        var whole=r.call("global_master_"+round,"review","global-review",master,LearningBusinessSchemas.global(outputs.keySet()));
        if(whole.decision().equals("INSPECT")){
            inspectionRemaining-=whole.inspectedUnitIds().size();if(inspectionRemaining<0)throw new LabException("WORKFLOW_REVIEW_REJECTED","本轮全局检查超过4个正文检查单元");
            var inspected=new ArrayList<Learning.Review>();for(String id:whole.inspectedUnitIds()){
                var summary=summaries.stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();var input=Map.of("unitId",id,"actualOutput",outputs.get(id),"evidence",r.evidence(summary.itemIds()));r.bounded(input);
                var checked=r.call("master_inspect_"+round+"_"+id,"review","unit-review",input,LearningSchemas.review(Set.of(id),ContentLimits.current()));issues.addAll(checked.issues());inspected.add(checked);
            }
            whole=r.call("global_master_after_inspect_"+round,"review","global-review",Map.of("context",master,"inspections",inspected),LearningBusinessSchemas.global(outputs.keySet()));
            if(whole.decision().equals("INSPECT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","全范围复核达到有限定点检查次数");
        }
        issues.addAll(whole.issues());
        if(plan.quiz()!=null) {
            var byId=summaries.stream().collect(Collectors.toMap(UnitSummary::id,s->s));
            for(var candidate:LearningPlanRules.reviewPairs(plan.quiz())) {
                var x=byId.get(candidate.leftId());var y=byId.get(candidate.rightId());
                var pair=Map.of("candidates",List.of(x,y),"actualQuestions",List.of(outputs.get(x.id()),outputs.get(y.id())),"rule","同知识同认知目的不能仅改写题干；不同认知目的允许再次覆盖");r.bounded(pair);
                var review=r.call("duplicate_"+round+"_"+candidate.key(),"review","global-review",pair,LearningBusinessSchemas.global(Set.of(x.id(),y.id())));
                if(review.decision().equals("INSPECT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","候选题已提供完整正文，无法继续请求正文");issues.addAll(review.issues());
            }
        }
        return issues;
    }
    private Map<String,Object> globalInput(List<UnitSummary> group,List<Coverage> coverage) {
        var chapters=group.stream().map(UnitSummary::chapterId).collect(Collectors.toSet());
        Object directory=plan.compilation()!=null?plan.compilation().directory().chapters().stream().filter(c->chapters.contains(c.id())).toList():plan.quiz().quotas().stream().filter(q->chapters.contains(q.chapterId())).toList();
        return Map.of("directoryOrQuotas",directory,"units",group,"coverage",coverage.stream().filter(c->group.stream().anyMatch(s->s.id().equals(c.id())||s.chapterId().equals(c.id()))).toList(),"requirements",plan.requirements());
    }
    record ReviewIndex(List<String> members,ContentWorkflow.Summary summary) { }
    private List<String> chapterIds(){return plan.compilation()!=null?plan.compilation().directory().chapters().stream().map(DirectoryChapter::id).toList():plan.quiz().chapters().stream().map(Chapter::id).toList();}
    private Map<String,String> parents(){return plan.compilation()!=null?plan.compilation().directory().chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId)):plan.quiz().chapters().stream().collect(Collectors.toMap(Chapter::id,Chapter::parentId));}
    private Set<String> descendants(String chapter){var ancestors=parents();return chapterIds().stream().filter(id->LearningHierarchy.ancestors(Set.of(id),ancestors).contains(chapter)).collect(Collectors.toSet());}
    private List<String> unitsInChapter(String chapter,Set<String> all){var children=descendants(chapter);return plan.compilation()!=null?plan.compilation().sections().stream().filter(s->children.contains(s.chapterId())&&all.contains(s.id())).map(SectionDuty::id).toList():plan.quiz().slots().stream().filter(s->children.contains(s.primaryChapterId())&&all.contains(s.id())).map(Slot::id).toList();}
    private List<Learning.Issue> chapterAndConflictPass(Map<String,Object> outputs,List<UnitSummary> summaries,String round) {
        var issues=new ArrayList<Learning.Issue>();
        for(String chapter:chapterIds()) {
            var children=descendants(chapter);var members=summaries.stream().filter(s->children.contains(s.chapterId())).toList();int part=0;
            for(var group:LearningPlanner.partition(members,r.policy().unitInputBytes()-1500,ContentJson::encode)) {
                var allowed=group.stream().map(UnitSummary::id).collect(Collectors.toSet());var input=Map.of("chapterId",chapter,"descendantChapterIds",children,"units",group,"rule","核对完整章职责、衔接、考点及冲突；父章按唯一单元汇总，问题定位单元");r.bounded(input);
                String id="chapter_review_"+round+"_"+chapter+"_"+part++;
                var review=r.call(id,"review","chapter-review",input,LearningBusinessSchemas.global(allowed));
                issues.addAll(inspectBusiness(review,id,input,allowed,outputs,summaries));
            }
        }
        if(plan.compilation()!=null)for(var knowledge:plan.compilation().knowledge())if(knowledge.relation().equals("CONFLICT")) {
            var affected=summaries.stream().filter(s->!Collections.disjoint(s.itemIds(),knowledge.sourceItemIds())).toList();var allowed=affected.stream().map(UnitSummary::id).collect(Collectors.toSet());
            var fixed=Map.of("conflictId",knowledge.id(),"conditions",knowledge.conditions(),"knowledgeSummary",knowledge.summary(),"affectedSections",affected,"sourceItemIds",knowledge.sourceItemIds());int part=0;
            for(var evidence:LearningPlanner.partition(r.evidence(knowledge.sourceItemIds()),r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(fixed))-300,ContentJson::encode)) {
                var input=new LinkedHashMap<String,Object>(fixed);input.put("evidence",evidence);r.bounded(input);
                String id="conflict_review_"+round+"_"+knowledge.id()+"_"+part++;
                var review=r.call(id,"review","conflict-review",input,LearningBusinessSchemas.global(allowed));issues.addAll(inspectBusiness(review,id,input,allowed,outputs,summaries));
            }
        }
        return issues;
    }
    private List<Learning.Issue> inspectBusiness(GlobalReview review,String id,Object context,Set<String> allowed,Map<String,Object> outputs,List<UnitSummary> summaries) {
        var issues=new ArrayList<>(review.issues());if(!review.decision().equals("INSPECT"))return issues;
        inspectionRemaining-=review.inspectedUnitIds().size();if(inspectionRemaining<0)throw new LabException("WORKFLOW_REVIEW_REJECTED","本轮所有全局和章级检查共用4个定点检查额度");
        var inspections=new ArrayList<Learning.Review>();for(String unit:review.inspectedUnitIds()) {
            var summary=summaries.stream().filter(s->s.id().equals(unit)).findFirst().orElseThrow();var full=Map.of("unitId",unit,"actualOutput",outputs.get(unit),"evidence",r.evidence(summary.itemIds()));r.bounded(full);
            var checked=r.call(id+"_inspect_"+unit,"review","unit-review",full,LearningSchemas.review(Set.of(unit),ContentLimits.current()));inspections.add(checked);issues.addAll(checked.issues());
        }
        var result=r.call(id+"_after_inspect","review","global-review",Map.of("context",context,"inspections",inspections),LearningBusinessSchemas.global(allowed));
        if(result.decision().equals("INSPECT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","正文检查后不得再次扩大检查范围");issues.addAll(result.issues());return issues;
    }
    private static RecordSchema<ContentWorkflow.Summary> compactSummary(){return new RecordSchema<>(ContentWorkflow.Summary.class,"保留全局职责、重复及冲突的简明索引",List.of(RecordSchema.FieldRule.utf8("title",120),RecordSchema.FieldRule.utf8("summary",600)),v->{});}
    private List<Coverage> coverage(List<Part> parts) {
        var out=new ArrayList<Coverage>();out.add(new Coverage("source","READ",r.reading().fragments().size(),r.reading().fragments().size(),r.reading().fragments().stream().map(f->f.source().id()).toList(),"全部自然拥有区间阅读，背景区间不重复计数"));
        if(plan.compilation()!=null){out.add(new Coverage("knowledge","KNOWLEDGE",r.facts().size(),r.facts().size(),plan.compilation().knowledge().stream().map(Knowledge::id).toList(),"每条事实具有唯一规范知识和主要小节"));for(var s:plan.compilation().sections())out.add(new Coverage(s.id(),"ACCEPTANCE",ownedFacts(s).size(),ownedFacts(s).size(),parts.stream().filter(p->p.logicalId().equals(s.id())).map(Part::id).toList(),"小节所有执行片段通过局部验收"));}
        else {var selected=plan.quiz().slots().stream().flatMap(s->s.itemIds().stream()).distinct().toList();out.add(new Coverage("knowledge","KNOWLEDGE",r.facts().size(),selected.size(),selected,"已选题位实际引用的事实；未入选知识另列原因"));for(var slot:plan.quiz().slots())out.add(new Coverage(slot.id(),"ACCEPTANCE",1,1,parts.stream().filter(p->p.logicalId().equals(slot.id())).map(Part::id).toList(),"题位通过局部验收及全局检查"));for(var q:plan.quiz().quotas())out.add(new Coverage(q.chapterId(),"QUIZ_CHAPTER",q.maximum(),q.allocated(),plan.quiz().slots().stream().filter(s->s.primaryChapterId().equals(q.chapterId())).map(Slot::id).toList(),q.reason()));for(var o:plan.quiz().objectives()){var slots=plan.quiz().slots().stream().filter(s->s.objectiveId().equals(o.id())).map(Slot::id).toList();out.add(new Coverage(o.id(),"QUIZ_OBJECTIVE",1,slots.size(),slots,slots.isEmpty()?"未被固定数量配额选择":o.reason()));}for(var u:plan.quiz().uncovered())out.add(new Coverage(u.id(),"UNEXAMINED_"+u.kind(),1,0,List.of(),u.reason()));}
        if(plan.quiz()!=null)for(var chapter:plan.quiz().chapters()){
            var associated=plan.quiz().slots().stream().filter(s->s.relatedChapterIds().contains(chapter.id())).map(Slot::id).toList();out.add(new Coverage(chapter.id(),"QUIZ_ASSOCIATED",(int)plan.quiz().objectives().stream().filter(o->o.relatedChapterIds().contains(chapter.id())).count(),associated.size(),associated,"支持章节关联，不增加独立题数"));
            var children=descendants(chapter.id());var objectives=plan.quiz().objectives().stream().filter(o->children.contains(o.primaryChapterId())).map(Objective::id).distinct().toList();var selected=plan.quiz().slots().stream().filter(s->children.contains(s.primaryChapterId())).map(Slot::objectiveId).distinct().toList();out.add(new Coverage(chapter.id(),"QUIZ_SUBTREE_OBJECTIVE",objectives.size(),selected.size(),selected,"按父章子树唯一考点汇总"));
        }
        var all=plan.compilation()!=null?plan.compilation().sections().stream().map(SectionDuty::id).collect(Collectors.toSet()):plan.quiz().slots().stream().map(Slot::id).collect(Collectors.toSet());
        for(String chapter:chapterIds()){var members=unitsInChapter(chapter,all);out.add(new Coverage(chapter,"CHAPTER_ACCEPTANCE",members.size(),members.size(),members,"所有必需单元和章级／全局检查通过后发布"));}
        return List.copyOf(out);
    }
    private List<String> ownedFacts(SectionDuty section){var byId=plan.compilation().knowledge().stream().collect(Collectors.toMap(Knowledge::id,k->k));return section.ownedKnowledgeIds().stream().flatMap(id->byId.get(id).sourceItemIds().stream()).toList();}
    private String sectionRelation(SectionDuty section){var kinds=plan.compilation().knowledge().stream().filter(k->section.ownedKnowledgeIds().contains(k.id())).map(Knowledge::relation).collect(Collectors.toSet());return kinds.contains("CONFLICT")?"CONFLICT":kinds.equals(Set.of("SAME"))?"MERGE":"COMPLEMENT";}
    private PartReceipt accepted(String id){return RECEIPT.validate(r.store().completedNode(r.lease(),"partaccepted_"+id).orElseThrow(()->new LabException("WORKFLOW_STATE_CONFLICT","已验收执行包缺少单元结果")),Set.of());}
    private RecordSchema<SectionPart> partSchema(Part part){return new RecordSchema<>(SectionPart.class,"修复partId="+part.id()+"的单个小节片段，保持itemIds",List.of(RecordSchema.FieldRule.utf8("body",ContentLimits.current().section())),v->RecordSchema.require(v.partId().equals(part.id())&&v.itemIds().equals(part.itemIds())&&!v.body().isBlank(),RecordSchema.Reason.CONTENT_CONSTRAINT));}
    private RecordSchema<Learning.Section> sectionSchema(String id){var duty=plan.compilation().sections().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();return new RecordSchema<>(Learning.Section.class,"输出groupId="+id+"的完整小节，保持itemIds",List.of(RecordSchema.FieldRule.utf8("body",ContentLimits.current().section())),v->RecordSchema.require(v.groupId().equals(id)&&v.itemIds().equals(ownedFacts(duty))&&!v.body().isBlank(),RecordSchema.Reason.CONTENT_CONSTRAINT));}
    private RecordSchema<Learning.Question> questionSchema(String id){var slot=plan.quiz().slots().stream().filter(s->s.id().equals(id)).findFirst().orElseThrow();return LearningSchemas.question(new Learning.Target(id,slot.type(),slot.itemIds()));}
    private static boolean capacity(LabException e){return Set.of("MODEL_TRUNCATED","MODEL_CONTEXT_INSUFFICIENT","WORKFLOW_UNIT_TOO_LARGE").contains(e.code());}
    private static boolean descendant(String actual,String parent){return actual.equals(parent)||actual.startsWith(parent+"_");}
    private static boolean resolved(String id,Map<String,PackageStatus> all,Set<String> seen){var p=all.get(id);if(p==null||!seen.add(id))return false;if(p.status().equals("ACCEPTED"))return true;return p.status().equals("REPLACED")&&!p.children().isEmpty()&&p.children().stream().allMatch(c->resolved(c,all,new HashSet<>(seen)));}
}
