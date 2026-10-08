package com.example.ailab.ai.workflows.quiz;

import com.example.ailab.ai.workflows.support.*;
import com.example.ailab.contract.dto.Learning.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;
import java.util.stream.Collectors;

/** 固定出题依赖：考点蓝图、题集、质检，唯一返工只替换问题题目。 */
public final class QuizWorkflow {
    public void run(LearningSession session) {
        var options = session.lease.request().quizOptions(); options.validate();
        var blueprint = session.generate("blueprint", "organize", "organize",
                Map.of("topic", session.lease.request().topic(), "options", options, "items", session.items.values()),
                LearningSchemas.blueprint(options, session.items));
        session.executor.finish("organize");
        var quiz = session.generate("quiz", "generate", "generate", Map.of("blueprint", blueprint, "items", session.items.values(), "options", options),
                LearningSchemas.quiz(blueprint.targets()));
        session.executor.finish("generate");
        var review = review(session, "review", quiz);
        if (review.decision().equals("REPAIR")) {
            session.repair(review);
            var broken = review.issues().stream().map(Issue::unitId).collect(Collectors.toSet());
            var targets = blueprint.targets().stream().filter(t -> broken.contains(t.id())).toList();
            var replacement = session.generate("quiz_repair", "review", "repair", Map.of("targets", targets,
                    "issues", review.issues(), "originalQuiz", quiz, "items", session.items.values(), "options", options), LearningSchemas.quiz(targets));
            var replacements = replacement.questions().stream().collect(Collectors.toMap(Question::id, q -> q));
            quiz = new Quiz(quiz.title(), quiz.questions().stream().map(q -> replacements.getOrDefault(q.id(), q)).toList());
            review = review(session, "review_repair", quiz);
        }
        if (!review.decision().equals("ACCEPT")) throw new LabException("WORKFLOW_REVIEW_REJECTED", "自测题唯一修复后仍未通过质检");
        session.executor.finish("review");
        session.publish(new Result(session.baseline.workflow().workflowId(), quiz.title(), quiz, null, List.of(), session.citations(),
                true, "MODEL_REVIEW_PASSED_PENDING_HUMAN"));
    }
    private Review review(LearningSession session, String id, Quiz quiz) {
        var units = quiz.questions().stream().map(Question::id).collect(Collectors.toSet());
        return session.generate(id, "review", "review", Map.of("quiz", quiz, "items", session.items.values(), "sourceText", session.original),
                LearningSchemas.review(units), review -> LearningSchemas.withLocalQuizChecks(quiz, review));
    }
}
