package com.example.ailab.app;

import com.example.ailab.data.repository.QueueMaintenanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每轮独立的有限维护事务，不延长领取及心跳的事务。 */
@Component
public final class QueueMaintenance {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(QueueMaintenance.class);
    private final QueueMaintenanceRepository repository;
    private final boolean tasks,ingestion;
    public QueueMaintenance(QueueMaintenanceRepository repository,
            @Value("${lab.task.worker-enabled:false}") boolean tasks,
            @Value("${lab.media.worker-enabled:false}") boolean media,
            @Value("${lab.ingestion.worker-enabled:false}") boolean ingestion) {
        this.repository=repository;this.tasks=tasks||media;this.ingestion=ingestion;
    }
    @Scheduled(initialDelay=5000,fixedDelayString="${lab.database.queue-maintenance-ms:5000}")
    public void run() {
        if(tasks)maintain("tasks",repository::tasks);
        if(ingestion){maintain("ingestion",repository::ingestion);maintain("cleanup",repository::cleanup);}
    }
    private void maintain(String queue,Runnable work) {
        try { work.run(); }
        catch(RuntimeException failure) {
            LOG.warn("event=queue.maintenance_deferred queue={} code={}",queue,
                    com.example.ailab.contract.error.DiagnosticFailure.code(failure));
        }
    }
}
