package com.example.ailab.ai.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

import java.util.*;

/**
 * 配置模型定义／逻辑 profile／任务路由，HTTP 不接受 endpoint 或模型 ID。
 */
@ConfigurationProperties("lab.model")
public record ModelProperties(String mode, Map<String, Definition> models, Map<String, Profile> profiles,
                              Routing routing, boolean externalDataAllowed, Failover failover) {
    /**
     * 旧装配入口保留，默认有限故障策略。
     */
    public ModelProperties(String mode, Map<String, Definition> models, Map<String, Profile> profiles,
                           Routing routing, boolean externalDataAllowed) {
        this(mode, models, profiles, routing, externalDataAllowed, null);
    }

    public record Definition(String providerId, String endpoint, String modelName, String credentialRef,
                             boolean enabled, Set<String> capabilities, Set<String> qualityTags,
                             Set<String> dataClassifications, int contextWindow, int outputLimit, int dimensions,
                             int timeoutSeconds, String quotaGroup, String priceRef, int maxConcurrency) {
        /**
         * 旧定义默认共享供应商配额、未知价格和至多四并发。
         */
        public Definition(String providerId, String endpoint, String modelName, String credentialRef,
                          boolean enabled, Set<String> capabilities, Set<String> qualityTags,
                          Set<String> dataClassifications, int contextWindow, int outputLimit, int dimensions,
                          int timeoutSeconds) {
            this(providerId, endpoint, modelName, credentialRef, enabled, capabilities, qualityTags,
                    dataClassifications, contextWindow, outputLimit, dimensions, timeoutSeconds, null, null, 4);
        }

        /**
         * 启动后集合不可变，UNKNOWN价格不表示免费。
         */
        @ConstructorBinding
        public Definition {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
            qualityTags = qualityTags == null ? Set.of() : Set.copyOf(qualityTags);
            dataClassifications = dataClassifications == null ? Set.of() : Set.copyOf(dataClassifications);
            quotaGroup = quotaGroup == null || quotaGroup.isBlank() ? providerId : quotaGroup;
            priceRef = priceRef == null || priceRef.isBlank() ? "UNKNOWN" : priceRef;
            if (maxConcurrency == 0) maxConcurrency = 4;
            if (maxConcurrency < 1 || maxConcurrency > 4) throw new IllegalArgumentException("模型并发须为1～4");
        }
    }

    public record Profile(List<String> candidateModelIds, Set<String> requiredCapabilities, Set<String> qualityTags,
                          boolean allowFallback) {
        /**
         * 排序与硬约束使用不可变快照。
         */
        public Profile {
            candidateModelIds = List.copyOf(candidateModelIds);
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
            qualityTags = qualityTags == null ? Set.of() : Set.copyOf(qualityTags);
        }
    }

    public record Routing(String policyVersion, Map<String, String> taskProfiles, Set<String> selectableProfiles,
                          Set<String> exactModelIds, String qualityVersion) {
        /**
         * 旧配置不自动开放显式选择。
         */
        public Routing(String policyVersion, Map<String, String> taskProfiles) {
            this(policyVersion, taskProfiles, Set.of(), Set.of(), "configured-unverified");
        }

        /**
         * HTTP仅逻辑profile；EXACT仅服务端白名单，质量版本不冒称评测通过。
         */
        @ConstructorBinding
        public Routing {
            if (policyVersion == null || policyVersion.isBlank()) throw new IllegalArgumentException("缺路由版本");
            taskProfiles = Map.copyOf(taskProfiles);
            selectableProfiles = selectableProfiles == null ? Set.of() : Set.copyOf(selectableProfiles);
            exactModelIds = exactModelIds == null ? Set.of() : Set.copyOf(exactModelIds);
            qualityVersion = qualityVersion == null ? "configured-unverified" : qualityVersion;
        }
    }

    /**
     * 固定一跳，不允许配置无限重试或后台探测。
     */
    public record Failover(int maxAttemptsPerLogicalCall, int failureThreshold, int cooldownSeconds) {
        /**
         * 只允许缩小尝试边界，冷却始终有限。
         */
        public Failover {
            if (maxAttemptsPerLogicalCall < 1 || maxAttemptsPerLogicalCall > 3 || failureThreshold < 1
                    || failureThreshold > 3 || cooldownSeconds < 1 || cooldownSeconds > 300)
                throw new IllegalArgumentException("主备策略超出有限边界");
        }
    }

    /**
     * 模式显式二选一，候选定义由 Registry 做进一步交叉核验。
     */
    @ConstructorBinding
    public ModelProperties {
        if (!Set.of("mock", "real").contains(mode) || models == null || profiles == null || routing == null)
            throw new IllegalArgumentException("必须明确 model.mode、models、profiles 和 routing");
        models = Map.copyOf(models);
        profiles = Map.copyOf(profiles);
        if (failover == null) failover = new Failover(3, 3, 30);
    }
}
