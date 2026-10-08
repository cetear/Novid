package com.example.ailab.ai.tools;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.error.LabException;
import java.util.Set;

/** 工具发现不授予执行权限；当前身份与服务端任务策略每次重查。 */
public final class ToolPolicy {
    public static final Set<String> TASKS = Set.of("KNOWLEDGE_QA", "RESEARCH", "ANALYSIS", "VISUAL_RESEARCH");
    private ToolPolicy() { }
    public static void actor(UserContext actor) {
        if (actor == null || !actor.enabled() || actor.passwordChangeRequired()) throw LabException.denied();
    }
    public static boolean visible(RegisteredTool tool, UserContext actor, String task, boolean enabled) {
        actor(actor);
        if (!TASKS.contains(task)) throw LabException.invalid("未知工具任务");
        // 只允许已实现的能力；新能力必须增加可信的校验实现，不能只添加字符串。
        if (!Set.of("KNOWLEDGE_READ", "PUBLIC_EXTERNAL_READ").containsAll(tool.definition().requiredCapabilities())) return false;
        return enabled && tool.definition().enabled() && tool.tasks().contains(task) && tool.available().getAsBoolean();
    }
}
