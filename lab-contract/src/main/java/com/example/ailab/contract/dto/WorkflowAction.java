package com.example.ailab.contract.dto;

/** 决策先记录PENDING，成功观察再原子提交；观察只包含类型化业务结果。 */
public record WorkflowAction(int sequence, String name, String inputHash, String status, String observation) {
    public WorkflowAction {
        if (sequence < 1 || sequence > 12 || name == null || !name.matches("[a-z][a-z_]{0,31}")
                || inputHash == null || !inputHash.matches("[a-f0-9]{64}")
                || !("PENDING".equals(status) || "COMPLETED".equals(status)) || observation == null
                || observation.length() > 100000 || status.equals("PENDING") && !observation.isEmpty())
            throw new IllegalArgumentException("工作流动作记录无效");
    }

    public WorkflowAction completed(String observation) {
        return new WorkflowAction(sequence, name, inputHash, "COMPLETED", observation);
    }
}
