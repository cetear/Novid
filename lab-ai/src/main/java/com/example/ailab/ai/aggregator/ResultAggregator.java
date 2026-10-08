package com.example.ailab.ai.aggregator;

import com.example.ailab.contract.dto.EvidenceBundle;
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
