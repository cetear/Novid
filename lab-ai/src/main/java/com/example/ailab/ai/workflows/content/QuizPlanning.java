package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.LearningContent.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;
import static com.example.ailab.ai.model.RecordSchema.*;

/** 小章节合包，保留逐组事实覆盖；并发只执行独立包，结果按冻结输入顺序合并。 */
final class QuizPlanning {
    public record Group(String groupId,Map<String,String> chapter,List<Learning.Item> facts) { }
    public record Result(String groupId,Objectives result) { }
    public record ObjectivesBatch(List<Result> groups) { }
    record Work(String id,List<Group> groups,int estimatedOutputBytes) { }
    private QuizPlanning() { }

    static List<Work> packages(LearningRuntime r,List<Chapter> chapters) {
        int maximum=Math.min(48000,r.policy().unitInputBytes()-2000);
        var groups=new ArrayList<Group>();
        for(var chapter:chapters)for(var facts:LearningPlanner.partition(r.evidence(chapter.itemIds()),maximum-1000,ContentJson::encode))
            for(int from=0;from<facts.size();from+=32)
                groups.add(new Group(String.format(Locale.ROOT,"g%05d",groups.size()+1),Map.of("id",chapter.id(),"title",chapter.title()),List.copyOf(facts.subList(from,Math.min(from+32,facts.size())))));
        var result=new ArrayList<Work>();var current=new ArrayList<Group>();int facts=0;
        for(var group:groups) {
            var next=new ArrayList<>(current);next.add(group);
            if(!current.isEmpty()&&(next.size()>8||facts+group.facts().size()>32||TextWindow.count(ContentJson.encode(next))>maximum)) {
                result.add(work(result.size(),current));current.clear();facts=0;
            }
            current.add(group);facts+=group.facts().size();
            if(TextWindow.count(ContentJson.encode(current))>maximum)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","命题目标的必要事实超过规划包容量");
        }
        if(!current.isEmpty())result.add(work(result.size(),current));return List.copyOf(result);
    }
    private static Work work(int index,List<Group> groups) {
        return new Work(String.format(Locale.ROOT,"objectives_batch_%05d",index+1),List.copyOf(groups),2048+groups.size()*600+groups.stream().mapToInt(g->g.facts().size()).sum()*1200);
    }
    static RecordSchema<ObjectivesBatch> schema(List<Group> groups,Set<String> types,Set<String> chapters) {
        return new RecordSchema<>(ObjectivesBatch.class,"逐组提取不同的可命题目标，cognition只能为RECALL/UNDERSTAND/APPLY/COMPARE，priority为1至3。groups按输入顺序原样返回groupId，每组result包括objectives、unexaminedItemIds、reason。每个目标必须有title、cognition、questionTypes、itemIds、priority、reason、relatedChapterIds；无相关章节时返回空数组。题型仅使用allowedTypes。只引用本组事实；不可遗漏任何组或事实，目录/引用/无效正文列入unexaminedItemIds并在reason解释。同事实集合与同认知目的只返回一个候选，不得改写题干凑数。",v->{
            require(v.groups().size()==groups.size(),Reason.MISSING_COVERAGE,"groups","完整返回全部规划组");
            for(int i=0;i<Math.min(v.groups().size(),groups.size());i++) {
                var expected=groups.get(i);var actual=v.groups().get(i);
                require(expected.groupId().equals(actual.groupId()),Reason.ID_ORDER_MISMATCH,"groups["+i+"].groupId",expected.groupId());
                nested("groups["+i+"].result",()->LearningBusinessSchemas.objectives(expected.facts(),types,chapters).validate(ContentJson.encode(actual.result()),Set.of()));
            }
        });
    }
    static List<Result> execute(LearningRuntime r,List<Chapter> chapters) {
        var chapterIds=new HashSet<>(chapters.stream().map(Chapter::id).toList());
        var types=new HashSet<>(r.lease().request().quizOptions().questionTypes());
        var work=packages(r,chapters);var results=new ArrayList<Result>();
        // 每波最多4包，复用父任务线程池与预算，不额外创建模型许可或嵌套并行。
        for(int from=0;from<work.size();from+=4) {
            var wave=work.subList(from,Math.min(from+4,work.size()));
            var completed=r.parallel(wave,w->execute(r,w,types,chapterIds));
            completed.forEach(results::addAll);
        }
        return List.copyOf(results);
    }
    private static List<Result> execute(LearningRuntime r,Work work,Set<String> types,Set<String> chapters) {
        var input=Map.of("groups",work.groups(),"difficulty",r.lease().request().quizOptions().difficulty(),"allowedTypes",types.stream().sorted().toList(),"estimatedOutputBytes",work.estimatedOutputBytes());
        var schema=schema(work.groups(),types,chapters);r.bounded(input);
        try {
            if(!r.preflight("objectives",input,schema))throw new LabException("MODEL_CONTEXT_INSUFFICIENT","命题规划包超过模型容量");
            return r.call(work.id(),"organize","objectives",input,schema).groups();
        } catch(LabException failure) {
            if(!Set.of("MODEL_TRUNCATED","MODEL_CONTEXT_INSUFFICIENT").contains(failure.code()))throw failure;
            List<Group> left,right;
            if(work.groups().size()>1) {
                int mid=work.groups().size()/2;left=work.groups().subList(0,mid);right=work.groups().subList(mid,work.groups().size());
            } else {
                var group=work.groups().get(0);if(group.facts().size()<2)throw failure;
                int mid=group.facts().size()/2;
                left=List.of(new Group(group.groupId(),group.chapter(),group.facts().subList(0,mid)));
                right=List.of(new Group(group.groupId(),group.chapter(),group.facts().subList(mid,group.facts().size())));
            }
            var a=work(0,left);var b=work(0,right);
            var result=new ArrayList<>(execute(r,new Work(work.id()+"_a",a.groups(),a.estimatedOutputBytes()),types,chapters));
            result.addAll(execute(r,new Work(work.id()+"_b",b.groups(),b.estimatedOutputBytes()),types,chapters));return List.copyOf(result);
        }
    }
}
