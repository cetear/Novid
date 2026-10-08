package com.example.ailab.ai.tools.mcp;

import com.example.ailab.ai.tools.*;
import com.example.ailab.contract.dto.ToolDefinition;
import com.example.ailab.contract.error.LabException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.mcp.client.*;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.springframework.stereotype.Component;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.time.Duration;
import java.util.*;

/**
 * MCP发现结果映射为应用工具，执行始终经过统一入口，首版只接入公共只读能力。
 */
@Component
@EnableConfigurationProperties(McpProperties.class)
public final class McpToolSource implements ToolSource, AutoCloseable {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(McpToolSource.class);
    private final List<McpClient> clients = new ArrayList<>();
    private final Map<String, SchemaPreservingTransport> transports = new HashMap<>();
    private final List<RegisteredTool> tools;

    public McpToolSource(McpProperties properties) {
        var registered = new ArrayList<RegisteredTool>();
        var ids = new HashSet<String>();
        try {
            if (properties.enabled()) for (var server : properties.servers()) {
                if (server.id() == null || !server.id().matches("[a-z][a-z0-9_-]{0,40}") || !ids.add(server.id())
                        || server.timeoutSeconds() < 1 || server.timeoutSeconds() > 120 || server.tools().isEmpty()
                        || server.tasks().isEmpty() || !ToolPolicy.TASKS.containsAll(server.tasks()))
                    throw new IllegalArgumentException("MCP配置的标识、任务、超时或工具白名单无效");
                McpClient client = null;
                try {
                    client = create(server);
                    var batch = discover(client, server);
                    clients.add(client);
                    registered.addAll(batch);
                } catch (RuntimeException failure) {
                    if (client != null) close(client);
                    if (server.required()) throw new IllegalStateException("必需MCP服务不可用: " + server.id());
                    LOG.warn("event=mcp.unavailable serverId={} reason={}", server.id(), failure.getClass().getSimpleName());
                }
            }
            // 不允许同源或跨服务别名冲突；与本地工具冲突由全局注册表检查。
            new ToolRegistry(registered);
            tools = List.copyOf(registered);
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    private McpClient create(McpProperties.Server s) {
        Duration timeout = Duration.ofSeconds(s.timeoutSeconds());
        McpTransport transport;
        if ("stdio".equals(s.transport())) {
            if (s.command().isEmpty()) throw new IllegalArgumentException("MCP stdio缺少命令");
            transport = StdioMcpTransport.builder().command(s.command()).environment(s.environment()).logEvents(false).build();
        } else if ("http".equals(s.transport())) {
            var uri = java.net.URI.create(s.url());
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("MCP地址无效");
            transport = StreamableHttpMcpTransport.builder().url(s.url()).customHeaders(s.headers()).timeout(timeout)
                    .followRedirects(false).logRequests(false).logResponses(false).build();
        } else throw new IllegalArgumentException("MCP transport仅支持stdio/http");
        try {
            var preserving = new SchemaPreservingTransport(transport);
            transports.put(s.id(), preserving);
            return DefaultMcpClient.builder().key(s.id()).transport(preserving).protocolVersion("2025-11-25")
                    .initializationTimeout(timeout).toolExecutionTimeout(timeout).autoHealthCheck(false)
                    .logHandler((message) -> {
                    }).subscribeToToolListChanges(false).subscribeToPromptListChanges(false)
                    .subscribeToResourceListChanges(false).multiRoundTripMaxRetries(0)
                    .toolResultConverter((content, isError) -> {
                        if (isError)
                            return ToolExecutionResult.builder().isError(true).resultText("MCP_REMOTE_ERROR").build();
                        var text = new StringBuilder();
                        for (var part : content) {
                            if (!"text".equals(part.get("type")) || !(part.get("text") instanceof String value))
                                throw new LabException("MCP_CONTENT_UNSUPPORTED", "MCP结果含未支持内容");
                            if (text.length() + value.length() > 24000)
                                throw new LabException("BUDGET_EXCEEDED", "MCP输出超限");
                            text.append(value).append('\n');
                        }
                        return ToolExecutionResult.builder().resultText(text.toString()).build();
                    }).build();
        } catch (RuntimeException failure) {
            try {
                transport.close();
            } catch (Exception ignored) {
            }
            throw failure;
        }
    }

    private List<RegisteredTool> discover(McpClient client, McpProperties.Server server) {
        var found = new HashMap<String, dev.langchain4j.agent.tool.ToolSpecification>();
        client.listTools().forEach(t -> {
            if (found.putIfAbsent(t.name(), t) != null) throw new IllegalArgumentException("MCP远端工具重名");
        });
        var result = new ArrayList<RegisteredTool>();
        for (var mapping : new TreeMap<>(server.tools()).entrySet()) {
            var spec = found.get(mapping.getKey());
            if (spec == null) throw new IllegalArgumentException("MCP白名单工具不存在");
            try {
                var schema = transports.get(server.id()).schemas().get(mapping.getKey());
                if (schema == null) throw new IllegalArgumentException("MCP工具缺少参数结构");
                Map<String, Object> parameters = ToolSchema.JSON.convertValue(schema, new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
                var d = new ToolDefinition(mapping.getValue(), "mcp-" + ToolSchema.hash(parameters).substring(0, 16),
                        spec.description() == null || spec.description().isBlank() ? mapping.getValue() : spec.description(), parameters,
                        "tool-outcome-v1", "READ", Set.of("PUBLIC_EXTERNAL_READ"), true, server.timeoutSeconds(), false);
                String endpointHash = ToolSchema.hash(Map.of("transport", server.transport(), "url", Objects.toString(server.url(), ""), "command", server.command()));
                result.add(new RegisteredTool(d, "mcp:" + server.id() + ":" + mapping.getKey() + ":" + endpointHash, server.tasks(), () -> true, (context, args) -> {
                    var answer = client.executeTool(ToolExecutionRequest.builder().id(context.callId()).name(mapping.getKey()).arguments(args.toString()).build());
                    if (answer.isError()) throw new LabException("MCP_REMOTE_ERROR", "MCP工具执行失败");
                    return RegisteredTool.Result.text(answer.resultText());
                }));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("MCP参数结构无效", invalid);
            }
        }
        return result;
    }

    public List<RegisteredTool> tools() {
        return tools;
    }

    private static void close(McpClient client) {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }

    /**
     * 应用停止或装配失败释放客户端及其本地进程。
     */
    @jakarta.annotation.PreDestroy
    public void close() {
        clients.forEach(McpToolSource::close);
        clients.clear();
    }
}
