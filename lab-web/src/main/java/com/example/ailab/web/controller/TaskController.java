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

/**
 * 持久任务与受认证私人下载；客户端不能覆盖 requester 或 provider 状态。
 */
@RestController
@RequestMapping("/api/v1")
public class TaskController {
    public record Create(@NotBlank String taskType, @NotBlank @Size(max = 1000) String topic, ScopeRequest scope,
                         @NotEmpty @Size(max = 6) List<@NotNull @Positive Long> documentIds) {
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
        return ResponseEntity.accepted().body(service.create(CurrentUser.from(a), new TaskRequest(r.taskType(), r.topic(), r.scope() == null ? ScopeRequest.self() : r.scope(), r.documentIds(), key)));
    }

    /**
     * 本人查询，ADMIN 不读取他人任务。
     */
    @GetMapping("/tasks/{id}")
    public TaskSnapshot read(Authentication a, @PathVariable long id) {
        return service.read(CurrentUser.from(a), id);
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
        return ResponseEntity.ok().contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8)).header("Content-Disposition", "attachment; filename=report-" + artifact.taskId() + ".md").header("X-Content-Type-Options", "nosniff").body(artifact.content().getBytes(StandardCharsets.UTF_8));
    }
}
