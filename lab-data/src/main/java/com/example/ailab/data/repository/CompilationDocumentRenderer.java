package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 仅渲染学习正文；运行信息留在任务。偏移以最终保存文本的UTF-16计算。 */
public final class CompilationDocumentRenderer {
    private CompilationDocumentRenderer() { }
    public record Rendered(String title, String text, List<Compilation.SectionMapping> mappings,
                           List<SourceDependency> sources) { }
    public static Rendered render(Learning.Result result) {
        if (result.quiz() != null || result.outline() == null || result.chapters().isEmpty()
                || !result.fullSourceRead() || result.title() == null || result.title().isBlank()
                || result.title().length() > 200) throw invalid();
        var citations = result.citations().stream().collect(Collectors.toMap(Learning.Citation::itemId, Function.identity(), (a,b) -> { throw invalid(); }));
        var chapters = result.chapters().stream().collect(Collectors.toMap(Learning.Chapter::id, Function.identity(), (a,b) -> { throw invalid(); }));
        var mapped = new HashSet<String>(); var chapterIds = new HashSet<String>();
        var mappings = new ArrayList<Compilation.SectionMapping>();
        var sources = new LinkedHashSet<SourceDependency>();
        var text = new StringBuilder("# ").append(escape(result.title())).append("\n\n");
        var directory=result.outline().chapters();var parents=directory.stream().collect(Collectors.toMap(Learning.ChapterPlan::id,Learning.ChapterPlan::parentId));
        var byId=directory.stream().collect(Collectors.toMap(Learning.ChapterPlan::id,Function.identity()));
        for (var plan : LearningHierarchy.ordered(directory,Learning.ChapterPlan::id,Learning.ChapterPlan::parentId)) {
            var chapter = chapters.get(plan.id());
            if (chapter == null || !chapterIds.add(plan.id()) || !chapter.title().equals(plan.title())) throw invalid();
            int depth=LearningHierarchy.depth(plan.id(),parents);if(depth>4)throw invalid();
            text.append("#".repeat(depth+1)).append(" ").append(escape(chapter.title())).append("\n\n");
            var sections = chapter.sections().stream().collect(Collectors.toMap(Learning.Section::groupId, Function.identity(), (a,b) -> { throw invalid(); }));
            var groupIds = new HashSet<String>();
            for (var group : plan.groups()) {
                var section = sections.get(group.id());
                if (section == null || !groupIds.add(group.id()) || !mapped.add(section.groupId())
                        || section.body() == null || section.body().isBlank() || section.itemIds().isEmpty()
                        || !section.itemIds().equals(group.itemIds())) throw invalid();
                int start = text.length();
                text.append("#".repeat(depth+2)).append(" ").append(escape(group.heading())).append("\n\n");
                if ("CONFLICT".equals(group.relation())) text.append("**资料存在不同说法，需结合来源核对。**\n\n");
                // 保存原Markdown，避免把代码中的尖括号改成HTML实体；展示端沿用原文的净化规则。
                text.append(DocumentMarkdown.below(section.body(),depth+2)).append("\n\n");
                var evidence = section.itemIds().stream().map(id -> {
                    var citation = citations.get(id); if (citation == null) throw invalid();
                    var source = citation.source();
                    sources.add(new SourceDependency(source.knowledgeBaseId(), source.documentId(), source.documentVersion()));
                    return citation;
                }).toList();
                var path=new ArrayList<Compilation.ChapterIdentity>();String owner=chapter.id();
                while(!owner.isEmpty()){var entry=byId.get(owner);path.add(0,new Compilation.ChapterIdentity(entry.id(),entry.parentId(),entry.title()));owner=entry.parentId();}
                mappings.add(new Compilation.SectionMapping(chapter.id(), section.groupId(), start, text.length(), evidence,path));
            }
            if (groupIds.size() != sections.size()) throw invalid();
        }
        if (chapterIds.size() != chapters.size() || sources.isEmpty()) throw invalid();
        String body = text.toString();
        if (body.codePointCount(0, body.length()) > 1000000 || TextWindow.count(body) > 10485760
                || body.codePoints().anyMatch(c -> c == 0 || c < 32 && c != 10 && c != 13 && c != 9))
            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "整编正文超过文档保存限制");
        return new Rendered(result.title(), body, List.copyOf(mappings), List.copyOf(sources));
    }
    private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static LabException invalid() { return new LabException("WORKFLOW_STATE_CONFLICT", "整编结果缺少可信章节、正文或证据"); }
}
