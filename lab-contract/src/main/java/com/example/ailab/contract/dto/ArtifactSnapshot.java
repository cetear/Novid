package com.example.ailab.contract.dto;

import java.util.List;

import com.example.ailab.contract.context.UserContext;

/**
 * ArtifactSnapshot 持久任务契约，不保存可执行 Java 对象。
 */
public record ArtifactSnapshot(long artifactId, long requesterUserId, long taskId, String filename, String mime,
                               String content, List<SourceDependency> sourceDependencies, String kind,
                               Long size, String checksum, String storageKey, String mediaOperationId,int revision,Integer previewVersion) {
    /** 原媒体构造兼容；真实产物版本由仓库查询填充。 */
    public ArtifactSnapshot(long id,long owner,long task,String filename,String mime,String content,List<SourceDependency> sources,String kind,Long size,String checksum,String key,String operation){
        this(id,owner,task,filename,mime,content,sources,kind,size,checksum,key,operation,1,null);
    }
    /** 保留旧报告入口，新增二进制描述只由服务端填充。 */
    public ArtifactSnapshot(long id,long owner,long task,String filename,String mime,String content,List<SourceDependency> sources) {
        this(id,owner,task,filename,mime,content,sources,"MARKDOWN",null,null,null,null,1,null);
    }
    /**
     * 防御性复制参数和来源列表。
     */
    public ArtifactSnapshot {
        sourceDependencies = List.copyOf(sourceDependencies);
    }
}
