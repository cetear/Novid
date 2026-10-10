package com.example.ailab.contract.context;

import com.example.ailab.contract.dto.TraceNode;
import com.example.ailab.contract.dto.TracePayload;
import com.example.ailab.contract.dto.SourceDependency;
import com.example.ailab.contract.error.LabException;

import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * 单次执行的有界节点集合；父上下文不可变并由异步入口显式传递。
 */
public final class TraceContext {
    private final State state;
    private final String parent;
    private final String step;
    private final String agent;

    private static final class State {
        final List<TraceNode> nodes = new ArrayList<>();
        final int maximum;
        final java.util.function.BiConsumer<List<TraceNode>, Boolean> finish;
        boolean incomplete, closed;
        boolean payloadsEnabled;
        int payloadChars;
        final Set<SourceDependency> sources = new LinkedHashSet<>();

        /**
         * 状态只持有限额和类型化完成回调，不访问业务存储。
         */
        State(int maximum, java.util.function.BiConsumer<List<TraceNode>, Boolean> finish) {
            this.maximum = maximum;
            this.finish = finish;
        }
    }

    /**
     * 创建根上下文；完成回调可持久化本人有界快照，外部导出必须过滤正文。
     */
    public TraceContext(int maximum, java.util.function.BiConsumer<List<TraceNode>, Boolean> finish) {
        this(new State(maximum, finish), null, null, null);
    }

    /**
     * 子上下文共享限额与终止状态，但不共享可变角色身份。
     */
    private TraceContext(State state, String parent, String step, String agent) {
        this.state = state;
        this.parent = parent;
        this.step = step;
        this.agent = agent;
    }

    /**
     * 缺追踪装配仍守业务预算，并明确观测不可用。
     */
    public static TraceContext disabled(String id) {
        var c = new TraceContext(0, (n, i) -> {
        });
        c.drop();
        return c;
    }

    /**
     * 截断与队列故障统一标不完整，不推断未记录步骤未发生。
     */
    public void drop() {
        synchronized (state) {
            state.incomplete = true;
        }
    }

    /**
     * 调用方可及时显示失败；落库前的运行记录始终为不完整。
     */
    public boolean incomplete() {
        synchronized (state) {
            return state.incomplete;
        }
    }

    /** 工作流绑定已授权来源后才允许采集内容，来源并集覆盖全部并行节点。 */
    public void payloadSources(List<SourceDependency> sources) {
        synchronized (state) {
            if (state.closed) return;
            state.sources.addAll(sources);
            state.payloadsEnabled = true;
        }
    }

    public boolean capturesPayloads() {
        synchronized (state) { return state.maximum > 0 && state.payloadsEnabled && !state.closed && state.payloadChars < 524288; }
    }

    /**
     * 登记真实开始事实，长度和字符白名单拒绝正文或凭证误传。
     */
    public Span span(String type, String name) {
        return span(type, name, step, agent, List.of());
    }

    /**
     * 角色和依赖只能由程序调度器生成，不能从模型正文直接拼接。
     */
    public Span span(String type, String name, String step, String agent, List<String> dependencies) {
        synchronized (state) {
            if (state.closed || state.nodes.size() >= state.maximum) {
                state.incomplete = true;
                return new Span(this, -1, null);
            }
            String id = UUID.randomUUID().toString();
            int index = state.nodes.size();
            state.nodes.add(new TraceNode(id, parent, safe(type), safe(name), safe(step), safe(agent), dependencies, index + 1, "RUNNING", Instant.now(), null, null, null, null, null, null, null, 0, null, null, "UNKNOWN", null));
            return new Span(new TraceContext(state, id, step, agent), index, id);
        }
    }

