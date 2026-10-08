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

    /**
     * 正式装配统一从 Spring 配置读取凭证引用，支持启动时加载的 .env。
     */
    @Autowired
    public ModelRegistry(ModelProperties config, Environment environment) {
        this.config = config;
        this.environment = environment;
        if (config.mode().equals("real") && !config.externalDataAllowed())
            throw new IllegalArgumentException("real 模式必须明确接受模型数据外发");
        for (var entry : config.models().entrySet()) {
            var d = entry.getValue();
            if (d.contextWindow() < 1024 || d.outputLimit() < 1 || d.outputLimit() >= d.contextWindow() || d.timeoutSeconds() < 1 || d.timeoutSeconds() > 120)
                throw new IllegalArgumentException("模型窗口或超时配置不合法");
            if (config.mode().equals("real") && d.enabled() && (d.modelName() == null || d.modelName().isBlank() || d.modelName().startsWith("mock-") || d.endpoint() == null || credentialValue(d) == null || credentialValue(d).isBlank()))
                throw new IllegalArgumentException("真实模型缺名称、地址或环境凭证：" + entry.getKey());
            if (config.mode().equals("real") && d.enabled()) validateBaseUrl(d.endpoint());
        }
        for (var profile : config.profiles().values()) {
            if (profile.candidateModelIds().isEmpty() || new HashSet<>(profile.candidateModelIds()).size() != profile.candidateModelIds().size())
                throw new IllegalArgumentException("profile 候选为空或重复");
            var targets = new HashSet<String>();
            for (String id : profile.candidateModelIds()) {
                var d = config.models().get(id);
                if (d == null) throw new IllegalArgumentException("未知候选 " + id);
                // 末尾斜杠不同仍是相同服务目标，不能以URL拼写变化伪造备用。
                String target = (d.endpoint() == null ? "" : d.endpoint().replaceAll("/+$", "")) + "|" + d.modelName();
                if (d.enabled() && !targets.add(target))
                    throw new IllegalArgumentException("两个别名不能作为两个实际主备目标");
            }
        }
        for (String profile : config.routing().taskProfiles().values())
            if (!config.profiles().containsKey(profile)) throw new IllegalArgumentException("路由引用未知 profile");
        if (!config.profiles().keySet().containsAll(config.routing().selectableProfiles())
                || !config.models().keySet().containsAll(config.routing().exactModelIds()))
            throw new IllegalArgumentException("显式选择白名单引用未知目标");
    }

    /**
     * SDK会追加具体操作路径；拒绝误填完整操作URL，避免重复拼接404，错误不回显地址。
     */
    private void validateBaseUrl(String endpoint) {
        String path;
        try {
            path = java.net.URI.create(endpoint).getPath();
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("模型基础地址格式不合法");
        }
        if (path != null) {
            path = path.replaceAll("/+$", "");
            if (path.endsWith("/chat/completions") || path.endsWith("/embeddings"))
                throw new IllegalArgumentException("模型endpoint必须是基础地址，不能包含chat/completions或embeddings操作路径");
        }
    }

    /**
     * 按可信 taskType 取候选，未知任务不静默降级。
     */
    public List<String> candidates(String task, Set<String> required) {
        return route(task, Selection.auto(), required).ids();
    }

    /**
     * 服务端选择类型，不把HTTP参数当EXACT或任意模型名称。
     */
    public record Selection(String mode, String value) {
        /**
         * 普通任务自动路由。
         */
        public static Selection auto() {
            return new Selection("AUTO", null);
        }

        /**
         * 逻辑选项仍须经过服务端白名单与任务硬条件。
         */
        public static Selection profile(String id) {
            return new Selection("PROFILE", id);
        }

        /**
         * 仅供可信服务端用例调用，默认禁止自动备用。
         */
        public static Selection exact(String id) {
            return new Selection("EXACT", id);
        }
    }

    /**
     * 脱敏决策仅返回注册ID，实际地址和名称保持内部。
     */
    public record Decision(String profile, String mode, List<String> ids) {
    }

    /**
     * 显式profile也合并原任务条件，不能用经济选项绕过分析质量门槛。
     */
    public Decision route(String task, Selection selection, Set<String> required) {
        String profileId = config.routing().taskProfiles().get(task);
        if (profileId == null) throw new LabException("MODEL_ROUTE_NOT_FOUND", "任务没有模型路由");
        var baseline = config.profiles().get(profileId);
        if (selection == null) selection = Selection.auto();
        var mode = selection.mode();
        if (!Set.of("AUTO", "PROFILE", "EXACT").contains(mode)) throw LabException.invalid("模型选择不合法");
        if (mode.equals("PROFILE")) {
            if (selection.value() == null) throw new LabException("MODEL_SELECTION_DENIED", "逻辑选项为空");
            validateProfile(selection.value());
            profileId = selection.value();
        }
        if (mode.equals("EXACT") && (selection.value() == null || !config.routing().exactModelIds().contains(selection.value())))
            throw new LabException("MODEL_SELECTION_DENIED", "精确目标未获服务端批准");
        var p = config.profiles().get(profileId);
        var capabilities = new HashSet<>(required);
        capabilities.addAll(p.requiredCapabilities());
        capabilities.addAll(baseline.requiredCapabilities());
        var quality = new HashSet<>(p.qualityTags());
        quality.addAll(baseline.qualityTags());
        var candidates = mode.equals("EXACT") ? List.of(selection.value()) : p.candidateModelIds();
        var result = candidates.stream().filter(id -> {
            var d = definition(id);
            return d.enabled() && d.capabilities().containsAll(capabilities) && d.qualityTags().containsAll(quality) && d.dataClassifications().contains("PRIVATE");
        }).toList();
        if (result.isEmpty()) throw new LabException("MODEL_CAPABILITY_MISMATCH", "没有满足能力、质量和数据范围的模型");
        return new Decision(profileId, mode, mode.equals("EXACT") || !p.allowFallback() ? List.of(result.get(0)) : result.stream().limit(2).toList());
    }

    /**
     * 在embedding／会话领取前拒绝非法HTTP逻辑选项，避免付费副作用。
     */
    public void validateProfile(String id) {
        if (id != null && !config.routing().selectableProfiles().contains(id))
            throw new LabException("MODEL_SELECTION_DENIED", "模型逻辑选项未获批准");
    }

    /**
     * 不可变版本和有限故障策略供统一网关读取。
     */
    public ModelProperties configuration() {
        return config;
    }

    /**
     * 定义只能从服务器注册表读取。
     */
    public ModelProperties.Definition definition(String id) {
        var d = config.models().get(id);
        if (d == null) throw new LabException("MODEL_ROUTE_NOT_FOUND", "未知模型");
        return d;
    }

    /**
     * 仅供服务端 SDK 取注册模型的凭证，禁止打印或通过 HTTP 返回该值。
     */
    public String credential(String id) {
        return credentialValue(definition(id));
    }

    /**
     * credential-ref 仍是变量名称，不能将配置中的字面密钥误当变量名使用。
     */
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
