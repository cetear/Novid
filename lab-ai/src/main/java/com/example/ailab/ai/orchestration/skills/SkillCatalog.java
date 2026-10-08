package com.example.ailab.ai.orchestration.skills;

import com.example.ailab.ai.tools.ToolSchema;
import com.example.ailab.contract.dto.TaskExecutionBinding;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 启动加载Skill目录；任务执行只使用已保存快照，不重新读取磁盘指令。
 */
@Component
public final class SkillCatalog {
    public record Skill(String id, String description, String version, Map<String, String> resources,
                        Set<String> allowedTools) {
        public Skill {
            resources = Map.copyOf(resources);
            allowedTools = Set.copyOf(allowedTools);
        }
    }

    private final Map<String, Skill> skills;
    private final String pptSkill;
    private final boolean enabled;

    public SkillCatalog(@Value("${lab.skills.enabled:true}") boolean enabled,
                        @Value("${lab.skills.roots:}") String roots,
                        @Value("${lab.skills.ppt-skill:ppt-generation}") String pptSkill) {
        this.enabled = enabled;
        this.pptSkill = pptSkill;
        var values = new LinkedHashMap<String, Skill>();
        if (enabled) {
            loadBuiltins(values);
            var external = new HashMap<String, Skill>();
            if (!roots.isBlank()) for (String root : roots.split(";")) loadExternal(Path.of(root.trim()), external);
            values.putAll(external); // 显式配置外部根目录时，以外部版本覆盖同名内置版本。
            if (!values.containsKey(pptSkill)) throw new IllegalArgumentException("PPT Skill未配置");
        }
        skills = Map.copyOf(values);
    }

