package com.example.ailab.ai.workflows.support;

import com.example.ailab.ai.model.*;
import com.example.ailab.contract.dto.Learning.*;
import java.util.*;
import java.util.stream.Collectors;

/** 每个阶段核验业务身份、数量、映射和原文证据；模型不能新增来源或遗漏已分配条目。 */
public final class LearningSchemas {
    private LearningSchemas() { }
    public static RecordSchema<Batch> batch(SourceSlice source, String text, boolean comprehensive) {
        return new RecordSchema<>(Batch.class, "输出items数组，每项id=" + source.id()
                + "-i1等连续编号，sourceId=" + source.id() + "，category为知识主题，content保留知识细节，quote为此页连续原文。"
                + "最多8项；整编提取全部有效知识，自测提取与学习主题相关的考点。", batch -> {
            require(batch.items().size() <= 8 && (!comprehensive || !batch.items().isEmpty()));
            for (int i = 0; i < batch.items().size(); i++) {
                var item = batch.items().get(i);
                require(item.id().equals(source.id() + "-i" + (i + 1)) && item.sourceId().equals(source.id()));
                text(item.category(), 120); text(item.content(), 1600); text(item.quote(), 1500);
                require(text.contains(item.quote()));
            }
        });
    }
    public static RecordSchema<Blueprint> blueprint(QuizOptions options, Map<String, Item> items) {
        return new RecordSchema<>(Blueprint.class, "输出targets数组，恰好" + options.questionCount()
                + "题，id为q1起连续编号，type只使用" + options.questionTypes()
                + "，itemIds为本题考点的已有知识条目编号，题目覆盖不同考点。", value -> {
            require(value.targets().size() == options.questionCount());
            for (int i = 0; i < value.targets().size(); i++) {
                var target = value.targets().get(i);
                require(target.id().equals("q" + (i + 1)) && options.questionTypes().contains(target.type()));
                refs(target.itemIds(), items.keySet());
            }
        });
    }
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
    public static RecordSchema<Outline> outline(CompilationOptions options, Map<String, Item> items) {
        return new RecordSchema<>(Outline.class, "生成统一知识目录title和chapters，最多" + options.maximumChapters()
                + "章；章节id为c1起连续编号，每章含title及groups。group包含唯一id、heading、relation和itemIds。"
                + "relation只用MERGE（重复合并）、COMPLEMENT（互补）、CONFLICT（保留不同说法）。"
                + "所有已有知识条目须恰好分配一次，按主题跨文档组织，保留冲突双方来源。", value -> {
            text(value.title(), 200); require(!value.chapters().isEmpty() && value.chapters().size() <= options.maximumChapters());
            var assigned = new HashSet<String>(); var groups = new HashSet<String>();
            for (int i = 0; i < value.chapters().size(); i++) {
                var chapter = value.chapters().get(i);
                require(chapter.id().equals("c" + (i + 1)) && !chapter.groups().isEmpty()); text(chapter.title(), 200);
                for (var group : chapter.groups()) {
                    require(group.id().matches("g[1-9][0-9]{0,2}") && groups.add(group.id())
                            && Set.of("MERGE", "COMPLEMENT", "CONFLICT").contains(group.relation()));
                    text(group.heading(), 200); refs(group.itemIds(), items.keySet());
                    group.itemIds().forEach(id -> require(assigned.add(id)));
                    if (group.relation().equals("CONFLICT")) require(group.itemIds().size() >= 2);
                }
            }
            require(assigned.equals(items.keySet()));
        });
    }
    public static RecordSchema<Chapter> chapter(ChapterPlan plan) {
        return new RecordSchema<>(Chapter.class, "输出id、title、sections；id和title与章节计划一致。"
                + "sections按groups顺序完整输出，每项groupId和itemIds与对应group一致，body为整合正文。"
                + "MERGE去重，COMPLEMENT保留互补细节，CONFLICT逐一写明不同说法及其来源条目编号。", value -> {
            require(value.id().equals(plan.id()) && value.title().equals(plan.title()) && value.sections().size() == plan.groups().size());
            int size = 0;
            for (int i = 0; i < plan.groups().size(); i++) {
                var section = value.sections().get(i); var group = plan.groups().get(i);
                require(section.groupId().equals(group.id()) && section.itemIds().equals(group.itemIds()));
                text(section.body(), 6000); size += section.body().length();
            }
            require(size <= 10000);
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
    private static void refs(List<String> refs, Set<String> allowed) {
        require(!refs.isEmpty() && refs.size() <= 64 && new HashSet<>(refs).size() == refs.size() && allowed.containsAll(refs));
    }
    public static void text(String text, int maximum) { require(text != null && !text.isBlank() && text.length() <= maximum); }
    public static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("学习输出不符合业务协议"); }
}
