package com.example.ailab.contract.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * S09媒体事实契约；教学登记项、执行Agent和权限角色分别建模。
 */
public final class Media {
    /**
     * 工具类不可实例化，契约中不包含SDK或可执行代码。
     */
    private Media() {
    }

    public record PresentationOptions(int pageCount, String themeId, BigDecimal maximumAmount, String imagePolicy) {
        /**
         * 旧显式构造默认混合配图，事实需求不会自动改为生图。
         */
        public PresentationOptions(int pages, String theme, BigDecimal limit) {
            this(pages, theme, limit, "MIXED");
        }

        /**
         * 默认策略明确，不由模型推断用户准许事实图替换。
         */
        public PresentationOptions {
            imagePolicy = imagePolicy == null ? "MIXED" : imagePolicy;
        }
    }

    public record VideoOptions(String characterId, String voiceId, String sceneId, int seconds,
                               BigDecimal maximumAmount, int shotCount, boolean burnSubtitles) {
        /**
         * 默认独立SRT；烧录字幕明确选择后才发生像素转换。
         */
        public VideoOptions(String character, String voice, String scene, int seconds, BigDecimal amount, int shots) {
            this(character, voice, scene, seconds, amount, shots, false);
        }

        /**
         * 旧请求缺省采用三镜头，单镜头验收显式传1；镜头身份仍使用shotId。
         */
        public VideoOptions {
            if (shotCount == 0) shotCount = 3;
        }

        /**
         * 保留原调用方构造。
         */
        public VideoOptions(String character, String voice, String scene, int seconds, BigDecimal amount) {
            this(character, voice, scene, seconds, amount, 3, false);
        }
    }

    public record Shot(String shotId, String narration, String visualPrompt, String motionPrompt,
                       String generationType, List<String> referenceAssetIds, List<String> sourceRefs,
                       long estimatedDurationMs, int maximumDurationSeconds, VideoApi.Selection video) {
        /**
         * 历史镜头无路由快照，不能冒充新注册API批准。
         */
        public Shot(String id, String narration, String visual, String motion, String type, List<String> assets, List<String> refs, long ms, int max) {
            this(id, narration, visual, motion, type, assets, refs, ms, max, null);
        }

        /**
         * 分镜是批准输入，实测时间轴不得覆盖这里的估计。
         */
        public Shot {
            referenceAssetIds = List.copyOf(referenceAssetIds);
            sourceRefs = List.copyOf(sourceRefs);
        }
    }

    public record Storyboard(int storyboardVersion, String hash, String aspectRatio, String mappingRule,
                             List<Integer> durationTiers, List<Shot> shots) {
        /**
         * 档位来自已核验服务端配置，随批准固定，不能重启换成更贵档位。
         */
        public Storyboard {
            durationTiers = List.copyOf(durationTiers);
            shots = List.copyOf(shots);
        }
    }

    public record ShotExecution(String shotId, String approvalHash, Long audioDurationMs,
                                Integer providerDurationSeconds, Long timelineStartMs, Long timelineEndMs,
                                String audioAssetId, String videoAssetId, String renderedAssetId) {
    }

    public record VideoProgress(int totalShots, int completedShots, String activeShotId, String renderPhase,
                                String waitReason, List<ShotExecution> shots, List<Operation> operations) {
        /**
         * 防御复制，镜头完成不等于整片已发布。
         */
        public VideoProgress {
            shots = List.copyOf(shots);
            operations = List.copyOf(operations);
        }
    }

    public record CatalogItem(String id, String kind, int version, String label, boolean enabled,
                              String provider, String mappingKind, String mappingValue,
                              java.util.Map<String, String> providerMappings) {
        /**
         * 历史单目标映射仍可读取，跨提供方映射由管理员显式登记。
         */
        public CatalogItem(String id, String kind, int version, String label, boolean enabled, String provider, String type, String value) {
            this(id, kind, version, label, enabled, provider, type, value, java.util.Map.of());
        }

        /**
         * 不允许可变映射影响已批准目录版本。
         */
        public CatalogItem {
            providerMappings = providerMappings == null ? java.util.Map.of() : java.util.Map.copyOf(providerMappings);
        }

        /**
         * 映射按模型及用途区分，不能将TTS音色ID伪装成原生视频音色ID。
         */
        public String mapping(String model, String type) {
            return providerMappings.getOrDefault(model + "/" + type, provider.equals(model) && mappingKind.equals(type) ? mappingValue : null);
        }
    }

    public record Step(String stepId, String action, String agentId, List<String> dependsOn,
                       List<String> inputRefs, String when, String completionCondition) {
        /**
         * 列表防御复制，不允许模型修改已校验依赖。
         */
        public Step {
            dependsOn = List.copyOf(dependsOn);
            inputRefs = List.copyOf(inputRefs);
        }
    }

    public record Plan(int planVersion, String schemaVersion, List<Step> steps) {
        /**
         * 计划一经保存不可变，恢复必须使用原版本。
         */
        public Plan {
            steps = List.copyOf(steps);
        }
    }

    public record PlanSnapshot(Plan plan, String hash, String modelId, String policyVersion) {
    }

    public record Unit(String unitId, String title, String text, String notes, String layout,
                       String imageMode, String imagePrompt, List<String> references, int seconds) {
        /**
         * 来源由程序核验，模型只能引用已有标签。
         */
        public Unit {
            references = List.copyOf(references);
        }
    }

