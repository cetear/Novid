package com.example.ailab.app;

import com.example.ailab.ai.model.*;
import com.example.ailab.ai.orchestration.media.MediaExecution;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.repository.SqlSupport;
import com.example.ailab.data.media.ControlledMediaFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import java.net.URI;

/** 本人批准的S09小额专项：真实适配器、SQL意图和JavaCV；不冒充资料检索或Planner端到端验收。 */
public final class S09PaidValidation {
    private static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    private static final String BATCH="s09_approved_20261005_water_cycle";
    private static final Path EVIDENCE=Path.of("var/stage-S09/paid");
    private static final String[] NARRATIONS={"水受热后，会变成水蒸气。","水蒸气遇冷，会凝结成小水滴。","蒸发和凝结，是水循环的一部分。"};
    /** 一次入口只推进一个已批准任务一步；再次运行读取同一SQL任务，不生成新的付费任务。 */
    public static void main(String[] args)throws Exception{
        String stage=args.length>=1?args[0]:"single";if(!Set.of("single","triple","image","image_retry").contains(stage))throw new IllegalArgumentException("未知专项");
        Files.createDirectories(EVIDENCE);TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        // 凭证只读入内存，不打印、不写证据、不改.env；完整保留多段密钥。
        var defaults=new LinkedHashMap<String,Object>(LocalEnvironmentLoader.read(Path.of(".env")));
        String privateText=Files.readString(Path.of("docs/API资源.txt"));
        var keyMatch=java.util.regex.Pattern.compile("(?m)^.*?密钥[^：:\\r\\n]*[：:]\\s*(\\S+)\\s*$").matcher(privateText);
        if(!keyMatch.find())throw new IllegalStateException("本地百炼凭证格式未识别，未调用API");
        defaults.put("BAILIAN_API_KEY",keyMatch.group(1));
        var workspaceMatch=java.util.regex.Pattern.compile("ws-[a-z0-9]+").matcher(privateText);
        if(!workspaceMatch.find()||!privateText.contains("北京"))throw new IllegalStateException("北京业务空间未核验");
        String workspace=workspaceMatch.group();
        var env=new StandardEnvironment();env.getPropertySources().addFirst(new MapPropertySource("private-local",defaults));
        var registry=new VideoApiRegistry(new VideoApiProperties(List.of(profile(true,workspace),profile(false,workspace))),env);
        var catalogs=catalogs();var config=new MediaProperties(true,false,true,"s09-paid-approved-v1",stage.equals("image_retry")?"https://open.bigmodel.cn/api/paas/v4/async/images/generations":null,null,null,null,null,null,null,false,true,price("glm-image","PER_IMAGE","0.1"),null,catalogs);
        var provider=new MediaModelGateway(config,env);provider.videoRegistry(registry);
        String hash=provider.configurationHash(stage.startsWith("image")?"IMAGE_GENERATION":"VIDEO_GENERATION");
        var app=new SpringApplication(LabApplication.class);app.setDefaultProperties(defaults);
        try(var context=app.run("--server.port=0","--server.address=127.0.0.1","--lab.bootstrap.enabled=false","--lab.task.worker-enabled=false","--lab.media.worker-enabled=false","--lab.ingestion.worker-enabled=false","--lab.governance.cleanup-enabled=false","--lab.observability.export-enabled=false","--lab.model.mode=mock","--lab.search.enabled=false","--spring.main.banner-mode=off","--logging.level.root=OFF")){
            var sql=ValidationSql.from(context);var tasks=context.getBean(TaskStorePort.class);var media=context.getBean(MediaStorePort.class);
            var tx=new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            // 固定身份及幂等键只属于此批准批次。原三份上限19.5元，另经本人明确批准的新图最多0.5元，总计20元。
            long actorId=tx.execute(s->{var ids=sql.jdbc.queryForList("SELECT id FROM users WHERE username=? FOR UPDATE",Long.class,BATCH);return ids.isEmpty()?sql.insert("INSERT INTO users(username,password_hash,role,password_change_required) VALUES(?,'disabled-validation-login','USER',FALSE)",BATCH):ids.get(0);});
            var actor=new UserContext(actorId,UserContext.Role.USER,true,1,false);
            if(Arrays.asList(args).contains("reject-triple")){
                for(long triple:sql.jdbc.queryForList("SELECT id FROM ai_tasks WHERE requester_user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.topic'))=? AND status='WAITING_MEDIA_REVIEW'",Long.class,actorId,"S09真实小额-triple"))
                    media.review(actor,triple,media.preview(actor,triple).orElseThrow().previewVersion(),false,"用户人工验收：万相原声无问题；Vidu加独立TTS有明显延迟及噪音，跨API风格差异无法衔接。同片不再混用API。");
            }
            // 只有人工明确给出验收通过后，由专项参数登记；不能根据技术成功自动验收。
            if(args.length>1&&args[1].equals("accept-single")){
                for(long single:sql.jdbc.queryForList("SELECT id FROM ai_tasks WHERE requester_user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.topic'))=? AND status='WAITING_MEDIA_REVIEW'",Long.class,actorId,"S09真实小额-single"))
                    media.review(actor,single,media.preview(actor,single).orElseThrow().previewVersion(),true,"用户在本会话播放单镜头后明确回复：人工验收通过。");
            }
            if(!stage.equals("single")){
                var ready=sql.jdbc.queryForList("SELECT status FROM ai_tasks WHERE requester_user_id=? AND JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.topic'))=?",String.class,actorId,"S09真实小额-single");
                if(ready.isEmpty()||!Set.of("WAITING_MEDIA_REVIEW","SUCCEEDED").contains(ready.get(0)))throw new IllegalStateException("先完成单镜头技术验证，尚未允许后续购买");
            }
            int count=stage.equals("triple")?3:1;BigDecimal cap=new BigDecimal(stage.equals("single")?"3":stage.equals("triple")?"16":"0.5");
            boolean image=stage.startsWith("image");
            var request=new TaskRequest(image?"NOTES_PPT":"NOTES_VIDEO","S09真实小额-"+stage,new ScopeRequest(ScopeRequest.Mode.SELF,List.of(),null),List.of(),BATCH+"-"+stage,"PLANNED",image?new Media.PresentationOptions(1,"concept",cap,"GENERATED"):null,image?null:new Media.VideoOptions("s09-character","s09-voice","s09-scene",count*5,cap,count,false));
            var task=tasks.create(actor,request);long id=task.taskId();
            if(stage.equals("triple")&&media.preview(actor,id).isEmpty())throw new IllegalStateException("旧混合配音验收方案已移除，不能沿用旧审批购买三镜头");
            if(media.preview(actor,id).isEmpty()){
                var units=new ArrayList<Media.Unit>();var choices=new ArrayList<VideoApi.Selection>();
                for(int i=0;!image&&i<count;i++){
                    units.add(new Media.Unit("shot-"+(i+1),"水循环概念示意",NARRATIONS[i],"教师缓慢指向示意图，镜头平稳。","SCENE","NONE","蓝衣卡通教师在简洁课堂讲解水循环概念示意；不是实验记录；不要添加多余字幕。",List.of("APPROVED-SYNTHETIC-SCRIPT"),5));
                    choices.add(provider.selectVideo(new VideoApi.Recommendation("shot-"+(i+1),"bailian-wan3","480P","NATIVE",5,"本人批准的单镜头原声验收"),catalogs));
                }
                if(image)units.add(new Media.Unit("slide-1","水循环概念示意","蒸发和凝结是水循环的一部分。","生成概念图，不作为实验记录或事实照片。","IMAGE_TEXT","GENERATED","绘制一张简洁的水循环教学概念图，展示太阳加热水面、向上蒸发、水蒸气遇冷凝结为小水滴；蓝白配色，画面显著标注中文“概念示意”，不要添加其他说明文字，不含真人。",List.of("APPROVED-SYNTHETIC-SCRIPT"),0));
                var board=image?null:StoryboardRules.routed(1,units,choices);var preview=new Media.Preview(id,1,1,image?MediaModelGateway.hash(JSON.writeValueAsString(units)):board.hash(),"WAITING",UUID.randomUUID().toString(),Instant.now().plusSeconds(1800),hash,"CNY",new BigDecimal(image?"0.1":stage.equals("single")?"1.5":"5.5036"),cap,units,List.of(),List.of(),image?List.of():catalogs,List.of(),"USER_APPROVED_SYNTHETIC_SCRIPT_NOT_PLANNER",board);
                media.prepare(lease(sql,tasks,actor,request,id),preview);
                media.decide(actor,preview.approvalId(),true,hash,Map.of("IMAGE_GENERATION",config.imagePrice()),Map.of("IMAGE_GENERATION","glm-image"));
            }
            String status=tasks.read(actor,id).status();
            var prior=media.preview(actor,id).orElseThrow();
            // 仅修复从未发送的旧摘要算法；脚本、路由和金额仍使用本次已批准内容，不适用于任何已发送操作。
            if(status.equals("NEEDS_RECONCILIATION")&&!prior.configurationHash().equals(hash)
                    &&media.operations(actor,id).stream().allMatch(o->o.state().equals("RESERVED"))){
                var corrected=media.edit(actor,id,prior.previewVersion(),prior.units(),hash);
                media.decide(actor,corrected.approvalId(),true,hash,Map.of("IMAGE_GENERATION",config.imagePrice()),Map.of("IMAGE_GENERATION","glm-image"));
                status=tasks.read(actor,id).status();
            }
            // 远程均已成功时仅恢复本地处理，任务共同截止和本地两次上限保持不变。
            if(status.equals("NEEDS_RECONCILIATION")&&media.operations(actor,id).stream().allMatch(o->o.state().equals("SUCCEEDED"))){
                tasks.action(actor,id,"resume");status=tasks.read(actor,id).status();
            }
            if(Set.of("WAITING_MEDIA_REVIEW","MEDIA_READY","SUCCEEDED","NEEDS_RECONCILIATION","FAILED","PAUSED").contains(status)){evidence(sql,media,actor,id,stage,status);return;}
            // 仅允许官方返回的OSS公共结果域；精确主机仍交给生产存储校验DNS和重定向。
            var hosts=new TreeSet<String>();
            for(String raw:sql.jdbc.queryForList("SELECT response_json FROM media_operations WHERE task_id=? AND response_json IS NOT NULL",String.class,id)){
                var result=JSON.readValue(raw,Media.ProviderResult.class);for(String url:result.urls()){
                    String host=URI.create(url).getHost();if(host!=null&&(host.endsWith(".aliyuncs.com")||host.endsWith(".bigmodel.cn")))hosts.add(host);
                }
            }
            var files=new ControlledMediaFiles("var/media",String.join(",",hosts),"",""){
                /** 仅本专项接受提供方响应中的公共OSS结果地址；生产部署仍配置精确白名单。 */
                @Override public Media.FileFact fetch(String assetId,String url,String kind){
                    String host=URI.create(url).getHost();
                    if(host==null||!(host.matches("[a-zA-Z0-9.-]+\\.oss[a-zA-Z0-9.-]*\\.aliyuncs\\.com")||host.endsWith(".bigmodel.cn")))throw new LabException("MEDIA_DOWNLOAD_HOST_UNVERIFIED","结果下载主机尚未核验");
                    return new ControlledMediaFiles("var/media",host,"","").fetch(assetId,url,kind);
                }
            };
            if(!files.videoRuntimeAvailable())throw new IllegalStateException("JavaCV未就绪，不发付费请求");
            var lease=lease(sql,tasks,actor,request,id);
            try{new MediaExecution(media,provider,files).execute(lease,hash);}
            catch(LabException error){media.yield(lease,"NEEDS_RECONCILIATION",error.code());System.out.println("S09_PAID_ERROR="+error.code());}
            evidence(sql,media,actor,id,stage,tasks.read(actor,id).status());
        }
    }
    /** 不领取正式队列；只为固定批准任务续租，并递增围栏。 */
    private static TaskLease lease(ValidationSql sql,TaskStorePort tasks,UserContext actor,TaskRequest request,long id){
        sql.jdbc.update("UPDATE ai_tasks SET status='RUNNING',worker_id='s09-paid-validation',fencing_token=fencing_token+1,claimed_at=CURRENT_TIMESTAMP(6),lease_until=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 180 SECOND) WHERE id=? AND requester_user_id=?",id,actor.userId());
        long fence=sql.jdbc.queryForObject("SELECT fencing_token FROM ai_tasks WHERE id=?",Long.class,id);return new TaskLease(tasks.read(actor,id),request,actor,"s09-paid-validation",fence);
    }
    /** 证据保留原ID和SQL状态，不保存签名下载URL或认证信息。 */
    private static void evidence(ValidationSql sql,MediaStorePort media,UserContext actor,long id,String stage,String status)throws Exception{
        var value=new LinkedHashMap<String,Object>();value.put("taskId",id);value.put("stage",stage);value.put("status",status);value.put("batchMaximumCny",20);value.put("operations",media.operations(actor,id));value.put("shots",media.shots(actor,id));value.put("fees",sql.jdbc.queryForList("SELECT f.operation_id,f.state,f.reserved_units,f.used_units FROM fee_attempts f JOIN media_operations m ON m.operation_id=f.operation_id WHERE m.task_id=?",id));
        value.put("artifacts",sql.jdbc.queryForList("SELECT id,kind,filename,storage_key,byte_size,checksum,published FROM artifacts WHERE task_id=?",id));
        value.put("humanReviews",sql.jdbc.queryForList("SELECT preview_version,accepted,note FROM media_human_reviews WHERE task_id=?",id));
        var hosts=new TreeSet<String>();for(String raw:sql.jdbc.queryForList("SELECT response_json FROM media_operations WHERE task_id=? AND response_json IS NOT NULL",String.class,id))for(String url:JSON.readValue(raw,Media.ProviderResult.class).urls())hosts.add(URI.create(url).getHost());value.put("resultHosts",hosts);
        value.put("batchFees",sql.jdbc.queryForList("SELECT m.task_id,m.operation_id,m.capability,m.state AS media_state,f.state AS fee_state,f.reserved_amount,f.estimated_amount,f.reserved_units,f.used_units FROM media_operations m JOIN fee_attempts f ON f.operation_id=m.operation_id JOIN ai_tasks t ON t.id=m.task_id WHERE t.requester_user_id=? ORDER BY m.task_id,m.operation_id",actor.userId()));
        Files.writeString(EVIDENCE.resolve(stage+"-status.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));System.out.println("S09_PAID_TASK="+id+" STATUS="+status);
    }
    /** 公开原价只作本轮保守预留；账户账单和折扣未核验，不能标为最终实付。 */
    private static FeePrice price(String id,String unit,String value){return new FeePrice(id,"official-public-20261005","CNY",unit,Instant.parse("2026-10-04T00:00:00Z"),new BigDecimal(value),BigDecimal.ZERO);}
    /** API差异全部通过同一生产注册器配置表达，原版本完整保留供查询。 */
    private static VideoApiProperties.Profile profile(boolean wan,String workspace){
        String base="https://"+workspace+".cn-beijing.maas.aliyuncs.com/api/v1";
        return new VideoApiProperties.Profile(wan?"bailian-wan3":"zhipu-vidu",1,true,"REGISTERED",wan?"bailian":"bigmodel",wan?"s09-bailian-beijing":"s09-bigmodel","cn-beijing",wan?"wan3.0-video":"viduq1-text",wan?"BAILIAN_API_KEY":"MODEL_BACKUP_API_KEY","Authorization","Bearer ",wan?base+"/services/aigc/video-generation/video-synthesis":"https://open.bigmodel.cn/api/paas/v4/videos/generations",wan?base+"/tasks/{id}":"https://open.bigmodel.cn/api/paas/v4/async-result/{id}",wan?Map.of("X-DashScope-Async","enable"):Map.of(),wan?Map.of("model","wan3.0-video","parameters",Map.of("prompt_extend",false,"watermark",true)):Map.of("model","viduq1-text","aspect_ratio","16:9"),wan?Map.of("prompt","/input/prompt","seconds","/parameters/duration","resolution","/parameters/resolution","aspectRatio","/parameters/ratio","audio","/parameters/audio"):Map.of("prompt","/prompt","seconds","/duration"),wan?"/output/task_id":"/id",wan?"/output/task_status":"/task_status",wan?"/output/video_url":"/video_result/0/url",wan?"/usage/duration":null,wan?Map.of("PENDING","PROCESSING","RUNNING","PROCESSING","SUCCEEDED","SUCCESS","FAILED","FAIL","CANCELED","FAIL","UNKNOWN","UNKNOWN"):Map.of("PROCESSING","PROCESSING","SUCCESS","SUCCESS","FAIL","FAIL"),List.of(5),List.of(wan?"480P":"1080P"),List.of(wan?"NATIVE":"TTS"),Map.of(wan?"480P":"1080P",price(wan?"wan3.0-video":"viduq1-text",wan?"PER_SECOND":"PER_VIDEO",wan?"0.3":"2.5")),true,15,60,30,wan?20000:512,"ASYNC_JSON");
    }
    /** 本人批准的提示映射不冒充厂商原生人物ID；原声台词仍须人工听验。 */
    private static List<Media.CatalogItem> catalogs(){
        return List.of(item("character","CHARACTER","蓝衣卡通教师","蓝衣卡通教师"),item("voice","VOICE","清晰普通话女声","清晰普通话女声，只说批准台词，不加音乐"),item("scene","SCENE","简洁课堂","明亮简洁课堂，水循环概念示意"));
    }
    /** 原生声音提示与GLM-TTS音色分别登记，不互相替代。 */
    private static Media.CatalogItem item(String id,String kind,String label,String prompt){var maps=new HashMap<String,String>();maps.put("wan3.0-video/PROMPT_GUIDANCE",prompt);maps.put("viduq1-text/PROMPT_GUIDANCE",prompt);return new Media.CatalogItem("s09-"+id,kind,1,label,true,"multi","PROMPT_GUIDANCE",prompt,maps);}
}
