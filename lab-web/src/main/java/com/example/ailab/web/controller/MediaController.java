package com.example.ailab.web.controller;

import com.example.ailab.business.application.MediaApplicationService;
import com.example.ailab.contract.dto.Media;
import com.example.ailab.contract.dto.VideoApi;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * 媒体私人接口只调业务用例，客户端永远不直接传外部查询ID或审批参数。
 */
@RestController
@RequestMapping("/api/v1")
public class MediaController {
    private final MediaApplicationService service;

    public record Edit(@Min(1) @Max(10) int previewVersion, @NotEmpty @Size(max = 512) List<Media.@NotNull Unit> units) {
    }

    public record Decision(boolean approved, @Positive long taskId) {
    }

    public record HumanReview(@Min(1) @Max(10) int previewVersion, boolean accepted,
                              @NotBlank @Size(max = 2000) String note) {
    }

    public record PresentationExport(@Min(1) @Max(10) int previewVersion) {
    }

    /**
     * 已批准素材只排队本地导出，不接受路径、来源或新预算。
     */
    @PostMapping("/tasks/{id}/presentation-export")
    public ResponseEntity<Void> export(Authentication a, @PathVariable long id, @Valid @RequestBody PresentationExport r) {
        service.exportPresentation(CurrentUser.from(a), id, r.previewVersion());
        return ResponseEntity.accepted().header("Cache-Control", "no-store").header("Retry-After", "2").build();
    }

    /**
     * 返回当前本人版本的检查／PNG下载ID，没有结果时204。
     */
    @GetMapping("/tasks/{id}/presentation-check")
    public ResponseEntity<com.example.ailab.contract.dto.Presentation.Bundle> check(Authentication a, @PathVariable long id) {
        return service.presentationCheck(CurrentUser.from(a), id).map(p -> ResponseEntity.ok().header("Cache-Control", "no-store").body(p)).orElseGet(() -> ResponseEntity.noContent().header("Cache-Control", "no-store").build());
    }

    public record VideoSelection(@Min(1) @Max(10) int previewVersion,
                                 @NotEmpty @Size(max = 6) List<VideoApi.@NotNull Recommendation> shots) {
    }

    /**
     * 修改建议后必须对新版本重新批准，不能沿用旧费用确认。
     */
    @PatchMapping("/tasks/{id}/video-selection")
    public ResponseEntity<Media.Preview> selection(Authentication a, @PathVariable long id, @Valid @RequestBody VideoSelection r) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.videoSelection(CurrentUser.from(a), id, r.previewVersion(), r.shots()));
    }

    /**
     * 本人对听看效果作明确验收，提交不同于费用批准。
     */
    @PostMapping("/tasks/{id}/media-review")
    public ResponseEntity<Void> review(Authentication a, @PathVariable long id, @Valid @RequestBody HumanReview r) {
        service.review(CurrentUser.from(a), id, r.previewVersion(), r.accepted(), r.note());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    /**
     * 只装配业务服务，不访问AI SDK或提供方凭证。
     */
    public MediaController(MediaApplicationService service) {
        this.service = service;
    }

    /**
     * 只提供Planner和审批需要的能力目录，不暴露协议模板和密钥。
     */
    @GetMapping("/media/video-capabilities")
    public ResponseEntity<List<com.example.ailab.contract.dto.VideoApi.Capability>> capabilities(Authentication a) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.videoCapabilities(CurrentUser.from(a)));
    }

    /**
     * 登记人物／配音／场景包含版本及映射能力说明。
     */
    @GetMapping("/media/catalogs")
    public ResponseEntity<List<Media.CatalogItem>> catalogs(Authentication a) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.catalogs(CurrentUser.from(a)));
    }

    /**
     * 无预览返回204，等待规划不能被误认为已批准或已生成。
     */
    @GetMapping("/tasks/{id}/preview")
    public ResponseEntity<Media.Preview> preview(Authentication a, @PathVariable long id) {
        return service.preview(CurrentUser.from(a), id).map(p -> ResponseEntity.ok().header("Cache-Control", "no-store").body(p)).orElseGet(() -> ResponseEntity.noContent().header("Cache-Control", "no-store").build());
    }

    /**
     * 只替换同版单位内容，旧批准立即失效。
     */
    @PatchMapping("/tasks/{id}/preview")
    public ResponseEntity<Media.Preview> edit(Authentication a, @PathVariable long id, @Valid @RequestBody Edit r) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.edit(CurrentUser.from(a), id, r.previewVersion(), r.units()));
    }

    /**
     * 查询原始提供方状态不触发生成或额外外部轮询。
     */
    @GetMapping("/tasks/{id}/media-operations")
    public ResponseEntity<List<Media.Operation>> operations(Authentication a, @PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.operations(CurrentUser.from(a), id));
    }

    /**
     * 逐镜头实测时间轴与当前制作阶段，轮询本接口不触发外部查询。
     */
    @GetMapping("/tasks/{id}/media-progress")
    public ResponseEntity<Media.VideoProgress> progress(Authentication a, @PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.videoProgress(CurrentUser.from(a), id));
    }

    /**
     * 计划初版及唯一返工版可查，不覆盖历史证据。
     */
    @GetMapping("/tasks/{id}/media-plans")
    public ResponseEntity<List<Media.PlanSnapshot>> plans(Authentication a, @PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.plans(CurrentUser.from(a), id));
    }

    /**
     * 独立媒体批准路由，taskId只用于本人归属，所有付费参数仍从服务器读取。
     */
    @PostMapping("/media/approvals/{approvalId}/decision")
    public ResponseEntity<Media.Preview> decide(Authentication a, @PathVariable String approvalId, @Valid @RequestBody Decision r) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.decide(CurrentUser.from(a), approvalId, r.approved(), r.taskId()));
    }
}
