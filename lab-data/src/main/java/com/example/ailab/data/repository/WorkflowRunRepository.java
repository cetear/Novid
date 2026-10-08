package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.WorkflowRunStorePort;
import com.example.ailab.data.persistence.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 租约锁保护架构基线与连续动作日志；模型调用始终由AI模块在事务外执行。 */
@Repository
@Transactional
public class WorkflowRunRepository implements WorkflowRunStorePort {
    private final TaskRepository tasks;
    private final WorkflowRunMapper mapper;
    private final ObjectMapper json = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    public WorkflowRunRepository(TaskRepository tasks, SqlSupport sql) {
        this.tasks = tasks;
        this.mapper = sql.mapper(WorkflowRunMapper.class);
    }
    public boolean eligible(TaskLease lease) {
        tasks.valid(lease);
        return mapper.eligible(new Object[]{lease.task().taskId()}).get(0).booleanValue("workflow_binding_eligible");
    }
    public Optional<WorkflowExecutionBinding> binding(TaskLease lease) {
        tasks.valid(lease);
        var rows = mapper.binding(new Object[]{lease.task().taskId()});
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(decode(rows.get(0).string("binding_json"), rows.get(0).string("binding_hash"), WorkflowExecutionBinding.class));
    }
    public void bind(TaskLease lease, WorkflowExecutionBinding value) {
        tasks.valid(lease);
        var old = binding(lease);
        if (old.isPresent()) {
            if (!old.get().equals(value)) throw conflict();
            return;
        }
        if (!eligible(lease)) throw conflict();
        mapper.bind(new Object[]{lease.task().taskId(), hash(value), encode(value)});
    }
    public List<WorkflowAction> actions(TaskLease lease) {
        tasks.valid(lease);
        var actions = mapper.actions(new Object[]{lease.task().taskId()}).stream()
                .map(row -> decode(row.string("action_json"), row.string("action_hash"), WorkflowAction.class)).toList();
        for (int i = 0; i < actions.size(); i++)
            if (actions.get(i).sequence() != i + 1 || i < actions.size() - 1 && !actions.get(i).status().equals("COMPLETED"))
                throw conflict();
        return actions;
    }
    public void start(TaskLease lease, WorkflowAction action) {
        tasks.valid(lease);
        var baseline = binding(lease).orElseThrow(WorkflowRunRepository::conflict);
        if (baseline.architecture() != ExecutionArchitecture.REACT || !lease.task().taskType().equals("NOTES_PPT")
                || !action.status().equals("PENDING")) throw conflict();
        var history = actions(lease);
        if (action.sequence() <= history.size()) {
            if (!history.get(action.sequence() - 1).equals(action)) throw conflict();
            return;
        }
        if (action.sequence() != history.size() + 1 || history.stream().anyMatch(a -> a.status().equals("PENDING"))) throw conflict();
        if (action.name().equals("repair") && mapper.consumeRework(new Object[]{lease.task().taskId()}) != 1)
            throw new LabException("BUDGET_EXCEEDED", "唯一语义返工预算耗尽");
        mapper.start(new Object[]{lease.task().taskId(), action.sequence(), hash(action), encode(action)});
        mapper.executing(new Object[]{lease.task().taskId()});
    }
    public void complete(TaskLease lease, WorkflowAction action) {
        tasks.valid(lease);
        var history = actions(lease);
        if (!action.status().equals("COMPLETED") || action.sequence() > history.size()) throw conflict();
        var pending = history.get(action.sequence() - 1);
        if (pending.status().equals("COMPLETED")) {
            if (!pending.equals(action)) throw conflict();
            return;
        }
        if (!pending.name().equals(action.name()) || !pending.inputHash().equals(action.inputHash())
                || mapper.complete(new Object[]{hash(action), encode(action), lease.task().taskId(), action.sequence(), hash(pending)}) != 1)
            throw conflict();
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw conflict(); }
    }
    private String hash(Object value) { return SqlSupport.hash(encode(value)); }
    private <T> T decode(String text, String hash, Class<T> type) {
        try {
            T value = json.readValue(text, type);
            if (!hash(value).equals(hash)) throw conflict();
            return value;
        } catch (Exception invalid) { throw conflict(); }
    }
    private static LabException conflict() {
        return new LabException("WORKFLOW_STATE_CONFLICT", "工作流执行基线或动作记录不一致");
    }
}
