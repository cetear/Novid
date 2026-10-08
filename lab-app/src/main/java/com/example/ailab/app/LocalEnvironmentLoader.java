package com.example.ailab.app;

import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 本机 .env 仅作为 UTF-8 文本配置读取，不执行脚本或修改 JVM/操作系统环境变量。 */
final class LocalEnvironmentLoader {
    // 与启动脚本相同的字段白名单，避免本机文件注入任意 Spring 配置。
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "DB_URL", "DB_USERNAME", "DB_PASSWORD", "BOOTSTRAP_ENABLED", "BOOTSTRAP_USERNAME",
            "BOOTSTRAP_PASSWORD", "SEARCH_ENABLED", "INGESTION_WORKER_ENABLED", "TASK_WORKER_ENABLED",
            "ES_URL", "ES_USERNAME", "ES_PASSWORD", "ES_CA_CERTIFICATE", "ES_TRUST_ALL", "ES_INDEX",
            "MODEL_MODE", "MODEL_EXTERNAL_DATA_ALLOWED", "MODEL_PRIMARY_NAME", "MODEL_BACKUP_NAME",
            "MODEL_BACKUP_ENABLED", "MODEL_BASE_URL", "MODEL_BACKUP_BASE_URL", "MODEL_API_KEY",
            "MODEL_BACKUP_API_KEY", "EMBEDDING_MODEL_NAME", "EMBEDDING_ENABLED", "EMBEDDING_BASE_URL",
            "MODEL_PRIMARY_TIMEOUT_SECONDS", "MODEL_BACKUP_TIMEOUT_SECONDS", "TOOL_TIMEOUT_SECONDS",
            "MEDIA_QUERY_FREE_VERIFIED", "MCP_ENABLED", "SKILLS_ENABLED", "SKILLS_ROOTS", "PPT_SKILL",
            "EMBEDDING_CREDENTIAL_REF", "EMBEDDING_API_KEY", "EMBEDDING_DIMENSIONS", "PORT",
            "MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD", "LOG_PATH", "LOG_LEVEL", "LAB_LOG_LEVEL",
            "LOG_MAX_FILE_SIZE", "LOG_MAX_HISTORY_DAYS", "LOG_MAX_ARCHIVE_SIZE");

    /** 工具类只提供启动配置装配，不允许创建携带凭证的可打印实例。 */
    private LocalEnvironmentLoader() {
    }

    /** 在 run 前注入默认属性；显式环境变量、JVM 参数及命令行参数仍可覆盖本机文件。 */
    static void initialize(SpringApplication application, Path file) {
        application.setDefaultProperties(read(file));
    }

    /** 首个等号前是字段名，之后完整保留为值，支持密码中的 #、等号和反斜杠。 */
    static Map<String, Object> read(Path file) {
        if (Files.notExists(file)) return Map.of();
        var properties = new LinkedHashMap<String, Object>();
        try {
            var lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                // Windows 编辑器可能写入 UTF-8 BOM，只去除文件开头的该标记。
                if (index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                int separator = line.indexOf('=');
                if (separator < 1) throw invalid(index + 1);
                String key = line.substring(0, separator);
                if (!ALLOWED_KEYS.contains(key) || properties.containsKey(key)) throw invalid(index + 1);
                properties.put(key, line.substring(separator + 1));
            }
            return Map.copyOf(properties);
        } catch (IOException error) {
            // 不附带原文或底层异常内容，避免启动日志泄露配置中的敏感信息。
            throw new IllegalStateException("无法读取本机 .env 配置文件，请检查文件权限和 UTF-8 编码");
        }
    }

    /** 格式错误只报告行号，未知字段及重复项也不回显配置内容。 */
    private static IllegalArgumentException invalid(int line) {
        return new IllegalArgumentException(".env 第 " + line + " 行格式错误、字段不受支持或重复");
    }
}
