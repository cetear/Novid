package com.example.ailab.ai.tools;

import com.example.ailab.contract.dto.ToolDefinition;
import com.example.ailab.contract.error.LabException;
import dev.langchain4j.agent.tool.ToolSpecification;
import java.util.*;

/** 不可变工具目录；本地方法与MCP工具使用同一层级的真实执行绑定。 */
public final class ToolRegistry {
    private final Map<String,RegisteredTool> tools;
    private final Map<String,com.networknt.schema.JsonSchema> schemas;
    public ToolRegistry(Collection<RegisteredTool> values) {
        var registered = new LinkedHashMap<String,RegisteredTool>();
        var validators = new LinkedHashMap<String,com.networknt.schema.JsonSchema>();
        for (var tool : values) {
            if (tool == null) throw new IllegalArgumentException("工具不能为空");
            var d=tool.definition();
            if (d.name()==null || !d.name().matches("[a-z_]{1,64}") || d.version()==null || d.version().isBlank()
                    || d.description()==null || d.description().isBlank() || !Set.of("READ","WRITE").contains(d.type())
                    || d.timeoutSeconds()<1 || d.timeoutSeconds()>120 || registered.putIfAbsent(d.name(),tool)!=null)
                throw new IllegalArgumentException("工具注册定义、名称或执行器不合法");
            validators.put(d.name(),ToolSchema.compile(d.parameters())); specification(d);
        }
        tools=Collections.unmodifiableMap(registered); schemas=Map.copyOf(validators);
    }
    /** 仅保留旧注册契约测试入口；此构造不能作为生产执行绑定。 */
    public ToolRegistry(List<ToolDefinition> values, Set<String> executors) {
        this(values.stream().map(d -> {
            if (d==null || !executors.contains(d.name())) throw new IllegalArgumentException("工具缺少执行器");
            return new RegisteredTool(d,"legacy-definition",ToolPolicy.TASKS,()->false,
                    (context,args)->{throw new LabException("TOOL_DISABLED","仅定义目录不可执行");});
        }).toList());
    }
    public List<ToolDefinition> all() { return tools.values().stream().map(RegisteredTool::definition).toList(); }
    public List<RegisteredTool> entries() { return List.copyOf(tools.values()); }
    public ToolDefinition require(String name) { return binding(name).definition(); }
    public RegisteredTool binding(String name) {
        var tool=tools.get(name); if(tool==null) throw new LabException("UNKNOWN_TOOL","未知工具"); return tool;
    }
    public com.fasterxml.jackson.databind.JsonNode arguments(String name,String text) {
        binding(name); return ToolSchema.arguments(text,schemas.get(name));
    }
    /** 快照记录来源和完整定义；恢复时不把另一个同名工具当原执行器。 */
    public Map<String,String> contracts() {
        var values=new TreeMap<String,String>();
        tools.values().forEach(t->values.put(t.definition().name(),ToolSchema.hash(Map.of("source",t.source(),"definition",t.definition(),"tasks",new TreeSet<>(t.tasks())))));
        return Map.copyOf(values);
    }
    public static ToolSpecification specification(ToolDefinition d) { return ToolSchema.specification(d); }
}