    private void loadBuiltins(Map<String, Skill> values) {
        try {
            var index = new ClassPathResource("ai/skills/index.txt");
            String listing = read(index);
            for (String id : listing.lines().filter(l -> !l.isBlank()).toList()) {
                validId(id);
                var files = new TreeMap<String, String>();
                String base = "ai/skills/" + id + "/";
                var manifest = new ClassPathResource(base + "resources.txt");
                for (String name : read(manifest).lines().filter(l -> !l.isBlank()).toList()) {
                    validResource(name);
                    files.put(name, read(new ClassPathResource(base + name)));
                }
                add(values, parse(id, files));
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("内置Skill读取失败", error);
        }
    }

    private static String read(org.springframework.core.io.Resource resource) throws java.io.IOException {
        try (var stream = resource.getInputStream()) {
            byte[] bytes = stream.readNBytes(32769);
            if (bytes.length > 32768) throw new IllegalArgumentException("Skill文件超限");
            return new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    private void loadExternal(Path configured, Map<String, Skill> values) {
        try {
            Path root = configured.toRealPath();
            try (var directories = Files.walk(root, 4)) {
                var candidates = directories.limit(2001).toList();
                if (candidates.size() > 2000) throw new IllegalArgumentException("Skill扫描目录超限");
                for (var file : candidates)
                    if (file.getFileName().toString().equals("SKILL.md") && Files.isRegularFile(file)) {
                        Path folder = file.getParent().toRealPath();
                        if (!folder.startsWith(root)) throw new IllegalArgumentException("Skill目录越界");
                        var resources = new TreeMap<String, String>();
                        try (var children = Files.walk(folder, 3)) {
                            var names = children.filter(p -> Files.isRegularFile(p) && p.toString().endsWith(".md")).limit(33).toList();
                            if (names.size() > 32) throw new IllegalArgumentException("Skill资源数量超限");
                            for (var child : names) {
                                Path real = child.toRealPath();
                                if (!real.startsWith(folder) || Files.size(real) > 32768)
                                    throw new IllegalArgumentException("Skill资源越界或超限");
                                String name = folder.relativize(child).toString().replace('\\', '/');
                                validResource(name);
                                resources.put(name, Files.readString(real, StandardCharsets.UTF_8).replace("\r\n", "\n"));
                            }
                        }
                        add(values, parse(folder.getFileName().toString(), resources));
                    }
            }
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("外部Skill目录读取失败", error);
        }
    }

    private static void add(Map<String, Skill> values, Skill skill) {
        if (values.putIfAbsent(skill.id(), skill) != null) throw new IllegalArgumentException("同源Skill重名");
    }

    private static Skill parse(String directory, Map<String, String> resources) {
        String text = resources.get("SKILL.md");
        if (text == null || !text.startsWith("---\n")) throw new IllegalArgumentException("Skill缺少frontmatter");
        int end = text.indexOf("\n---\n", 4);
        if (end < 0) throw new IllegalArgumentException("Skill frontmatter未闭合");
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        Object document = new Yaml(new SafeConstructor(options)).load(text.substring(4, end));
        if (!(document instanceof Map<?, ?> metadata)) throw new IllegalArgumentException("Skill元数据无效");
        String id = Objects.toString(metadata.get("name"), "");
        validId(id);
        String description = Objects.toString(metadata.get("description"), "");
        if (!id.equals(directory) || description.isBlank() || description.length() > 1024)
            throw new IllegalArgumentException("Skill名称或描述无效");
        String version = metadata.get("metadata") instanceof Map<?, ?> extra ? Objects.toString(extra.get("version"), "") : "";
        if (version.isBlank()) throw new IllegalArgumentException("项目Skill需metadata.version");
        var tools = new TreeSet<String>();
        Object allowed = metadata.get("allowed-tools");
        if (!(allowed instanceof String names) || names.isBlank())
            throw new IllegalArgumentException("项目Skill需显式allowed-tools");
        for (String name : names.trim().split("\\s+")) {
            if (!name.matches("[a-z_]{1,64}")) throw new IllegalArgumentException("Skill工具别名无效");
            tools.add(name);
        }
        int total = resources.values().stream().mapToInt(String::length).sum();
        if (total > 65536) throw new IllegalArgumentException("Skill总内容超限");
        // 主指令中引用的Markdown资源必须存在，按需分配给角色时不会再访问外部路径。
        var references = java.util.regex.Pattern.compile("references/[a-zA-Z0-9_/-]+\\.md").matcher(text);
        while (references.find())
            if (!resources.containsKey(references.group())) throw new IllegalArgumentException("Skill引用资源不存在");
        var body = new TreeMap<>(resources);
        body.put("SKILL.md", text.substring(end + 5).strip());
        return new Skill(id, description, version, body, tools);
    }

    private static void validId(String id) {
        if (!id.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || id.length() > 64)
            throw new IllegalArgumentException("Skill标识无效");
    }

    private static void validResource(String name) {
        if (name.startsWith("/") || name.contains("..") || name.contains("\\") || !name.matches("[a-zA-Z0-9_/-]+\\.md"))
            throw new IllegalArgumentException("Skill资源路径无效");
    }

    public boolean enabled() {
        return enabled;
    }

    public List<Skill> all() {
        return List.copyOf(skills.values());
    }

    /**
     * 新任务固定目录契约，所需别名不存在时在首次模型调用前失败。
     */
    public TaskExecutionBinding pptBinding(Map<String, String> contracts) {
        var skill = skills.get(pptSkill);
        if (skill == null) throw new LabException("SKILL_UNAVAILABLE", "PPT Skill未启用");
        if (!contracts.keySet().containsAll(skill.allowedTools()))
            throw new LabException("SKILL_TOOL_UNAVAILABLE", "Skill依赖的工具未注册");
        var selected = new TreeMap<String, String>();
        skill.allowedTools().forEach(name -> selected.put(name, contracts.get(name)));
        return new TaskExecutionBinding(1, skill.id(), skill.version(), hash(skill.resources(), skill.allowedTools()), skill.resources(), skill.allowedTools(), selected, "ppt-actions-v1");
    }

    private static String hash(Map<String, String> resources, Set<String> allowed) {
        return ToolSchema.hash(Map.of("resources", resources, "allowedTools", new TreeSet<>(allowed)));
    }

    public static void verify(TaskExecutionBinding binding) {
        if (binding.schemaVersion() != 1 || !"ppt-actions-v1".equals(binding.actionPolicyVersion())
                || !binding.skillHash().equals(hash(binding.resources(), binding.allowedTools())) || !binding.resources().containsKey("SKILL.md")
                || !binding.toolContracts().keySet().equals(binding.allowedTools()))
            throw new LabException("SKILL_SNAPSHOT_INVALID", "Skill快照不完整或版本不兼容");
    }

    /**
     * 单独传递可信指令；原始资料和前序正文继续作为低信任任务数据。
     */
    public static String instructions(TaskExecutionBinding binding, String action) {
        if (binding == null) return "";
        verify(binding);
        String resource = "references/" + action + ".md";
        return "\n已绑定Skill=" + binding.skillId() + "@" + binding.skillVersion() + "\n" + binding.resources().get("SKILL.md")
                + "\n角色指引：\n" + binding.resources().getOrDefault(resource, "") + "\n程序Schema、权限和批准状态以服务端校验为准。";
    }
}
