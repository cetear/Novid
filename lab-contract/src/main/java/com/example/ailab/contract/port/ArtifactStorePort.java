package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.ArtifactSnapshot;

/**
 * 私人产物读取端口，不能返回磁盘路径。
 */
public interface ArtifactStorePort {
    /**
     * 本人、发布态与当前来源复核后返回真实产物。
     */
    ArtifactSnapshot artifact(UserContext actor, long id);
    /** 老Markdown下载保持兼容，二进制实现必须再次验证当前归属和来源。 */
    default byte[] bytes(UserContext actor, long id) { return artifact(actor,id).content().getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
