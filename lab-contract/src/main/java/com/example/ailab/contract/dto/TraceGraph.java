package com.example.ailab.contract.dto;

import java.util.List;

/** 调用嵌套与调度依赖分别返回；缺失节点明确列出，不补造静态步骤。 */
public record TraceGraph(TraceSnapshot run, List<TraceNode> nodes, List<Edge> edges, List<String> missingNodeIds,
                         boolean incomplete, String costStatus) {
    public record Edge(String from, String to, String kind) { }
    /** 对持久节点重建图，序号只表示登记顺序，时间线使用实际时间。 */
    public static TraceGraph from(TraceSnapshot run, List<TraceNode> nodes) {
        var edges = new java.util.ArrayList<Edge>();
        var missing = new java.util.LinkedHashSet<String>();
        var ids = nodes.stream().map(TraceNode::spanId).collect(java.util.stream.Collectors.toSet());
        for (var n : nodes) {
            if (n.parentSpanId() != null) edges.add(new Edge(n.parentSpanId(), n.spanId(), "CALL"));
            for (var dependency : n.dependsOn()) edges.add(new Edge(dependency, n.spanId(), "DEPENDENCY"));
        }
        edges.forEach(e -> { if (!ids.contains(e.from())) missing.add(e.from()); if (!ids.contains(e.to())) missing.add(e.to()); });
        boolean unfinished = nodes.stream().anyMatch(n -> n.endedAt() == null);
        return new TraceGraph(run, List.copyOf(nodes), List.copyOf(edges), List.copyOf(missing),
                run.incomplete() || unfinished || !missing.isEmpty() || nodes.isEmpty(), "UNKNOWN");
    }
}
