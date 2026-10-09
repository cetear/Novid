package com.example.ailab.ai.workflows.support;

import com.example.ailab.ai.model.*;
import com.example.ailab.contract.dto.Learning.*;
import java.util.*;
import java.util.stream.Collectors;

/** 每个阶段核验业务身份、数量、映射和原文证据；模型不能新增来源或遗漏已分配条目。 */
public final class LearningSchemas {
    private LearningSchemas() { }


    public static RecordSchema<Quiz> quiz(List<Target> targets) {
        return new RecordSchema<>(Quiz.class, "输出title和questions；questions必须按给定targets顺序完整输出。"
                + "每题id、type、itemIds与target完全一致。stem为题干，options为四个不含编号的选项文本；"
                + "SINGLE_CHOICE的answer只能为A/B/C/D；SHORT_ANSWER的options为空，answer为参考答案；explanation解释答案依据。", value -> {
            text(value.title(), 200); require(value.questions().size() == targets.size());
            for (int i = 0; i < targets.size(); i++) {
                var q = value.questions().get(i); var t = targets.get(i);
                require(q.id().equals(t.id()) && q.type().equals(t.type()) && q.itemIds().equals(t.itemIds()));
                text(q.stem(), 1200); text(q.answer(), 1600); text(q.explanation(), 2000);
                if (q.type().equals("SINGLE_CHOICE")) {
                    require(q.options().size() == 4 && Set.of("A", "B", "C", "D").contains(q.answer()));
                    q.options().forEach(option -> text(option, 600));
                } else require(q.options().isEmpty());
            }
        });
    }


    public static RecordSchema<Review> review(Set<String> units) {
        return new RecordSchema<>(Review.class, "输出decision=ACCEPT或REPAIR及issues数组。ACCEPT时issues为空；"
                + "REPAIR时每项含unitId、code、evidence、suggestion，unitId须属于" + new TreeSet<>(units)
                + "。code只用UNSUPPORTED_ANSWER、AMBIGUOUS、DUPLICATE、MISSING_CONTENT、CONFLICT_LOST、BAD_ORGANIZATION。", value -> {
            require(Set.of("ACCEPT", "REPAIR").contains(value.decision()) && value.issues().size() <= 20
                    && (value.decision().equals("ACCEPT") == value.issues().isEmpty()));
            for (var issue : value.issues()) {
                require(units.contains(issue.unitId()) && Set.of("UNSUPPORTED_ANSWER", "AMBIGUOUS", "DUPLICATE",
                        "MISSING_CONTENT", "CONFLICT_LOST", "BAD_ORGANIZATION").contains(issue.code()));
                text(issue.evidence(), 1000); text(issue.suggestion(), 1000);
            }
        });
    }
    public static Review withLocalQuizChecks(Quiz quiz, Review review) {
        var issues = new LinkedHashMap<String, Issue>();
        review.issues().forEach(issue -> issues.putIfAbsent(issue.unitId(), issue));
        var stems = new HashSet<String>();
        for (var q : quiz.questions()) {
            if (!stems.add(normalize(q.stem()))) issues.put(q.id(), new Issue(q.id(), "DUPLICATE", "题干与已有题目重复", "围绕已有考点生成不同的问题"));
            if (q.options().stream().map(LearningSchemas::normalize).distinct().count() != q.options().size())
                issues.put(q.id(), new Issue(q.id(), "AMBIGUOUS", "选项文本重复", "保留唯一正确答案并生成互不重复的选项"));
        }
        return issues.isEmpty() ? review : new Review("REPAIR", List.copyOf(issues.values()));
    }
    private static String normalize(String text) { return text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT); }

    public static void text(String text, int maximum) { require(text != null && !text.isBlank() && text.length() <= maximum); }
    public static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("学习输出不符合业务协议"); }
}
