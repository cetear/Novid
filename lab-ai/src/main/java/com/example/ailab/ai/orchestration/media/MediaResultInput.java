package com.example.ailab.ai.orchestration.media;

import com.example.ailab.contract.dto.Media;
import com.example.ailab.contract.dto.TextWindow;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashMap;
import java.util.List;

/** PPT 多角色交接的无损表示；仅压缩模型输入，不改变持久结果和输出契约。 */
final class MediaResultInput {
    private static final String ENCODING = "含baseStepId的单位继承前面该stepId中相同unitId的完整单位，"
            + "再用overrides逐字段替换（包括空值和空数组）；其余单位是完整定义。审查须比较各角色还原后的内容。";

    private MediaResultInput() { }

    static String encode(ObjectMapper json, List<Media.WorkerResult> prior) {
        try {
            String original = json.writeValueAsString(prior);
            if (prior.size() < 2) return original;
            ArrayNode results = json.valueToTree(prior);
            var bases = new HashMap<String, BaseUnit>();
            boolean replaced = false;
            for (var result : results) {
                String stepId = result.path("stepId").asText();
                var units = (ArrayNode) result.path("units");
                for (int i = 0; i < units.size(); i++) {
                    var unit = (ObjectNode) units.get(i);
                    String unitId = unit.path("unitId").asText();
                    var base = bases.get(unitId);
                    if (base == null) {
                        bases.put(unitId, new BaseUnit(stepId, unit));
                        continue;
                    }
                    var overrides = json.createObjectNode();
                    unit.fields().forEachRemaining(field -> {
                        if (!field.getValue().equals(base.value().get(field.getKey())))
                            overrides.set(field.getKey(), field.getValue());
                    });
                    var reference = json.createObjectNode().put("unitId", unitId).put("baseStepId", base.stepId());
                    reference.set("overrides", overrides);
                    // 每个引用都指向先前保留的完整单位，避免引用链和循环；变化过多时保留原单位。
                    if (TextWindow.count(reference.toString()) < TextWindow.count(unit.toString())) {
                        units.set(i, reference);
                        replaced = true;
                    }
                }
            }
            if (!replaced) return original;
            var envelope = json.createObjectNode().put("unitEncoding", ENCODING);
            envelope.set("results", results);
            String compact = json.writeValueAsString(envelope);
            return TextWindow.count(compact) < TextWindow.count(original) ? compact : original;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("媒体前序输入序列化失败");
        }
    }

    private record BaseUnit(String stepId, ObjectNode value) { }
}
