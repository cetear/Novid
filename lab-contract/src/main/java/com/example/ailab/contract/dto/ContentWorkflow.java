package com.example.ailab.contract.dto;

import java.util.*;

/** 资料驱动执行协议。来源、产出计划和资源策略独立于创建请求，均不可变。 */
public final class ContentWorkflow {
    private ContentWorkflow() { }
    public record Policy(int sourceBytes, int pageBytes, int maximumItems, int indexBytes, int unitInputBytes,
                         int maximumUnits, int parallelism, int turns, int attempts, int tools,
                         int executionSeconds, int resultBytes) {
        public Policy {
            if (sourceBytes < 3000 || sourceBytes > 10000000 || pageBytes < 500 || pageBytes > 6000
                    || maximumItems < 8 || maximumItems > 20000 || indexBytes < 6000 || indexBytes > 24000
                    || unitInputBytes < indexBytes || unitInputBytes > 32000 || maximumUnits < 1 || maximumUnits > 512
                    || parallelism < 1 || parallelism > 16 || turns < 10 || turns > 10000 || attempts < turns || attempts > 20000
                    || tools < 1 || tools > 10000 || executionSeconds < 1200 || executionSeconds > 86400
                    || resultBytes < 500000 || resultBytes > 16000000) throw new IllegalArgumentException("资料工作流资源策略无效");
        }
        public static Policy defaults() { return new Policy(1000000, 3000, 4096, 8000, 8000, 256, 8, 1200, 1800, 1200, 7200, 4000000); }
        public Learning.Limits limits() { return new Learning.Limits(turns, attempts, tools); }
    }
    public record SourcePlan(WorkflowExecutionBinding workflow, TaskExecutionBinding skill, String requestHash,
                             Policy policy, List<Learning.SourceSlice> slices, List<SourceDependency> sources) {
        public SourcePlan { slices = List.copyOf(slices); sources = List.copyOf(sources); }
    }
    public record Summary(String title, String summary) { }
    public record Facts(List<Learning.Item> items, String status) { public Facts { items = List.copyOf(items); } }
    public record Theme(String id, String title, String purpose) { }
    public record Intent(String title, List<Theme> themes, String requirements, String reason, String requestedUnits) {
        public Intent { themes = List.copyOf(themes); }
    }
    public record UnitDraft(String kind, String themeId, String title, String purpose, String relation, String imageMode, String imagePrompt, List<String> itemIds) {
        public UnitDraft { itemIds = List.copyOf(itemIds); }
    }
    public record UnitBatch(List<UnitDraft> units, List<String> omittedItemIds, String omissionReason) {
        public UnitBatch { units = List.copyOf(units); omittedItemIds = List.copyOf(omittedItemIds); }
    }
    public record UnitPlan(String id, String kind, String themeId, String title, String purpose, String relation, String imageMode, String imagePrompt, List<String> itemIds) {
        public UnitPlan { itemIds = List.copyOf(itemIds); }
    }
    public record Omission(List<String> itemIds, String reason) { public Omission { itemIds = List.copyOf(itemIds); } }
    public record Counts(int questions, int chapters, int sections, int contentSlides, int sourceSlides, int totalSlides) { }
    public record OutputPlan(int version, String taskType, String sourceHash, Intent intent, List<UnitPlan> units,
                             List<Omission> omissions, Counts counts, List<String> requiredNodes, int estimatedTurns,
                             int estimatedAttempts, int estimatedOutputBytes) {
        public OutputPlan { units = List.copyOf(units); omissions = List.copyOf(omissions); requiredNodes = List.copyOf(requiredNodes); }
    }
    public record Snapshot(OutputPlan plan, String planHash, int completedUnits, boolean fullSourceRead) { }
}
