package com.example.ailab.ai.orchestration.worker;

import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** 数据库端口返回后才调用 ES；每次扫描只处理一个有限批次。 */
@Component
public class IndexCleanupWorker {
    private final IndexCleanupStorePort store;
    private final KnowledgeIndexPort index;
    private final boolean enabled;
    private final String workerId="cleanup-"+UUID.randomUUID();

    /** 常驻清理沿用入库 Worker 开关；显式运维／测试可受控执行单批。 */
    public IndexCleanupWorker(IndexCleanupStorePort store,KnowledgeIndexPort index,
                              @Value("${lab.ingestion.worker-enabled:false}") boolean enabled){
        this.store=store;this.index=index;this.enabled=enabled;
    }

    /** 关闭自动 Worker 时不会自行访问 ES 或领取事件。 */
    @Scheduled(fixedDelay=3000)
    public void scan(){
        if(!enabled)return;
        try{executeNext();}catch(LabException retryable){/* 延迟重试由持久 Outbox 的 next_attempt_at 驱动。 */}
    }

    /** 返回是否领取到事件；失败保持数据库中的可靠意图，便于延迟恢复。 */
    public synchronized boolean executeNext(){
        var claimed=store.claim(workerId);
        if(claimed.isEmpty())return false;
        var lease=claimed.get();
        try{store.finish(lease,index.cleanup(lease));}
        catch(RuntimeException failure){
            store.fail(lease);
            throw new LabException("SEARCH_UNAVAILABLE","索引清理失败，数据库意图已保留供重试");
        }
        return true;
    }
}
