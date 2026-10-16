package com.example.ailab.contract.dto;

import com.example.ailab.contract.error.LabException;
import java.util.*;
import java.util.stream.Collectors;
import static com.example.ailab.contract.dto.LearningContent.*;

/** 业务覆盖、整数配额与执行职责守恒；模型及数据仓库使用同一规则。 */
public final class LearningPlanRules {
    private LearningPlanRules() { }
    public static final int MAX_SPLIT_DEPTH=8;
    public static List<ReviewPair> reviewPairs(Blueprint quiz,int version) {
        if(version!=3)return reviewPairs(quiz);
        // 已接受的 v3 计划保持原候选集合与检查点身份。
        var objectives=quiz.objectives().stream().collect(Collectors.toMap(Objective::id,o->o));var pairs=new LinkedHashMap<String,ReviewPair>();
        for(var x:quiz.slots())for(var y:quiz.slots().stream().filter(y->!x.id().equals(y.id())&&x.cognition().equals(y.cognition()))
                .map(y->Map.entry(y,reviewSimilarity(x,y,objectives))).filter(e->e.getValue()>0)
                .sorted(Map.Entry.<Slot,Integer>comparingByValue().reversed().thenComparing(e->e.getKey().id())).limit(8).map(Map.Entry::getKey).toList()) {
            var pair=x.id().compareTo(y.id())<0?new ReviewPair(x.id(),y.id()):new ReviewPair(y.id(),x.id());pairs.putIfAbsent(pair.key(),pair);
        }
        return List.copyOf(pairs.values());
    }
    /** 题对身份和排序只依赖冻结蓝图，预算和两轮执行使用完全相同的候选。 */
    public static List<ReviewPair> reviewPairs(Blueprint quiz) {
        var objectives=quiz.objectives().stream().collect(Collectors.toMap(Objective::id,o->o));
        var buckets=new HashMap<String,List<Slot>>();var keys=new HashMap<String,Set<String>>();
        for(var slot:quiz.slots()) {
            var values=new LinkedHashSet<String>();String prefix=slot.cognition()+"|";
            values.add(prefix+"chapter:"+slot.primaryChapterId());
            slot.itemIds().forEach(id->values.add(prefix+"fact:"+id));
            String title=objectives.get(slot.objectiveId()).title();values.add(prefix+"title:"+title);
            for(int i=0;i+1<title.length();i++)values.add(prefix+"text:"+title.substring(i,i+2));
            keys.put(slot.id(),values);for(String key:values)buckets.computeIfAbsent(key,k->new ArrayList<>()).add(slot);
        }
        buckets.values().forEach(bucket->bucket.sort(Comparator.comparing(Slot::id)));
        var pairs=new LinkedHashMap<String,ReviewPair>();
        for(var x:quiz.slots()) {
            var nearby=new LinkedHashMap<String,Slot>();
            for(String key:keys.get(x.id())) {
                var bucket=buckets.get(key);
                // 大量相同章节或常用词不再触发全题库扫描；保留首批与稳定邻域候选。
                int at=Collections.binarySearch(bucket,x,Comparator.comparing(Slot::id));
                for(int i=0;i<Math.min(16,bucket.size());i++)nearby.put(bucket.get(i).id(),bucket.get(i));
                for(int i=Math.max(0,at-16);i<Math.min(bucket.size(),at+17);i++)nearby.put(bucket.get(i).id(),bucket.get(i));
            }
            var candidates=nearby.values().stream().filter(y->!y.id().equals(x.id()))
                    .map(y->Map.entry(y,reviewSimilarity(x,y,objectives))).filter(e->e.getValue()>0)
                    .sorted(Map.Entry.<Slot,Integer>comparingByValue().reversed().thenComparing(e->e.getKey().id())).limit(8).toList();
            for(var candidate:candidates) {
                var y=candidate.getKey();var pair=x.id().compareTo(y.id())<0?new ReviewPair(x.id(),y.id()):new ReviewPair(y.id(),x.id());
                pairs.putIfAbsent(pair.key(),pair);
            }
        }
        return List.copyOf(pairs.values());
    }
    private static int reviewSimilarity(Slot x,Slot y,Map<String,Objective> objectives) {
        int score=x.primaryChapterId().equals(y.primaryChapterId())?1:0;
        if(!Collections.disjoint(x.itemIds(),y.itemIds()))score+=100;
        String a=objectives.get(x.objectiveId()).title(),b=objectives.get(y.objectiveId()).title();
        if(a.equals(b))score+=50;
        var tokens=new HashSet<String>();for(int i=0;i+1<a.length();i++)tokens.add(a.substring(i,i+2));
        for(int i=0;i+1<b.length();i++)if(tokens.contains(b.substring(i,i+2)))score++;
        return score;
    }
    public static Map<String,Integer> allocate(List<Quota> quotas,int requested) {
        long minimum=quotas.stream().mapToLong(Quota::minimum).sum(),maximum=quotas.stream().mapToLong(Quota::maximum).sum();
        if(requested<minimum||requested>maximum)throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","题数须介于章节最低配额与不同有效题位容量之间");
        var result=new LinkedHashMap<String,Integer>();for(var q:quotas){check(q.weight()>0&&q.minimum()>=0&&q.maximum()>=q.minimum()&&!result.containsKey(q.chapterId()));result.put(q.chapterId(),q.minimum());}
        int remaining=(int)(requested-minimum);
        while(remaining>0) {
            var active=quotas.stream().filter(q->result.get(q.chapterId())<q.maximum()).toList();long weight=active.stream().mapToLong(Quota::weight).sum();check(weight>0);
            int before=remaining;var fractions=new ArrayList<Map.Entry<Quota,Long>>();
            for(var q:active) {long numerator=(long)before*q.weight();int add=Math.min(q.maximum()-result.get(q.chapterId()),(int)(numerator/weight));
                result.put(q.chapterId(),result.get(q.chapterId())+add);remaining-=add;fractions.add(Map.entry(q,numerator%weight));}
            fractions.sort(Map.Entry.<Quota,Long>comparingByValue().reversed().thenComparingInt(e->quotas.indexOf(e.getKey())).thenComparing(e->e.getKey().chapterId()));
            for(var fraction:fractions) {if(remaining==0)break;var q=fraction.getKey();if(result.get(q.chapterId())<q.maximum()){result.put(q.chapterId(),result.get(q.chapterId())+1);remaining--;}}
            check(remaining<before);
        }
        return Collections.unmodifiableMap(result);
    }
    public static void validate(Plan p,TaskRequest request,Set<String> facts,ContentWorkflow.Policy policy) {
        check((p.version()==3||p.version()==VERSION)&&p.taskType().equals(request.taskType())&&(p.compilation()!=null ^ p.quiz()!=null));
        var logical=new LinkedHashMap<String,Set<String>>();
        if(p.compilation()!=null) {
            check(request.taskType().equals("KNOWLEDGE_COMPILATION"));var c=p.compilation();var chapters=c.directory().chapters();
            check(!chapters.isEmpty());dag(chapters.stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::resultDependencies)));
            var chapterIds=chapters.stream().map(DirectoryChapter::id).collect(Collectors.toSet());check(chapterIds.size()==chapters.size());
            for(var chapter:chapters)check(chapter.parentId().isEmpty()||chapterIds.contains(chapter.parentId()));
            dag(chapters.stream().collect(Collectors.toMap(DirectoryChapter::id,chapter->chapter.parentId().isEmpty()?List.of():List.of(chapter.parentId()))));
            var knowledge=new LinkedHashMap<String,Knowledge>();var covered=new HashSet<String>();
            for(var k:c.knowledge()) {check(!k.sourceItemIds().isEmpty()&&knowledge.putIfAbsent(k.id(),k)==null&&Set.of("SAME","COMPLEMENT","CONFLICT","SINGLE").contains(k.relation()));
                check(facts.containsAll(k.sourceItemIds()));for(String id:k.sourceItemIds())check(covered.add(id));if(k.relation().equals("CONFLICT"))check(!k.conditions().isEmpty());}
            check(covered.equals(facts));var owned=new HashSet<String>();var usedChapters=new HashSet<String>();
            for(var s:c.sections()) {
                check(chapterIds.contains(s.chapterId())&&!s.ownedKnowledgeIds().isEmpty()&&knowledge.keySet().containsAll(s.ownedKnowledgeIds())&&knowledge.keySet().containsAll(s.contextKnowledgeIds())
                        &&Collections.disjoint(s.ownedKnowledgeIds(),s.contextKnowledgeIds())&&s.outputBytes()>0&&s.outputBytes()<=ContentLimits.current().section());
                for(String id:s.ownedKnowledgeIds())check(owned.add(id));usedChapters.add(s.chapterId());
                var itemIds=s.ownedKnowledgeIds().stream().flatMap(id->knowledge.get(id).sourceItemIds().stream()).collect(Collectors.toSet());check(logical.putIfAbsent(s.id(),itemIds)==null);
            }
            var parents=chapters.stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId));
            check(owned.equals(knowledge.keySet())&&LearningHierarchy.ancestors(usedChapters,parents).equals(chapterIds));
            for(var id:chapterIds)check(LearningHierarchy.depth(id,parents)<=4);
            check(p.counts().equals(new ContentWorkflow.Counts(0,chapters.size(),c.sections().size(),0,0,0)));
            if(request.compilationOptions().maximumChapters()>0&&chapters.size()>request.compilationOptions().maximumChapters())throw requirements();
        } else {
            check(request.taskType().equals("QUIZ_GENERATION")&&request.documentIds().size()==1);var b=p.quiz();check(!b.slots().isEmpty());
            var chapters=b.chapters().stream().collect(Collectors.toMap(Chapter::id,Chapter::itemIds));var chapterFacts=new HashSet<String>();
            for(var chapter:b.chapters()){check(Set.of("PARSED","COMPILATION_MAPPING","INFERRED").contains(chapter.origin()));for(String id:chapter.itemIds())check(chapterFacts.add(id));}
            var parents=b.chapters().stream().collect(Collectors.toMap(Chapter::id,Chapter::parentId));
            for(var chapter:b.chapters())check(chapter.parentId().isEmpty()||parents.containsKey(chapter.parentId()));
            dag(b.chapters().stream().collect(Collectors.toMap(Chapter::id,c->c.parentId().isEmpty()?List.of():List.of(c.parentId()))));
            check(chapterFacts.equals(facts));var objectives=b.objectives().stream().collect(Collectors.toMap(Objective::id,o->o));var purposes=new HashSet<String>();
            for(var o:b.objectives()) {var evidenceChapters=new HashSet<>(o.relatedChapterIds());evidenceChapters.add(o.primaryChapterId());var allowed=evidenceChapters.stream().filter(chapters::containsKey).flatMap(id->chapters.get(id).stream()).collect(Collectors.toSet());
                check(chapters.containsKey(o.primaryChapterId())&&!Collections.disjoint(chapters.get(o.primaryChapterId()),o.itemIds())&&allowed.containsAll(o.itemIds())&&chapters.keySet().containsAll(o.relatedChapterIds())&&!o.itemIds().isEmpty()
                        &&o.priority()>=1&&o.priority()<=3&&request.quizOptions().questionTypes().containsAll(o.questionTypes())&&!o.questionTypes().isEmpty());
                check(purposes.add(o.primaryChapterId()+"|"+new TreeSet<>(o.itemIds())+"|"+o.cognition()));}
            var counts=new HashMap<String,Integer>();var selected=new HashSet<String>();
            for(var slot:b.slots()){var o=objectives.get(slot.objectiveId());check(o!=null&&selected.add(o.id())&&slot.primaryChapterId().equals(o.primaryChapterId())
                    &&slot.cognition().equals(o.cognition())&&o.questionTypes().contains(slot.type())&&slot.itemIds().equals(o.itemIds())&&chapters.keySet().containsAll(slot.relatedChapterIds()));
                counts.merge(slot.primaryChapterId(),1,Integer::sum);check(logical.putIfAbsent(slot.id(),new HashSet<>(slot.itemIds()))==null);}
            check(b.quotas().stream().map(Quota::chapterId).collect(Collectors.toSet()).equals(chapters.keySet())&&b.quotas().size()==chapters.size());
            for(var q:b.quotas()) {
                long capacity=b.objectives().stream().filter(o->o.primaryChapterId().equals(q.chapterId())).count();check(q.maximum()<=capacity);
                if(b.intent().coveragePolicy().equals("EVERY_CHAPTER")&&capacity>0)check(q.minimum()>=1);
                if(b.intent().coveragePolicy().equals("FOCUS"))check(b.intent().focusChapterIds().contains(q.chapterId())?capacity==0||q.minimum()>=1:q.maximum()==0);
            }
            var allocated=allocate(b.quotas(),b.slots().size());
            for(var q:b.quotas())check(q.allocated()==counts.getOrDefault(q.chapterId(),0)&&q.allocated()==allocated.get(q.chapterId()));
            check(p.counts().equals(new ContentWorkflow.Counts(b.slots().size(),b.chapters().size(),0,0,0,0)));
            int requested=request.quizOptions().questionCount()>0?request.quizOptions().questionCount():b.intent().requestedQuestions().equals("AUTO")?0:Integer.parseInt(b.intent().requestedQuestions());
            if(requested>0&&requested!=b.slots().size())throw requirements();
        }
        execution(p.execution(),logical);check(p.requiredNodes().equals(logical.keySet().stream().map(id->"accepted_"+id).toList()));
        for(var pack:p.execution().packages())check(pack.inputBytes()<=policy.unitInputBytes()&&pack.outputBytes()<=policy.packageOutputBytes());
        for(var part:p.execution().parts()) {
            if(p.compilation()!=null){var section=p.compilation().sections().stream().filter(s->s.id().equals(part.logicalId())).findFirst().orElseThrow();
                check(part.kind().equals("SECTION")&&part.chapterId().equals(section.chapterId())&&part.purpose().equals(section.purpose())&&section.ownedKnowledgeIds().containsAll(part.ownedKnowledgeIds())&&part.contextKnowledgeIds().equals(section.contextKnowledgeIds()));
                var referenced=p.compilation().knowledge().stream().filter(k->part.ownedKnowledgeIds().contains(k.id())).flatMap(k->k.sourceItemIds().stream()).collect(Collectors.toSet());check(referenced.containsAll(part.itemIds()));
            }else{var slot=p.quiz().slots().stream().filter(s->s.id().equals(part.logicalId())).findFirst().orElseThrow();check(part.kind().equals("QUESTION")&&part.chapterId().equals(slot.primaryChapterId())&&part.itemIds().equals(slot.itemIds())&&part.ownedKnowledgeIds().isEmpty()&&part.contextKnowledgeIds().isEmpty());}
        }
        check(p.estimatedTurns()>=p.execution().parts().size()*2&&p.estimatedAttempts()>=p.estimatedTurns()&&p.estimatedOutputBytes()>0);
        check(p.estimatedOutputBytes()<=policy.resultBytes()
                &&p.execution().maximumPackages()>=p.execution().packages().size()
                &&p.execution().maximumSplitDepth()>0&&p.execution().maximumSplitDepth()<=MAX_SPLIT_DEPTH);
    }
    public static void execution(ExecutionPlan e,Map<String,Set<String>> logical) {
        var parts=e.parts().stream().collect(Collectors.toMap(Part::id,p->p));var assigned=new HashSet<String>();
        var coverage=new HashMap<String,Set<String>>();
        for(var part:e.parts()) {check(logical.containsKey(part.logicalId())&&!part.itemIds().isEmpty());var seen=coverage.computeIfAbsent(part.logicalId(),id->new HashSet<>());for(String id:part.itemIds())check(seen.add(id));}
        check(coverage.equals(logical));
        for(var pack:e.packages()){check(pack.parentId().isEmpty()&&pack.depth()==0&&!pack.partIds().isEmpty()&&pack.inputBytes()>0&&pack.outputBytes()>0);
            for(String id:pack.partIds())check(parts.containsKey(id)&&assigned.add(id));}
        check(assigned.equals(parts.keySet()));dag(e.packages().stream().collect(Collectors.toMap(WorkPackage::id,WorkPackage::dependencies)));
    }
    public static void split(List<Part> before,List<Part> after,WorkPackage parent,List<WorkPackage> children) {
        check(children.size()>=2&&!after.isEmpty());var old=new HashMap<String,Set<String>>();var fresh=new HashMap<String,Set<String>>();
        for(var p:before)old.computeIfAbsent(p.logicalId(),k->new HashSet<>()).addAll(p.itemIds());
        var ids=new HashSet<String>();for(var p:after){check(ids.add(p.id())&&!p.itemIds().isEmpty());var seen=fresh.computeIfAbsent(p.logicalId(),k->new HashSet<>());for(String id:p.itemIds())check(seen.add(id));
            var original=before.stream().filter(v->p.id().equals(v.id())||p.id().startsWith(v.id()+"_")).findFirst().orElseThrow(()->new LabException("WORKFLOW_STATE_CONFLICT","子包片段缺少原始职责"));
            check(p.logicalId().equals(original.logicalId())&&p.kind().equals(original.kind())&&p.chapterId().equals(original.chapterId())&&p.purpose().equals(original.purpose())
                    &&original.itemIds().containsAll(p.itemIds())&&original.ownedKnowledgeIds().containsAll(p.ownedKnowledgeIds())&&p.contextKnowledgeIds().equals(original.contextKnowledgeIds())&&p.outputBytes()>0);
            if(p.id().equals(original.id()))check(p.equals(original));if(p.kind().equals("QUESTION"))check(p.equals(original));
        }
        check(old.equals(fresh));var assigned=new HashSet<String>();var childIds=new HashSet<String>();
        for(var original:before){var owned=after.stream().filter(p->p.id().equals(original.id())||p.id().startsWith(original.id()+"_")).flatMap(p->p.ownedKnowledgeIds().stream()).collect(Collectors.toSet());check(owned.equals(new HashSet<>(original.ownedKnowledgeIds())));}
        for(var child:children){check(childIds.add(child.id())&&child.id().startsWith(parent.id()+"_")&&child.parentId().equals(parent.id())&&child.depth()==parent.depth()+1&&child.dependencies().equals(parent.dependencies())&&!child.partIds().isEmpty()&&child.inputBytes()>0&&child.outputBytes()>0);for(String id:child.partIds())check(ids.contains(id)&&assigned.add(id));}
        check(assigned.equals(ids));
    }
    public static void dag(Map<String,List<String>> dependencies) {
        var done=new HashSet<String>();while(done.size()<dependencies.size()){int before=done.size();dependencies.forEach((id,deps)->{check(dependencies.keySet().containsAll(deps)&&!deps.contains(id));if(done.containsAll(deps))done.add(id);});check(done.size()>before);}
    }
    public static void check(boolean value){if(!value)throw new LabException("WORKFLOW_STATE_CONFLICT","学习业务计划、执行包职责或覆盖证明不一致");}
    private static LabException requirements(){return new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","已接受目录或蓝图无法满足明确数量要求");}
}