    public record Issue(String code, String stepId, String unitId, String evidence, String suggestion) {
    }

    public record Review(String decision, List<Issue> issues) {
        /**
         * 质检不能生成无限返工列表。
         */
        public Review {
            issues = List.copyOf(issues);
        }
    }

    public record WorkerResult(String stepId, String agentId, String inputHash, List<Unit> units, Review review,
                               List<ImageSource> webCandidates, List<SourceDependency> sourceDependencies) {
        /**
         * 候选事实只由程序补入，不让模型自报搜索成功。
         */
        public WorkerResult(String step, String agent, String hash, List<Unit> units, Review review) {
            this(step, agent, hash, units, review, List.of(), List.of());
        }

        /**
         * 不共享可变聊天窗口，只传版本化角色事实。
         */
        public WorkerResult {
            units = List.copyOf(units);
            webCandidates = webCandidates == null ? List.of() : List.copyOf(webCandidates);
            sourceDependencies = sourceDependencies == null ? List.of() : List.copyOf(sourceDependencies);
        }
    }

    public record ImageSource(String candidateId, String query, String sourcePageUrl, String imageUrl,
                              String title, String author, String license, String objectAndPeriod,
                              Instant retrievedAt) {
    }

    public record FileFact(String storageKey, String mime, long size, String checksum) {
    }

    public record Asset(String assetId, String unitId, String kind, String operationId, FileFact file,
                        ImageSource webSource, Long artifactId) {
    }

    public record ContentPlanRef(String planHash, String title, int contentSlides, int sourceSlides, int totalSlides) {
        public ContentPlanRef {
            if (planHash == null || !planHash.matches("[a-f0-9]{64}") || title == null || title.isBlank()
                    || contentSlides < 1 || contentSlides > 512 || sourceSlides < 1 || sourceSlides > 4
                    || totalSlides != contentSlides + sourceSlides) throw new IllegalArgumentException("内容计划引用无效");
        }
    }

    public record Preview(long taskId, int previewVersion, int planVersion, String hash, String status,
                          String approvalId, Instant expiresAt, String configurationHash,
                          String currency, BigDecimal estimatedAmount, BigDecimal maximumAmount,
                          List<Unit> units, List<SourceDependency> sourceDependencies,
                          List<DocumentCoverage> coverage, List<CatalogItem> catalogs, List<Asset> assets,
                          String qualityStatus, Storyboard storyboard,
                          @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) ContentPlanRef contentPlan) {
        public Preview(long taskId, int previewVersion, int planVersion, String hash, String status, String approvalId, Instant expiresAt,
                       String configurationHash, String currency, BigDecimal estimatedAmount, BigDecimal maximumAmount, List<Unit> units,
                       List<SourceDependency> sources, List<DocumentCoverage> coverage, List<CatalogItem> catalogs, List<Asset> assets,
                       String qualityStatus, Storyboard storyboard) {
            this(taskId,previewVersion,planVersion,hash,status,approvalId,expiresAt,configurationHash,currency,estimatedAmount,maximumAmount,
                    units,sources,coverage,catalogs,assets,qualityStatus,storyboard,null);
        }
        /**
         * 旧图片预览和历史整片数据不伪造分镜事实。
         */
        public Preview(long taskId, int previewVersion, int planVersion, String hash, String status, String approvalId, Instant expiresAt,
                       String configurationHash, String currency, BigDecimal estimatedAmount, BigDecimal maximumAmount, List<Unit> units,
                       List<SourceDependency> sources, List<DocumentCoverage> coverage, List<CatalogItem> catalogs, List<Asset> assets, String qualityStatus) {
            this(taskId, previewVersion, planVersion, hash, status, approvalId, expiresAt, configurationHash, currency, estimatedAmount, maximumAmount, units, sources, coverage, catalogs, assets, qualityStatus, null);
        }

        /**
         * 本人编辑只替换单位内容，来源、目录、报价与资产事实仍由服务端保存。
         */
        public Preview {
            units = List.copyOf(units);
            sourceDependencies = List.copyOf(sourceDependencies);
            coverage = List.copyOf(coverage);
            catalogs = List.copyOf(catalogs);
            assets = List.copyOf(assets);
        }
    }

    public record Operation(String operationId, long taskId, int previewVersion, String unitId,
                            String capability, String state, String providerJobId, String providerStatus,
                            int pollCount, Instant lastPollAt, Instant nextPollAt, Instant deadline,
                            String errorCode, String assetId, String costStatus) {
    }

    public record ProviderResult(String providerJobId, String requestId, String model, String status,
                                 List<String> urls, List<String> covers, Long usageUnits, String errorCode) {
        /**
         * 同步图片与异步视频使用独立状态，不从HTTP200推断成功。
         */
        public ProviderResult {
            urls = List.copyOf(urls);
            covers = List.copyOf(covers);
        }
    }

    public record Submission(String operationId, String capability, String prompt, int seconds,
                             List<CatalogItem> catalogs, VideoApi.Selection video) {
        /**
         * 图片提交保留原构造；新视频由Storyboard补入批准选择。
         */
        public Submission(String id, String capability, String prompt, int seconds, List<CatalogItem> catalogs) {
            this(id, capability, prompt, seconds, catalogs, null);
        }

        /**
         * 只有已批准服务端参数可以进入提供方适配器。
         */
        public Submission {
            catalogs = List.copyOf(catalogs);
        }
    }
}
