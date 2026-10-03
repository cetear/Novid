package com.example.ailab.ai.model;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import com.example.ailab.contract.error.LabException;

import java.util.*;

/**
 * 不可变模型／profile 注册表，能力、质量和数据范围是硬条件。
 */
@Component
public class ModelRegistry {
    private final ModelProperties config;
    private final Environment environment;

    /**
     * 独立 CLI/测试沿用系统配置来源，无需加载本机真实 .env。
     */
    public ModelRegistry(ModelProperties config) {
        this(config, new StandardEnvironment());
    }

    /** 正式装配统一从 Spring 配置读取凭证引用，支持启动时加载的 .env。 */
    @Autowired
    public ModelRegistry(ModelProperties config, Environment environment) {
        this.config = config;
        this.environment = environment;
        if (config.mode().equals("real") && !config.externalDataAllowed())
            throw new IllegalArgumentException("real 模式必须明确接受模型数据外发");
        for (var entry : config.models().entrySet()) {
            var d = entry.getValue();
            if (d.contextWindow() < 1024 || d.outputLimit() < 1 || d.outputLimit() >= d.contextWindow() || d.timeoutSeconds() < 1 || d.timeoutSeconds() > 30)
                throw new IllegalArgumentException("模型窗口或超时配置不合法");
            if (config.mode().equals("real") && d.enabled() && (d.modelName() == null || d.modelName().isBlank() || d.modelName().startsWith("mock-") || d.endpoint() == null || credentialValue(d) == null || credentialValue(d).isBlank()))
                throw new IllegalArgumentException("真实模型缺名称、地址或环境凭证：" + entry.getKey());
        }
        for (var profile : config.profiles().values()) {
            if (profile.candidateModelIds().isEmpty() || new HashSet<>(profile.candidateModelIds()).size() != profile.candidateModelIds().size())
                throw new IllegalArgumentException("profile 候选为空或重复");
            var targets = new HashSet<String>();
            for (String id : profile.candidateModelIds()) {
                var d = config.models().get(id);
                if (d == null) throw new IllegalArgumentException("未知候选 " + id);
                String target = d.endpoint() + "|" + d.modelName();
                if (d.enabled() && !targets.add(target))
                    throw new IllegalArgumentException("两个别名不能作为两个实际主备目标");
            }
        }
        for (String profile : config.routing().taskProfiles().values())
            if (!config.profiles().containsKey(profile)) throw new IllegalArgumentException("路由引用未知 profile");
    }

    /**
     * 按可信 taskType 取候选，未知任务不静默降级。
     */
    public List<String> candidates(String task, Set<String> required) {
        String profileId = config.routing().taskProfiles().get(task);
        if (profileId == null) throw new LabException("MODEL_ROUTE_NOT_FOUND", "任务没有模型路由");
        var p = config.profiles().get(profileId);
        var capabilities = new HashSet<>(required);
        capabilities.addAll(p.requiredCapabilities());
        var result = p.candidateModelIds().stream().filter(id -> {
            var d = definition(id);
            return d.enabled() && d.capabilities().containsAll(capabilities) && d.qualityTags().containsAll(p.qualityTags()) && d.dataClassifications().contains("PRIVATE");
        }).toList();
        if (result.isEmpty()) throw new LabException("MODEL_CAPABILITY_MISMATCH", "没有满足能力、质量和数据范围的模型");
        return p.allowFallback() ? result : List.of(result.get(0));
    }

    /**
     * 定义只能从服务器注册表读取。
     */
    public ModelProperties.Definition definition(String id) {
        var d = config.models().get(id);
        if (d == null) throw new LabException("MODEL_ROUTE_NOT_FOUND", "未知模型");
        return d;
    }

    /** 仅供服务端 SDK 取注册模型的凭证，禁止打印或通过 HTTP 返回该值。 */
    public String credential(String id) {
        return credentialValue(definition(id));
    }

    /** credential-ref 仍是变量名称，不能将配置中的字面密钥误当变量名使用。 */
    private String credentialValue(ModelProperties.Definition definition) {
        String reference = definition.credentialRef();
        if (reference == null || !reference.matches("[A-Z][A-Z0-9_]*")) return null;
        return environment.getProperty(reference);
    }

    /**
     * 模式用于报告实际能力，不自动替换失败真实服务。
     */
    public boolean mock() {
        return config.mode().equals("mock");
    }
}
