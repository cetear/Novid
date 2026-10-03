package com.example.ailab.ai.aggregator;

import com.example.ailab.contract.dto.EvidenceBundle;
import com.example.ailab.contract.dto.SourceDependency;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.*;

/**
 * 所有用户可见模型回答的出口；不调用模型、不写业务数据。
 */
@Component
public class ResultAggregator {
    private static final Pattern CITATION = Pattern.compile("\\[(E[0-9]+)\\]");
    private static final Pattern REPORT_CITATION = Pattern.compile("\\[D([0-9]+)v([0-9]+)\\]");

    /**
     * 报告使用类型化版本来源，禁止汇聚新增未交付的 D 引用。
     */
    public String validateReport(String text, List<SourceDependency> sources, boolean mock, boolean requireCitation) {
        validate(text, List.of(), mock);
        var allowed = new HashSet<String>();
        sources.forEach(s -> allowed.add(s.documentId() + ":" + s.documentVersion()));
        var matcher = REPORT_CITATION.matcher(text);
        int count = 0;
        while (matcher.find()) {
            if (!allowed.contains(matcher.group(1) + ":" + matcher.group(2)))
                throw new LabException("CONTEXT_MAPPING_INVALID", "报告包含未交付的版本引用");
            count++;
        }
        if (!mock && requireCitation && !sources.isEmpty() && count == 0)
            throw new LabException("MODEL_INVALID_OUTPUT", "报告缺少有效来源引用");
        return text;
    }

    /**
     * 只允许引用实际交给模型的证据，空回答／假保存事实拒绝。
     */
    public String validate(String text, List<EvidenceBundle> evidence, boolean mock) {
        if (text == null || text.isBlank() || text.length() > 40000)
            throw new LabException("MODEL_INVALID_OUTPUT", "回答结构不合法");
        if (text.contains("笔记已保存") || text.contains("已成功保存笔记"))
            throw new LabException("MODEL_INVALID_OUTPUT", "模型不能伪造写入成功事实");
        var ids = new HashSet<String>();
        evidence.forEach(e -> ids.add(e.evidenceId()));
        var matcher = CITATION.matcher(text);
        int citations = 0;
        while (matcher.find()) {
            if (!ids.contains(matcher.group(1)))
                throw new LabException("CONTEXT_MAPPING_INVALID", "回答包含未交付证据引用");
            citations++;
        }
        if (!mock && !evidence.isEmpty() && citations == 0)
            throw new LabException("MODEL_INVALID_OUTPUT", "知识回答缺少有效引用");
        // 内容支持质量另用固定评测验收；引用属于集合本身不证明答案正确。
        return text;
    }
}
