package com.example.ailab.app;

import org.springframework.boot.SpringApplication;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 本机 .env 仅作为 UTF-8 文本配置读取，不执行脚本或修改 JVM/操作系统环境变量。
 */
final class LocalEnvironmentLoader {
    // Java 与验收脚本共用同一字段清单。
    private static final Set<String> ALLOWED_KEYS = allowedKeys();

    private static Set<String> allowedKeys() {
        try (var input = LocalEnvironmentLoader.class.getResourceAsStream("/local-environment-keys.txt")) {
            if (input == null) throw new IllegalStateException("缺少本机配置字段清单");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        } catch (IOException error) {
            throw new IllegalStateException("无法读取本机配置字段清单");
        }
    }

    static Path file(String[] args) {
        var values = new org.springframework.boot.DefaultApplicationArguments(args).getOptionValues("lab.env-file");
        if (values != null) {
            if (values.size() != 1 || values.get(0).isBlank())
                throw new IllegalArgumentException("lab.env-file 必须指定一个配置文件路径");
            return Path.of(values.get(0));
        }
        return Path.of(System.getProperty("lab.env-file", System.getenv().getOrDefault("LAB_ENV_FILE", ".env")));
    }

    /**
     * 工具类只提供启动配置装配，不允许创建携带凭证的可打印实例。
     */
    private LocalEnvironmentLoader() {
    }

    /**
     * 在 run 前注入默认属性；显式环境变量、JVM 参数及命令行参数仍可覆盖本机文件。
     */
    static void initialize(SpringApplication application, Path file) {
        application.setDefaultProperties(read(file));
    }

    /**
     * 首个等号前是字段名，之后完整保留为值，支持密码中的 #、等号和反斜杠。
     */
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

    /**
     * 格式错误只报告行号，未知字段及重复项也不回显配置内容。
     */
    private static IllegalArgumentException invalid(int line) {
        return new IllegalArgumentException(".env 第 " + line + " 行格式错误、字段不受支持或重复");
    }
}
