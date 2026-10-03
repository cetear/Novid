package com.example.ailab.contract.port;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.ArtifactSnapshot;
/** 私人产物读取端口，不能返回磁盘路径。 */
public interface ArtifactStorePort {
    /** 本人、发布态与当前来源复核后返回真实产物。 */
    ArtifactSnapshot artifact(UserContext actor,long id);
}
