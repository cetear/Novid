package com.example.ailab.web.controller;

import com.example.ailab.business.application.AccountApplicationService;
import com.example.ailab.contract.dto.*;
import com.example.ailab.web.dto.Requests;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;

import java.util.List;

/**
 * 认证及受控 ADMIN 账户管理入口。
 */
@RestController
@RequestMapping("/api/v1")
public class AccountController {
    private final AccountApplicationService service;

    /**
     * 接入层只依赖业务用例。
     */
    public AccountController(AccountApplicationService service) {
        this.service = service;
    }

    /**
     * 登录签发原 token，仅在本次响应返回。
     */
    @PostMapping("/auth/login")
    public LoginResult login(@Valid @RequestBody Requests.Login r) {
        return service.login(r.username(), r.password());
    }

    /**
     * 退出撤销当前登录。
     */
    @PostMapping("/auth/logout")
    public void logout(@RequestHeader("Authorization") String token) {
        service.logout(token.substring(7));
    }

    /**
     * 身份来自认证上下文。
     */
    @GetMapping("/auth/me")
    public UserSnapshot me(Authentication a) {
        return service.me(CurrentUser.from(a));
    }

    /**
     * 改密后旧 token 包括本次登录失效，客户端须重新登录。
     */
    @PostMapping("/auth/password")
    public void password(Authentication a, @Valid @RequestBody Requests.Password r) {
        service.changePassword(CurrentUser.from(a), r.oldPassword(), r.newPassword());
    }

    /**
     * 创建 USER，不接收 role/userId。
     */
    @PostMapping("/admin/users")
    public CreatedUser create(Authentication a, @Valid @RequestBody Requests.CreateUser r) {
        return service.create(CurrentUser.from(a), r.username());
    }

    /**
     * 管理列表不返回密码哈希。
     */
    @GetMapping("/admin/users")
    public List<UserSnapshot> list(Authentication a, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(CurrentUser.from(a), page, size);
    }

    /**
     * 角色／禁用更新受最后管理员保护。
     */
    @PatchMapping("/admin/users/{id}")
    public UserSnapshot update(Authentication a, @PathVariable long id, @Valid @RequestBody Requests.UserUpdate r) {
        return service.update(CurrentUser.from(a), id, r.enabled(), r.role());
    }
}
