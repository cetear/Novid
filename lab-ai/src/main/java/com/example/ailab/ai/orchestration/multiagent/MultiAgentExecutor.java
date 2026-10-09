package com.example.ailab.ai.orchestration.multiagent;

import com.example.ailab.ai.orchestration.*;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.contract.dto.ExecutionArchitecture;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import java.util.*;

/** 独立角色依赖架构；整个图校验通过后才执行，不重新分配预算或创建线程池。 */
@Component
public final class MultiAgentExecutor implements ArchitectureExecutor {
    public ExecutionArchitecture architecture() { return ExecutionArchitecture.MULTI_AGENT; }
    public String version() { return "multi-agent-v1"; }
    public boolean supports(WorkflowProgram<?> program) { return program instanceof MultiAgentProgram<?, ?>; }
    public <R> R execute(WorkflowProgram<R> program, ExecutionBudget budget) throws Exception {
        if (!(program instanceof MultiAgentProgram<?, R> agents)) throw new LabException("WORKFLOW_ARCHITECTURE_MISMATCH", "需要多角色程序");
        return run(agents, budget);
    }
    private <N, R> R run(MultiAgentProgram<N, R> program, ExecutionBudget budget) throws Exception {
        budget.check(); var ordered = topology(program.nodes());
        try (var graph = new AgentDagExecutor<N>(program.executor())) {
            for (var node : ordered) graph.submit(node.id(), node.dependencies(), prior -> {
                budget.check(); var value = node.action().apply(prior); budget.check(); return value;
            });
            var values = graph.await(budget.deadline()); budget.check();
            var result = program.result().apply(Map.copyOf(values)); budget.check(); return result;
        }
    }
    private <N> List<MultiAgentProgram.Node<N>> topology(List<MultiAgentProgram.Node<N>> nodes) {
        if (nodes.isEmpty() || nodes.size() > 4096) throw invalid();
        var remaining = new LinkedHashMap<String, MultiAgentProgram.Node<N>>();
        for (var node : nodes) if (remaining.putIfAbsent(node.id(), node) != null) throw invalid();
        for (var node : nodes) if (node.dependencies().stream().anyMatch(id -> !remaining.containsKey(id))
                || node.dependencies().stream().distinct().count() != node.dependencies().size()) throw invalid();
        var seen = new HashSet<String>(); var ordered = new ArrayList<MultiAgentProgram.Node<N>>();
        while (!remaining.isEmpty()) {
            var ready = remaining.values().stream().filter(n -> seen.containsAll(n.dependencies())).toList();
            if (ready.isEmpty()) throw invalid();
            for (var node : ready) { remaining.remove(node.id()); seen.add(node.id()); ordered.add(node); }
        }
        return ordered;
    }
    private LabException invalid() { return new LabException("WORKFLOW_GRAPH_INVALID", "角色依赖缺失、重复或存在环路"); }
}
