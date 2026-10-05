package com.example.ailab.data.persistence.po;

import com.baomidou.mybatisplus.annotation.*;
import com.example.ailab.contract.dto.KnowledgeBaseSnapshot;

@TableName("knowledge_bases")
public final class KnowledgeBasePo {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long ownerUserId;
    public String name;
    public String description;
    public Boolean enabled;
    public Boolean deleted;
    public Long version;

    public KnowledgeBaseSnapshot snapshot() {
        return new KnowledgeBaseSnapshot(id, ownerUserId, name, description, enabled, deleted, version);
    }
}
