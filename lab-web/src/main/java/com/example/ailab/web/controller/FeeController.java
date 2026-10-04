package com.example.ailab.web.controller;

import com.example.ailab.business.application.FeeApplicationService;
import com.example.ailab.contract.dto.*;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.List;

/** 费用查询全部no-store；提交和对账不是公开用户接口，避免伪造用量退款。 */
@RestController
@RequestMapping("/api/v1")
public class FeeController {
    private final FeeApplicationService service;
    /** HTTP层不调用模型或数据库。 */
    public FeeController(FeeApplicationService service) { this.service = service; }
    /** 同一运行只汇总其叶子尝试，后台累计从任务／代次接口读取。 */
    @GetMapping("/runs/{id}/fees")
    public ResponseEntity<FeeSummary> run(Authentication a, @PathVariable String id) { return read(a, "RUN", id); }
    /** 恢复后的全部运行共享本人任务累计金额。 */
    @GetMapping("/tasks/{id}/fees")
    public ResponseEntity<FeeSummary> task(Authentication a, @PathVariable String id) { return read(a, "TASK", id); }
    /** 入库按代次id汇总，不混合不同用户明确创建的新代次。 */
    @GetMapping("/ingestions/{id}/fees")
    public ResponseEntity<FeeSummary> ingestion(Authentication a, @PathVariable String id) { return read(a, "INGESTION", id); }
    /** 管理员仅获币种小计，没有用户／任务钻取。 */
    @GetMapping("/admin/fees")
    public ResponseEntity<List<FeeAggregate>> aggregate(Authentication a, @RequestParam(defaultValue="7") int days) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.aggregate(CurrentUser.from(a), days));
    }
    /** 统一认证及禁止缓存，身份永远取服务端上下文。 */
    private ResponseEntity<FeeSummary> read(Authentication a, String kind, String id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.summary(CurrentUser.from(a), kind, id));
    }
}
