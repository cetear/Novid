package com.example.ailab.ai.model;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 深封装配置映射、鉴权、原ID查询和状态转换；调用者只传批准选择，不感知厂商字段。 */
@Component
public final class VideoApiRegistry {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private final Map<String,VideoApiProperties.Profile> profiles=new LinkedHashMap<>();
    private final Environment environment;private final HttpClient http;
    /** 正式构造禁止HTTP和重定向，只有服务端登记地址可接收凭证。 */
    @org.springframework.beans.factory.annotation.Autowired
    public VideoApiRegistry(VideoApiProperties properties,Environment environment){
        this(properties,environment,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build(),false);
    }
    /** 包内协议测试可使用环回HTTP，不放宽生产配置校验。 */
    VideoApiRegistry(VideoApiProperties properties,Environment environment,HttpClient http,boolean localTest){
        this.environment=environment;this.http=http;if(properties.profiles().size()>50)throw invalid();
        for(var p:properties.profiles()){
            validate(p,localTest);if(profiles.put(p.id()+":"+p.version(),p)!=null)throw invalid();
        }
        var active=new HashSet<String>();for(var p:profiles.values())if(p.enabled()&&!active.add(p.id()))throw invalid();
    }
    /** 只返回可规划版本；历史禁用版本保留给在途原ID查询。 */
    public List<VideoApi.Capability> capabilities(){return profiles.values().stream().filter(VideoApiProperties.Profile::enabled).filter(p->p.audioModes().stream().anyMatch(m->Set.of("NATIVE","NONE").contains(m))).map(this::capability).toList();}
    /** 选择时补入服务端能力事实，拒绝模型编造目标、档位或价格。 */
    public VideoApi.Selection select(VideoApi.Recommendation r){
        if(!Set.of("NATIVE","NONE").contains(r.audioMode()))throw new LabException("MEDIA_TTS_DISABLED","独立配音合轨功能已移除");
        var c=capabilities().stream().filter(x->x.id().equals(r.profileId())).findFirst().orElseThrow(VideoApiRegistry::unavailable);
        try{return new VideoApi.Selection(c,r.resolution(),r.audioMode(),r.seconds(),r.reason());}catch(IllegalArgumentException e){throw unavailable();}
    }
    /** 提交必须仍为启用的同版本配置；查询允许已禁用但未删除的原版本。 */
    public void verify(VideoApi.Selection selection,boolean submitting){var profile=resolve(selection,submitting);if(submitting){if(!Set.of("NATIVE","NONE").contains(selection.audioMode()))throw new LabException("MEDIA_TTS_DISABLED","独立配音合轨功能已移除");if(environment.getProperty(profile.credentialRef(),"").isBlank())throw unavailable();}}
    /** 配置上的查询上限不能突破整任务预算，次数由仓库可靠消费。 */
    public int maxPolls(VideoApi.Selection selection){return resolve(selection,false).maxPolls();}
    /** 查询间隔来自原版本，程序持久保存下一次时间，不在内存轮询。 */
    public int pollInterval(VideoApi.Selection selection){return resolve(selection,false).pollIntervalSeconds();}
    /** 原请求通过类型化字段映射生成，不做字符串JSON拼接，也不发送模型提供的URL。 */
    public Media.ProviderResult submit(Media.Submission submission,Instant deadline){
        verify(submission.video(),true);
        var choice=submission.video();var p=resolve(choice,true);
        if(submission.prompt()==null||submission.prompt().isBlank()||submission.prompt().codePointCount(0,submission.prompt().length())>p.maxPromptCharacters())throw new LabException("MEDIA_PARAMETERS_REJECTED","镜头提示超过登记API长度限制");
        if(!p.durations().contains(submission.seconds())||submission.seconds()>choice.seconds())throw unavailable();
        ObjectNode body=JSON.valueToTree(p.body());
        var values=new HashMap<String,Object>();values.put("model",p.model());values.put("prompt",submission.prompt());
        values.put("seconds",submission.seconds());values.put("resolution",choice.resolution());values.put("aspectRatio","16:9");values.put("audio",choice.audioMode().equals("NATIVE"));
        p.inputs().forEach((name,path)->put(body,path,JSON.valueToTree(values.get(name))));
        return parse(p,request(p,"POST",p.submitUrl(),body,deadline),null,true);
    }
    /** 查询仅编码原提供方ID，绝不使用本地taskId或request_id。 */
    public Media.ProviderResult query(VideoApi.Selection choice,String id,Instant deadline){
        var p=resolve(choice,false);if(!p.mode().equals("ASYNC_JSON")||id==null||id.isBlank()||id.length()>256)throw protocol();
        String url=p.queryUrl().replace("{id}",URLEncoder.encode(id,StandardCharsets.UTF_8).replace("+","%20"));
        return parse(p,request(p,"GET",url,null,deadline),id,false);
    }
    /** 只接受有效类型；初次响应有ID但其他字段无效仍返回原ID供可靠保存。 */
    private Media.ProviderResult parse(VideoApiProperties.Profile p,JsonNode json,String original,boolean submit){
        String id=string(json.at(p.idPath()));if(original!=null&&id!=null&&!original.equals(id))throw protocol();
        if(id==null)id=original;if(p.mode().equals("ASYNC_JSON")&&(id==null||id.isBlank()||id.length()>256))throw protocol();
        String rawState=string(json.at(p.statusPath()));String state=rawState==null?null:p.statuses().get(rawState);String url=string(json.at(p.resultUrlPath()));
        if(p.mode().equals("SYNC_JSON")&&json.at(p.statusPath()).isMissingNode())state="SUCCESS";
        if(state==null||state.equals("SUCCESS")&&(url==null||url.isBlank())){
            if(!submit)throw protocol();return new Media.ProviderResult(id,null,p.model(),"PROTOCOL_INVALID",List.of(),List.of(),null,"MEDIA_PROTOCOL_INVALID");
        }
        Long usage=null;if(p.usagePath()!=null&&!p.usagePath().isBlank()){
            var node=json.at(p.usagePath());if(node.isNumber()&&node.decimalValue().signum()>=0){
                // 账本当前单位为整数；小数秒未配置可核验取整规则时保持未知，不伪造向上取整的真实用量。
                try{usage=node.decimalValue().longValueExact();}catch(ArithmeticException fractional){usage=null;}
            }
        }
        return new Media.ProviderResult(id,null,p.model(),state,state.equals("SUCCESS")?List.of(url):List.of(),List.of(),usage,state.equals("UNKNOWN")?"MEDIA_PROVIDER_UNKNOWN":null);
    }
    /** 有界响应与剩余截止共同约束请求；远程调用不重试、不记录认证或响应正文。 */
    private JsonNode request(VideoApiProperties.Profile p,String method,String url,JsonNode body,Instant deadline){
        long millis=Math.min(p.timeoutSeconds()*1000L,Duration.between(Instant.now(),deadline).toMillis());if(millis<=0)throw unavailable();
        String key=environment.getProperty(p.credentialRef(),"");if(key.isBlank())throw unavailable();
        try{
            var b=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(millis)).header(p.authHeader(),p.authPrefix()+key);
            if(method.equals("POST")){p.headers().forEach(b::header);b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));}else b.GET();
            var r=http.send(b.build(),info->new LimitedBodySubscriber(65536));
            if(r.statusCode()==401||r.statusCode()==403)throw new LabException("MEDIA_AUTH_FAILED","视频提供方鉴权失败");
            if(r.statusCode()==400||r.statusCode()==404||r.statusCode()==422)throw new LabException("MEDIA_PARAMETERS_REJECTED","视频目标或请求参数被提供方拒绝");
            if(r.statusCode()<200||r.statusCode()>=300)throw new LabException("MEDIA_REMOTE_UNAVAILABLE","视频调用未取得有效结果");
            return JSON.readTree(r.body());
        }catch(LabException e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new LabException("MEDIA_REMOTE_TIMEOUT","视频结果待核对");}catch(Exception e){throw new LabException("MEDIA_REMOTE_TIMEOUT","视频结果待核对");}
    }
    /** 配置摘要不含密钥值，启停不改历史协议；同版本内容改变时拒绝旧批准。 */
    private VideoApi.Capability capability(VideoApiProperties.Profile p){
        try{
            ObjectNode tree=JSON.valueToTree(p);tree.remove("enabled");
            return new VideoApi.Capability(p.id(),p.version(),MediaModelGateway.hash(JSON.writeValueAsString(tree)),p.provider(),p.accountNamespace(),p.model(),p.region(),p.durations(),p.resolutions(),p.audioModes(),p.prices(),p.verificationStatus(),p.pollIntervalSeconds(),p.maxPolls());
        }catch(Exception e){throw invalid();}
    }
    /** 历史版本缺失时暂停，不从最新配置猜测原操作查询地址。 */
    private VideoApiProperties.Profile resolve(VideoApi.Selection choice,boolean submitting){
        if(choice==null)throw unavailable();var c=choice.capability();var p=profiles.get(c.id()+":"+c.version());
        if(p==null||submitting&&!p.enabled()||!capability(p).equals(c)||p.mode().equals("ASYNC_JSON")&&!p.queryFreeVerified())throw new LabException("PREVIEW_CHANGED","原视频配置不可用或已变化");return p;
    }
    /** 有限JSON Pointer仅写对象字段，不允许脚本、动态URL和任意请求头。 */
    private static void put(ObjectNode root,String path,JsonNode value){
        String[] parts=path.substring(1).split("/");ObjectNode current=root;
        for(int i=0;i<parts.length-1;i++){JsonNode n=current.get(parts[i]);if(n==null)current=current.putObject(parts[i]);else if(n instanceof ObjectNode object)current=object;else throw invalid();}
        current.set(parts[parts.length-1],value);
    }
    /** 配置在启动时失败，不能等付费后才发现协议没有查询ID或支持范围。 */
    private static void validate(VideoApiProperties.Profile p,boolean local){
        if(p.audioModes().contains("NATIVE")&&p.audioModes().contains("NONE")&&!p.inputs().containsKey("audio"))throw invalid();
        if(!Set.of("ASYNC_JSON","SYNC_JSON").contains(p.mode())||p.id()==null||!p.id().matches("[a-z][a-z0-9_-]{0,63}")||p.version()<1||p.version()>10000
                ||p.accountNamespace()==null||!p.accountNamespace().matches("[A-Za-z0-9_.-]{1,64}")
                ||p.provider()==null||p.model()==null||p.region()==null||p.credentialRef()==null||!p.credentialRef().matches("[A-Z][A-Z0-9_]{1,63}")
                ||!Set.of("Authorization","X-API-Key","api-key").contains(p.authHeader())||p.authPrefix()==null||p.authPrefix().contains("\n")||p.authPrefix().contains("\r")
                ||!Set.of("REGISTERED","PROTOCOL_TESTED","REAL_VERIFIED").contains(p.verificationStatus())||p.timeoutSeconds()<1||p.timeoutSeconds()>60
                ||p.pollIntervalSeconds()<5||p.pollIntervalSeconds()>60||p.maxPolls()<1||p.maxPolls()>60
                ||p.durations().isEmpty()||p.durations().size()>90||p.durations().stream().anyMatch(x->x<1||x>90)
                ||p.maxPromptCharacters()<1||p.maxPromptCharacters()>20000||p.resolutions().isEmpty()||p.resolutions().size()>10||p.audioModes().isEmpty()||!Set.of("NATIVE","NONE","TTS").containsAll(p.audioModes())
                ||!p.inputs().keySet().containsAll(Set.of("prompt","seconds"))||!Set.of("model","prompt","seconds","resolution","aspectRatio","audio").containsAll(p.inputs().keySet())
                ||!Set.of("PROCESSING","SUCCESS","FAIL","UNKNOWN").containsAll(p.statuses().values())||!p.statuses().containsValue("SUCCESS")
                ||p.headers().keySet().stream().anyMatch(k->!k.equals("X-DashScope-Async"))||p.headers().values().stream().anyMatch(v->v.contains("\n")||v.contains("\r")))throw invalid();
        for(String pointer:Arrays.asList(p.idPath(),p.statusPath(),p.resultUrlPath()))if(pointer==null||!pointer.matches("(/[A-Za-z0-9_-]+){1,8}"))throw invalid();
        if(p.usagePath()!=null&&!p.usagePath().isBlank()&&!p.usagePath().matches("(/[A-Za-z0-9_-]+){1,8}"))throw invalid();
        if(new HashSet<>(p.inputs().values()).size()!=p.inputs().size()||p.inputs().values().stream().anyMatch(v->!v.matches("(/[A-Za-z0-9_-]+){1,8}")))throw invalid();
        for(String left:p.inputs().values())for(String right:p.inputs().values())if(!left.equals(right)&&right.startsWith(left+"/"))throw invalid();
        try{if(JSON.writeValueAsBytes(p.body()).length>65536)throw invalid();ObjectNode template=JSON.valueToTree(p.body());p.inputs().values().forEach(path->put(template,path,JSON.getNodeFactory().nullNode()));}catch(Exception e){throw invalid();}
        endpoint(p.submitUrl(),local);if(p.mode().equals("ASYNC_JSON")){if(p.queryUrl()==null||!p.queryUrl().endsWith("/{id}"))throw invalid();endpoint(p.queryUrl().replace("{id}","validation"),local);}
        if(p.prices().values().stream().anyMatch(x->!Set.of("PER_SECOND","PER_VIDEO").contains(x.unit())))throw invalid();
    }
    /** 端点由后端管理员登记，仍禁止userinfo、片段、端口和非HTTPS；测试只允许127.0.0.1。 */
    private static void endpoint(String text,boolean local){
        try{var u=URI.create(text);if(u.getUserInfo()!=null||u.getFragment()!=null||u.getQuery()!=null||u.getHost()==null
                ||!("https".equals(u.getScheme())&&u.getPort()==-1||local&&"http".equals(u.getScheme())&&u.getHost().equals("127.0.0.1")))throw invalid();}catch(Exception e){throw invalid();}
    }
    /** 缺失或非字符串字段不强制转换成假ID。 */
    private static String string(JsonNode n){return n.isTextual()?n.textValue():null;}
    /** 配置错误不泄漏模板或凭证值。 */
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("视频注册配置无效");}
    /** 未登记能力不能购买。 */
    private static LabException unavailable(){return new LabException("MEDIA_CAPABILITY_UNAVAILABLE","视频能力或凭证尚未就绪");}
    /** 返回值不符合已登记查询协议。 */
    private static LabException protocol(){return new LabException("MEDIA_PROTOCOL_INVALID","视频响应不符合登记协议");}
}
