package com.example.ailab.app;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

/** 不借新连接的池状态采样；满池且有等待者时提供现场资源信号。 */
@Component
public final class DatabasePoolDiagnostics {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DatabasePoolDiagnostics.class);
    private final org.springframework.beans.factory.ObjectProvider<DataSource> sources;
    public DatabasePoolDiagnostics(org.springframework.beans.factory.ObjectProvider<DataSource> sources) { this.sources=sources; }
    @Scheduled(fixedDelayString="${lab.database.pool-diagnostics-ms:10000}")
    public void sample() {
        if(!(sources.getIfAvailable() instanceof HikariDataSource pool)||pool.isClosed())return;
        var state=pool.getHikariPoolMXBean();if(state==null)return;
        int active=state.getActiveConnections(),idle=state.getIdleConnections(),waiting=state.getThreadsAwaitingConnection();
        if(idle==0&&waiting>0)
            LOG.warn("event=db.pool_wait active={} idle={} total={} waiting={} maximum={}",active,idle,state.getTotalConnections(),waiting,pool.getMaximumPoolSize());
    }
}
