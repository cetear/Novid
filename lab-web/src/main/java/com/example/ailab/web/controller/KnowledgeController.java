package com.example.ailab.web.controller;

import com.example.ailab.business.application.*;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.web.dto.Requests;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import jakarta.validation.Valid;

import java.util.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 普通知识 CRUD，范围请求与身份分别传入。
 */
@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {
    private final KnowledgeBaseApplicationService bases;
    private final DocumentApplicationService docs;
    private final KnowledgeCapabilityPort capability;
    private final DocumentContextPort context;
    private final DocumentIngestionStorePort ingestion;
    private final DocumentContextApplicationService contextApplication;

    /**
     * 公共端口的真实实现由 app 装配，web 不依赖 data 模块。
     */
    public KnowledgeController(KnowledgeBaseApplicationService b, DocumentApplicationService d, KnowledgeCapabilityPort k, DocumentContextPort c, DocumentIngestionStorePort i, DocumentContextApplicationService contextApplication) {
        bases = b;
        docs = d;
        capability = k;
        context = c;
        ingestion = i;
        this.contextApplication = contextApplication;
    }
    /** S02 章节详情使用绝对 UTF-16 游标，禁止默认把旧章节绑定到新版；私人正文不缓存。 */
    @GetMapping("/documents/{id}/sections/{sectionId}")
    public ResponseEntity<SectionPage> section(Authentication a, @PathVariable long id, @PathVariable String sectionId,
                                               @RequestParam int documentVersion, @RequestParam long processingRevision,
                                               @RequestParam(required = false) Integer afterOffset,
                                               @RequestParam(defaultValue = "4000") int maxTokens) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contextApplication.section(CurrentUser.from(a),id,sectionId,documentVersion,processingRevision,afterOffset,maxTokens));
    }
    /** S02 处理详情不输出 worker／lease／内部路径或原始异常，失败状态与旧激活代次分别展示。 */
    @GetMapping("/documents/{id}/ingestion")
    public ResponseEntity<IngestionMetadata> ingestionMetadata(Authentication a, @PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(contextApplication.ingestion(CurrentUser.from(a),id));
    }

    /**
     * 创建 owner 固定为当前身份。
     */
    @PostMapping("/knowledge-bases")
    public KnowledgeBaseSnapshot create(Authentication a, @Valid @RequestBody Requests.BaseCreate r) {
        return bases.create(CurrentUser.from(a), r.name(), r.description());
    }

    /**
     * 所有角色默认 SELF，跨库必须显式请求。
     */
    @GetMapping("/knowledge-bases")
    public List<KnowledgeBaseSnapshot> list(Authentication a, @RequestParam(defaultValue = "SELF") ScopeRequest.Mode scopeMode, @RequestParam(required = false) List<Long> knowledgeBaseIds, @RequestParam(required = false) Long ownerUserId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return bases.list(CurrentUser.from(a), new ScopeRequest(scopeMode, knowledgeBaseIds, ownerUserId), page, size);
    }

    /**
     * 读取资料元数据的当前权限。
     */
    @GetMapping("/knowledge-bases/{id}")
    public KnowledgeBaseSnapshot read(Authentication a, @PathVariable long id) {
        return bases.read(CurrentUser.from(a), id);
    }

    /**
     * 按 owner 和版本 CAS 修改。
     */
    @PatchMapping("/knowledge-bases/{id}")
    public KnowledgeBaseSnapshot update(Authentication a, @PathVariable long id, @Valid @RequestBody Requests.BaseUpdate r) {
        return bases.update(CurrentUser.from(a), id, r.version(), r.name(), r.description(), r.enabled());
    }

    /**
     * 删除先在 MySQL 失效。
     */
    @DeleteMapping("/knowledge-bases/{id}")
    public void delete(Authentication a, @PathVariable long id, @RequestParam long version) {
        bases.delete(CurrentUser.from(a), id, version);
    }

    /**
     * 上传只说明已登记待处理，HTTP 202 不宣称可搜索。
     */
    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentSnapshot> upload(Authentication a, @RequestParam long knowledgeBaseId, @RequestPart MultipartFile file, @RequestHeader("Idempotency-Key") String key) throws IOException {
        return ResponseEntity.accepted().body(docs.upload(CurrentUser.from(a), knowledgeBaseId, file.getOriginalFilename(), file.getContentType(), file.getBytes(), key));
    }

    /**
     * 文档标题列表同样经过来源授权。
     */
    @GetMapping("/documents")
    public List<DocumentSnapshot> documents(Authentication a, @RequestParam(defaultValue = "SELF") ScopeRequest.Mode scopeMode, @RequestParam(required = false) List<Long> knowledgeBaseIds, @RequestParam(required = false) Long ownerUserId, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return docs.list(CurrentUser.from(a), new ScopeRequest(scopeMode, knowledgeBaseIds, ownerUserId), page, size);
    }

    /**
     * 默认当前版本，原文与来源一并复核。
     */
    @GetMapping("/documents/{id}")
    public DocumentContent document(Authentication a, @PathVariable long id) {
        return docs.read(CurrentUser.from(a), id);
    }

    /**
     * 原文只走认证下载，不发布磁盘路径或静态 URL。
     */
    @GetMapping("/documents/{id}/source")
    public ResponseEntity<byte[]> source(Authentication a, @PathVariable long id) {
        var d = docs.read(CurrentUser.from(a), id);
        return ResponseEntity.ok().contentType(new MediaType("text", "plain", StandardCharsets.UTF_8)).header("Content-Disposition", "attachment; filename=document-" + id + ".txt").header("X-Content-Type-Options", "nosniff").body(d.text().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 目录按当前处理批次查询。
     */
    @GetMapping("/documents/{id}/sections")
    public List<SectionSnapshot> sections(Authentication a, @PathVariable long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        AccountApplicationService.page(page, size);
        var u = CurrentUser.from(a);
        var d = docs.read(u, id);
        return context.sections(capability.authorize(u, new ScopeRequest(ScopeRequest.Mode.SELECTED, List.of(d.document().knowledgeBaseId()), null)), id, page * size, size);
    }

    /**
     * 小片位置及父段 ID 由服务器结构解析确定。
     */
    @GetMapping("/documents/{id}/chunks")
    public List<ChunkSnapshot> chunks(Authentication a, @PathVariable long id, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        AccountApplicationService.page(page, size);
        var u = CurrentUser.from(a);
        var d = docs.read(u, id);
        return context.chunks(capability.authorize(u, new ScopeRequest(ScopeRequest.Mode.SELECTED, List.of(d.document().knowledgeBaseId()), null)), id, page * size, size);
    }

    /**
     * 支持新代次 retry/reprocess 或指定失败代次 recover；客户端不能重置预算。
     */
    @PostMapping("/documents/{id}/index-actions")
    public void reprocess(Authentication a, @PathVariable long id, @RequestParam String action, @RequestParam(required=false) Long processingRevision) {
        if (!action.equals("reprocess") && !action.equals("retry") && !action.equals("recover"))
            throw LabException.invalid("仅支持 retry/reprocess/recover");
        var u = CurrentUser.from(a);
        capability.authorize(u, ScopeRequest.self());
        if(action.equals("recover")) {
            if(processingRevision==null || processingRevision<=0) throw LabException.invalid("recover必须指定正数processingRevision");
            ingestion.recover(u,id,processingRevision);
        } else {
            if(processingRevision!=null) throw LabException.invalid("新处理代次不接收processingRevision");
            ingestion.reprocess(u, id);
        }
    }

    /**
     * 手工修订保留服务端来源。
     */
    @PatchMapping("/documents/{id}")
    public DocumentSnapshot revise(Authentication a, @PathVariable long id, @Valid @RequestBody Requests.DocumentUpdate r) {
        return docs.revise(CurrentUser.from(a), id, r.documentVersion(), r.title(), r.text());
    }

    /**
     * 手工删除不套 AI 确认。
     */
    @DeleteMapping("/documents/{id}")
    public void deleteDocument(Authentication a, @PathVariable long id, @RequestParam int documentVersion) {
        docs.delete(CurrentUser.from(a), id, documentVersion);
    }

    /**
     * 数值来自 SQL。
     */
    @GetMapping("/knowledge/statistics")
    public KnowledgeStatistics statistics(Authentication a, @RequestParam(defaultValue = "SELF") ScopeRequest.Mode scopeMode, @RequestParam(required = false) List<Long> knowledgeBaseIds) {
        return docs.statistics(CurrentUser.from(a), new ScopeRequest(scopeMode, knowledgeBaseIds, null));
    }
}
