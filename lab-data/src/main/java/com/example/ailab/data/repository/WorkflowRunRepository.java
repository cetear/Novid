package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.WorkflowRunStorePort;
import com.example.ailab.data.persistence.mapper.WorkflowRunMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** 租约锁保护不可变架构基线；模型调用始终由AI模块在事务外执行。 */
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
        mapper.bind(new Object[]{lease.task().taskId(), hash(value), encode(value)});
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
        return new LabException("WORKFLOW_STATE_CONFLICT", "工作流执行基线不一致");
    }
}
