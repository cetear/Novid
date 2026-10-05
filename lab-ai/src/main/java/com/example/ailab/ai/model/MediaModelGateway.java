package com.example.ailab.ai.model;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.MediaProviderPort;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** 真正媒体协议适配；生成绝不自动重试，查询只使用原提供方ID。 */
@Component
public class MediaModelGateway implements MediaProviderPort {
    private final MediaProperties config;
    private final Environment environment;
    private final HttpClient http;
    private VideoApiRegistry videos;
    /** 注册执行器统一接管视频差异；图片协议仍沿自身能力。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void videoRegistry(VideoApiRegistry registry){this.videos=registry;}
    /** 公开候选没有密钥或端点。 */
    public List<VideoApi.Capability> videoCapabilities(){return videos==null?List.of():videos.capabilities();}
    /** 人物／场景映射必须适配所选模型；原生声音需登记提示或参考支持，不能忽略选择。 */
    public VideoApi.Selection selectVideo(VideoApi.Recommendation r,List<Media.CatalogItem> catalogs){
        if(catalogs.size()!=3||!catalogs.stream().map(Media.CatalogItem::kind).collect(java.util.stream.Collectors.toSet()).equals(Set.of("CHARACTER","VOICE","SCENE")))throw new LabException("MEDIA_SELECTION_UNSUPPORTED","每个镜头必须保留人物、配音和场景三种有效登记选择");
        var selection=videos.select(r);
        for(var c:catalogs){
            if(c.kind().equals("VOICE")&&selection.audioMode().equals("NONE"))continue;
            if(!c.enabled()||c.mapping(selection.capability().model(),"PROMPT_GUIDANCE")==null)throw new LabException("MEDIA_SELECTION_UNSUPPORTED","登记项没有所选视频能力的有效映射");
        }
        return selection;
    }
    /** 对批准快照再次复核，禁止同版本静默变更。 */
    public void verifyVideo(VideoApi.Selection s,boolean submitting){videos.verify(s,submitting);}
    /** 配置查询次数只是上限，数据库仍消费整任务共享次数。 */
    public int queryLimit(VideoApi.Selection s){return s==null?60:videos.maxPolls(s);}
    /** 查询节奏由选中配置明确提供。 */
    public int queryInterval(VideoApi.Selection s){return s==null?5:videos.pollInterval(s);}
    /** 视频统一使用登记协议与共同截止，图片仍调用原真实生成接口。 */
    public Media.ProviderResult submit(Media.Submission s,java.time.Instant deadline){
        if(!s.capability().equals("VIDEO_GENERATION"))return submitFixed(s,deadline);
        if(!config.enabled()||!config.externalDataAllowed())throw unavailable();
        String prompt=s.prompt();for(var c:s.catalogs())if(!c.kind().equals("VOICE")||s.video().audioMode().equals("NATIVE")){
            String mapping=c.mapping(s.video().capability().model(),"PROMPT_GUIDANCE");if(mapping==null)throw unavailable();prompt+="\n"+c.kind()+"："+mapping;
        }
        return videos.submit(new Media.Submission(s.operationId(),s.capability(),prompt,s.seconds(),s.catalogs(),s.video()),deadline);
    }
    /** 在途任务恢复永远使用自己的原配置与原提供方ID。 */
    public Media.ProviderResult query(Media.Submission s,String id,java.time.Instant deadline){
        if(!s.capability().equals("IMAGE_GENERATION"))return videos.query(s.video(),id,deadline);
        if(!config.queryFreeVerified()||id==null||id.isBlank()||id.length()>256)throw unavailable();
        long remaining=Math.min(30000,Duration.between(java.time.Instant.now(),deadline).toMillis());if(remaining<=0)throw unavailable();
        String segment=URLEncoder.encode(id,StandardCharsets.UTF_8).replace("+","%20");
        return parseAsyncImage(request("GET","https://open.bigmodel.cn/api/paas/v4/async-result/"+segment,null,Duration.ofMillis(remaining)),id,false);
    }
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /** 客户端禁止重定向，认证头只发到已注册智谱地址；测试可显式注入本机协议客户端。 */
    @org.springframework.beans.factory.annotation.Autowired
    public MediaModelGateway(MediaProperties config, Environment environment) {
        this(config, environment, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
        endpoint(config.imageSubmitUrl()); endpoint(config.videoSubmitUrl()); endpoint(config.videoQueryUrl().replace("{id}", "validation"));
        if (!config.videoQueryUrl().endsWith("/{id}")) throw new IllegalArgumentException("媒体查询必须为独立完整URL模板");
    }
    /** 显式构造只供协议测试，不将本机替身当真实提供方。 */
    public MediaModelGateway(MediaProperties config, Environment environment, HttpClient http) {
        this.config = config; this.environment = environment; this.http = http;
    }
    /** 固定HTTPS主机避免媒体配置把密钥发送到任意远程地址。 */
    private void endpoint(String url) {
        var uri = URI.create(url);
        if (!"https".equals(uri.getScheme()) || !"open.bigmodel.cn".equals(uri.getHost()) || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getPort() != -1
                || !uri.getPath().startsWith("/api/paas/v4/")) throw new IllegalArgumentException("媒体提供方地址不受支持");
    }
    /** 两种任务配置分别校验，不支持目录映射时明确拒绝，不吞掉用户选择。 */
    public void validate(TaskRequest request) {
        if (!config.enabled() || !config.externalDataAllowed()) throw unavailable();
        if (request.taskType().equals("NOTES_VIDEO")) {
            if (videoCapabilities().isEmpty())
                throw new LabException("MEDIA_SELECTION_UNSUPPORTED", "视频人物／配音／场景支持或查询收费尚未核验");
            var options = request.videoOptions();
            if (options == null) throw LabException.invalid("视频必须选择登记人物、配音和场景");
            var selected=List.of(options.characterId(),options.voiceId(),options.sceneId());var kinds=List.of("CHARACTER","VOICE","SCENE");
            for(int i=0;i<3;i++){String id=selected.get(i),kind=kinds.get(i);if(config.catalogs().stream().noneMatch(c->c.enabled()&&c.id().equals(id)&&c.kind().equals(kind)))throw new LabException("MEDIA_SELECTION_UNSUPPORTED","登记人物／声音／场景类型或映射尚未配置");}
        } else {if(key().isBlank())throw unavailable();if(request.presentationOptions() == null) throw LabException.invalid("PPT准备必须包含presentationOptions");}
    }
    /** 哈希绑定地址、模型、目录及价格配置；不保存密钥文本或其摘要。 */
    public String configurationHash(String capability) {
        // Map的toString顺序会随JVM启动改变；使用排序JSON，避免同一目录重启后误判批准失效。
        try{return hash(new ObjectMapper().findAndRegisterModules().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(config)+":"+capability+(capability.equals("IMAGE_GENERATION")?":"+imageTimeoutSeconds():""));}
        catch(Exception error){throw new IllegalStateException("媒体配置摘要失败");}
    }
    /** 没价仍可显示脚本准备，审批入口禁止无价付费；报价不从网页猜测账户实价。 */
    public FeePrice price(String capability) { return capability.equals("IMAGE_GENERATION") ? config.imagePrice() : capability.equals("VIDEO_GENERATION")?config.videoPrice():null; }
    /** 目录列表包含版本和支持方式，不返回凭证、端点或执行Agent。 */
    public List<Media.CatalogItem> catalogs() { return config.catalogs(); }
    /** 真实请求使用服务端模型名，不能从Planner读取model字段。 */
    public String modelId(String capability) { return capability.equals("IMAGE_GENERATION") ? config.imageModel() : capability.equals("VIDEO_GENERATION")?config.videoModel():"UNSUPPORTED"; }
    /** 2026-10-04官方Schema：viduq1-text仅5秒，不能用prompt伪造更长视频。 */
    public List<Integer> videoDurationTiers() {
        if(videos!=null)return videoCapabilities().stream().flatMap(c->c.durations().stream()).distinct().sorted().toList();
        return switch(config.videoModel()){case "viduq1-text"->List.of(5);case "cogvideox-3"->List.of(5,10);default->List.of();};
    }
    /** 一次发送批准参数；请求键仅本地关联，不能据此声称提供方提交幂等。 */
    public Media.ProviderResult submit(Media.Submission submission) {
        return submitFixed(submission,java.time.Instant.now().plusSeconds(submission.capability().equals("IMAGE_GENERATION")?imageTimeoutSeconds():30));
    }
    /** 同步图像等待上限是后端有界配置，仍取整任务剩余期限的较小值；超时不会自动重购。 */
    private Media.ProviderResult submitFixed(Media.Submission submission,java.time.Instant deadline){
        if (!config.enabled() || !config.externalDataAllowed()) throw unavailable();
        if(!Set.of("IMAGE_GENERATION","VIDEO_GENERATION").contains(submission.capability()))throw unavailable();
        boolean image = submission.capability().equals("IMAGE_GENERATION");
        var body = new LinkedHashMap<String, Object>(); body.put("model", modelId(submission.capability()));
        String prompt = submission.prompt();
        if (!image) {
            if (!config.videoSelectionVerified()) throw new LabException("MEDIA_SELECTION_UNSUPPORTED", "目录支持未核验");
            for (var c : submission.catalogs()) if(!c.kind().equals("VOICE")) prompt += "\n" + c.kind() + "：" + c.mappingValue();
            if(!videoDurationTiers().contains(submission.seconds()))throw new LabException("MEDIA_DURATION_MISMATCH","视频时长不是当前模型支持档位");
            if(prompt.codePointCount(0,prompt.length())>512)throw new LabException("MEDIA_PARAMETERS_REJECTED","镜头提示及目录描述超过提供方512字符上限");
            body.put("duration",submission.seconds());
            if(config.videoModel().equals("viduq1-text"))body.put("aspect_ratio","16:9");
        }
        body.put("prompt", prompt);
        if (image) { body.put("size", config.imageSize()); body.put("watermark_enabled", true); }
        long remaining=Math.min((image?imageTimeoutSeconds():30)*1000L,Duration.between(java.time.Instant.now(),deadline).toMillis());
        if(remaining<=0)throw new LabException("BUDGET_EXCEEDED","媒体请求共同期限耗尽");
        var root = request("POST", image ? config.imageSubmitUrl() : config.videoSubmitUrl(), body,Duration.ofMillis(remaining));
        return image ? (URI.create(config.imageSubmitUrl()).getPath().equals("/api/paas/v4/async/images/generations")?parseAsyncImage(root,null,true):parseImage(root)) : parseVideo(root, null, true);
    }
    /** 路径仅来自持久操作，UTF-8百分号编码为单路径段；没有POST或生成请求体。 */
    public Media.ProviderResult query(String providerJobId) {
        if (!config.enabled() || !config.queryFreeVerified()) throw unavailable();
        if (providerJobId == null || providerJobId.isBlank() || providerJobId.length() > 256) throw protocol();
        String segment = URLEncoder.encode(providerJobId, StandardCharsets.UTF_8).replace("+", "%20");
        return parseVideo(request("GET", config.videoQueryUrl().replace("{id}", segment), null), providerJobId, false);
    }
    /** 有界响应、30秒总请求，错误仅保留稳定分类；不输出提供方正文或认证头。 */
    private JsonNode request(String method, String url, Object body) {
        return request(method,url,body,Duration.ofSeconds(30));
    }
    /** 图片默认30秒，明确登记最多120秒；不放宽其他协议或整任务总期限。 */
    private int imageTimeoutSeconds(){
        int seconds=environment.getProperty("LAB_MEDIA_IMAGE_TIMEOUT_SECONDS",Integer.class,30);
        if(seconds<1||seconds>120)throw new IllegalArgumentException("图像请求等待上限须在1到120秒之间");return seconds;
    }
    /** 有界响应订阅与HTTP总超时共同生效，不把请求超时当未计费。 */
    private JsonNode request(String method,String url,Object body,Duration timeout){
        try {
            if (key().isBlank()) throw unavailable();
            var builder = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                    .header("Authorization", "Bearer " + key());
            if (method.equals("GET")) builder.GET();
            else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            // 自定义订阅器在收取阶段限制字节，而非读完无界响应后才检查。
            var response = http.send(builder.build(), info -> new LimitedBodySubscriber(65536));
            int status = response.statusCode();
            if (status == 401 || status == 403) throw new LabException("MEDIA_AUTH_FAILED", "媒体提供方鉴权失败");
            if (status == 400 || status == 422) throw new LabException("MEDIA_PARAMETERS_REJECTED", "媒体提供方参数拒绝");
            if (status == 429) throw new LabException("MEDIA_RATE_LIMITED", "媒体查询或提交限流");
            if (status < 200 || status >= 300) throw new LabException("MEDIA_REMOTE_UNAVAILABLE", "媒体提供方结果暂不可得");
            return JSON.readTree(response.body());
        } catch (LabException known) { throw known; }
        catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); throw new LabException("MEDIA_REMOTE_TIMEOUT", "媒体请求中断，须核对原操作"); }
        catch (Exception failed) { throw new LabException("MEDIA_REMOTE_TIMEOUT", "媒体请求未获可核验结果，须核对原操作"); }
    }
    /** 同步生图只取data[].url，不接受choices或空文件地址成功。 */
    public static Media.ProviderResult parseImage(JsonNode root) {
        var urls = urls(root.path("data"), "url");
        if (urls.size() != 1 || root.has("error")) throw protocol();
        return new Media.ProviderResult(null, field(root,"request_id"), field(root,"model"), "SUCCESS", urls, List.of(), null, null);
    }
    /** 智谱异步图像沿同一原ID状态信封，结果只读image_result，不能拿data或video_result冒充图片。 */
    public static Media.ProviderResult parseAsyncImage(JsonNode root,String originalId,boolean submission){
        if(!root.isObject())throw protocol();
        var envelope=((com.fasterxml.jackson.databind.node.ObjectNode)root).deepCopy();
        envelope.set("video_result",root.path("image_result"));
        var result=parseVideo(envelope,originalId,submission);
        if(result.urls().size()>1)throw protocol();return result;
    }
    /** HTTP200不代表成功；id不匹配、未知状态或空结果保留协议异常，不能发布。 */
    public static Media.ProviderResult parseVideo(JsonNode root, String originalId, boolean submission) {
        String id = field(root,"id"), status = field(root,"task_status");
        if (submission && (id == null || id.isBlank() || id.length() > 256)) throw protocol();
        if (originalId != null && id != null && !originalId.equals(id)) throw protocol();
        if (id == null) id = originalId;
        // 首次提交一旦有真实id就先保留，状态异常也不能丢id再被误判为可重购。
        if (!Set.of("PROCESSING", "SUCCESS", "FAIL").contains(status == null ? "" : status)) {
            if(submission)return new Media.ProviderResult(id,field(root,"request_id"),field(root,"model"),"PROTOCOL_INVALID",List.of(),List.of(),null,"MEDIA_PROTOCOL_INVALID");
            throw protocol();
        }
        var urls = urls(root.path("video_result"), "url");
        var covers = urls(root.path("video_result"), "cover_image_url");
        if (status.equals("SUCCESS") && urls.isEmpty()) {
            if(submission)return new Media.ProviderResult(id,field(root,"request_id"),field(root,"model"),"PROTOCOL_INVALID",List.of(),List.of(),null,"MEDIA_PROTOCOL_INVALID");
            throw protocol();
        }
        return new Media.ProviderResult(id, field(root,"request_id"), field(root,"model"), status, urls, covers, null,
                status.equals("FAIL") || root.has("error") ? "MEDIA_PROVIDER_FAILED" : null);
    }
    /** 不允许将数字／对象强转任务标识；字段缺失保持null。 */
    private static String field(JsonNode root, String name) { return root.path(name).isTextual() ? root.path(name).textValue() : null; }
    /** 提供方URL仅作为受控取回候选，真正下载仍受数据层地址和文件校验。 */
    private static List<String> urls(JsonNode array, String name) {
        var values = new ArrayList<String>();
        if (array.isArray()) for (var value : array) {
            String url = field(value,name);
            if (url != null && !url.isBlank() && url.length() <= 4096) values.add(url);
            if (values.size() > 8) throw protocol();
        }
        return List.copyOf(values);
    }
    /** 后端凭证引用不在DTO、日志和异常中出现。 */
    private String key() { return environment.getProperty(config.credentialRef(), ""); }
    /** 稳定协议错误不授权重购，已有ID可以有限继续核对。 */
    private static LabException protocol() { return new LabException("MEDIA_PROTOCOL_INVALID", "媒体响应状态、ID或结果不合法"); }
    /** 未配置真实能力时不造视频或提供方ID。 */
    private LabException unavailable() { return new LabException("MEDIA_CAPABILITY_UNAVAILABLE", "真实媒体能力未启用或配置尚未核验"); }
    /** 稳定UTF-8摘要供批准与恢复核验，不包含任何凭证。 */
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("摘要算法不可用"); }
    }
}
