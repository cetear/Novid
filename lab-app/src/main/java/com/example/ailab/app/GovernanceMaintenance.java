package com.example.ailab.app;

import com.example.ailab.contract.port.GovernanceStorePort;
import com.example.ailab.contract.dto.RetentionResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.*;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * 清理默认关闭，运维明确启用后每小时仅一批；专项测试禁止扫描用户资料。
 */
@Component
@EnableConfigurationProperties(GovernanceMaintenance.Settings.class)
@ConditionalOnProperty(name = "lab.governance.cleanup-enabled", havingValue = "true")
public class GovernanceMaintenance {
    @ConfigurationProperties("lab.governance")
    public record Settings(@DefaultValue("false") boolean cleanupEnabled, @DefaultValue("30") int retentionDays,
                           @DefaultValue("100") int batchSize) {
        /**
         * 历史保留至少七天，限制单次清理和配置范围。
         */
        public Settings {
            if (retentionDays < 7 || retentionDays > 365 || batchSize < 1 || batchSize > 100)
                throw new IllegalArgumentException("运维保留期／清理批次超限");
        }
    }

    private final GovernanceStorePort store;
    private final Settings settings;

    /**
     * 使用唯一正式仓库，不直接写数据库或调用模型。
     */
    public GovernanceMaintenance(GovernanceStorePort store, Settings settings) {
        this.store = store;
        this.settings = settings;
    }

    /**
     * 有限清理失败后保留事实，下一调度再试；不循环清全部积压。
     */
    @Scheduled(initialDelay = 3600000, fixedDelay = 3600000)
    public void scheduled() {
        try {
            var result = execute();
            org.slf4j.LoggerFactory.getLogger(getClass()).info("event=governance.cleanup_complete counts={}", result);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("event=governance.cleanup_failed", com.example.ailab.contract.error.DiagnosticFailure.sanitized(failure));
        }
    }

    /**
     * 截止时间只由服务端生成，不清费用、任务、审批、原文版本或可靠执行记录。
     */
    public RetentionResult execute() {
        var now = Instant.now();
        return store.purge(now, now.minusSeconds(settings.retentionDays() * 86400L), settings.batchSize());
    }
}
