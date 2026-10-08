package com.example.ailab.demo;

import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.ai.model.*;
import com.example.ailab.ai.rag.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.time.Duration;
import java.util.*;
/** 无数据库副作用的 CLI，使用正式解析与模型路由组件验证固定输入。 */
public final class LabDemo {
    /** 禁止构造 CLI 类。 */
    private LabDemo(){}
    /** 固定数据验证 AST 归属与主模型超时后切备用，不能证明真实模型已通过。 */
    public static void main(String[] args){
        var rag=new RagProperties(500,50,100,64,2000,4000,6,6,1);var parser=new StructureParser(rag);
        String source="# RAG\n向量召回与 BM25 互补。\n\n```text\n# 这不是标题\n```\n## RRF\n合并两路排序。\n";
        var lease=new IngestionLease(1,1,1,1,new UserContext(1,UserContext.Role.USER,true,1,false),"demo",1,"RAG.md","md",source);
        var parsed=parser.parse(lease);if(parsed.sections().size()!=3)throw new IllegalStateException("结构解析断言失败");
        var defs=new LinkedHashMap<String,ModelProperties.Definition>();
        defs.put("primary",definition("mock-timeout"));defs.put("backup",definition("mock-backup"));
        var profile=new ModelProperties.Profile(List.of("primary","backup"),Set.of("CHAT"),Set.of("mock-tested"),true);
        var properties=new ModelProperties("mock",defs,Map.of("knowledge",profile),new ModelProperties.Routing("demo-v1",Map.of("KNOWLEDGE_QA","knowledge")),false);
        var gateway=new ModelGateway(new ModelRegistry(properties));var budget=new ExecutionBudget(Duration.ofSeconds(60),10);
        var result=gateway.chat("KNOWLEDGE_QA","根据证据回答","[E1] RRF 合并两个排序列表",budget);
        if(!result.modelId().equals("backup")||budget.attempts()!=2)throw new IllegalStateException("主备断言失败");
        System.out.println("PASS [Mock] AST sections="+parsed.sections().size()+", chunks="+parsed.chunks().size()+", fallback="+result.modelId()+", attempts="+budget.attempts());
    }
    /** 固定 Mock 定义不使用真实凭证。 */
    private static ModelProperties.Definition definition(String name){return new ModelProperties.Definition("mock","mock://local",name,null,true,Set.of("CHAT"),Set.of("mock-tested"),Set.of("PRIVATE"),16000,2048,0,30);}
}
