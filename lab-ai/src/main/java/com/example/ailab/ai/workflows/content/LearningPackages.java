package com.example.ailab.ai.workflows.content;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.LearningContent.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;
import java.util.stream.Collectors;

/** 工作包只调整调用边界，逻辑小节、题位和主要证据职责守恒。 */
final class LearningPackages {
    private LearningPackages() { }
    static ExecutionPlan build(LearningRuntime r,CompilationPlan compilation,Blueprint quiz) {
        return build(r,compilation,quiz,true,Integer.MAX_VALUE);
    }
    private static ExecutionPlan build(LearningRuntime r,CompilationPlan compilation,Blueprint quiz,boolean allowRelated,int maximumParts) {
        return build(r,compilation,quiz,allowRelated,maximumParts,Math.min(ContentLimits.current().section(),r.policy().packageOutputBytes()-256));
    }
    private static ExecutionPlan build(LearningRuntime r,CompilationPlan compilation,Blueprint quiz,boolean allowRelated,int maximumParts,int maximumSectionOutput) {
        var parts=new ArrayList<Part>();
        if(compilation!=null) {
            var knowledge=compilation.knowledge().stream().collect(Collectors.toMap(Knowledge::id,k->k));
            for(var s:compilation.sections()) {
                var ids=s.ownedKnowledgeIds().stream().flatMap(id->knowledge.get(id).sourceItemIds().stream()).toList();
                var context=s.contextKnowledgeIds().stream().map(knowledge::get).toList();
                int overhead=TextWindow.count(ContentJson.encode(Map.of("section",s,"context",context)))+1200;
                var groups=LearningPlanner.partition(r.evidence(ids),r.policy().unitInputBytes()-overhead,ContentJson::encode);
                int targetGroups=(s.outputBytes()+maximumSectionOutput-1)/maximumSectionOutput;
                if(targetGroups>groups.size()){
                    var finer=new ArrayList<List<Learning.Item>>();int size=Math.max(1,(ids.size()+targetGroups-1)/targetGroups);
                    for(var group:groups)for(int offset=0;offset<group.size();offset+=size)finer.add(List.copyOf(group.subList(offset,Math.min(group.size(),offset+size))));groups=finer;
                }
                // 验证带职责、规范知识及背景的完整输入；事实数组本身的容量不能代替完整包。
                boolean refined;
                do {
                    refined=false;var fitting=new ArrayList<List<Learning.Item>>();
                    for(var group:groups){var owned=group.stream().map(Learning.Item::id).toList();var ownedKnowledge=s.ownedKnowledgeIds().stream().filter(id->!Collections.disjoint(knowledge.get(id).sourceItemIds(),owned)).toList();
                        var probe=new Part("p00000",s.id(),s.chapterId(),"SECTION",s.purpose(),ownedKnowledge,s.contextKnowledgeIds(),owned,Math.max(600,(s.outputBytes()+groups.size()-1)/groups.size()));
                        if(bytes(r,List.of(probe),compilation,null)>r.policy().unitInputBytes()){
                            if(group.size()==1)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","单条证据连同固定职责及背景超出容量");int middle=group.size()/2;fitting.add(List.copyOf(group.subList(0,middle)));fitting.add(List.copyOf(group.subList(middle,group.size())));refined=true;
                        }else fitting.add(group);
                    }groups=fitting;
                }while(refined);
                for(var group:groups){var owned=group.stream().map(Learning.Item::id).toList();var ownedKnowledge=s.ownedKnowledgeIds().stream().filter(id->!Collections.disjoint(knowledge.get(id).sourceItemIds(),owned)).toList();
                    parts.add(new Part(String.format(Locale.ROOT,"p%05d",parts.size()+1),s.id(),s.chapterId(),"SECTION",s.purpose(),ownedKnowledge,s.contextKnowledgeIds(),owned,Math.max(600,(s.outputBytes()+groups.size()-1)/groups.size())));}
            }
        } else for(var slot:quiz.slots()){var objective=quiz.objectives().stream().filter(o->o.id().equals(slot.objectiveId())).findFirst().orElseThrow();parts.add(new Part(String.format(Locale.ROOT,"p%05d",parts.size()+1),slot.id(),slot.primaryChapterId(),"QUESTION",objective.title()+"；认知目的："+slot.cognition(),List.of(),List.of(),slot.itemIds(),2200));}
        var groups=new ArrayList<List<Part>>();var current=new ArrayList<Part>();
        for(var part:parts) {
            var candidate=new ArrayList<>(current);candidate.add(part);
            boolean related=current.isEmpty()||current.stream().allMatch(p->p.chapterId().equals(part.chapterId())||allowRelated&&(r.compact()&&compilation!=null?independent(p.chapterId(),part.chapterId(),compilation):related(p.chapterId(),part.chapterId(),compilation,quiz)));
            if(!current.isEmpty()&&(!related||candidate.size()>maximumParts||bytes(r,candidate,compilation,quiz)>r.policy().unitInputBytes()||output(candidate)>r.policy().packageOutputBytes())){groups.add(List.copyOf(current));current.clear();candidate= new ArrayList<>(List.of(part));}
            if(bytes(r,candidate,compilation,quiz)>r.policy().unitInputBytes())throw new LabException("WORKFLOW_UNIT_TOO_LARGE","最小执行单元的必要证据超出工作包容量");current.add(part);
        }
        if(!current.isEmpty())groups.add(List.copyOf(current));
        var packs=new ArrayList<WorkPackage>();int i=0;
        for(var group:groups)packs.add(new WorkPackage(String.format(Locale.ROOT,"w%05d",++i),"",0,group.stream().map(Part::id).toList(),List.of(),bytes(r,group,compilation,quiz),output(group)));
        if(compilation!=null) {
            var byPart=parts.stream().collect(Collectors.toMap(Part::id,p->p));var dependencies=compilation.directory().chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::resultDependencies));
            var parents=compilation.directory().chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId));
            var revised=new ArrayList<WorkPackage>();
            for(var pack:packs){var required=pack.partIds().stream().map(byPart::get).flatMap(p->dependencies.get(p.chapterId()).stream()).collect(Collectors.toSet());
                var deps=packs.stream().filter(other->other.partIds().stream().map(byPart::get).anyMatch(p->!Collections.disjoint(required,LearningHierarchy.ancestors(Set.of(p.chapterId()),parents)))).map(WorkPackage::id).toList();
                if(deps.contains(pack.id())){if(allowRelated)return build(r,compilation,quiz,false,maximumParts,maximumSectionOutput);throw new LabException("WORKFLOW_STATE_CONFLICT","实际结果依赖的章节不能合入同一个执行包");}revised.add(new WorkPackage(pack.id(),"",0,pack.partIds(),deps,pack.inputBytes(),pack.outputBytes()));}packs=revised;
        }
        try{LearningPlanRules.dag(packs.stream().collect(Collectors.toMap(WorkPackage::id,WorkPackage::dependencies)));}
        catch(LabException conflict){if(allowRelated&&compilation!=null)return build(r,compilation,quiz,false,maximumParts,maximumSectionOutput);throw conflict;}
        var byPart=parts.stream().collect(Collectors.toMap(Part::id,p->p));var byPack=packs.stream().collect(Collectors.toMap(WorkPackage::id,p->p));
        for(var pack:packs) {
            var owned=pack.partIds().stream().map(byPart::get).toList();var request=input(r,owned,compilation,quiz);
            int dependencyBytes=pack.dependencies().stream().map(byPack::get).mapToInt(WorkPackage::outputBytes).sum();
            if(dependencyBytes>0)request.put("dependencyResultsReservation","X".repeat(dependencyBytes+pack.dependencies().size()*300));
            var schema=compilation!=null?(r.compact()?CompactGeneration.schema(owned):LearningBusinessSchemas.sectionBatch(owned)):LearningBusinessSchemas.questions(owned,quiz.slots().stream().collect(Collectors.toMap(Slot::id,s->s)));
            if(TextWindow.count(ContentJson.encode(request))>r.policy().unitInputBytes()||!r.preflight("package-write",request,schema)) {
                if(owned.size()>1)return build(r,compilation,quiz,allowRelated,Math.max(1,owned.size()/2),maximumSectionOutput);
                var part=owned.get(0);
                if(compilation!=null&&part.itemIds().size()>1&&maximumSectionOutput>500)return build(r,compilation,quiz,false,1,Math.max(500,maximumSectionOutput/2));
                throw new LabException("WORKFLOW_UNIT_TOO_LARGE","接受计划前确认完整单元及依赖结果无法容纳合法模型窗口");
            }
        }
        if(packs.size()>r.executionLimits().turns())throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","执行包所需调用超过任务剩余执行预算");
        return new ExecutionPlan(parts,packs,r.executionLimits().turns(),LearningPlanRules.MAX_SPLIT_DEPTH);
    }
    private static boolean related(String a,String b,CompilationPlan c,Blueprint q){
        if(a.equals(b))return true;
        if(c!=null){var chapter=c.directory().chapters().stream().filter(x->x.id().equals(a)).findFirst().orElseThrow();var other=c.directory().chapters().stream().filter(x->x.id().equals(b)).findFirst().orElseThrow();return !chapter.resultDependencies().contains(b)&&!other.resultDependencies().contains(a)&&(chapter.relatedChapterIds().contains(b)||other.relatedChapterIds().contains(a));}
        return q.objectives().stream().anyMatch(o->o.primaryChapterId().equals(a)&&o.relatedChapterIds().contains(b)||o.primaryChapterId().equals(b)&&o.relatedChapterIds().contains(a));
    }
    private static boolean independent(String a,String b,CompilationPlan c){
        var parents=c.directory().chapters().stream().collect(Collectors.toMap(DirectoryChapter::id,DirectoryChapter::parentId));
        var ancestorsA=LearningHierarchy.ancestors(Set.of(a),parents);var ancestorsB=LearningHierarchy.ancestors(Set.of(b),parents);
        return c.directory().chapters().stream().filter(ch->ch.id().equals(a)).noneMatch(ch->!Collections.disjoint(ch.resultDependencies(),ancestorsB))
                &&c.directory().chapters().stream().filter(ch->ch.id().equals(b)).noneMatch(ch->!Collections.disjoint(ch.resultDependencies(),ancestorsA));
    }
    static Map<String,Object> input(LearningRuntime r,List<Part> parts,CompilationPlan c,Blueprint q) {
        var input=new LinkedHashMap<String,Object>();input.put("parts",parts);input.put("estimatedOutputBytes",output(parts));input.put("remarks",r.lease().request().instructions());
        input.put("facts",r.evidence(parts.stream().flatMap(p->p.itemIds().stream()).distinct().toList()));
        if(c!=null){var ids=parts.stream().flatMap(p->java.util.stream.Stream.concat(p.ownedKnowledgeIds().stream(),p.contextKnowledgeIds().stream())).collect(Collectors.toSet());input.put("knowledge",c.knowledge().stream().filter(k->ids.contains(k.id())).toList());
            var contexts=parts.stream().flatMap(p->p.contextKnowledgeIds().stream()).collect(Collectors.toSet());input.put("backgroundEvidence",r.evidence(c.knowledge().stream().filter(k->contexts.contains(k.id())).flatMap(k->k.sourceItemIds().stream()).distinct().toList()));
            var chapters=parts.stream().map(Part::chapterId).collect(Collectors.toSet());input.put("chapterResponsibilities",c.directory().chapters().stream().filter(ch->chapters.contains(ch.id())).toList());input.put("terms",c.directory().terms());input.put("requirements",c.directory().requirements());}
        else {var ids=parts.stream().map(Part::logicalId).collect(Collectors.toSet());input.put("slots",q.slots().stream().filter(s->ids.contains(s.id())).toList());input.put("difficulty",q.difficulty());input.put("requirements",q.intent().requirements());}
        return input;
    }
    static int bytes(LearningRuntime r,List<Part> parts,CompilationPlan c,Blueprint q){return TextWindow.count(ContentJson.encode(input(r,parts,c,q)));}
    static int output(List<Part> parts){return parts.stream().mapToInt(Part::outputBytes).sum()+256;}
    record Split(List<Part> parts,List<WorkPackage> children) { }
    static Split split(LearningRuntime r,Plan plan,PackageStatus parent) {
        var work=parent.workPackage();if(work.depth()>=plan.execution().maximumSplitDepth())throw new LabException("WORKFLOW_UNIT_TOO_LARGE","执行包达到有限拆分深度");
        var old=parent.parts();List<List<Part>> groups;
        if(old.size()>1){int mid=old.size()/2;groups=List.of(old.subList(0,mid),old.subList(mid,old.size()));}
        else {
            var p=old.get(0);if(p.kind().equals("QUESTION")||p.itemIds().size()<2)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","完整单题或最小必要小节证据仍超过模型容量");
            int mid=p.itemIds().size()/2;var split=new ArrayList<Part>();int index=0;
            for(var ids:List.of(p.itemIds().subList(0,mid),p.itemIds().subList(mid,p.itemIds().size()))) {
                var owned=p.ownedKnowledgeIds();if(plan.compilation()!=null)owned=owned.stream().filter(id->plan.compilation().knowledge().stream().anyMatch(k->k.id().equals(id)&&!Collections.disjoint(k.sourceItemIds(),ids))).toList();
                split.add(new Part(p.id()+"_"+(++index),p.logicalId(),p.chapterId(),p.kind(),p.purpose(),owned,p.contextKnowledgeIds(),ids,Math.max(500,p.outputBytes()/2)));
            }groups=List.of(List.of(split.get(0)),List.of(split.get(1)));
        }
        var children=new ArrayList<WorkPackage>();int i=0;for(var group:groups)children.add(new WorkPackage(work.id()+"_"+(++i),work.id(),work.depth()+1,group.stream().map(Part::id).toList(),work.dependencies(),bytes(r,group,plan.compilation(),plan.quiz()),output(group)));
        return new Split(groups.stream().flatMap(Collection::stream).toList(),children);
    }
}
