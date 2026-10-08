package com.example.ailab.ai.orchestration.react;

import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.contract.dto.WorkflowAction;
import com.example.ailab.contract.error.LabException;
import java.util.Optional;
import java.util.Set;

/** 通用受控动作循环；业务提供观察、可用动作及持久化，执行器不依赖业务工作流。 */
public final class ReActExecutor {
    public interface Session {
        boolean finished();
        int completedActions();
        Set<String> allowedActions();
        Optional<WorkflowAction> pending();
        WorkflowAction decide(Set<String> allowed);
        void start(WorkflowAction action);
        void execute(WorkflowAction action);
    }

    public void run(Session session, ExecutionBudget budget) {
        while (!session.finished()) {
            budget.check();
            if (session.completedActions() >= 12) throw new LabException("BUDGET_EXCEEDED", "ReAct动作次数耗尽");
            var allowed = session.allowedActions();
            var pending = session.pending();
            var action = pending.orElseGet(() -> session.decide(allowed));
            if (!"PENDING".equals(action.status()) || action.sequence() != session.completedActions() + 1
                    || !allowed.contains(action.name()))
                throw new LabException("WORKFLOW_ACTION_INVALID", "动作不满足当前状态的执行条件");
            if (pending.isEmpty()) session.start(action);
            session.execute(action);
        }
    }
}
