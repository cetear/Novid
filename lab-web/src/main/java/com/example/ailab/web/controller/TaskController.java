package com.example.ailab.web.controller;

import com.example.ailab.business.application.TaskApplicationService;
import com.example.ailab.contract.dto.*;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.http.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.net.URI;

/**
 * 持久任务与受认证私人下载；客户端不能覆盖 requester 或 provider 状态。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {
    public record Create(@NotBlank String taskType, @NotBlank @Size(max = 1000) String topic, ScopeRequest scope,
                         @NotEmpty @Size(max = 6) List<@NotNull @Positive Long> documentIds, String strategy,
                         Media.PresentationOptions presentationOptions, Media.VideoOptions videoOptions) {
        /**
         * 旧五参数JSON／测试构造继续使用普通任务语义。
         */
        public Create(String taskType, String topic, ScopeRequest scope, List<Long> documentIds, String strategy) {
            this(taskType, topic, scope, documentIds, strategy, null, null);
        }

        /**
         * 旧四参数构造保持默认固定工作流。
         */
        public Create(String taskType, String topic, ScopeRequest scope, List<Long> documentIds) {
            this(taskType, topic, scope, documentIds, null);
        }
    }

    public record Action(@NotBlank String action) {
    }

    private final TaskApplicationService service;

    /**
     * 接入层只调用业务用例。
     */
    public TaskController(TaskApplicationService service) {
        this.service = service;
    }

    /**
     * 202 仅表示任务登记，不返回伪造最终报告。
     */
    @PostMapping("/tasks")
    public ResponseEntity<TaskSnapshot> create(Authentication a, @Valid @RequestBody Create r, @RequestHeader("Idempotency-Key") String key) {
        var task = service.create(CurrentUser.from(a), new TaskRequest(r.taskType(), r.topic(), r.scope() == null ? ScopeRequest.self() : r.scope(), r.documentIds(), key, r.strategy(), r.presentationOptions(), r.videoOptions()));
        // 立即返回任务和真实进度入口，客户端不要阻塞等待最终产物或重复创建任务。
        return ResponseEntity.accepted().location(URI.create("/api/v1/tasks/" + task.taskId()))
                .header("Retry-After", "2").header("Cache-Control", "no-store").body(task);
    }

    /**
     * 本人查询，ADMIN 不读取他人任务。
     */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<TaskSnapshot> read(Authentication a, @PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.read(CurrentUser.from(a), id));
    }

    /**
     * 只输出已持久计划，无计划返回204，不能将排队当作规划完成。
     */
    @GetMapping("/tasks/{id}/plan")
    public ResponseEntity<TaskPlanSnapshot> plan(Authentication a, @PathVariable long id) {
        return service.plan(CurrentUser.from(a), id)
                .map(p -> ResponseEntity.ok().header("Cache-Control", "no-store").body(p))
                .orElseGet(() -> ResponseEntity.noContent().header("Cache-Control", "no-store").build());
    }

    /**
     * pause/resume/cancel 使用数据库状态机。
     */
    @PostMapping("/tasks/{id}/actions")
    public TaskSnapshot action(Authentication a, @PathVariable long id, @Valid @RequestBody Action r) {
        return service.action(CurrentUser.from(a), id, r.action());
    }

    /**
     * 先复核本人及全部来源，再输出已发布报告，不提供静态路径。
     */
    @GetMapping("/artifacts/{id}")
    public ResponseEntity<byte[]> artifact(Authentication a, @PathVariable long id) {
        var artifact = service.artifact(CurrentUser.from(a), id);
        byte[] bytes = artifact.storageKey() == null ? artifact.content().getBytes(StandardCharsets.UTF_8) : service.artifactBytes(CurrentUser.from(a), id);
        service.artifact(CurrentUser.from(a), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(artifact.mime())).header("Cache-Control", "no-store")
                .header("Content-Disposition", "attachment; filename=artifact-" + id + (artifact.mime().equals(Presentation.MIME) ? ".pptx" : artifact.mime().equals("application/x-subrip") ? ".srt" : artifact.mime().equals("video/mp4") ? ".mp4" : artifact.mime().equals("image/png") ? ".png" : artifact.mime().equals("image/jpeg") ? ".jpg" : artifact.mime().equals("audio/wav") ? ".wav" : artifact.mime().equals("application/json") ? ".json" : ".md"))
                .header("X-Content-Type-Options", "nosniff").header("X-Artifact-Checksum", artifact.checksum() == null ? "" : artifact.checksum()).header("X-Artifact-Revision", Integer.toString(artifact.revision())).contentLength(bytes.length).body(bytes);
    }
}
