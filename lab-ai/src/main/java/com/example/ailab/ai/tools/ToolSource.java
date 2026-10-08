package com.example.ailab.ai.tools;

import java.util.List;

/** 工具来源只提供显式执行绑定；Skill 不属于工具来源。 */
public interface ToolSource {
    List<RegisteredTool> tools();
}
