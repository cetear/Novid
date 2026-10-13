package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.*;
import java.util.*;

/** 已授权的同任务运行按时间延续；成功业务分支沿用，失败分支仅显示最新执行。 */
final class ContinuationTraceGraph {
    private ContinuationTraceGraph() { }

    static TraceGraph merge(List<TraceGraph> history,boolean historyMissing) {
        var current=history.get(history.size()-1);
        var all=new HashMap<String,TraceNode>();
        var origins=new HashMap<String,String>();
        var selected=new LinkedHashMap<String,TraceNode>();
        var invalidModels=new HashSet<String>();
        for(var graph:history) {
            var latestModel=new HashMap<String,String>();
            for(var node:graph.nodes()) {
                if(node.type().equals("MODEL"))latestModel.put(node.parentSpanId(),node.spanId());
                if(node.type().equals("VALIDATION")) {
                    String model=latestModel.remove(node.parentSpanId());
                    if(model!=null&&!successful(node))invalidModels.add(model);
                }
            }
        }
        for(var graph:history)for(var node:graph.nodes()) {
            all.put(node.spanId(),node);origins.put(node.spanId(),graph.run().traceId());
            if(!node.type().equals("AGENT")||node.status().equals("REUSED"))continue;
            if(graph==current||successful(node))selected.put(node.name(),node);
            else selected.remove(node.name());
        }
        var wrappers=new HashMap<String,TraceNode>();
        for(var node:current.nodes())if(!node.type().equals("AGENT")&&node.observation()==null
                &&Set.of("TASK","ARCHITECTURE").contains(node.type()))wrappers.put(key(node),node);
        var kept=new LinkedHashMap<String,TraceNode>();
        // 优先使用本轮真实结构，历史结构只在尚无对应当前节点时作为祖先保留。
        for(var node:current.nodes())if(wrappers.containsValue(node))kept.put(node.spanId(),node);
        for(var graph:history)for(var node:graph.nodes()) {
            if(graph==current) {
                if(!reusedAncestor(node,all))kept.putIfAbsent(node.spanId(),node);
            } else if(node.type().equals("AGENT")) {
                var replacement=selected.get(node.name());
                if(replacement!=null)kept.putIfAbsent(replacement.spanId(),replacement);
            } else if(successful(node)&&!invalidModels.contains(node.spanId())) {
                var owner=owner(node,all);
                if(owner!=null&&selected.get(owner.name())==owner)kept.putIfAbsent(node.spanId(),node);
            }
        }
        // 当前结构尚未落库时保留成功分支真实祖先；历史状态与来源不冒充本轮事实。
        for(var node:List.copyOf(kept.values())) {
            var seen=new HashSet<String>();String id=node.parentSpanId();
            while(id!=null&&seen.add(id)&&!kept.containsKey(id)) {
                var ancestor=all.get(id);
                if(ancestor==null)break;
                var replacement=ancestor.type().equals("AGENT")?selected.get(ancestor.name()):wrappers.get(key(ancestor));
                if(replacement!=null)break;
                if(Set.of("TASK","ARCHITECTURE").contains(ancestor.type())&&ancestor.observation()==null)
                    kept.putIfAbsent(id,ancestor);
                id=ancestor.parentSpanId();
            }
        }
        var nodes=new ArrayList<TraceNode>();var nodeRuns=new LinkedHashMap<String,String>();
        for(var node:kept.values()) {
            String parent=parent(node,all,kept,selected,wrappers);
            var dependencies=node.dependsOn().stream().map(id->{
                if(kept.containsKey(id))return id;
                var original=all.get(id);var replacement=original==null||!original.type().equals("AGENT")?null:selected.get(original.name());
                return replacement==null?id:replacement.spanId();
            }).toList();
            nodes.add(node.layout(parent,dependencies,nodes.size()+1));
            nodeRuns.put(node.spanId(),origins.get(node.spanId()));
        }
        var graph=TraceGraph.from(current.run(),nodes);
        boolean incomplete=historyMissing||history.stream().anyMatch(TraceGraph::incomplete)||graph.incomplete();
        return new TraceGraph(graph.run(),graph.nodes(),graph.edges(),graph.missingNodeIds(),incomplete,graph.costStatus(),nodeRuns);
    }

    private static boolean successful(TraceNode node) {
        return node.status().equals("SUCCESS")&&node.endedAt()!=null
                &&(node.observation()==null||!Set.of("FAILED","INVALID").contains(node.observation().validationStatus()));
    }
    private static boolean reusedAncestor(TraceNode node,Map<String,TraceNode> all) {
        var seen=new HashSet<String>();
        for(var n=node;n!=null&&seen.add(n.spanId());n=all.get(n.parentSpanId()))
            if(n.status().equals("REUSED"))return true;
        return false;
    }
    private static TraceNode owner(TraceNode node,Map<String,TraceNode> all) {
        var seen=new HashSet<String>();
        for(var n=all.get(node.parentSpanId());n!=null&&seen.add(n.spanId());n=all.get(n.parentSpanId()))
            if(n.type().equals("AGENT"))return n;
        return null;
    }
    private static String parent(TraceNode node,Map<String,TraceNode> all,Map<String,TraceNode> kept,
                                 Map<String,TraceNode> selected,Map<String,TraceNode> wrappers) {
        var seen=new HashSet<String>();String id=node.parentSpanId();
        while(id!=null&&seen.add(id)) {
            if(kept.containsKey(id))return id;
            var original=all.get(id);
            if(original==null)return id;
            var replacement=original.type().equals("AGENT")?selected.get(original.name()):wrappers.get(key(original));
            if(replacement!=null&&!replacement.spanId().equals(node.spanId()))return replacement.spanId();
            id=original.parentSpanId();
        }
        return node.parentSpanId();
    }
    private static String key(TraceNode node) {return node.type()+":"+node.name();}
}
