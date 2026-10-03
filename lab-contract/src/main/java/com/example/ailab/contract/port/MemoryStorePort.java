package com.example.ailab.contract.port;

import java.util.*;
import java.time.Instant;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.context.UserContext;

/**
 * MemoryStorePort 窄端口，实现由运行入口装配。
 */
public interface MemoryStorePort {
    /**
     * 返回本人偏好，管理员没有全局读取权。
     */
    List<MemorySnapshot> list(UserContext actor);

    /**
     * 明确用户命令创建偏好，不接受模型自动写入。
     */
    MemorySnapshot create(UserContext actor, String content);

    /**
     * 本人偏好按版本更正。
     */
    MemorySnapshot update(UserContext actor, long id, long version, String content);

    /**
     * 本人偏好按版本删除。
     */
    void delete(UserContext actor, long id, long version);
}

