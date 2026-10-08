package com.example.ailab.ai.orchestration.media;

import com.example.ailab.ai.model.StructuredSchema;
import com.example.ailab.contract.dto.VideoApi;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;

import java.util.*;

/**
 * Planner只返回候选ID与选择理由，具体协议和报价始终由后端填充。
 */
public final class VideoSelectionSchema implements StructuredSchema<VideoApi.Recommendations> {
    private static final ObjectMapper JSON = new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final List<String> shots;

    /**
     * 与已完成脚本镜头顺序绑定，不能漏镜头或新增购买。
     */
    public VideoSelectionSchema(List<String> shots) {
        this.shots = List.copyOf(shots);
    }

    /**
     * 严格结构排除端点、凭证、价格以及自定义执行代码。
     */
    public JsonSchema schema() {
        return JsonSchema.builder().name("video_selection_s09").rootElement(JsonObjectSchema.builder()
                .addProperty("shots", JsonArraySchema.builder().items(JsonObjectSchema.builder().addStringProperty("shotId").addStringProperty("profileId")
                        .addStringProperty("resolution").addStringProperty("audioMode").addIntegerProperty("seconds").addStringProperty("reason")
                        .required("shotId", "profileId", "resolution", "audioMode", "seconds", "reason").additionalProperties(false).build()).build())
                .required("shots").additionalProperties(false).build()).build();
    }

    /**
     * 选择是用户待批准建议，不是模型自行购买授权。
     */
    public String instruction(Set<String> refs) {
        return "仅输出JSON shots数组，每项shotId/profileId/resolution/audioMode/seconds/reason。严格按镜头顺序" + shots + "选择已登记候选，所有镜头必须使用同一个profileId，禁止片内跨API混用或失败后自动换API。比较整片费用、风格连续性、台词、声音与目录兼容性。audioMode仅NATIVE（API输出声音）或NONE（无声）；NONE时脚本台词必须为空。独立配音功能已移除。不得新增API或报价，未知价格如实写在reason。seconds为明确批准上限，不使用自动时长。";
    }

    /**
     * 程序还会在注册表中验证每一条选择。
     */
    public VideoApi.Recommendations validate(String text, Set<String> refs) {
        try {
            if (text.length() > 12000) throw new IllegalArgumentException();
            var result = JSON.readValue(text, VideoApi.Recommendations.class);
            if (!result.shots().stream().map(VideoApi.Recommendation::shotId).toList().equals(shots)
                    || result.shots().stream().anyMatch(r -> !Set.of("NATIVE", "NONE").contains(r.audioMode()))
                    || result.shots().stream().map(VideoApi.Recommendation::profileId).distinct().count() != 1)
                throw new IllegalArgumentException();
            return result;
        } catch (Exception e) {
            throw new LabException("MODEL_STRUCTURED_INVALID", "视频选择必须覆盖实际镜头并统一使用一个登记API");
        }
    }
}
