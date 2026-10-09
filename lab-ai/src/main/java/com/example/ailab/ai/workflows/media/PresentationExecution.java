package com.example.ailab.ai.workflows.media;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;

/**
 * 批准素材之后的本地导出阶段，不调用模型、搜索或付费媒体提供方。
 */
public final class PresentationExecution {
    private final MediaStorePort store;
    private final PresentationPort exporter;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules().enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    /**
     * 编排只依赖契约端口，实际文件工具留在数据层。
     */
    public PresentationExecution(MediaStorePort store, PresentationPort exporter) {
        this.store = store;
        this.exporter = exporter;
    }

    /**
     * 当前成功操作决定GENERATED资产，事实图只复用预览内的批准选择。
     */
    public void execute(TaskLease lease, Media.Preview approved) {
        var operations = store.operations(lease.actor(), approved.taskId());
        var assets = new ArrayList<Media.Asset>();
        for (var unit : approved.units()) {
            if (unit.imageMode().equals("NONE")) continue;
            var matches = approved.assets().stream().filter(a -> a.unitId().equals(unit.unitId()) && a.kind().equals(unit.imageMode())
                    && (a.kind().equals("WEB_SEARCH") || operations.stream().anyMatch(o -> o.unitId().equals(unit.unitId()) && o.state().equals("SUCCEEDED") && Objects.equals(o.assetId(), a.assetId())))).toList();
            if (matches.size() != 1)
                throw new LabException("PPT_IMAGE_MISSING", "当前批准需要的图片未就绪或选择不唯一");
            assets.add(matches.get(0));
        }
        var p = new Media.Preview(approved.taskId(), approved.previewVersion(), approved.planVersion(), approved.hash(), approved.status(), approved.approvalId(), approved.expiresAt(), approved.configurationHash(), approved.currency(), approved.estimatedAmount(), approved.maximumAmount(), approved.units(), approved.sourceDependencies(), approved.coverage(), approved.catalogs(), assets, approved.qualityStatus(), approved.storyboard(),approved.contentPlan());
        String hash = hash(p);
        var saved = store.presentationStart(lease, hash, false);
        if (saved.isPresent() && exporter.intact(saved.get())) {
            store.presentationComplete(lease, saved.get());
            return;
        }
        if (saved.isPresent()) store.presentationStart(lease, hash, true);
        var deadline = store.deadline(lease);
        Instant local = Instant.now().plusSeconds(120);
        if (deadline.isAfter(local)) deadline = local;
        long used = store.assets(lease).stream().mapToLong(a -> a.file().size()).sum();
        var bundle = exporter.export(p, hash, deadline, 209715200L - used);
        store.presentationComplete(lease, bundle);
    }

    /**
     * 摘要绑定原批准、计划、来源、文件checksum及导出版本，不受费用查询／资产下载ID变化影响。
     */
    private String hash(Media.Preview p) {
        try {
            var images = p.assets().stream().map(a -> List.of(a.assetId(), a.unitId(), a.kind(), a.file().checksum(), Objects.toString(a.operationId(), ""), Objects.toString(a.webSource(), ""))).toList();
            var facts = List.of(exporter.version(), p.taskId(), p.previewVersion(), p.planVersion(), p.hash(), p.units(), p.sourceDependencies(), p.coverage(), images);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(facts)));
        } catch (Exception e) {
            throw new LabException("PPT_INPUT_INVALID", "演示文稿输入摘要不可用");
        }
    }
}
