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
    /** 单一 RAG 参数源经框架无关契约交给数据端，正式装配不引入反向依赖。 */
    @Bean
    public com.example.ailab.contract.dto.ContextPolicy contextPolicy(com.example.ailab.ai.orchestration.rag.RagProperties config) {
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
     * 明确维护命令执行有限索引操作，结束后关闭上下文，不启动业务 Worker。
     */
    @Bean
    public ApplicationRunner maintenance(org.springframework.core.env.Environment env,
                                         com.example.ailab.contract.port.KnowledgeIndexPort index,
                                         com.example.ailab.ai.orchestration.worker.IndexCleanupWorker cleanup,
                                         org.springframework.context.ConfigurableApplicationContext context) {
        return args -> {
            String command = env.getProperty("lab.command");
            if ("init-index".equals(command)) {
                index.initialize();
                System.out.println("索引初始化完成（已有索引保留）");
                org.springframework.boot.SpringApplication.exit(context);
            } else if ("cleanup-index".equals(command)) {
                int maximum = env.getProperty("lab.cleanup.max-batches", Integer.class, 20);
                if (maximum < 1 || maximum > 100) throw new IllegalArgumentException("清理批次须在 1～100 之间");
                int batches = 0;
                // 每次仅领取一个有版本边界的事件，不扩大为整索引删除。
                while (batches < maximum && cleanup.executeNext()) batches++;
                System.out.println("已执行索引清理批次：" + batches + "；剩余、待重试或失败事件保留在 Outbox");
                org.springframework.boot.SpringApplication.exit(context);
            }
        };
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
