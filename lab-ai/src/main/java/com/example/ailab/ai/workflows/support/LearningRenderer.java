package com.example.ailab.ai.workflows.support;

import com.example.ailab.contract.dto.Learning.*;
import java.util.*;
import java.util.stream.Collectors;

/** 类型化结果渲染为一个Markdown；题目与答案分区，来源和原文位置由程序添加。 */
public final class LearningRenderer {
    private LearningRenderer() { }
    public static String render(Result result) {
        var out = new StringBuilder("# ").append(text(result.title())).append("\n\n");
        out.append("覆盖说明：已完整读取本次限定范围的原文。知识条目组织及模型质检已完成，内容质量仍需本人核对。\n\n");
        var citations = result.citations().stream().collect(Collectors.toMap(Citation::itemId, c -> c));
        if (result.quiz() != null) {
            out.append("## 自测题\n\n");
            for (var q : result.quiz().questions()) {
                out.append("### ").append(q.id()).append(" · ").append(q.type()).append("\n\n").append(text(q.stem())).append("\n\n");
                for (int i = 0; i < q.options().size(); i++) out.append((char)('A' + i)).append(". ").append(text(q.options().get(i))).append("\n");
                out.append("\n");
            }
            out.append("## 答案与解析\n\n");
            for (var q : result.quiz().questions()) out.append("### ").append(q.id()).append("\n\n答案：")
                    .append(text(q.answer())).append("\n\n解析：").append(text(q.explanation())).append("\n\n依据：")
                    .append(refs(q.itemIds(), citations)).append("\n\n");
        } else {
            out.append("## 目录\n\n");
            result.outline().chapters().forEach(c -> out.append("- ").append(text(c.title())).append("\n")); out.append("\n");
            for (int i = 0; i < result.chapters().size(); i++) {
                var chapter = result.chapters().get(i); var plan = result.outline().chapters().get(i);
                out.append("## ").append(text(chapter.title())).append("\n\n");
                for (int j = 0; j < chapter.sections().size(); j++) {
                    var section = chapter.sections().get(j); var group = plan.groups().get(j);
                    out.append("### ").append(text(group.heading())).append("\n\n");
                    if (group.relation().equals("CONFLICT")) out.append("**资料存在不同说法，需结合来源核对。**\n\n");
                    out.append(text(section.body())).append("\n\n来源：").append(refs(section.itemIds(), citations)).append("\n\n");
                }
            }
        }
        out.append("## 来源与原文位置\n\n");
        for (var c : result.citations()) out.append("- ").append(c.itemId()).append("：").append(text(c.source().title()))
                .append(" [D").append(c.source().documentId()).append("v").append(c.source().documentVersion()).append("]，处理代次 ")
                .append(c.source().processingRevision()).append("，UTF-16 [").append(c.quoteStartOffset()).append(',').append(c.quoteEndOffset()).append(")\n");
        return out.toString();
    }
    private static String refs(List<String> ids, Map<String, Citation> citations) {
        return ids.stream().map(id -> { var c = citations.get(id); return id + " [D" + c.source().documentId() + "v" + c.source().documentVersion() + "]"; }).collect(Collectors.joining("、"));
    }
    private static String text(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
