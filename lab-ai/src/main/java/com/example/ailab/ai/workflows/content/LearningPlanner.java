package com.example.ailab.ai.workflows.content;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.LearningContent.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 先形成全局业务承诺，再按必要输入和输出容量生成执行包。 */
final class LearningPlanner {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(LearningPlanner.class);
    private final LearningRuntime r;
    private List<ContentReading.TopicCard> cards;private boolean inferDirectory;
    LearningPlanner(LearningRuntime runtime){r=runtime;cards=r.cards();}
    Plan plan() {
        var existing=r.store().acceptedLearningPlan(r.lease());if(existing.isPresent()){r.store().finish(r.lease(),"organize",List.of(existing.get().quiz()==null?"directory":"quiz_intent"));return existing.get();}
        cards=r.cards();if(!r.compact())clarify();
        CompilationPlan compilation=null;Blueprint quiz=null;
        if(r.lease().request().taskType().equals("KNOWLEDGE_COMPILATION"))compilation=compilation();else quiz=quiz();
        var execution=LearningPackages.build(r,compilation,quiz);
        var logical=compilation!=null?compilation.sections().stream().map(SectionDuty::id).toList():quiz.slots().stream().map(Slot::id).toList();
        int turns=estimatedTurns(compilation,quiz,execution);
        if(turns>r.executionLimits().turns()||turns*2>r.executionLimits().attempts())throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","生成、复核及一次修复超过父任务执行额度");
        var counts=new ContentWorkflow.Counts(quiz==null?0:quiz.slots().size(),quiz==null?compilation.directory().chapters().size():quiz.chapters().size(),compilation==null?0:compilation.sections().size(),0,0,0);
        int output=estimatedOutputBytes(compilation,quiz,execution);
        var result=new Plan(LearningContent.VERSION,r.lease().request().taskType(),ContentJson.hash(r.source()),compilation,quiz,execution,counts,logical.stream().map(id->"accepted_"+id).toList(),turns,turns*2,output);
        LearningPlanRules.validate(result,r.lease().request(),r.facts().keySet(),r.policy());r.store().accept(r.lease(),result);
        r.store().finish(r.lease(),"organize",List.of(compilation==null?"quiz_intent":"directory"));return result;
    }
    static int estimatedTurns(CompilationPlan compilation,Blueprint quiz,ExecutionPlan execution) {
        int logical=compilation!=null?compilation.sections().size():quiz.slots().size();
        // 两轮题对复核分别对应初次检查和局部修复后的检查，候选来自不可变蓝图。
        int pairs=quiz==null?0:LearningPlanRules.reviewPairs(quiz).size();
        return execution.packages().size()*2+execution.parts().size()*(compilation==null?4:2)+logical*2
                +(quiz==null?0:quiz.chapters().size()*4)
                +(compilation==null?0:(int)compilation.knowledge().stream().filter(k->k.relation().equals("CONFLICT")).count()*2)+pairs*2+32;
    }
    private int estimatedOutputBytes(CompilationPlan compilation,Blueprint quiz,ExecutionPlan execution) {
        if(compilation==null)return execution.parts().stream().mapToInt(Part::outputBytes).sum()+r.facts().size()*3500;
        var slices=r.source().slices().stream().collect(Collectors.toMap(Learning.SourceSlice::id,Function.identity()));
        var citations=r.facts().values().stream().map(item->{int start=r.quoteOffset(item);return new Learning.Citation(item.id(),slices.get(item.sourceId()),start,start+item.quote().length(),item.quote());}).toList();
        int metadata=TextWindow.count(ContentJson.encode(Map.of("compilation",compilation,"execution",execution,"citations",citations)));
        // 基于真实元数据和计划正文留出重复职责、覆盖报告及JSON转义余量；实际产物仍受持久容量限制。
        long estimate=metadata*2L+execution.parts().stream().mapToLong(Part::outputBytes).sum()*2L+4096;
        return (int)Math.min(Integer.MAX_VALUE,estimate);
    }
    private CompilationPlan compilation() {
        if(r.compact()){var joined=new CompactCompilationPlanner(r).plan();if(joined.isPresent()){var value=joined.get();r.checkpoint("directory","organize",Map.of("compact",ContentJson.hash(value)),LearningBusinessSchemas.directory(LearningPlanRules.MAX_CHAPTERS),value::directory);return value;}}
        int maximum=r.lease().request().compilationOptions().maximumChapters();if(maximum==0)maximum=LearningPlanRules.MAX_CHAPTERS;else maximum=Math.min(maximum,LearningPlanRules.MAX_CHAPTERS);
        var directory=r.call("directory","organize","directory",Map.of("remarks",r.lease().request().instructions(),"options",r.lease().request().compilationOptions(),"allThemes",index(cards)),LearningBusinessSchemas.directory(maximum));
        var assignments=new LinkedHashMap<String,String>();var fixedDirectory=directory;
        var cardGroups=partition(cards,r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(directory))-800,ContentJson::encode);
        var routedCards=r.parallel(java.util.stream.IntStream.range(0,cardGroups.size()).boxed().toList(),i->r.call("assign_"+i,"organize","assign",Map.of("directory",fixedDirectory,"topicCards",cardGroups.get(i)),LearningBusinessSchemas.assignments(cardGroups.get(i),fixedDirectory)));
        routedCards.forEach(batch->batch.assignments().forEach(a->assignments.put(a.cardId(),a.chapterId())));
        var factChapter=new LinkedHashMap<String,String>();var factCard=new HashMap<String,ContentReading.TopicCard>();
        for(var card:cards)for(String id:card.topic().itemIds()){factChapter.put(id,assignments.get(card.id()));factCard.put(id,card);}
        var canonical=new ArrayList<Knowledge>();var owner=new HashMap<String,String>();
        if(r.facts().size()>(r.policy().unitInputBytes()>=128000?32:64))normalizeBatches(factCard,factChapter,canonical,owner);
        else for(var fact:r.facts().values())normalizeOne(fact,factCard,factChapter,canonical,owner);
        var parents=directory.chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId));
        var used=LearningHierarchy.ancestors(new LinkedHashSet<>(owner.values()),parents);var chapters=directory.chapters().stream().filter(c->used.contains(c.id())).map(c->new DirectoryChapter(c.id(),c.parentId(),c.title(),c.purpose(),c.exclusions(),
                c.resultDependencies().stream().flatMap(id->used.contains(id)?java.util.stream.Stream.of(id):canonical.stream().filter(k->k.sourceItemIds().stream().anyMatch(f->id.equals(factChapter.get(f)))).map(k->owner.get(k.id()))).filter(id->!id.equals(c.id())).distinct().toList(),c.relatedChapterIds().stream().filter(used::contains).toList(),c.terms())).toList();
        directory=new Directory(directory.title(),directory.audience(),directory.requirements(),directory.reason(),chapters,directory.terms());
        var sections=new ArrayList<SectionDuty>();
        for(var chapter:chapters) {
            var owned=canonical.stream().filter(k->owner.get(k.id()).equals(chapter.id())).toList();if(owned.isEmpty())continue;
            var outline=r.call("section_outline_"+chapter.id(),"organize","sections",Map.of("chapter",chapter,"knowledgeIndex",index(owned,"section_index_"+chapter.id()),"terms",directory.terms()),LearningBusinessSchemas.sectionOutline());
            var sectionAssignments=new LinkedHashMap<String,List<String>>();var contexts=new LinkedHashMap<String,List<String>>();outline.sections().forEach(s->{sectionAssignments.put(s.id(),new ArrayList<>());contexts.put(s.id(),new ArrayList<>());});int group=0;
            int overhead=TextWindow.count(ContentJson.encode(outline))+TextWindow.count(ContentJson.encode(chapter))+600;
            for(var batch:partition(owned,r.policy().unitInputBytes()-overhead,ContentJson::encode)) {
                var routed=r.call("section_assign_"+chapter.id()+"_"+group++,"organize","assign",Map.of("chapter",chapter,"outline",outline,"knowledge",batch),LearningBusinessSchemas.knowledgeAssignments(batch,outline));
                for(var a:routed.assignments()){sectionAssignments.get(a.sectionId()).add(a.knowledgeId());for(String support:a.contextSectionIds())contexts.get(support).add(a.knowledgeId());}
            }
            for(var s:outline.sections())if(!sectionAssignments.get(s.id()).isEmpty())sections.add(new SectionDuty(String.format(Locale.ROOT,"s%05d",sections.size()+1),chapter.id(),s.title(),s.purpose(),sectionAssignments.get(s.id()),contexts.get(s.id()).stream().distinct().filter(id->!sectionAssignments.get(s.id()).contains(id)).toList(),s.outputBytes()));
        }
        return new CompilationPlan(directory,canonical,sections);
    }
    private void normalizeOne(Learning.Item fact,Map<String,ContentReading.TopicCard> factCard,Map<String,String> factChapter,List<Knowledge> canonical,Map<String,String> owner) {
        var card=factCard.get(fact.id());var candidates=candidates(fact,factCard,canonical);
        Knowledge merge=null;Relation relation=null;
        var judgments=relations(fact,card,candidates);
        for(var k:candidates) {
            var check=judgments.get(k.id());
            // 互补事实保留独立主要职责；冲突项保留各版本证据与条件。
            if(check.decision().equals("SAME")||check.decision().equals("CONFLICT")){merge=k;relation=check;break;}
        }
        if(merge==null){String id=String.format(Locale.ROOT,"k%05d",canonical.size()+1);canonical.add(new Knowledge(id,"SINGLE",fact.content(),List.of(fact.id()),card.topic().conditions()));owner.put(id,factChapter.get(fact.id()));}
        else {
            var ids=new ArrayList<>(merge.sourceItemIds());ids.add(fact.id());var conditions=new LinkedHashSet<>(merge.conditions());conditions.addAll(card.topic().conditions());conditions.addAll(relation.conditions());
            String kind=merge.relation().equals("CONFLICT")?"CONFLICT":relation.decision();
            canonical.set(canonical.indexOf(merge),new Knowledge(merge.id(),kind,relation.summary(),ids,List.copyOf(conditions)));
        }
    }
    private void normalizeBatches(Map<String,ContentReading.TopicCard> factCard,Map<String,String> chapters,List<Knowledge> canonical,Map<String,String> owner) {
        int maximumWindow=r.compact()?128:r.policy().unitInputBytes()>=128000?64:32;
        var facts=List.copyOf(r.facts().values());int from=0,window=maximumWindow,batches=0;boolean singleMode=false;long started=System.nanoTime();
        while(from<facts.size()) {
            if(singleMode){normalizeOne(facts.get(from++),factCard,chapters,canonical,owner);continue;}
            int end=Math.min(from+window,facts.size());Map<String,Object> input;boolean fallback=false;
            while(true) {
                input=normalizationInput(facts.subList(from,end),factCard,canonical);
                if(TextWindow.count(ContentJson.encode(input))<=r.policy().unitInputBytes())break;
                if(end-from==1){normalizeOne(facts.get(from),factCard,chapters,canonical,owner);from++;window=maximumWindow;fallback=true;break;}
                end=from+Math.max(1,(end-from)/2);
            }
            if(fallback)continue;
            var groupFacts=facts.subList(from,end);var ids=groupFacts.stream().map(Learning.Item::id).toList();
            var candidates=new LinkedHashMap<String,List<String>>();
            for(var fact:groupFacts)candidates.put(fact.id(),candidates(fact,factCard,canonical).stream().map(Knowledge::id).toList());
            LearningBusinessSchemas.NormalizationBatch result;
            try {
                result=r.call("normalize_batch_"+ids.get(0)+"_"+ids.get(ids.size()-1),"organize","normalize",input,LearningBusinessSchemas.normalizationBatch(ids,candidates,(a,b)->
                        r.facts().containsKey(a)&&r.facts().containsKey(b)&&related(factCard.get(a),factCard.get(b),r.facts().get(a),r.facts().get(b))));
            }catch(LabException failure) {
                if(end-from>1&&Set.of("MODEL_TRUNCATED","MODEL_CONTEXT_INSUFFICIENT").contains(failure.code())){window=Math.max(1,(end-from)/2);continue;}
                if(end-from==1&&Set.of("MODEL_TRUNCATED","MODEL_CONTEXT_INSUFFICIENT").contains(failure.code())) {
                    normalizeOne(facts.get(from),factCard,chapters,canonical,owner);from++;singleMode=true;
                    LOG.info("event=content.normalize_fallback completedFacts={} facts={} reason={}",from,facts.size(),failure.code());continue;
                }
                throw failure;
            }
            for(var group:result.groups()) {
                Knowledge old=group.existingId().isEmpty()?null:canonical.stream().filter(k->k.id().equals(group.existingId())).findFirst().orElseThrow();
                var refs=new ArrayList<String>();var conditions=new LinkedHashSet<String>();
                if(old!=null){refs.addAll(old.sourceItemIds());conditions.addAll(old.conditions());}
                refs.addAll(group.itemIds());for(String id:group.itemIds())conditions.addAll(factCard.get(id).topic().conditions());conditions.addAll(group.conditions());
                String id=old==null?String.format(Locale.ROOT,"k%05d",canonical.size()+1):old.id();
                String relation=old!=null&&old.relation().equals("CONFLICT")?"CONFLICT":group.relation();
                String summary=relation.equals("SINGLE")?r.facts().get(group.itemIds().get(0)).content():group.summary();
                var value=new Knowledge(id,relation,summary,refs,List.copyOf(conditions));
                if(old==null){canonical.add(value);owner.put(id,chapters.get(group.itemIds().get(0)));}else canonical.set(canonical.indexOf(old),value);
            }
            from=end;window=maximumWindow;
            LOG.info("event=content.normalize_batch completedFacts={} facts={} batches={} inputBytes={} durationMs={}",from,facts.size(),++batches,TextWindow.count(ContentJson.encode(input)),java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started));
        }
    }
    private List<Knowledge> candidates(Learning.Item fact,Map<String,ContentReading.TopicCard> cards,List<Knowledge> canonical) {
        var card=cards.get(fact.id());return canonical.stream().filter(k->related(card,cards.get(k.sourceItemIds().get(0)),fact,r.facts().get(k.sourceItemIds().get(0))))
                .sorted(Comparator.comparingInt((Knowledge k)->similarity(card,cards.get(k.sourceItemIds().get(0)))).reversed().thenComparing(Knowledge::id)).limit(8).toList();
    }
    private Map<String,Object> normalizationInput(List<Learning.Item> facts,Map<String,ContentReading.TopicCard> cards,List<Knowledge> canonical) {
        var existing=new LinkedHashMap<String,Object>();var rows=new ArrayList<Object>();
        for(var fact:facts) {
            var card=cards.get(fact.id());var allowed=candidates(fact,cards,canonical);
            allowed.forEach(k->existing.putIfAbsent(k.id(),Map.of("knowledge",k,"evidence",r.evidence(List.of(k.sourceItemIds().get(0))))));
            rows.add(Map.of("item",fact,"conditions",card.topic().conditions(),"keywords",card.topic().keywords(),"entities",card.topic().entities(),"candidateIds",allowed.stream().map(Knowledge::id).toList()));
        }
        return Map.of("facts",rows,"existing",List.copyOf(existing.values()));
    }
    private Map<String,Relation> relations(Learning.Item fact,ContentReading.TopicCard card,List<Knowledge> candidates) {
        if(candidates.isEmpty())return Map.of();
        var entries=candidates.stream().map(k->Map.<String,Object>of("candidateId",k.id(),"existing",k,"existingEvidence",r.evidence(List.of(k.sourceItemIds().get(0))))).toList();
        var base=Map.of("candidate",fact,"candidateConditions",card.topic().conditions());
        var result=new LinkedHashMap<String,Relation>();int group=0;
        for(var batch:partition(entries,r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(base))-100,ContentJson::encode)) {
            var input=new LinkedHashMap<String,Object>(base);input.put("candidates",batch);r.bounded(input);
            var ids=batch.stream().map(v->(String)v.get("candidateId")).toList();
            var checked=r.call("relation_batch_"+fact.id()+"_"+group++,"organize","normalize",input,LearningBusinessSchemas.candidateRelations(ids));
            checked.relations().forEach(v->result.put(v.candidateId(),v.relation()));
            // 与原候选顺序一致；一个批次已经找到归并目标时不请求后续批次。
            if(checked.relations().stream().anyMatch(v->Set.of("SAME","CONFLICT").contains(v.relation().decision())))break;
        }
        return result;
    }
    private Blueprint quiz() {
        var chapters=chapters();var chapterIds=chapters.stream().map(Chapter::id).collect(Collectors.toSet());
        if(chapters.size()>LearningPlanRules.MAX_CHAPTERS)
            throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","有效学习章节超过"+LearningPlanRules.MAX_CHAPTERS+"个，请缩小资料范围或整理章节层级");
        var intent=r.call("quiz_intent","organize","quiz-intent",Map.of("remarks",r.lease().request().instructions(),"options",r.lease().request().quizOptions(),"chapters",index(chapters.stream().map(c->Map.of("id",c.id(),"title",c.title(),"facts",c.itemIds().size())).toList())),LearningBusinessSchemas.quizIntent(chapterIds));
        if(!intent.coveragePolicy().equals("EVERY_CHAPTER")&&r.lease().request().instructions().isBlank())throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","默认自测须覆盖每个有效章节，抽样需要明确备注");
        var objectives=new ArrayList<Objective>();var uncovered=new ArrayList<Uncovered>();int nextObjectiveId=1;
        if(r.batchQuiz()) {
            var owners=new HashMap<String,String>();QuizPlanning.packages(r,chapters).forEach(w->w.groups().forEach(g->owners.put(g.groupId(),g.chapter().get("id"))));
            for(var group:QuizPlanning.execute(r,chapters)) {
                String chapter=owners.get(group.groupId());var candidates=group.result();
                for(var o:candidates.objectives())objectives.add(new Objective(String.format(Locale.ROOT,"o%05d",nextObjectiveId++),chapter,o.title(),o.cognition(),o.questionTypes(),o.itemIds(),o.priority(),o.reason(),o.relatedChapterIds()));
                for(String id:candidates.unexaminedItemIds())uncovered.add(new Uncovered("FACT",id,candidates.reason()));
            }
        } else for(var chapter:chapters) {int group=0;
            for(var facts:partition(r.evidence(chapter.itemIds()),r.policy().unitInputBytes()-1000,ContentJson::encode)) {
                var candidates=r.call("objectives_"+chapter.id()+"_"+group++,"organize","objectives",Map.of("chapter",Map.of("id",chapter.id(),"title",chapter.title()),"facts",facts,"difficulty",r.lease().request().quizOptions().difficulty(),"allowedTypes",r.lease().request().quizOptions().questionTypes()),LearningBusinessSchemas.objectives(facts,new HashSet<>(r.lease().request().quizOptions().questionTypes()),chapterIds));
                for(var o:candidates.objectives())objectives.add(new Objective(String.format(Locale.ROOT,"o%05d",nextObjectiveId++),chapter.id(),o.title(),o.cognition(),o.questionTypes(),o.itemIds(),o.priority(),o.reason(),o.relatedChapterIds()));
                for(String id:candidates.unexaminedItemIds())uncovered.add(new Uncovered("FACT",id,candidates.reason()));
            }
        }
        var distinct=distinctObjectives(objectives);objectives.clear();objectives.addAll(distinct);
        int explicit=r.lease().request().quizOptions().questionCount();
        if(explicit==0&&!intent.requestedQuestions().equals("AUTO"))explicit=Integer.parseInt(intent.requestedQuestions());
        final int target=explicit;
        long capacity=objectives.stream().filter(o->!intent.coveragePolicy().equals("FOCUS")||intent.focusChapterIds().contains(o.primaryChapterId())).count();
        // 最多一轮业务纠正；只允许新增有证据、认知目的不同的目标，不复制题位凑数。
        if(target>capacity) {
            for(var chapter:chapters) {
                if(intent.coveragePolicy().equals("FOCUS")&&!intent.focusChapterIds().contains(chapter.id()))continue;
                int group=0;
                var existingIndex=index(objectives.stream().filter(o->o.primaryChapterId().equals(chapter.id())).toList(),"objective_expand_index_"+chapter.id(),Math.min(2000,r.policy().indexBytes()));
                int room=r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(existingIndex))-1200;
                for(var facts:partition(r.evidence(chapter.itemIds()),room,ContentJson::encode)) {
                    var input=Map.of("chapter",Map.of("id",chapter.id(),"title",chapter.title()),"facts",facts,"existingObjectives",existingIndex,"requestedQuestions",target,"difficulty",r.lease().request().quizOptions().difficulty(),"allowedTypes",r.lease().request().quizOptions().questionTypes(),"correction","补充确有依据的不同认知目的；无法增加时明确保留原目标，禁止名称改写或虚构知识");r.bounded(input);
                    var extra=r.call("objective_expand_"+chapter.id()+"_"+group++,"organize","objectives",input,LearningBusinessSchemas.objectives(facts,new HashSet<>(r.lease().request().quizOptions().questionTypes()),chapterIds));
                    for(var o:extra.objectives())objectives.add(new Objective(String.format(Locale.ROOT,"o%05d",nextObjectiveId++),chapter.id(),o.title(),o.cognition(),o.questionTypes(),o.itemIds(),o.priority(),o.reason(),o.relatedChapterIds()));
                }
            }
            var corrected=distinctObjectives(objectives);objectives.clear();objectives.addAll(corrected);
            var examFacts=objectives.stream().flatMap(o->o.itemIds().stream()).collect(Collectors.toSet());uncovered.removeIf(u->u.kind().equals("FACT")&&examFacts.contains(u.id()));
        }
        for(int i=0;i<objectives.size();i++) {
            var objective=objectives.get(i);var related=objective.relatedChapterIds().stream().filter(id->!id.equals(objective.primaryChapterId())).distinct().toList();if(related.isEmpty())continue;
            var primary=r.evidence(objective.itemIds());var support=chapters.stream().filter(c->related.contains(c.id())).flatMap(c->c.itemIds().stream()).distinct().map(r.facts()::get).toList();
            // 已声明支持章节必须有真实证据；按有限证据包选择，再统一验证完整单题容量。
            var selected=new LinkedHashSet<>(objective.itemIds());int chunk=0;
            for(var group:partition(support,r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(primary))-TextWindow.count(ContentJson.encode(objective))-800,ContentJson::encode)) {
                var allowed=group.stream().map(Learning.Item::id).collect(Collectors.toSet());var schema=new com.example.ailab.ai.model.RecordSchema<>(ObjectiveEvidence.class,"仅选择实现此跨章考点确实必要的支持证据编号；无必要返回空数组。主要事实由程序保持。",v->com.example.ailab.ai.model.RecordSchema.require(new HashSet<>(v.itemIds()).size()==v.itemIds().size()&&allowed.containsAll(v.itemIds()),com.example.ailab.ai.model.RecordSchema.Reason.CONTENT_CONSTRAINT));
                selected.addAll(r.call("objective_evidence_"+objective.id()+"_"+chunk++,"organize","objective-evidence",Map.of("objective",objective,"primaryEvidence",primary,"supportCandidates",group),schema).itemIds());
            }
            var selectedChapters=chapters.stream().filter(c->!c.id().equals(objective.primaryChapterId())&&!Collections.disjoint(c.itemIds(),selected)).map(Chapter::id).toList();
            objectives.set(i,new Objective(objective.id(),objective.primaryChapterId(),objective.title(),objective.cognition(),objective.questionTypes(),List.copyOf(selected),objective.priority(),objective.reason(),selectedChapters));
        }
        if(objectives.isEmpty())throw new LabException("WORKFLOW_NEEDS_INPUT","正文没有可命题的有效目标");
        var quotas=new ArrayList<Quota>();
        for(var chapter:chapters) {
            var os=objectives.stream().filter(o->o.primaryChapterId().equals(chapter.id())).toList();
            boolean effective=!os.isEmpty();boolean focus=intent.coveragePolicy().equals("FOCUS")&&intent.focusChapterIds().contains(chapter.id());
            int lower=effective&&(intent.coveragePolicy().equals("EVERY_CHAPTER")||focus)?1:0;
            int upper=intent.coveragePolicy().equals("FOCUS")&&!focus?0:os.size();
            quotas.add(new Quota(chapter.id(),lower,upper,Math.max(1,os.stream().mapToInt(Objective::priority).sum()),0,effective?"按有效目标重要性分配，主章节计数一次":"无有效可命题目标"));
        }
        int requested=r.lease().request().quizOptions().questionCount();if(requested==0&&!intent.requestedQuestions().equals("AUTO"))requested=Integer.parseInt(intent.requestedQuestions());
        if(requested==0)requested=quotas.stream().mapToInt(q->Math.max(q.minimum(),Math.min(q.maximum(),(int)objectives.stream().filter(o->o.primaryChapterId().equals(q.chapterId())&&o.priority()>=2).count()))).sum();
        requested=Math.max(1,requested);if(requested>LearningPlanRules.MAX_QUESTIONS)throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","题位超过服务器蓝图上限");
        var allocated=LearningPlanRules.allocate(quotas,requested);var slots=new ArrayList<Slot>();var types=r.lease().request().quizOptions().questionTypes();var typeCounts=new HashMap<String,Integer>();
        for(var chapter:chapters) {
            int count=allocated.get(chapter.id());var selected=objectives.stream().filter(o->o.primaryChapterId().equals(chapter.id())).sorted(Comparator.comparingInt(Objective::priority).reversed().thenComparing(Objective::id)).limit(count).toList();
            for(var o:selected){String type=o.questionTypes().stream().min(Comparator.comparingInt((String t)->typeCounts.getOrDefault(t,0)).thenComparingInt(types::indexOf)).orElseThrow();typeCounts.merge(type,1,Integer::sum);slots.add(new Slot(String.format(Locale.ROOT,"q%05d",slots.size()+1),chapter.id(),o.id(),o.cognition(),type,o.itemIds(),o.relatedChapterIds()));}
            if(count==0)uncovered.add(new Uncovered("CHAPTER",chapter.id(),quotas.stream().filter(q->q.chapterId().equals(chapter.id())).findFirst().orElseThrow().maximum()==0?"无有效目标或明确重点范围之外":"用户代表性抽样配额为零"));
        }
        var selected=slots.stream().map(Slot::objectiveId).collect(Collectors.toSet());for(var o:objectives)if(!selected.contains(o.id()))uncovered.add(new Uncovered("OBJECTIVE",o.id(),"固定数量下优先选择其他重要目标"));
        var finalQuotas=quotas.stream().map(q->new Quota(q.chapterId(),q.minimum(),q.maximum(),q.weight(),allocated.get(q.chapterId()),q.reason())).toList();
        return new Blueprint(intent,r.lease().request().quizOptions().difficulty(),chapters,objectives,finalQuotas,slots,uncovered);
    }
    private List<Objective> distinctObjectives(List<Objective> candidates) {
        if(r.batchQuiz()) {
            var chapters=candidates.stream().collect(Collectors.groupingBy(Objective::primaryChapterId,LinkedHashMap::new,Collectors.toList()));
            var groups=new ArrayList<>(chapters.values());var result=new ArrayList<Objective>();
            // 章内归并依赖前序结果，章间独立；只并行不同章，编号保留初始分配值。
            for(int from=0;from<groups.size();from+=4)
                r.parallel(groups.subList(from,Math.min(from+4,groups.size())),this::distinctChapterObjectives).forEach(result::addAll);
            return result;
        }
        return distinctChapterObjectives(candidates);
    }
    private List<Objective> distinctChapterObjectives(List<Objective> candidates) {
        var result=new ArrayList<Objective>();
        for(var candidate:candidates) {
            Objective merge=result.stream().filter(o->o.primaryChapterId().equals(candidate.primaryChapterId())&&o.cognition().equals(candidate.cognition())
                    &&new HashSet<>(o.itemIds()).equals(new HashSet<>(candidate.itemIds()))).findFirst().orElse(null);
            var near=result.stream().filter(o->o.primaryChapterId().equals(candidate.primaryChapterId())&&o.cognition().equals(candidate.cognition()))
                    .filter(o->o.title().equals(candidate.title())||o.itemIds().stream().anyMatch(a->candidate.itemIds().stream().anyMatch(b->r.facts().get(a).category().equals(r.facts().get(b).category()))))
                    .limit(8).toList();
            if(merge==null)for(var old:near){var input=Map.of("existingObjective",old,"candidateObjective",candidate,"evidence",r.evidence(java.util.stream.Stream.concat(old.itemIds().stream(),candidate.itemIds().stream()).distinct().toList()),"rule","只有知识与条件相同且认知目的相同才SAME，改写考点名称不形成新容量");r.bounded(input);
                if(r.call("objective_relation_"+old.id()+"_"+candidate.id(),"organize","normalize",input,LearningBusinessSchemas.relation()).decision().equals("SAME")){merge=old;break;}}
            if(merge==null)result.add(candidate);else{var evidence=new LinkedHashSet<>(merge.itemIds());evidence.addAll(candidate.itemIds());var types=new LinkedHashSet<>(merge.questionTypes());types.addAll(candidate.questionTypes());var related=new LinkedHashSet<>(merge.relatedChapterIds());related.addAll(candidate.relatedChapterIds());
                result.set(result.indexOf(merge),new Objective(merge.id(),merge.primaryChapterId(),merge.title(),merge.cognition(),List.copyOf(types),List.copyOf(evidence),Math.max(merge.priority(),candidate.priority()),"相同知识和认知目的合并，保留全部证据",List.copyOf(related)));}
        }return result;
    }
    List<Chapter> chapters() {
        var doc=r.reading().documents().get(0);var origin=r.origin();var fragments=r.reading().fragments();
        var descriptors=new ArrayList<Chapter>();
        if(origin!=null&&origin.generatedVersion()==doc.documentVersion()&&origin.mappingValid()&&origin.bindingStatus().equals("BOUND")&&!origin.sections().isEmpty()
                &&!origin.parsedBindings().isEmpty()&&origin.parsedBindings().stream().allMatch(b->b.processingRevision()==doc.processingRevision())) {
            var grouped=origin.sections().stream().collect(Collectors.groupingBy(Compilation.SectionMapping::chapterId,LinkedHashMap::new,Collectors.toList()));
            var identities=new LinkedHashMap<String,Compilation.ChapterIdentity>();origin.sections().forEach(m->m.chapterPath().forEach(c->{var old=identities.putIfAbsent(c.id(),c);LearningPlanRules.check(old==null||old.equals(c));}));
            for(var identity:identities.values())descriptors.add(new Chapter(identity.id(),identity.parentId(),descriptors.size(),identity.title(),"COMPILATION_MAPPING",List.of(),List.of()));
            for(var entry:grouped.entrySet()) {
                var ranges=new ArrayList<Range>();for(var mapping:entry.getValue())for(var f:fragments){int a=Math.max(mapping.startOffset(),f.source().startOffset()),b=Math.min(mapping.endOffset(),f.source().endOffset());if(a<b)ranges.add(new Range(f.source().id(),f.sectionId(),a,b));}
                var parsed=doc.sections().stream().filter(s->s.startOffset()<=entry.getValue().get(0).startOffset()&&s.endOffset()>entry.getValue().get(0).startOffset()).reduce((a,b)->b);
                String title=parsed.map(s->doc.sections().stream().filter(parent->parent.sectionId().equals(s.parentSectionId())).findFirst().map(SectionSnapshot::headingPath).orElse(s.headingPath())).orElse(entry.getKey());
                var identity=identities.get(entry.getKey());var existing=descriptors.stream().filter(c->c.id().equals(entry.getKey())).findFirst();
                var chapter=new Chapter(entry.getKey(),identity==null?"":identity.parentId(),existing.map(Chapter::order).orElse(descriptors.size()),identity==null?title:identity.title(),"COMPILATION_MAPPING",ranges,List.of());
                if(existing.isPresent())descriptors.set(descriptors.indexOf(existing.get()),chapter);else descriptors.add(chapter);
            }
        } else if(inferDirectory||doc.sections().size()==1||doc.sections().stream().map(s->s.headingPath()).distinct().count()!=doc.sections().size()) {
            var units=r.call("learning_units","organize","learning-units",Map.of("topicIndex",index(cards,"learning_units_index"),"sourceTitle",doc.title()),LearningBusinessSchemas.learningDirectory());
            var assignedCards=new LinkedHashMap<String,List<ContentReading.TopicCard>>();units.chapters().forEach(c->assignedCards.put(c.id(),new ArrayList<>()));int group=0;
            for(var portion:partition(cards,r.policy().unitInputBytes()-TextWindow.count(ContentJson.encode(units))-800,ContentJson::encode)) {
                var assignment=r.call("learning_unit_assign_"+group++,"organize","assign",Map.of("directory",units,"topicCards",portion),LearningBusinessSchemas.assignments(portion,units));
                for(var a:assignment.assignments())assignedCards.get(a.chapterId()).add(portion.stream().filter(c->c.id().equals(a.cardId())).findFirst().orElseThrow());
            }
            for(var unit:units.chapters()) {
                var cards=assignedCards.get(unit.id());var ids=cards.stream().flatMap(c->c.topic().itemIds().stream()).toList();
                var ranges=cards.stream().map(c->fragments.stream().filter(f->f.source().id().equals(c.fragmentId())).findFirst().orElseThrow()).distinct().map(f->new Range(f.source().id(),f.sectionId(),f.source().startOffset(),f.source().endOffset())).toList();
                descriptors.add(new Chapter(unit.id(),unit.parentId(),descriptors.size(),unit.title(),"INFERRED",ranges,ids));
            }
        } else for(var s:doc.sections()) {
            var ranges=fragments.stream().filter(f->f.sectionId().equals(s.sectionId())).map(f->new Range(f.source().id(),f.sectionId(),f.source().startOffset(),f.source().endOffset())).toList();
            descriptors.add(new Chapter(s.sectionId(),s.parentSectionId()==null?"":s.parentSectionId(),descriptors.size(),s.headingPath(),"PARSED",ranges,List.of()));
        }
        var assigned=new LinkedHashMap<String,List<String>>();descriptors.forEach(c->assigned.put(c.id(),new ArrayList<>()));var remainder=new ArrayList<String>();
        for(var fact:r.facts().values()) {
            var f=fragments.stream().filter(x->x.source().id().equals(fact.sourceId())).findFirst().orElseThrow();
            int offset=f.source().startOffset()+factOffset(fact);
            var match=descriptors.stream().filter(c->c.origin().equals("INFERRED")?c.itemIds().contains(fact.id()):c.ranges().stream().anyMatch(x->x.fragmentId().equals(fact.sourceId())&&x.startOffset()<=offset&&offset<x.endOffset())).findFirst();
            if(match.isPresent())assigned.get(match.get().id()).add(fact.id());else remainder.add(fact.id());
        }
        if(!remainder.isEmpty()){var ranges=fragments.stream().filter(f->remainder.stream().anyMatch(id->r.facts().get(id).sourceId().equals(f.source().id()))).map(f->new Range(f.source().id(),f.sectionId(),f.source().startOffset(),f.source().endOffset())).toList();descriptors.add(new Chapter("body_context","",descriptors.size(),"正文导读与附注","PARSED",ranges,List.of()));assigned.put("body_context",remainder);}
        var kept=LearningHierarchy.ancestors(assigned.entrySet().stream().filter(e->!e.getValue().isEmpty()).map(Map.Entry::getKey).collect(Collectors.toSet()),descriptors.stream().collect(Collectors.toMap(Chapter::id,Chapter::parentId)));
        return descriptors.stream().filter(c->kept.contains(c.id())).map(c->new Chapter(c.id(),kept.contains(c.parentId())?c.parentId():"",c.order(),c.title(),c.origin(),c.ranges(),assigned.get(c.id()))).toList();
    }
    private int factOffset(Learning.Item fact) {
        // 证据坐标在抽取阶段已校验；读取固定正文，禁止回读祖先正文。
        var source=r.reading().fragments().stream().filter(f->f.source().id().equals(fact.sourceId())).findFirst().orElseThrow().source();
        // Topic evidence has an exact contiguous quote; runtime exposes its frozen position.
        return r.quoteOffset(fact)-source.startOffset();
    }
    private void clarify() {
        for(int round=0;round<=2;round++) {
            int room=(r.policy().unitInputBytes()-TextWindow.count(r.lease().request().instructions())-500)/2;
            var input=Map.of("taskType",r.lease().request().taskType(),"remarks",r.lease().request().instructions(),"topicIndex",index(cards,"advice_topics_"+round,room),"sourceStructure",index(r.reading().documents().stream().flatMap(d->d.sections().stream()).toList(),"advice_structure_"+round,room));
            r.bounded(input);var advice=r.call("planning_advice_"+round,"organize","planning-advice",input,LearningBusinessSchemas.planningAdvice());inferDirectory=advice.directoryPolicy().equals("INFER");
            if(advice.decision().equals("READY"))return;
            if(round==2)throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","规划澄清达到两轮上限仍无法形成可信职责");
            var selected=new LinkedHashSet<ContentReading.TopicCard>();
            for(var request:advice.requests()) {
                var matches=cards.stream().filter(c->ContentJson.encode(c.topic()).toLowerCase(Locale.ROOT).contains(request.query().toLowerCase(Locale.ROOT))).limit(4).toList();
                if(matches.isEmpty())throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","规划澄清未命中有来源的主题");selected.addAll(matches);
            }
            if(selected.size()>4)throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","一轮规划澄清超过4张主题卡");
            int i=0;for(var card:selected) {
                var supplementInput=Map.of("card",card,"questions",advice.requests(),"ownedText",r.fragmentText(card.fragmentId()));r.bounded(supplementInput);
                var supplements=r.call("planning_supplement_"+round+"_"+i++,"organize","clarify-read",supplementInput,LearningBusinessSchemas.supplements(Set.of(card.id())));
                var value=supplements.cards().get(0);var topic=card.topic();var enriched=new ContentReading.TopicDraft(topic.id(),topic.title(),value.summary(),java.util.stream.Stream.concat(topic.keywords().stream(),value.keywords().stream()).distinct().toList(),topic.entities(),topic.contentRole(),java.util.stream.Stream.concat(topic.conditions().stream(),value.conditions().stream()).distinct().toList(),topic.relations(),topic.dependencyItemIds(),topic.itemIds());
                var next=new ArrayList<>(cards);next.set(next.indexOf(card),new ContentReading.TopicCard(card.id(),card.fragmentId(),card.sectionId(),card.headingPath(),enriched,card.factCount(),card.sourceBytes()));cards=List.copyOf(next);
            }
        }
    }
    private List<?> index(List<?> input) { return index(input,"global_index"); }
    private List<?> index(List<?> input,String prefix) { return index(input,prefix,r.compact()?Math.max(r.policy().indexBytes(),r.policy().unitInputBytes()-16000):r.policy().indexBytes()); }
    private List<?> index(List<?> input,String prefix,int maximum) {
        List<?> level=input;
        if(r.compact()&&!input.isEmpty()&&input.get(0) instanceof ContentReading.TopicCard)level=CompactCompilationPlanner.themeView(input.stream().map(ContentReading.TopicCard.class::cast).toList());
        int depth=0;
        while(TextWindow.count(ContentJson.encode(level))>maximum) {
            var groups=partition(level,r.policy().indexBytes()-300,ContentJson::encode);int current=depth;
            var batches=r.parallel(java.util.stream.IntStream.range(0,groups.size()).boxed().toList(),i->indexSummaries(r.compact()?"shared_index_"+ContentJson.hash(groups.get(i)).substring(0,32):prefix+"_"+current+"_"+i,groups.get(i)));
            var next=batches.stream().flatMap(List::stream).toList();
            // 容量拆分可能保留条目数；总字节数缩小仍是有效收敛。
            if((next.size()>=level.size()&&TextWindow.count(ContentJson.encode(next))>=TextWindow.count(ContentJson.encode(level)))||++depth>16)throw new LabException("WORKFLOW_INDEX_CAPACITY_EXCEEDED","全范围轻量索引无法收敛");level=next;
        }return level;
    }
    private List<ContentWorkflow.Summary> indexSummaries(String id,List<?> children) {
        try {
            var schema=r.compact()?new com.example.ailab.ai.model.RecordSchema<>(ContentWorkflow.Summary.class,"将children归纳为后续规划的导航。保留主题、条件、例外及冲突；明细与身份由程序保存。尽量精简，但不要为短字节目标删掉必要条件。",v->com.example.ailab.ai.model.RecordSchema.require(!v.title().isBlank()&&!v.summary().isBlank(),com.example.ailab.ai.model.RecordSchema.Reason.CONTENT_CONSTRAINT)):ContentSchemas.summary(ContentLimits.current());
            var factIds=new LinkedHashSet<String>();
            for(Object child:children)if(child instanceof ContentReading.TopicCard card)factIds.addAll(card.topic().itemIds());
            if(!r.compact()&&!factIds.isEmpty())schema=schema.withSummaryFacts(r.evidence(factIds).stream()
                    .map(f->new com.example.ailab.ai.model.RecordSchema.SummaryFact(f.id(),f.content(),f.quote())).toList());
            return List.of(r.call(id,"organize","index",Map.of("children",children),schema));
        } catch(LabException failure) {
            if(children.size()<2||!Set.of("MODEL_TRUNCATED","MODEL_CONTEXT_INSUFFICIENT","WORKFLOW_TEXT_UNREPRESENTABLE").contains(failure.code()))throw failure;
            LOG.info("event=content.index_split node={} children={} code={}",id,children.size(),failure.code());
            int mid=children.size()/2;
            var summaries=new ArrayList<ContentWorkflow.Summary>(indexSummaries(id+"_a",children.subList(0,mid)));
            summaries.addAll(indexSummaries(id+"_b",children.subList(mid,children.size())));
            return List.copyOf(summaries);
        }
    }
    private static boolean related(ContentReading.TopicCard a,ContentReading.TopicCard b,Learning.Item x,Learning.Item y){return x.category().equals(y.category())||similarity(a,b)>0;}
    private static int similarity(ContentReading.TopicCard a,ContentReading.TopicCard b){var keys=new HashSet<>(a.topic().keywords());keys.addAll(a.topic().entities());var other=new HashSet<>(b.topic().keywords());other.addAll(b.topic().entities());keys.retainAll(other);return keys.size();}
    static <T>List<List<T>> partition(List<T> input,int max,Function<T,String> encode) {
        var out=new ArrayList<List<T>>();var current=new ArrayList<T>();int bytes=2;
        for(var value:input){int size=TextWindow.count(encode.apply(value))+1;if(size+2>max)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","最小必要资料超出固定工作包容量");if(bytes+size>max&&!current.isEmpty()){out.add(List.copyOf(current));current.clear();bytes=2;}current.add(value);bytes+=size;}if(!current.isEmpty())out.add(List.copyOf(current));return out;
    }
}
