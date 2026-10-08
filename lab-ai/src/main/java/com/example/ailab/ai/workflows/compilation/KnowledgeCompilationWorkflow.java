package com.example.ailab.ai.workflows.compilation;

import com.example.ailab.ai.workflows.support.*;
import com.example.ailab.contract.dto.Learning.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;
import java.util.stream.Collectors;

/** 固定整编依赖；目录是业务内容，程序按目录逐章编写，不调用模型执行计划器。 */
public final class KnowledgeCompilationWorkflow {
    public void run(LearningSession session) {
        var options = session.lease.request().compilationOptions(); options.validate();
        var outline = session.generate("outline", "organize", "organize", Map.of("topic", session.lease.request().topic(),
                "options", options, "items", session.items.values()), LearningSchemas.outline(options, session.items));
        session.executor.finish("organize");
        var chapters = new LinkedHashMap<String, Chapter>();
        for (var plan : outline.chapters()) chapters.put(plan.id(), chapter(session, "chapter_" + plan.id(), "generate", plan, List.of()));
        session.executor.finish("generate");
        var review = review(session, "review", outline, chapters);
        if (review.decision().equals("REPAIR")) {
            session.repair(review);
            for (var plan : outline.chapters()) {
                var issues = review.issues().stream().filter(issue -> issue.unitId().equals(plan.id())).toList();
                if (!issues.isEmpty()) chapters.put(plan.id(), chapter(session, "chapter_repair_" + plan.id(), "review", plan, issues));
            }
            review = review(session, "review_repair", outline, chapters);
        }
        if (!review.decision().equals("ACCEPT")) throw new LabException("WORKFLOW_REVIEW_REJECTED", "整编唯一修复后仍未通过质检");
        session.executor.finish("review");
        session.publish(new Result(session.baseline.workflow().workflowId(), outline.title(), null, outline, List.copyOf(chapters.values()),
                session.citations(), true, "MODEL_REVIEW_PASSED_PENDING_HUMAN"));
    }
    private Chapter chapter(LearningSession session, String id, String stage, ChapterPlan plan, List<Issue> issues) {
        var itemIds = plan.groups().stream().flatMap(group -> group.itemIds().stream()).collect(Collectors.toSet());
        var items = session.items.values().stream().filter(item -> itemIds.contains(item.id())).toList();
        return session.generate(id, stage, issues.isEmpty() ? "generate" : "repair", Map.of("chapter", plan, "items", items,
                "options", session.lease.request().compilationOptions(), "issues", issues), LearningSchemas.chapter(plan));
    }
    private Review review(LearningSession session, String id, Outline outline, Map<String, Chapter> chapters) {
        return session.generate(id, "review", "review", Map.of("outline", outline, "chapters", chapters.values(),
                "items", session.items.values(), "sourceText", session.original), LearningSchemas.review(chapters.keySet()));
    }
}
