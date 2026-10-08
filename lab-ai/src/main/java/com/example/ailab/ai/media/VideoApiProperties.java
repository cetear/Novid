package com.example.ailab.ai.media;

import com.example.ailab.contract.dto.FeePrice;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/**
 * 声明式HTTP JSON协议注册；只解析字段映射，不运行配置中的脚本或表达式。
 */
@ConfigurationProperties("lab.video-registry")
public record VideoApiProperties(List<Profile> profiles) {
    /**
     * 没登记目标时保持不可用，不偷偷启用固定提供方。
     */
    public VideoApiProperties {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
    }

    public record Profile(String id, int version, boolean enabled, String verificationStatus, String provider,
                          String accountNamespace, String region, String model, String credentialRef, String authHeader,
                          String authPrefix,
                          String submitUrl, String queryUrl, Map<String, String> headers, Map<String, Object> body,
                          Map<String, String> inputs, String idPath, String statusPath, String resultUrlPath,
                          String usagePath,
                          Map<String, String> statuses, List<Integer> durations, List<String> resolutions,
                          List<String> audioModes,
                          Map<String, FeePrice> prices, boolean queryFreeVerified, int pollIntervalSeconds,
                          int maxPolls, int timeoutSeconds, int maxPromptCharacters, String mode) {
        /**
         * 原始模板只在AI模块使用，不能序列化给Planner或前端。
         */
        public Profile {
            mode = mode == null ? "ASYNC_JSON" : mode;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            body = body == null ? Map.of() : Map.copyOf(body);
            inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
            statuses = statuses == null ? Map.of() : Map.copyOf(statuses);
            durations = durations == null ? List.of() : List.copyOf(durations);
            resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
            audioModes = audioModes == null ? List.of() : List.copyOf(audioModes);
            prices = prices == null ? Map.of() : Map.copyOf(prices);
        }
    }
}
