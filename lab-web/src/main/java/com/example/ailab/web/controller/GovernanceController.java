package com.example.ailab.web.controller;

import com.example.ailab.business.application.GovernanceApplicationService;
import com.example.ailab.contract.dto.*;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** 管理读取经过业务及SQL双层鉴权，没有正文导出或维护写入口。 */
@RestController
@RequestMapping("/api/v1/admin")
public class GovernanceController {
    private final GovernanceApplicationService service;

    /** 只依赖业务用例，Web不访问数据库。 */
    public GovernanceController(GovernanceApplicationService service) { this.service = service; }

    /** 有界游标，只返回访问元数据，禁止浏览器共享缓存。 */
    @GetMapping("/access-audit")
    public ResponseEntity<List<AccessAudit>> audits(Authentication auth,
            @RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.audits(CurrentUser.from(auth), afterId, size));
    }

    /** 聚合不会显示他人的私人运行／任务／问题。 */
    @GetMapping("/metrics")
    public ResponseEntity<OperationalMetrics> metrics(Authentication auth, @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.metrics(CurrentUser.from(auth), days));
    }
}
