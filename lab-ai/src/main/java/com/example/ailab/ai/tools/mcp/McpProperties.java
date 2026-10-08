package com.example.ailab.ai.tools.mcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.*;

/**
 * 连接和白名单来自服务端配置，远端描述不能覆盖本地执行策略。
 */
@ConfigurationProperties("lab.tools.mcp")
public record McpProperties(boolean enabled, List<Server> servers) {
    public McpProperties {
        servers = servers == null ? List.of() : List.copyOf(servers);
    }

    public record Server(String id, String transport, String url, List<String> command, Map<String, String> headers,
                         Map<String, String> environment, boolean required, int timeoutSeconds,
                         Map<String, String> tools, Set<String> tasks) {
        public Server {
            command = command == null ? List.of() : List.copyOf(command);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            environment = environment == null ? Map.of() : Map.copyOf(environment);
            tools = tools == null ? Map.of() : Map.copyOf(tools);
            tasks = tasks == null ? Set.of() : Set.copyOf(tasks);
            if (timeoutSeconds == 0) timeoutSeconds = 30;
        }

        /**
         * 配置诊断不展开地址、启动参数或凭证。
         */
        @Override
        public String toString() {
            return "McpServer[id=" + id + ", transport=" + transport + ", required=" + required + "]";
        }
    }
}
