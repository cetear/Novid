package com.example.ailab.contract.dto;
import java.util.List;
import com.example.ailab.contract.context.UserContext;
/** ArtifactSnapshot 持久任务契约，不保存可执行 Java 对象。 */
public record ArtifactSnapshot(long artifactId, long requesterUserId, long taskId, String filename, String mime, String content, List<SourceDependency> sourceDependencies){
    /** 防御性复制参数和来源列表。 */
    public ArtifactSnapshot { sourceDependencies=List.copyOf(sourceDependencies); }
}
