package com.example.ailab.ai.config;

import org.springframework.context.annotation.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.ailab.ai.model.ModelProperties;
import com.example.ailab.ai.rag.RagProperties;

/**
 * 装配 AI 执行机制、业务工作流与共享能力，不扫描业务数据实现。
 */
@Configuration
@ComponentScan("com.example.ailab.ai")
@EnableConfigurationProperties({ModelProperties.class, RagProperties.class, com.example.ailab.ai.fees.FeeProperties.class, com.example.ailab.ai.media.MediaProperties.class, com.example.ailab.ai.media.VideoApiProperties.class})
public class AiConfiguration {
}
