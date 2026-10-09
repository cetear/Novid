package com.example.ailab.contract.dto;

import java.util.*;
import com.example.ailab.contract.error.LabException;

/** 学习工作流的持久数据协议；来源位置采用UTF-16，模型不能指定权限或执行参数。 */
public final class Learning {
    private Learning() { }
    public static boolean supports(String type) { return Set.of("QUIZ_GENERATION", "KNOWLEDGE_COMPILATION").contains(type); }
    public static String digest(String text) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public record QuizOptions(int questionCount, List<String> questionTypes, String difficulty) {
        public QuizOptions { questionTypes = List.copyOf(questionTypes); }
        public static QuizOptions defaults() { return new QuizOptions(0, List.of("SINGLE_CHOICE", "SHORT_ANSWER"), "MEDIUM"); }
        public void validate() {
            if (questionCount < 0 || questionCount > 512 || questionTypes.isEmpty() || questionTypes.size() > 2
                    || new HashSet<>(questionTypes).size() != questionTypes.size()
                    || !Set.of("SINGLE_CHOICE", "SHORT_ANSWER").containsAll(questionTypes)
                    || difficulty == null || !Set.of("EASY", "MEDIUM", "HARD").contains(difficulty))
                throw LabException.invalid("自测数量0表示自动，显式要求最多512题；题型及难度须合法");
        }
    }
    public record CompilationOptions(String detailLevel, int maximumChapters) {
        public static CompilationOptions defaults() { return new CompilationOptions("DETAILED", 0); }
        public void validate() {
            if (detailLevel == null || !Set.of("CONCISE", "DETAILED").contains(detailLevel)
                    || maximumChapters < 0 || maximumChapters > 512) throw LabException.invalid("整编详略或章节上限不合法");
        }
    }
    public record Limits(int turns, int attempts, int tools) {
        public static Limits video() { return new Limits(24,36,24); }
    }

    public record SourceSlice(String id, long knowledgeBaseId, long documentId, int documentVersion,
                              long processingRevision, String title, int startOffset, int endOffset, String textHash) { }
    public record Item(String id, String sourceId, String category, String content, String quote) { }
    public record Target(String id, String type, List<String> itemIds) { public Target { itemIds = List.copyOf(itemIds); } }
    public record Question(String id, String type, String stem, List<String> options, String answer,
                           String explanation, List<String> itemIds) {
        public Question { options = List.copyOf(options); itemIds = List.copyOf(itemIds); }
    }
    public record Quiz(String title, List<Question> questions) { public Quiz { questions = List.copyOf(questions); } }
    public record Group(String id, String heading, String relation, List<String> itemIds) { public Group { itemIds = List.copyOf(itemIds); } }
    public record ChapterPlan(String id, String title, List<Group> groups) { public ChapterPlan { groups = List.copyOf(groups); } }
    public record Outline(String title, List<ChapterPlan> chapters) { public Outline { chapters = List.copyOf(chapters); } }
    public record Section(String groupId, String body, List<String> itemIds) { public Section { itemIds = List.copyOf(itemIds); } }
    public record Chapter(String id, String title, List<Section> sections) { public Chapter { sections = List.copyOf(sections); } }
    public record Issue(String unitId, String code, String evidence, String suggestion) { }
    public record Review(String decision, List<Issue> issues) { public Review { issues = List.copyOf(issues); } }
    public record Citation(String itemId, SourceSlice source, int quoteStartOffset, int quoteEndOffset, String quote) { }
    /** 原文读取覆盖、知识条目分配和生成质量是独立事实，完整读取不等于人工质量验收。 */
    public record Result(String workflowId, String title, Quiz quiz, Outline outline, List<Chapter> chapters,
                         List<Citation> citations, boolean fullSourceRead, String qualityStatus) {
        public Result { chapters = List.copyOf(chapters); citations = List.copyOf(citations); }
    }
}
