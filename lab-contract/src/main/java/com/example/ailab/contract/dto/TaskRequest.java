package com.example.ailab.contract.dto;

import java.util.List;
import java.math.BigDecimal;

/** 任务请求：资料工作流使用备注，视频使用主题；不保存执行版本选择。 */
public record TaskRequest(String taskType, String topic, ScopeRequest scope, List<Long> documentIds,
        String idempotencyKey, String strategy, Media.PresentationOptions presentationOptions,
        Media.VideoOptions videoOptions, Learning.QuizOptions quizOptions,
        Learning.CompilationOptions compilationOptions, String remarks) {
    public boolean documentDriven() { return Learning.supports(taskType) || "NOTES_PPT".equals(taskType); }
    public String instructions() { return remarks == null ? "" : remarks; }
    public static boolean supported(String type) {
        return type != null && java.util.Set.of("NOTES_PPT", "NOTES_VIDEO", "QUIZ_GENERATION", "KNOWLEDGE_COMPILATION").contains(type);
    }
    public static boolean retired(String type) { return "FAQ".equals(type) || "RESEARCH_REPORT".equals(type); }
    public TaskRequest {
        documentIds = List.copyOf(documentIds);
        strategy = strategy == null ? ("NOTES_VIDEO".equals(taskType) ? "PLANNED" : "FIXED") : strategy;
        if(topic != null && topic.isBlank()) topic = null;
        remarks = remarks == null || remarks.isBlank() ? null : remarks.strip();
        if("NOTES_PPT".equals(taskType) && presentationOptions == null)
            presentationOptions = new Media.PresentationOptions(0, "default", new BigDecimal("30"), "MIXED");
        if("QUIZ_GENERATION".equals(taskType) && quizOptions == null) quizOptions = Learning.QuizOptions.defaults();
        if("KNOWLEDGE_COMPILATION".equals(taskType) && compilationOptions == null) compilationOptions = Learning.CompilationOptions.defaults();
    }
}
