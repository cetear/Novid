package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.RecordSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.ContentWorkflowStorePort;
import java.util.*;
import java.util.function.*;

/** 阅读、规划、执行共享父任务运行能力；业务角色不持有模型、线程池或数据库。 */
interface LearningRuntime {
    TaskLease lease(); ContentWorkflow.SourcePlan source(); ContentReading.Plan reading();
    Map<String,Learning.Item> facts(); List<ContentReading.TopicCard> cards(); Compilation.Origin origin();
    int quoteOffset(Learning.Item item);
    String fragmentText(String id);
    ContentWorkflowStorePort store();
    <T>T call(String id,String stage,String role,Object input,RecordSchema<T> schema);
    <T>T correct(String id,String role,Object input,RecordSchema<T> schema,String originalId);
    <T>T checkpoint(String id,String stage,Object input,RecordSchema<T> schema,Supplier<T> execute);
    boolean preflight(Object input,RecordSchema<?> schema);
    <A,B>List<B> parallel(List<A> input,Function<A,B> execute);
    default ContentWorkflow.Policy policy(){return source().policy();}
    default ContentExecutionLimits executionLimits(){return ContentExecutionLimits.from(policy());}
    default List<Learning.Item> evidence(Collection<String> ids){return ids.stream().map(id->Objects.requireNonNull(facts().get(id))).toList();}
    default void bounded(Object input){if(TextWindow.count(ContentJson.encode(input))>policy().unitInputBytes())throw new com.example.ailab.contract.error.LabException("WORKFLOW_UNIT_TOO_LARGE","必要证据及执行上下文超过创建时局部容量");}
}
