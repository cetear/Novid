package com.example.ailab.web.controller;

import com.example.ailab.business.application.CompilationApplicationService;
import com.example.ailab.contract.dto.Compilation;
import com.example.ailab.web.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class CompilationController {
    public record Prepare(@Positive long knowledgeBaseId) { }
    private final CompilationApplicationService service;
    public CompilationController(CompilationApplicationService service) { this.service = service; }
    @PostMapping("/tasks/{id}/compilation-save/prepare")
    public ResponseEntity<Compilation.SaveStatus> prepare(Authentication actor, @PathVariable long id,
            @Valid @RequestBody Prepare request, @RequestHeader("Idempotency-Key") String key) {
        return response(service.prepare(CurrentUser.from(actor), id, request.knowledgeBaseId(), key));
    }
    @GetMapping("/compilation-saves/{approvalId}")
    public ResponseEntity<Compilation.SaveStatus> status(Authentication actor, @PathVariable String approvalId) {
        return response(service.status(CurrentUser.from(actor), approvalId));
    }
    @GetMapping("/documents/{id}/origin")
    public ResponseEntity<Compilation.Origin> origin(Authentication actor, @PathVariable long id) {
        return response(service.origin(CurrentUser.from(actor), id));
    }
    @GetMapping("/documents/{id}/compilations")
    public ResponseEntity<List<Compilation.Participation>> participations(Authentication actor, @PathVariable long id,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return response(service.participations(CurrentUser.from(actor), id, page, size));
    }
    private static <T> ResponseEntity<T> response(T value) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(value);
    }
}
