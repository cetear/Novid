package com.example.ailab.ai.orchestration.multiagent;

import com.example.ailab.ai.orchestration.WorkflowProgram;
import com.example.ailab.contract.dto.ExecutionArchitecture;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Function;

/** 工作流提供已授权的角色动作、依赖和有界线程池；架构负责依赖调度与结果汇合。 */
public record MultiAgentProgram<N, R>(List<Node<N>> nodes, Executor executor, Function<Map<String, N>, R> result)
        implements WorkflowProgram<R> {
    public record Node<N>(String id, List<String> dependencies, Function<List<N>, N> action) {
        public Node {
            dependencies = List.copyOf(dependencies); Objects.requireNonNull(action);
            if (id == null || !id.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("角色节点ID无效");
        }
    }
    public MultiAgentProgram { nodes = List.copyOf(nodes); Objects.requireNonNull(executor); Objects.requireNonNull(result); }
    public ExecutionArchitecture architecture() { return ExecutionArchitecture.MULTI_AGENT; }
}
