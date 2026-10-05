package com.example.ailab.ai.config;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.ailab.ai.model.ModelProperties;
import com.example.ailab.ai.orchestration.rag.RagProperties;

/**
 * AI 六层在单模块内部装配，不扫描业务数据实现。
 */
@Configuration
@ComponentScan("com.example.ailab.ai")
@EnableConfigurationProperties({ModelProperties.class, RagProperties.class, com.example.ailab.ai.model.FeeProperties.class, com.example.ailab.ai.model.MediaProperties.class, com.example.ailab.ai.model.VideoApiProperties.class})
public class AiConfiguration {
}