    /**
     * 通用短步骤自动记录成功与稳定错误码，异常正文不进入追踪。
     */
    public <T> T call(String type, String name, Supplier<T> action) {
        try (var s = span(type, name)) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                s.fail(e);
                throw e;
            }
        }
    }

    /**
     * 完成后有限快照，不等待外部观测，不把迟到节点拼入已结束执行。
     */
    public void finish() {
        synchronized (state) {
            if (state.closed) return;
            state.closed = true;
            if (state.nodes.stream().anyMatch(n -> n.endedAt() == null)) state.incomplete = true;
            try {
                var sources = List.copyOf(state.sources);
                state.finish.accept(state.nodes.stream().map(n -> n.payloads(n.input(), n.output(),
                        n.input() == null && n.output() == null ? List.of() : sources)).toList(), state.incomplete);
            } catch (RuntimeException ignored) {
                state.incomplete = true;
            }
        }
    }

    /**
     * 元数据使用逻辑标识，禁止URL、空格、控制字符或任意文本。
     */
    private static String safe(String s) {
        return s == null ? null : s.matches("[A-Za-z0-9_./:-]{1,128}") && !s.contains("://") ? s : "REDACTED";
    }

    public final class Span implements AutoCloseable {
        private final TraceContext context;
        private final int index;
        private final String id;
        private String status = "SUCCESS", error, model, task, profile, policy, reason, usage = "UNKNOWN", toolHash;
        private int attempt;
        private Integer in, out;
        private boolean ended;
        private TracePayload input, output;

        /** 只接受已过滤的文本；每侧16K字符、每运行512K字符，观测不改变业务结果。 */
        public void input(String content) { input = payload(content); }
        public void output(String content) { output = payload(content); }
        private TracePayload payload(String content) {
            synchronized (state) {
                if (index < 0 || ended || !context.capturesPayloads() || content == null) return null;
                int length = Math.min(content.length(), Math.min(16384, 524288 - state.payloadChars));
                if (length > 0 && length < content.length() && Character.isHighSurrogate(content.charAt(length - 1))) length--;
                state.payloadChars += length;
                return new TracePayload(content.substring(0, length), length < content.length(), content.length());
            }
        }

        /**
         * 不创建空节点；截断后的子上下文继续共享同一不完整状态。
         */
        private Span(TraceContext context, int index, String id) {
            this.context = context;
            this.index = index;
            this.id = id;
        }

        /**
         * 不可变父上下文交给异步工作线程，线程不继承调用者身份。
         */
        public TraceContext context() {
            return context;
        }

        /**
         * 调度器保存节点ID用于实际依赖，截断时保留一个可识别的缺失标识。
         */
        public String id() {
            return id == null ? "missing-node" : id;
        }

        /**
         * 仅模型叶节点保存提供方用量，父角色不重复计数。
         */
        public void model(String model, String task, String profile, String policy, String reason, int attempt, Integer in, Integer out, String usage) {
            this.model = safe(model);
            this.task = safe(task);
            this.profile = safe(profile);
            this.policy = safe(policy);
            this.reason = safe(reason);
            this.attempt = attempt;
            this.in = in;
            this.out = out;
            this.usage = safe(usage);
        }

        /**
         * 工具callId只留摘要，避免提供方ID中夹带用户内容。
         */
        public void tool(String id) {
            try {
                toolHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
                context.drop();
            }
        }

        /**
         * 失败仅保留程序错误码，提供方异常信息可能含凭证。
         */
        public void fail(Throwable e) {
            status = "FAILED";
            error = e instanceof LabException l ? safe(l.code()) : "INTERNAL_ERROR";
        }

        /**
         * 复用与超时等事实由调度程序指定，不采纳模型自报成功。
         */
        public void status(String status) {
            this.status = safe(status);
        }

        /**
         * 节点终态只提交一次；执行结束后的迟到完成不能覆盖其快照。
         */
        public void close() {
            synchronized (state) {
                if (ended) return;
                ended = true;
                if (index < 0 || state.closed) {
                    state.incomplete = true;
                    return;
                }
                var n = state.nodes.get(index);
                state.nodes.set(index, new TraceNode(n.spanId(), n.parentSpanId(), n.type(), n.name(), n.stepId(), n.agentId(), n.dependsOn(), n.sequence(), status, n.startedAt(), Instant.now(), error, model, task, profile, policy, reason, attempt, in, out, usage, toolHash, input, output, List.of()));
            }
        }
    }
}
