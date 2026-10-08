package com.example.ailab.web.controller;

import com.example.ailab.business.application.SessionApplicationService;
import com.example.ailab.contract.dto.SessionMessage;
import com.example.ailab.contract.dto.SessionSnapshot;
import com.example.ailab.web.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

/**
 * 私有会话 HTTP 入口；请求无法指定 userId 或替换服务端当前身份。
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {
    /**
     * 只接受会话标题，未知所有者、版本和模型状态字段由统一 JSON 配置拒绝。
     */
    public record Create(@NotBlank @Size(max = 200) String title) {
    }

    private final SessionApplicationService service;

    /**
     * 接入层只注入业务用例，不能绕过本人存储端口。
     */
    public SessionController(SessionApplicationService service) {
        this.service = service;
    }

    /**
     * 幂等创建返回稳定资源地址，201 不代表已经生成任何回答。
     */
    @PostMapping
    public ResponseEntity<SessionSnapshot> create(Authentication authentication, @Valid @RequestBody Create request,
                                                  @RequestHeader("Idempotency-Key") String key) {
        var session = service.create(CurrentUser.from(authentication), request.title(), key);
        return ResponseEntity.created(URI.create("/api/v1/sessions/" + session.id()))
                .header("Cache-Control", "no-store").body(session);
    }

    /**
     * 列表分页只包含本人会话；ADMIN 身份也遵守同一私人边界。
     */
    @GetMapping
    public ResponseEntity<List<SessionSnapshot>> list(Authentication authentication,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.list(CurrentUser.from(authentication), page, size));
    }

    /**
     * 元数据不携带模型窗口、内部摘要或执行权信息。
     */
    @GetMapping("/{id}")
    public ResponseEntity<SessionSnapshot> read(Authentication authentication, @PathVariable long id) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.read(CurrentUser.from(authentication), id));
    }

    /**
     * 顺序分页输出完整历史；受限来源只显示同序号占位事件。
     */
    @GetMapping("/{id}/messages")
    public ResponseEntity<List<SessionMessage>> messages(Authentication authentication, @PathVariable long id,
                                                         @RequestParam(defaultValue = "0") long afterSeq,
                                                         @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(service.messages(CurrentUser.from(authentication), id, afterSeq, size));
    }

    /**
     * 版本化删除成功返回 204，旧版本或执行中冲突不会伪造删除成功。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Authentication authentication, @PathVariable long id,
                                       @RequestParam long version) {
        service.delete(CurrentUser.from(authentication), id, version);
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
