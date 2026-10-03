package com.example.ailab.ai.model;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.*;
/** 配置模型定义／逻辑 profile／任务路由，HTTP 不接受 endpoint 或模型 ID。 */
@ConfigurationProperties("lab.model")
public record ModelProperties(String mode,Map<String,Definition> models,Map<String,Profile> profiles,Routing routing,boolean externalDataAllowed) {
    public record Definition(String providerId,String endpoint,String modelName,String credentialRef,boolean enabled,Set<String> capabilities,Set<String> qualityTags,Set<String> dataClassifications,int contextWindow,int outputLimit,int dimensions,int timeoutSeconds) {}
    public record Profile(List<String> candidateModelIds,Set<String> requiredCapabilities,Set<String> qualityTags,boolean allowFallback) {}
    public record Routing(String policyVersion,Map<String,String> taskProfiles) {}
    /** 模式显式二选一，候选定义由 Registry 做进一步交叉核验。 */
    public ModelProperties {if(!Set.of("mock","real").contains(mode)||models==null||profiles==null||routing==null)throw new IllegalArgumentException("必须明确 model.mode、models、profiles 和 routing");models=Map.copyOf(models);profiles=Map.copyOf(profiles);}
}
