package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.CompilationStorePort;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class CompilationApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final CompilationStorePort compilations;
    public CompilationApplicationService(KnowledgeAccessPolicy policy, CompilationStorePort compilations) {
        this.policy = policy; this.compilations = compilations;
    }
    public Compilation.SaveStatus prepare(UserContext actor, long taskId, long targetBaseId, String key) {
        DocumentApplicationService.validateKey(key);
        policy.writable(actor, targetBaseId); policy.readable(actor, targetBaseId);
        return compilations.prepare(policy.current(actor), taskId, targetBaseId, key);
    }
    public Compilation.SaveStatus status(UserContext actor, String approvalId) {
        return compilations.status(policy.current(actor), approvalId);
    }
    private AuthorizedKnowledgeScope scope(UserContext actor) {
        return policy.authorize(actor, new ScopeRequest(actor.role() == UserContext.Role.ADMIN ? ScopeRequest.Mode.ALL : ScopeRequest.Mode.SELF, List.of(), null));
    }
    public Compilation.Origin origin(UserContext actor, long id) { return compilations.origin(scope(actor), id); }
    public List<Compilation.Participation> participations(UserContext actor, long id, int page, int size) {
        AccountApplicationService.page(page, size);
        return compilations.participations(scope(actor), id, page * size, size);
    }
}
