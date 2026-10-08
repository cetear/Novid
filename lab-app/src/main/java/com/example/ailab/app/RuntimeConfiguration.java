package com.example.ailab.app;

import com.example.ailab.business.application.AccountApplicationService;
import com.example.ailab.contract.port.PasswordHashPort;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.boot.ApplicationRunner;

/**
 * 运行入口只装配组件，不承载权限与业务规则。
 */
@Configuration
public class RuntimeConfiguration {
    /**
     * S11在创建任何队列Bean之前校验维护配置，避免等ApplicationRunner时后台扫描已启动。
     */
    @Bean
    public static org.springframework.beans.factory.config.BeanFactoryPostProcessor maintenanceStartupIsolation(
            org.springframework.core.env.Environment env) {
        return factory -> {
            String command = env.getProperty("lab.command", "");
            if (java.util.Set.of("governance-cleanup", "fees-reconcile", "init-index", "cleanup-index").contains(command))
                requireMaintenanceIsolation(env);
        };
    }

    /**
     * 单一 RAG 参数源经框架无关契约交给数据端，正式装配不引入反向依赖。
     */
    @Bean
    public com.example.ailab.contract.dto.ContextPolicy contextPolicy(com.example.ailab.ai.rag.RagProperties config) {
        return config.contextPolicy();
    }

    /**
     * BCrypt 单向密码组件通过端口提供给 business。
     */
    @Bean
    public PasswordHashPort passwordHash() {
        var encoder = new BCryptPasswordEncoder(12);
        return new PasswordHashPort() {
            /** 密码只存单向哈希。 */
            public String hash(String password) {
                return encoder.encode(password);
            }

            /** 校验不解密、不记录密码。 */
            public boolean matches(String password, String hash) {
                return encoder.matches(password, hash);
            }
        };
    }

    /**
     * 明确维护命令执行有限索引／费用／治理操作，结束后关闭上下文；治理命令强制核验Worker关闭。
     */
    @Bean
    public ApplicationRunner maintenance(org.springframework.core.env.Environment env,
                                         com.example.ailab.contract.port.KnowledgeIndexPort index,
                                         com.example.ailab.ai.rag.IndexCleanupWorker cleanup,
                                         com.example.ailab.contract.port.FeeStorePort fees,
                                         com.example.ailab.contract.port.GovernanceStorePort governance,
                                         org.springframework.context.ConfigurableApplicationContext context) {
        return args -> {
            String command = env.getProperty("lab.command");
            // S11：所有维护命令都可能改变持久事实，先阻止自动扫描和首次初始化并发运行。
            // 仅检查实际维护入口，普通正式启动继续沿用原有Worker配置。
            if (java.util.Set.of("governance-cleanup", "fees-reconcile", "init-index", "cleanup-index")
                    .contains(command == null ? "" : command)) {
                requireMaintenanceIsolation(env);
            }
            if ("governance-cleanup".equals(command)) {
                // 运维命令一次只清一批；必须关闭业务扫描，不能消费正式用户队列。
                if (env.getProperty("lab.task.worker-enabled", Boolean.class, true)
                        || env.getProperty("lab.ingestion.worker-enabled", Boolean.class, true))
                    throw new IllegalArgumentException("维护命令必须显式关闭业务Worker");
                var settings = new GovernanceMaintenance.Settings(false,
                        env.getProperty("lab.governance.retention-days", Integer.class, 30),
                        env.getProperty("lab.governance.batch-size", Integer.class, 100));
                var now = java.time.Instant.now();
                var result = governance.purge(now, now.minusSeconds(settings.retentionDays() * 86400L), settings.batchSize());
                org.slf4j.LoggerFactory.getLogger(RuntimeConfiguration.class).info("本批维护删除计数：" + result + "；可靠执行／费用／原文保留");
                org.springframework.boot.SpringApplication.exit(context);
            } else if ("fees-reconcile".equals(command)) {
                // 只封存最多100条旧意图；不查询提供方、不退款、不扫描正式任务队列。
                int changed = fees.markUnknownBefore(java.time.Instant.now().minusSeconds(120), 100);
                org.slf4j.LoggerFactory.getLogger(RuntimeConfiguration.class).info("已标记待对账费用：" + changed + "；原预留保持，未提交任何模型请求");
                org.springframework.boot.SpringApplication.exit(context);
            } else if ("init-index".equals(command)) {
                index.initialize();
                org.slf4j.LoggerFactory.getLogger(RuntimeConfiguration.class).info("索引初始化完成（已有索引保留）");
                org.springframework.boot.SpringApplication.exit(context);
            } else if ("cleanup-index".equals(command)) {
                int maximum = env.getProperty("lab.cleanup.max-batches", Integer.class, 20);
                if (maximum < 1 || maximum > 100) throw new IllegalArgumentException("清理批次须在 1～100 之间");
                int batches = 0;
                // 每次仅领取一个有版本边界的事件，不扩大为整索引删除。
                while (batches < maximum && cleanup.executeNext()) batches++;
                org.slf4j.LoggerFactory.getLogger(RuntimeConfiguration.class).info("已执行索引清理批次：" + batches + "；剩余、待重试或失败事件保留在 Outbox");
                org.springframework.boot.SpringApplication.exit(context);
            }
        };
    }

    /**
     * 维护进程必须显式关闭三个队列及初始化；缺省开启视为未隔离，拒绝任何写操作。
     */
    static void requireMaintenanceIsolation(org.springframework.core.env.Environment env) {
        for (String key : java.util.List.of("lab.task.worker-enabled", "lab.ingestion.worker-enabled",
                "lab.media.worker-enabled", "lab.bootstrap.enabled")) {
            boolean defaultEnabled = key.equals("lab.task.worker-enabled") || key.equals("lab.ingestion.worker-enabled");
            if (env.getProperty(key, Boolean.class, defaultEnabled))
                throw new IllegalArgumentException("维护命令必须关闭任务、入库、媒体Worker及首次初始化");
        }
    }

    /**
     * 空系统初始化明确失败时停止启动，不在正常启动插入教学用户。
     */
    @Bean
    public ApplicationRunner bootstrap(BootstrapProperties config, AccountApplicationService accounts,
                                       org.springframework.core.env.Environment environment) {
        return args -> {
            if (config.enabled()) {
                // 与数据库、模型凭证使用同一个 Spring 配置来源，IDEA 直接启动也可读取 .env。
                String password = environment.getProperty("BOOTSTRAP_PASSWORD");
                if (password == null || password.isBlank())
                    throw new IllegalArgumentException("缺少 BOOTSTRAP_PASSWORD 环境变量");
                accounts.bootstrap(config.username(), password);
            }
        };
    }
}
