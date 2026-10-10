package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Service;

import java.util.*;
import java.math.BigDecimal;

/**
 * 本人媒体用例：来源、目录、脚本批准及当前费用约束均独立于模型输出。
 */
@Service
public class MediaApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final MediaStorePort store;
    private final MediaProviderPort provider;
    private final MediaFilePort files;
    private final TaskStorePort tasks;
    private PresentationPort presentation;

    /**
     * 正式PPT部署在购买前核对字体；旧独立业务测试构造保持兼容。
     */
    @org.springframework.beans.factory.annotation.Autowired
    public void presentation(PresentationPort exporter) {
        this.presentation = exporter;
    }

    /**
     * 业务只依赖窄端口，不访问SDK、SQL、ffmpeg或文件路径。
     */
    public MediaApplicationService(KnowledgeAccessPolicy policy, MediaStorePort store, MediaProviderPort provider, MediaFilePort files, TaskStorePort tasks) {
        this.policy = policy;
        this.store = store;
        this.provider = provider;
        this.files = files;
        this.tasks = tasks;
    }

    /**
     * 创建前校验媒体类型、限额和真实能力，旧报告选项不能误用为媒体。
     */
    public void validate(UserContext actor, TaskRequest r) {
        policy.current(actor);
        if (!Set.of("NOTES_PPT", "NOTES_VIDEO").contains(r.taskType()) || !r.documentDriven() && !r.strategy().equals("PLANNED"))
            throw LabException.invalid("媒体任务必须使用PLANNED");
        BigDecimal limit;
        if (r.taskType().equals("NOTES_PPT")) {
            var p = r.presentationOptions();
            if (p == null || r.videoOptions() != null || (p.pageCount() != 0 && (p.pageCount() < 2 || p.pageCount() > 512)) || !"default".equals(p.themeId()) || !Set.of("MIXED", "CONCEPT", "FACTUAL").contains(p.imagePolicy()))
                throw LabException.invalid("PPT页数0表示自动，显式总页数须2～512页，主题／配图策略须合法");
            limit = p.maximumAmount();
            if (presentation != null) presentation.validateConfiguration();
        } else {
            var v = r.videoOptions();
            if (v == null || r.presentationOptions() != null || v.shotCount() < 1 || v.shotCount() > 6 || v.seconds() < 1 || v.seconds() > 90)
                throw LabException.invalid("视频需登记选项和1～6镜头");
            limit = v.maximumAmount();
            if (v.characterId() == null || v.characterId().isBlank()
                    || v.voiceId() == null || v.voiceId().isBlank()
                    || v.sceneId() == null || v.sceneId().isBlank())
                throw LabException.invalid("视频必须选择登记人物、配音和场景");
            var tiers = provider.videoDurationTiers();
            if (tiers.isEmpty())
                throw new LabException("MEDIA_CAPABILITY_UNAVAILABLE", "当前视频模型没有已核验时长档位");
            if (v.seconds() > v.shotCount() * Collections.max(tiers))
                throw new LabException("MEDIA_DURATION_MISMATCH", "目标总时长超过所选模型与镜头数共同上限");
            if (!files.videoRuntimeAvailable())
                throw new LabException("MEDIA_CAPABILITY_UNAVAILABLE", "JavaCV视频流检测与镜头拼接运行时尚未配置");
        }
        if (limit == null || limit.signum() <= 0 || limit.scale() > 8 || limit.compareTo(new BigDecimal("30")) > 0)
            throw LabException.invalid("媒体批准金额须为正数且不超过服务端30元限额");
        provider.validate(r);
    }

    /**
     * 目录仅供合法身份读取，教学人物不是权限角色或执行Agent。
     */
    public List<Media.CatalogItem> catalogs(UserContext actor) {
        policy.current(actor);
        return provider.catalogs();
    }

    /**
     * 当前可供规划的公开能力，不含配置URL或凭证引用。
     */
    public List<VideoApi.Capability> videoCapabilities(UserContext actor) {
        policy.current(actor);
        return provider.videoCapabilities();
    }

    /**
     * 最终人工验收不会授权另一次付费生成。
     */
    public void review(UserContext actor, long id, int version, boolean accepted, String note) {
        store.review(policy.current(actor), id, version, accepted, note);
    }

    /**
     * 本人显式恢复旧素材导出不表示允许购买，数据端保持原截止与原批准。
     */
    public void exportPresentation(UserContext actor, long id, int version) {
        store.requestPresentation(policy.current(actor), id, version);
    }

    /**
     * 检查只来自真实本地文件登记，无结果不能补造通过。
     */
    public Optional<Presentation.Bundle> presentationCheck(UserContext actor, long id) {
        return store.presentationCheck(policy.current(actor), id);
    }

    /**
     * 本人可改Planner建议，所有选项重新登记核验并生成新批准。
     */
    public Media.Preview videoSelection(UserContext actor, long id, int version, List<VideoApi.Recommendation> recommendations) {
        actor = policy.current(actor);
        var p = store.preview(actor, id).orElseThrow(LabException::denied);
        if (p.storyboard() == null || recommendations == null || recommendations.stream().anyMatch(Objects::isNull) || !recommendations.stream().map(VideoApi.Recommendation::shotId).toList().equals(p.units().stream().map(Media.Unit::unitId).toList()))
            throw LabException.invalid("路由选择必须覆盖原镜头顺序");
        var selections = recommendations.stream().map(r -> provider.selectVideo(r, p.catalogs())).toList();
        return store.edit(actor, id, version, p.units(), configurationHash("NOTES_VIDEO"), selections);
    }

    /**
     * 本人预览及所有来源由数据端再次复核。
     */
    public Optional<Media.Preview> preview(UserContext actor, long id) {
        return store.preview(policy.current(actor), id);
    }

    /**
     * 私人动态计划版本列表，不用静态生命周期图冒充执行计划。
     */
    public List<Media.PlanSnapshot> plans(UserContext actor, long id) {
        return store.plans(policy.current(actor), id);
    }

    /**
     * 返回真实等待／查询／UNKNOWN事实，不允许指定外部提供方ID。
     */
    public List<Media.Operation> operations(UserContext actor, long id) {
        return store.operations(policy.current(actor), id);
    }

    /**
     * 编辑保留稳定ID、来源引用、目录及素材选择；已付费PPT由仓库撤回导出并要求重新批准。
     */
    public Media.Preview edit(UserContext actor, long id, int version, List<Media.Unit> units) {
        actor = policy.current(actor);
        var p = store.preview(actor, id).orElseThrow(LabException::denied);
        if (units == null || units.size() != p.units().size())
            throw LabException.invalid("编辑保留原页／镜头数量与稳定ID；重规划需另行有限执行");
        var task = tasks.read(actor, id);
        boolean video = task.taskType().equals("NOTES_VIDEO");
        var limits=ContentLimits.current();
        boolean utf8=!video;
        var refs = p.sourceDependencies().stream().map(s -> "D" + s.documentId() + "v" + s.documentVersion()).collect(java.util.stream.Collectors.toSet());
        var ids = new HashSet<String>();
        for (var u : units) {
            if (u == null || u.unitId() == null || u.layout() == null || u.imageMode() == null)
                throw LabException.invalid("编辑单位及ID、版式、配图方式不能为空");
            var old = p.units().stream().filter(x -> x.unitId().equals(u.unitId())).findFirst().orElseThrow(() -> LabException.invalid("编辑单位ID不合法"));
            if (!ids.add(u.unitId()) || u.title() == null || u.title().isBlank() || !within(u.title(),video?200:limits.title(),utf8) || u.text() == null || (!video && u.text().isBlank()) || !within(u.text(),video?3000:limits.slideText(),utf8) || u.notes() == null || !within(u.notes(),video?1500:limits.slideNotes(),utf8)
                    || !(video?Set.of("TITLE", "TEXT", "TWO_COLUMN", "IMAGE_TEXT", "SCENE"):Set.of("TITLE", "TEXT", "TWO_COLUMN", "IMAGE_TEXT")).contains(u.layout()) || u.references().isEmpty() || !refs.containsAll(u.references())
                    || !u.imageMode().equals(old.imageMode()) || u.imagePrompt() == null || !within(u.imagePrompt(),utf8?limits.imagePrompt():1000,utf8) || u.seconds() != old.seconds()
                    || u.imageMode().equals("WEB_SEARCH") && !u.imagePrompt().equals(old.imagePrompt()))
                throw LabException.invalid("编辑超限或改变不可替换来源／资产约束");
            if (video) {
                if (u.text().codePointCount(0, u.text().length()) > 1024)
                    throw LabException.invalid("单镜头台词超过1024字符范围");
                if (p.storyboard() != null) {
                    var shot = p.storyboard().shots().stream().filter(s -> s.shotId().equals(u.unitId())).findFirst()
                            .orElseThrow(() -> LabException.invalid("缺少对应的批准分镜"));
                    if (shot.video() != null) StoryboardRules.audioPolicy(shot.video(), u.text());
                }
            }
        }
        if(p.contentPlan()!=null&&!units.stream().map(Media.Unit::unitId).toList().equals(p.units().stream().map(Media.Unit::unitId).toList()))
            throw LabException.invalid("资料驱动预览须保持内容计划的页面顺序");
        return store.edit(actor, id, version, units, configurationHash(task.taskType()));
    }
    private static boolean within(String value,int maximum,boolean utf8){return value!=null&&(utf8?TextWindow.count(value):value.length())<=maximum;}

    /**
     * 决定只接收批准ID和布尔值，不允许覆盖脚本／价格／来源；数据端原子消费。
     */
    public Media.Preview decide(UserContext actor, String id, boolean approved, long taskId) {
        actor = policy.current(actor);
        var t = tasks.read(actor, taskId);
        var prices = new HashMap<String, FeePrice>();
        var models = new HashMap<String, String>();
        if (approved && t.taskType().equals("NOTES_PPT")) {
            var preview = store.preview(actor, taskId).orElseThrow(LabException::denied);
            if (preview.units().stream().anyMatch(u -> u.imageMode().equals("GENERATED"))) provider.verifyImage();
        }
        if (approved && t.taskType().equals("NOTES_PPT") && presentation != null) {
            presentation.validateConfiguration();
            var preview = store.preview(actor, taskId).orElseThrow(LabException::denied);
            var issues = presentation.validateLayout(preview.units());
            if (!issues.isEmpty())
                throw new LabException(issues.get(0).code(), "PPT排版预检未通过，请调整标题、正文或版式后再批准：" + issues.get(0).unitId());
        }
        for (String c : List.of("IMAGE_GENERATION", "VIDEO_GENERATION")) {
            var price = provider.price(c);
            if (price != null) prices.put(c, price);
            models.put(c, provider.modelId(c));
        }
        if (approved && t.taskType().equals("NOTES_VIDEO") && !files.videoRuntimeAvailable())
            throw new LabException("MEDIA_CAPABILITY_UNAVAILABLE", "JavaCV视频处理运行时不可用");
        if (approved && t.taskType().equals("NOTES_VIDEO")) {
            var preview = store.preview(actor, taskId).orElseThrow(LabException::denied);
            if (preview.storyboard() == null) throw new LabException("PREVIEW_CHANGED", "缺少分镜路由批准");
            for (var shot : preview.storyboard().shots()) provider.verifyVideo(shot.video(), true);
        }
        return store.decide(actor, id, approved, configurationHash(t.taskType()), prices, models);
    }

    /**
     * 批准绑定当前视频／图片及目录配置，不包含已移除的独立配音报价。
     */
    public String configurationHash(String taskType) {
        return provider.configurationHash(taskType.equals("NOTES_VIDEO") ? "VIDEO_GENERATION" : "IMAGE_GENERATION");
    }

    /**
     * 独立镜头进度来自当前本人版本的真实素材与时间轴，不计算远程假百分比。
     */
    public Media.VideoProgress videoProgress(UserContext actor, long taskId) {
        actor = policy.current(actor);
        var t = tasks.read(actor, taskId);
        var p = store.preview(actor, taskId);
        var shots = p.isEmpty() ? List.<Media.ShotExecution>of() : store.shots(actor, taskId);
        var operations = store.operations(actor, taskId);
        int total = p.isPresent() && p.get().storyboard() != null ? p.get().storyboard().shots().size() : 0;
        int complete = (int) shots.stream().filter(s -> s.renderedAssetId() != null).count();
        String active = operations.stream().filter(o -> !o.state().equals("SUCCEEDED")).map(Media.Operation::unitId).findFirst().orElse(null);
        return new Media.VideoProgress(total, complete, active, t.progress().stage(), t.progress().message(), shots, operations);
    }
}
