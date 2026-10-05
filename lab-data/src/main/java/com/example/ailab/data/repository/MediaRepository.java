package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** S09可靠媒体状态图；审批／预算／操作原子绑定，外部提交和下载始终在事务外。 */
@Repository
public class MediaRepository implements MediaStorePort {
    private final SqlSupport sql;
    private final TaskRepository tasks;
    private final DocumentSqlRepository docs;
    private final FeeStorePort fees;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    /** 所有写入沿用system_control→actor→task→scope→operation锁序。 */
    public MediaRepository(SqlSupport sql, TaskRepository tasks, DocumentSqlRepository docs, FeeStorePort fees) {
        this.sql=sql; this.tasks=tasks; this.docs=docs; this.fees=fees;
    }
    /** 私人预览与来源二次复核；没有预览时不返回模型草稿。 */
    @Transactional(readOnly=true)
    public Optional<Media.Preview> preview(UserContext actor,long taskId) {
        tasks.read(actor,taskId);
        var values=sql.jdbc.queryForList("SELECT preview_json,status FROM generation_previews WHERE task_id=? ORDER BY preview_version DESC LIMIT 1",taskId);
        if(values.isEmpty()) return Optional.empty();
        var p=decode((String)values.get(0).get("preview_json"),Media.Preview.class);
        verify(actor,p.sourceDependencies());
        return Optional.of(copy(p,(String)values.get(0).get("status"),loadAssets(taskId)));
    }
    /** 每版计划不可覆盖，hash复核后登记真实模型／策略事实。 */
    @Transactional
    public void plan(TaskLease lease,Media.PlanSnapshot p) {
        tasks.valid(lease);
        if(p.plan().planVersion()<1 || p.plan().planVersion()>2 || !SqlSupport.hash(encode(p.plan())).equals(p.hash())) throw LabException.invalid("计划版本或摘要无效");
        var old=plans(lease.actor(),lease.task().taskId()).stream().filter(x->x.plan().planVersion()==p.plan().planVersion()).findFirst();
        if(old.isPresent()) { if(!old.get().equals(p)) throw conflict(); return; }
        sql.jdbc.update("INSERT INTO media_plans(task_id,plan_version,plan_hash,plan_json,model_id,policy_version) VALUES(?,?,?,?,?,?)",lease.task().taskId(),p.plan().planVersion(),p.hash(),encode(p.plan()),p.modelId(),p.policyVersion());
        phase(lease,"EXECUTING_AGENTS");
    }
    /** 本人计划按版本返回，不删除初版或隐藏返工历史。 */
    @Transactional(readOnly=true)
    public List<Media.PlanSnapshot> plans(UserContext actor,long taskId) {
        tasks.read(actor,taskId);
        return sql.jdbc.query("SELECT * FROM media_plans WHERE task_id=? ORDER BY plan_version",(r,n)->{
            var p=decode(r.getString("plan_json"),Media.Plan.class);
            if(!SqlSupport.hash(encode(p)).equals(r.getString("plan_hash"))) throw conflict();
            return new Media.PlanSnapshot(p,r.getString("plan_hash"),r.getString("model_id"),r.getString("policy_version"));
        },taskId);
    }
    /** 恢复只取当前计划版本；成功结果不得跨输入摘要复用。 */
    @Transactional
    public List<Media.WorkerResult> results(TaskLease lease,int version) {
        tasks.valid(lease);
        return sql.jdbc.query("SELECT result_json FROM media_worker_results WHERE task_id=? AND plan_version=? ORDER BY step_id",(r,n)->decode(r.getString(1),Media.WorkerResult.class),lease.task().taskId(),version);
    }
    /** 保存校验后角色结果，fencing保护与预算无关的迟到提交。 */
    @Transactional
    public void result(TaskLease lease,int version,Media.WorkerResult value) {
        tasks.valid(lease);
        var old=results(lease,version).stream().filter(r->r.stepId().equals(value.stepId())).findFirst();
        if(old.isPresent()) { if(!old.get().equals(value)) throw conflict(); return; }
        sql.jdbc.update("INSERT INTO media_worker_results(task_id,plan_version,step_id,input_hash,result_json) VALUES(?,?,?,?,?)",lease.task().taskId(),version,value.stepId(),value.inputHash(),encode(value));
    }
    /** 返工与重规划额度单调，字段名仅程序白名单，绝不执行模型SQL。 */
    @Transactional
    public void consume(TaskLease lease,String kind) {
        tasks.valid(lease);
        String column=switch(kind) { case "REPLAN"->"media_replans"; case "REWORK"->"media_reworks"; default->throw LabException.invalid("未知媒体预算"); };
        if(sql.jdbc.update("UPDATE ai_tasks SET "+column+"="+column+"+1 WHERE id=? AND "+column+"<1",lease.task().taskId())!=1)
            throw new LabException("BUDGET_EXCEEDED","有限质检返工预算耗尽");
    }
    /** 来源由服务端准备且不能被用户删除；目录不可变快照单独保留版本。 */
    @Transactional
    public Media.Preview prepare(TaskLease lease,Media.Preview value) {
        tasks.valid(lease); verify(lease.actor(),value.sourceDependencies());
        if(value.taskId()!=lease.task().taskId() || value.previewVersion()!=1 || value.units().isEmpty() || value.units().size()>12) throw LabException.invalid("预览范围无效");
        for(var c:value.catalogs()) {
            var old=sql.jdbc.queryForList("SELECT item_json FROM media_catalog_items WHERE item_id=? AND version=?",String.class,c.id(),c.version());
            // MySQL JSON会规范化空白和键序；比较类型化值，不能把存储格式差异当目录篡改。
            if(!old.isEmpty()&&!decode(old.get(0),Media.CatalogItem.class).equals(c)) throw new LabException("PREVIEW_CHANGED","目录同版本映射发生变化");
            if(old.isEmpty()) sql.jdbc.update("INSERT INTO media_catalog_items(item_id,version,kind,enabled,item_json) VALUES(?,?,?,?,?)",c.id(),c.version(),c.kind(),c.enabled(),encode(c));
        }
        if(lease.request().taskType().equals("NOTES_VIDEO")&&(value.storyboard()==null||!value.storyboard().equals(StoryboardRules.recreate(value.previewVersion(),value.units(),value.storyboard()))))throw conflict();
        insertPreview(value);
        sql.jdbc.update("UPDATE task_step_progress SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6) WHERE task_id=? AND step_id IN ('research','analysis','report')",value.taskId());
        this.yield(lease,"WAITING_APPROVAL",null); return value;
    }
    /** 编辑推进预览与批准版本；已付费PPT保留素材事实并撤回旧导出，未变成功素材可复用。 */
    @Transactional
    public Media.Preview edit(UserContext actor,long taskId,int version,List<Media.Unit> units,String configurationHash) {
        return edit(actor,taskId,version,units,configurationHash,null);
    }
    /** 路由调整也必须使原批准失效；旧外部任务仍未明确时禁止新购。 */
    @Transactional
    public Media.Preview edit(UserContext actor,long taskId,int version,List<Media.Unit> units,String configurationHash,List<VideoApi.Selection> selections) {
        sql.actor(actor,true); var t=tasks.read(actor,taskId); var old=preview(actor,taskId).orElseThrow(LabException::denied);
        if(!(Set.of("WAITING_APPROVAL","FAILED","NEEDS_RECONCILIATION","PAUSED").contains(t.status())
                ||t.taskType().equals("NOTES_PPT")&&Set.of("MEDIA_READY","WAITING_MEDIA_REVIEW","SUCCEEDED").contains(t.status())) || !Set.of("WAITING","APPROVED").contains(old.status()) || old.previewVersion()!=version || version>=10
                ) throw new LabException("PREVIEW_CHANGED","预览或配置已变化");
        if(operations(actor,taskId).stream().anyMatch(o->Set.of("SENDING","UNKNOWN","WAITING_EXTERNAL").contains(o.state())))throw new LabException("MEDIA_SUBMISSION_UNKNOWN","原外部操作仍未明确，先核对原ID，不能借编辑重新购买");
        // 只有确定未发送的预留释放；已成功／已发送未知账本永不退款。
        for(var row:sql.jdbc.queryForList("SELECT operation_id,fee_scope_id FROM media_operations WHERE task_id=? AND state='RESERVED'",taskId)){
            fees.release(new FeeReservation((String)row.get("operation_id"),(String)row.get("fee_scope_id")));
            sql.jdbc.update("UPDATE media_operations SET state='INVALIDATED' WHERE operation_id=?",row.get("operation_id"));
        }
        sql.jdbc.update("UPDATE generation_previews SET status='INVALIDATED' WHERE task_id=? AND preview_version=?",taskId,version);
        // 修改批准内容立即撤下旧PPT及页面，原付费素材、费用与角色结果保持。
        if(t.taskType().equals("NOTES_PPT")){
            sql.jdbc.update("UPDATE artifacts SET published=FALSE WHERE task_id=? AND kind IN ('PPTX','PAGE_PREVIEW','PPT_CHECK')",taskId);
            sql.jdbc.update("UPDATE ai_tasks SET artifact_id=NULL WHERE id=?",taskId);
        }
        var board=old.storyboard()==null?null:selections==null?StoryboardRules.recreate(version+1,units,old.storyboard()):StoryboardRules.routed(version+1,units,selections);
        String hash=SqlSupport.hash(encode(units)+encode(board)+configurationHash+encode(old.sourceDependencies())+encode(old.catalogs())+old.maximumAmount());
        var p=new Media.Preview(taskId,version+1,old.planVersion(),hash,"WAITING",UUID.randomUUID().toString(),Instant.now().plusSeconds(1800),
                configurationHash,old.currency(),null,old.maximumAmount(),units,old.sourceDependencies(),old.coverage(),old.catalogs(),old.assets(),"EDITED_REQUIRES_STRUCTURAL_CHECK_AND_HUMAN_REVIEW",
                board);
        insertPreview(p);
        sql.jdbc.update("UPDATE ai_tasks SET status='WAITING_APPROVAL',media_phase='REAPPROVAL_REQUIRED',error_code=NULL,media_remaining_seconds=IF(media_deadline IS NULL,media_remaining_seconds,GREATEST(0,TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP(6),media_deadline))),media_deadline=NULL,fencing_token=fencing_token+1,state_version=state_version+1 WHERE id=?",taskId);return p;
    }
    /** 同一短事务消费本人确认并预留全部图片／视频／配音费用，任一缺价整笔回滚。 */
    @Transactional
    public Media.Preview decide(UserContext actor,String approvalId,boolean approved,String configurationHash,
                                Map<String,FeePrice> prices,Map<String,String> models) {
        sql.actor(actor,true);
        var ids=sql.jdbc.queryForList("SELECT task_id FROM generation_previews WHERE approval_id=?",Long.class,approvalId);
        if(ids.isEmpty()) throw LabException.denied();
        var t=tasks.read(actor,ids.get(0)); var p=preview(actor,t.taskId()).orElseThrow(LabException::denied);
        if(!approvalId.equals(p.approvalId())) throw new LabException("PREVIEW_CHANGED","批准对应旧预览");
        if(p.status().equals("APPROVED")&&approved) return p;
        if(!t.status().equals("WAITING_APPROVAL") || !p.status().equals("WAITING")) throw conflict();
        if(!p.configurationHash().equals(configurationHash)) throw new LabException("PREVIEW_CHANGED","模型／目录／报价配置已变化");
        if(!p.expiresAt().isAfter(Instant.now())) throw new LabException("APPROVAL_EXPIRED","媒体批准已过期");
        if(!approved) {
            sql.jdbc.update("UPDATE generation_previews SET status='REJECTED' WHERE approval_id=?",approvalId);
            sql.jdbc.update("UPDATE ai_tasks SET status='CANCELLED',media_phase='REJECTED',fencing_token=fencing_token+1,state_version=state_version+1 WHERE id=?",t.taskId());
            return copy(p,"REJECTED",p.assets());
        }
        boolean video=t.taskType().equals("NOTES_VIDEO");
        // PPT来源页与图片数先限制，再产生任何购买意图，防止导出阶段才发现超额。
        if(!video&&(p.units().size()>11||p.units().stream().filter(u->!u.imageMode().equals("NONE")).count()>8))throw new LabException("PPT_PAGE_LIMIT","PPT内容加来源最多12页，配图最多8张");
        var submissions=new ArrayList<Media.Submission>();
        var operationUnits=new HashMap<String,String>();
        if(video) {
            if(p.storyboard()==null||!p.storyboard().equals(StoryboardRules.recreate(p.previewVersion(),p.units(),p.storyboard())))throw new LabException("PREVIEW_CHANGED","旧整片预览不能批准为分镜");
            for(var shot:p.storyboard().shots()){
                if(shot.video()==null)throw new LabException("PREVIEW_CHANGED","镜头缺少登记视频路由，请重新规划");
                StoryboardRules.audioPolicy(shot.video(),shot.narration());
                String narration=shot.video().audioMode().equals("NATIVE")?"\n批准台词："+shot.narration():"\n无声视频，不生成台词或音轨。";
                var clip=new Media.Submission(UUID.randomUUID().toString(),"VIDEO_GENERATION",shot.visualPrompt()+"\n"+shot.motionPrompt()+narration,shot.maximumDurationSeconds(),p.catalogs(),shot.video());
                submissions.add(clip);operationUnits.put(clip.operationId(),shot.shotId());
                sql.jdbc.update("INSERT INTO media_shot_execution(task_id,preview_version,shot_id,approval_hash) VALUES(?,?,?,?)",p.taskId(),p.previewVersion(),shot.shotId(),p.hash());
            }
        } else for(var unit:p.units()) if(unit.imageMode().equals("GENERATED"))
            submissions.add(new Media.Submission(UUID.randomUUID().toString(),"IMAGE_GENERATION",unit.imagePrompt(),0,List.of()));
        BigDecimal estimate=BigDecimal.ZERO;
        for(var s:submissions) {
            var price=s.video()==null?prices.get(s.capability()):s.video().price();
            if(price==null || !price.currency().equals(p.currency()) || price.effectiveAt().isAfter(Instant.now())) throw new LabException("FEE_PRICE_UNAVAILABLE","媒体缺少当前生效报价");
            estimate=estimate.add(price.amount(quantity(price,s),0));
        }
        if(estimate.compareTo(p.maximumAmount())>0) throw new LabException("BUDGET_EXCEEDED","新媒体估价超过批准上限");
        String run=UUID.randomUUID().toString(); int imageIndex=0;
        for(var s:submissions) {
            var price=s.video()==null?prices.get(s.capability()):s.video().price();
            String unit=s.capability().equals("IMAGE_GENERATION") ? p.units().stream().filter(u->u.imageMode().equals("GENERATED")).toList().get(imageIndex++).unitId() : operationUnits.get(s.operationId());
            String input=SqlSupport.hash(s.capability()+":"+s.prompt()+":"+s.seconds()+":"+encode(s.catalogs())+":"+encode(s.video()));
            var reusable=sql.jdbc.queryForList("SELECT operation_id,asset_id FROM media_operations WHERE task_id=? AND unit_id=? AND capability=? AND input_hash=? AND state='SUCCEEDED' ORDER BY preview_version DESC LIMIT 1",p.taskId(),unit,s.capability(),input);
            String operation=s.operationId();
            if(reusable.isEmpty()){
                var fee=fees.reserve(new FeeScope(actor,"TASK",Long.toString(t.taskId()),run),operation,s.video()==null?models.get(s.capability()):s.video().capability().model(),s.capability(),quantity(price,s),0,price,p.currency(),p.maximumAmount(),300000,false);
                sql.jdbc.update("INSERT INTO media_operations(operation_id,task_id,preview_version,unit_id,capability,input_hash,submission_json,fee_scope_id) VALUES(?,?,?,?,?,?,?,?)",operation,t.taskId(),p.previewVersion(),unit,s.capability(),input,encode(s),fee.scopeId());
                if(s.video()!=null)sql.jdbc.update("UPDATE media_operations SET provider_namespace=? WHERE operation_id=?",s.video().capability().accountNamespace(),operation);
            }else{
                operation=(String)reusable.get(0).get("operation_id");String field=s.capability().equals("VIDEO_GENERATION")?"video_asset_id":null;
                if(field!=null)sql.jdbc.update("UPDATE media_shot_execution SET "+field+"=? WHERE task_id=? AND preview_version=? AND shot_id=?",reusable.get(0).get("asset_id"),p.taskId(),p.previewVersion(),unit);
            }
            sql.jdbc.update("INSERT INTO media_preview_operations(task_id,preview_version,operation_id) VALUES(?,?,?)",p.taskId(),p.previewVersion(),operation);
        }
        sql.jdbc.update("UPDATE generation_previews SET status='APPROVED' WHERE approval_id=? AND status='WAITING'",approvalId);
        // 审批等待不计入期限；批准后外部等待和停机均受同一个剩余期限约束。
        sql.jdbc.update("UPDATE ai_tasks SET status='QUEUED',media_phase='APPROVED_MEDIA_PENDING',media_deadline=COALESCE(media_deadline,DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL LEAST(media_remaining_seconds,1200-total_execution_seconds) SECOND)),state_version=state_version+1 WHERE id=?",t.taskId());
        return copy(p,"APPROVED",p.assets());
    }
    /** 单位随已核验价快照解释，不能强套词元或把秒数当张数。 */
    private long quantity(FeePrice price,Media.Submission s) {
        boolean valid=s.capability().equals("IMAGE_GENERATION")?price.unit().equals("PER_IMAGE"):
                s.capability().equals("VIDEO_GENERATION")&&Set.of("PER_VIDEO","PER_SECOND").contains(price.unit());
        if(!valid)throw new LabException("FEE_PRICE_UNAVAILABLE","媒体能力与计费单位不匹配");
        return switch(price.unit()) { case "PER_IMAGE","PER_VIDEO"->1; case "PER_SECOND"->s.seconds(); default->throw new LabException("FEE_PRICE_UNAVAILABLE","媒体计费单位不匹配"); };
    }
    /** 本人操作只返回状态事实及原ID，不提供外部任意ID查询入口。 */
    @Transactional(readOnly=true)
    public List<Media.Operation> operations(UserContext actor,long taskId) {
        tasks.read(actor,taskId);var p=preview(actor,taskId);if(p.isEmpty())return List.of();
        return sql.jdbc.query("SELECT m.*,f.state AS fee_state FROM media_operations m JOIN fee_attempts f ON f.operation_id=m.operation_id JOIN media_preview_operations x ON x.operation_id=m.operation_id WHERE x.task_id=? AND x.preview_version=? ORDER BY m.capability,m.unit_id",(r,n)->new Media.Operation(r.getString("operation_id"),taskId,r.getInt("preview_version"),r.getString("unit_id"),r.getString("capability"),r.getString("state"),r.getString("provider_job_id"),r.getString("provider_status"),r.getInt("poll_count"),instant(r,"last_poll_at"),instant(r,"next_poll_at"),instant(r,"deadline"),r.getString("error_code"),r.getString("asset_id"),r.getString("fee_state")),taskId,p.get().previewVersion());
    }
    /** 一次性发送许可，持久attempt／工具／费用在同一事务提交。 */
    @Transactional
    public Media.Submission sending(TaskLease lease,String operationId) {
        tasks.valid(lease); var row=operation(lease,operationId);
        var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(LabException::denied);
        if(!p.status().equals("APPROVED") || !p.expiresAt().isAfter(Instant.now())) throw new LabException("APPROVAL_EXPIRED","新付费提交需要有效本人批准");
        if(!row.get("state").equals("RESERVED") || ((Number)row.get("preview_version")).intValue()!=p.previewVersion()) throw conflict();
        var submission=decode((String)row.get("submission_json"),Media.Submission.class);
        if(submission.capability().equals("AUDIO_GENERATION"))throw new LabException("MEDIA_TTS_DISABLED","独立配音生成已移除");
        if(submission.capability().equals("VIDEO_GENERATION")){
            StoryboardRules.singleVideoApi(p.storyboard().shots().stream().map(Media.Shot::video).toList());
            var approved=p.storyboard().shots().stream().filter(s->s.shotId().equals(row.get("unit_id"))).findFirst().orElseThrow(this::conflict);
            StoryboardRules.audioPolicy(approved.video(),approved.narration());
        }
        checkDeadline(lease);
        tasks.reserveModelAttempt(lease); tasks.reserveToolCall(lease);
        fees.sending(new FeeReservation(operationId,(String)row.get("fee_scope_id")));
        sql.jdbc.update("UPDATE media_operations SET state='SENDING',deadline=(SELECT media_deadline FROM ai_tasks WHERE id=?) WHERE operation_id=? AND state='RESERVED'",p.taskId(),operationId);
        sql.jdbc.update("INSERT INTO media_attempts(operation_id,action,attempt,fencing_token) VALUES(?,'SUBMIT',1,?)",operationId,lease.fencingToken());
        phase(lease,"SUBMITTING_MEDIA"); return submission;
    }
    /** 迟到响应只能补原ID和费用事实；保存真实id先于任何取回／发布，绝不从request_id补id。 */
    @Transactional
    public void received(String operationId,Media.ProviderResult response) {
        var initial=sql.jdbc.queryForMap("SELECT task_id FROM media_operations WHERE operation_id=?",operationId);
        // 外部事实补录也遵守全局锁序，防与取消、费用维护相反顺序死锁。
        sql.jdbc.queryForObject("SELECT id FROM system_control WHERE id=1 FOR UPDATE",Integer.class);
        var row=sql.jdbc.queryForMap("SELECT * FROM media_operations WHERE operation_id=? FOR UPDATE",operationId);
        String old=(String)row.get("provider_job_id");
        if(old!=null && response.providerJobId()!=null && !old.equals(response.providerJobId())) throw conflict();
        if(Set.of("SUCCEEDED","REMOTE_READY","FAILED").contains(row.get("state"))) {
            // 已完成操作仍接收迟到的真实用量，但不回退成功状态或更换原ID。
            if(response.usageUnits()!=null)fees.completeMedia(new FeeReservation(operationId,(String)row.get("fee_scope_id")),response.usageUnits(),row.get("state").equals("FAILED")?"FAILED":"SUCCESS");
            return;
        }
        if(!Set.of("SENDING","UNKNOWN","WAITING_EXTERNAL").contains(row.get("state")))throw conflict();
        String id=old==null?response.providerJobId():old;
        String next=switch(response.status()) { case "SUCCESS"->"REMOTE_READY"; case "FAIL"->"FAILED"; case "UNKNOWN"->"UNKNOWN"; default->id==null?"UNKNOWN":"WAITING_EXTERNAL"; };
        fees.completeMedia(new FeeReservation(operationId,(String)row.get("fee_scope_id")),response.usageUnits(),response.status().equals("SUCCESS")?"SUCCESS":response.status().equals("FAIL")?"FAILED":"RESULT_UNKNOWN");
        var original=decode((String)row.get("submission_json"),Media.Submission.class);
        long polls=((Number)row.get("poll_count")).longValue(); int delay=original.video()==null?(int)Math.min(30,5L+polls*5L):original.video().capability().pollIntervalSeconds();
        sql.jdbc.update("UPDATE media_operations SET state=?,provider_job_id=?,provider_status=?,response_json=?,error_code=?,next_poll_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL ? SECOND) WHERE operation_id=?",
                next,id,response.status(),encode(response),response.errorCode(),delay,operationId);
    }
    /** 查询前持久消费独立次数／期限与fencing；正常查询不消费模型轮数或生成attempt。 */
    @Transactional
    public void polling(TaskLease lease,String operationId) {
        tasks.valid(lease); var row=operation(lease,operationId);
        checkDeadline(lease);
        if(row.get("provider_job_id")==null || !Set.of("WAITING_EXTERNAL","SENDING").contains(row.get("state"))) throw conflict();
        int polls=((Number)row.get("poll_count")).intValue(); var deadline=(Timestamp)row.get("deadline");
        var original=decode((String)row.get("submission_json"),Media.Submission.class);
        int limit=original.video()==null?60:original.video().capability().maxPolls();
        if(polls>=Math.min(60,limit) || deadline==null || !deadline.toInstant().isAfter(Instant.now())) throw new LabException("MEDIA_QUERY_EXHAUSTED","原任务查询次数或期限耗尽");
        var preview=preview(lease.actor(),lease.task().taskId()).orElseThrow(this::conflict);
        int max=preview.storyboard()==null?60:preview.storyboard().shots().size()*60;
        if(sql.jdbc.queryForObject("SELECT COALESCE(SUM(poll_count),0) FROM media_operations WHERE task_id=?",Integer.class,lease.task().taskId())>=max)throw new LabException("MEDIA_QUERY_EXHAUSTED","整任务查询预算耗尽");
        var next=(Timestamp)row.get("next_poll_at"); if(next!=null && next.toInstant().isAfter(Instant.now())) throw conflict();
        sql.jdbc.update("UPDATE media_operations SET poll_count=poll_count+1,last_poll_at=CURRENT_TIMESTAMP(6),next_poll_at=DATE_ADD(CURRENT_TIMESTAMP(6),INTERVAL 30 SECOND) WHERE operation_id=?",operationId);
        sql.jdbc.update("INSERT INTO media_attempts(operation_id,action,attempt,fencing_token) VALUES(?,'QUERY',?,?)",operationId,polls+1,lease.fencingToken()); phase(lease,"QUERYING_ORIGINAL_JOB");
    }
    /** 每次下载只取原响应，不因链接失效授予重购；崩溃重试仍受持久三次上限。 */
    @Transactional
    public Media.ProviderResult downloading(TaskLease lease,String operationId) {
        tasks.valid(lease); var row=operation(lease,operationId);
        if(!row.get("state").equals("REMOTE_READY")) throw conflict();
        if(sql.jdbc.update("UPDATE media_operations SET download_count=download_count+1 WHERE operation_id=? AND download_count<3",operationId)!=1) throw new LabException("MEDIA_DOWNLOAD_EXHAUSTED","原文件取回次数耗尽");
        tasks.reserveToolCall(lease); phase(lease,"FETCHING_AND_VALIDATING");
        sql.jdbc.update("INSERT INTO media_attempts(operation_id,action,attempt,fencing_token) VALUES(?,'DOWNLOAD',?,?)",operationId,((Number)row.get("download_count")).intValue()+1,lease.fencingToken());
        return decode((String)row.get("response_json"),Media.ProviderResult.class);
    }
    /** 已校验文件先落盘，登记与操作成功同事务；孤立文件不公开且可按稳定assetId复用。 */
    @Transactional
    public void publishAsset(TaskLease lease,String operationId,Media.Asset asset) {
        tasks.valid(lease); var row=operation(lease,operationId); var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(LabException::denied);
        if(!p.status().equals("APPROVED") || !Set.of("REMOTE_READY","SUCCEEDED").contains(row.get("state"))) throw conflict();
        if(row.get("asset_id")!=null) return;
        if(!asset.unitId().equals(row.get("unit_id"))||!operationId.equals(asset.operationId())||((Number)row.get("preview_version")).intValue()!=p.previewVersion())throw conflict();
        long used=sql.jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM media_assets WHERE task_id=?",Long.class,lease.task().taskId());
        if(asset.file().size()<1 || used+asset.file().size()>209715200L) throw new LabException("BUDGET_EXCEEDED","媒体任务空间超限");
        long artifact=sql.insert("INSERT INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum,kind,revision,unit_id,preview_version,storage_key,byte_size,media_operation_id,published) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE)",lease.actor().userId(),lease.task().taskId(),asset.unitId()+extension(asset.file().mime()),asset.file().mime(),"",encode(p.sourceDependencies()),asset.file().checksum(),asset.kind(),p.previewVersion(),asset.unitId(),p.previewVersion(),asset.file().storageKey(),asset.file().size(),operationId);
        var saved=new Media.Asset(asset.assetId(),asset.unitId(),asset.kind(),operationId,asset.file(),asset.webSource(),artifact);
        sql.jdbc.update("INSERT INTO media_assets(asset_id,task_id,unit_id,operation_id,asset_json,byte_size) VALUES(?,?,?,?,?,?)",asset.assetId(),lease.task().taskId(),asset.unitId(),operationId,encode(saved),asset.file().size());
        sql.jdbc.update("UPDATE media_operations SET state='SUCCEEDED',asset_id=? WHERE operation_id=?",asset.assetId(),operationId);
        String column=asset.kind().equals("AUDIO")?"audio_asset_id":asset.kind().equals("VIDEO_CLIP")?"video_asset_id":null;
        if(column!=null)sql.jdbc.update("UPDATE media_shot_execution SET "+column+"=? WHERE task_id=? AND preview_version=? AND shot_id=?",asset.assetId(),p.taskId(),p.previewVersion(),asset.unitId());
    }
    /** 受控候选预览前保存出处及checksum；同资产键只允许复用同一事实。 */
    @Transactional
    public void webAsset(TaskLease lease,Media.Asset asset) {
        tasks.valid(lease);
        if(!asset.kind().equals("WEB_SEARCH")||asset.webSource()==null||asset.operationId()!=null)throw LabException.invalid("事实图必须保存真实出处");
        long used=sql.jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM media_assets WHERE task_id=?",Long.class,lease.task().taskId());
        if(used+asset.file().size()>209715200L)throw new LabException("BUDGET_EXCEEDED","媒体空间超限");
        var old=sql.jdbc.queryForList("SELECT asset_json FROM media_assets WHERE asset_id=?",String.class,asset.assetId());
        if(!old.isEmpty()){if(!old.get(0).equals(encode(asset)))throw conflict();return;}
        sql.jdbc.update("INSERT INTO media_assets(asset_id,task_id,unit_id,asset_json,byte_size) VALUES(?,?,?,?,?)",asset.assetId(),lease.task().taskId(),asset.unitId(),encode(asset),asset.file().size());
    }
    /** 私人已成功文件事实单独保存，恢复不新买付费图片或配音。 */
    @Transactional
    public List<Media.Asset> assets(TaskLease lease){tasks.valid(lease);return loadAssets(lease.task().taskId());}
    /** 超长仍保存真实时长，但不写入可发送档位；拒绝付费后续不回滚已经发生的测量事实。 */
    @Transactional(noRollbackFor=LabException.class)
    public void measured(TaskLease lease,String shotId,long milliseconds){
        tasks.valid(lease);checkDeadline(lease);var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(this::conflict);
        if(!p.status().equals("APPROVED")||p.storyboard()==null)throw conflict();
        var shot=p.storyboard().shots().stream().filter(s->s.shotId().equals(shotId)).findFirst().orElseThrow(this::conflict);
        boolean nativeAudio=shot.video()!=null&&shot.video().audioMode().equals("NATIVE");
        boolean silent=shot.video()!=null&&shot.video().audioMode().equals("NONE");
        StoryboardRules.audioPolicy(shot.video(),shot.narration());
        if(operations(lease.actor(),p.taskId()).stream().noneMatch(o->o.unitId().equals(shotId)&&o.capability().equals("VIDEO_GENERATION")&&o.state().equals("SUCCEEDED")))throw conflict();
        var old=shots(lease.actor(),p.taskId()).stream().filter(s->s.shotId().equals(shotId)).findFirst().orElseThrow(this::conflict);
        if(silent?milliseconds!=0:milliseconds<=0)throw new LabException("MEDIA_AUDIO_MISMATCH","实际音轨与批准的有声／无声参数不一致");
        if(milliseconds>540000||old.audioDurationMs()!=null&&old.audioDurationMs()!=milliseconds)throw conflict();
        sql.jdbc.update("UPDATE media_shot_execution SET audio_duration_ms=? WHERE task_id=? AND preview_version=? AND shot_id=?",milliseconds,p.taskId(),p.previewVersion(),shotId);
        if(nativeAudio&&(milliseconds<=0||milliseconds>shot.maximumDurationSeconds()*1000L+100))throw new LabException("MEDIA_REAPPROVAL_REQUIRED","原生音轨超过批准时长及100毫秒编码容差");
        int seconds=shot.maximumDurationSeconds();
        if(old.providerDurationSeconds()!=null){if(old.providerDurationSeconds()!=seconds)throw conflict();return;}
        sql.jdbc.update("UPDATE media_shot_execution SET audio_duration_ms=?,provider_duration_seconds=? WHERE task_id=? AND preview_version=? AND shot_id=? AND approval_hash=?",milliseconds,seconds,p.taskId(),p.previewVersion(),shotId,p.hash());
    }
    /** 当前本人预览版本的实测事实，不用模型估时补null字段。 */
    @Transactional(readOnly=true)
    public List<Media.ShotExecution> shots(UserContext actor,long taskId){
        var p=preview(actor,taskId).orElseThrow(LabException::denied);
        var rows=sql.jdbc.query("SELECT * FROM media_shot_execution WHERE task_id=? AND preview_version=?",(r,n)->new Media.ShotExecution(r.getString("shot_id"),r.getString("approval_hash"),(Long)r.getObject("audio_duration_ms"),(Integer)r.getObject("provider_duration_seconds"),(Long)r.getObject("timeline_start_ms"),(Long)r.getObject("timeline_end_ms"),r.getString("audio_asset_id"),r.getString("video_asset_id"),r.getString("rendered_asset_id")),taskId,p.previewVersion());
        if(p.storyboard()==null)return List.of();
        return p.storyboard().shots().stream().flatMap(s->rows.stream().filter(r->r.shotId().equals(s.shotId()))).toList();
    }
    /** 本地重建按输入hash限制两次，外部生成成功事实不因此清零。 */
    @Transactional
    public Optional<Media.Asset> rendering(TaskLease lease,String unit,String hash){
        tasks.valid(lease);checkDeadline(lease);
        if(!unit.matches("shot-[1-9][0-9]?|FINAL|SUBTITLES")||!hash.matches("[a-f0-9]{64}"))throw conflict();
        var rows=sql.jdbc.queryForList("SELECT asset_json FROM media_local_renders WHERE task_id=? AND unit_id=? AND input_hash=?",lease.task().taskId(),unit,hash);
        if(!rows.isEmpty()&&rows.get(0).get("asset_json")!=null)return Optional.of(decode((String)rows.get(0).get("asset_json"),Media.Asset.class));
        if(rows.isEmpty())sql.jdbc.update("INSERT INTO media_local_renders(task_id,unit_id,input_hash) VALUES(?,?,?)",lease.task().taskId(),unit,hash);
        if(sql.jdbc.update("UPDATE media_local_renders SET attempt=attempt+1 WHERE task_id=? AND unit_id=? AND input_hash=? AND attempt<2",lease.task().taskId(),unit,hash)!=1)throw new LabException("MEDIA_RENDER_EXHAUSTED","同输入本地制作最多两次");
        phase(lease,unit.equals("FINAL")?"CONCATENATING_SUBTITLES":"RENDERING_SHOT");return Optional.empty();
    }
    /** 片段与字幕均作为私有素材计入共享空间；时间轴由实际片段时长给出。 */
    @Transactional
    public void rendered(TaskLease lease,String hash,Media.Asset asset,Long start,Long end){
        tasks.valid(lease);checkDeadline(lease);var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(this::conflict);
        if(!p.status().equals("APPROVED")||asset.operationId()!=null)throw conflict();
        var old=sql.jdbc.queryForList("SELECT asset_json FROM media_local_renders WHERE task_id=? AND unit_id=? AND input_hash=?",p.taskId(),asset.unitId(),hash);
        if(old.isEmpty())throw conflict();if(old.get(0).get("asset_json")!=null)return;
        long used=sql.jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM media_assets WHERE task_id=?",Long.class,p.taskId());
        if(asset.file().size()<1||used+asset.file().size()>209715200L)throw new LabException("BUDGET_EXCEEDED","全部镜头及成品共享200MB空间");
        if(asset.kind().equals("RENDERED_SHOT")){
            var shot=shots(lease.actor(),p.taskId()).stream().filter(s->s.shotId().equals(asset.unitId())).findFirst().orElseThrow(this::conflict);
            if(start==null||end==null||start<0||end<=start||shot.audioDurationMs()==null||end-start<shot.audioDurationMs())throw conflict();
            sql.jdbc.update("UPDATE media_shot_execution SET timeline_start_ms=?,timeline_end_ms=?,rendered_asset_id=? WHERE task_id=? AND preview_version=? AND shot_id=?",start,end,asset.assetId(),p.taskId(),p.previewVersion(),asset.unitId());
        }
        long id=sql.insert("INSERT INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum,kind,revision,unit_id,preview_version,storage_key,byte_size,published) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE)",lease.actor().userId(),p.taskId(),asset.unitId()+extension(asset.file().mime()),asset.file().mime(),"",encode(p.sourceDependencies()),asset.file().checksum(),asset.kind(),p.previewVersion(),asset.unitId(),p.previewVersion(),asset.file().storageKey(),asset.file().size());
        var saved=new Media.Asset(asset.assetId(),asset.unitId(),asset.kind(),null,asset.file(),null,id);
        sql.jdbc.update("INSERT INTO media_assets(asset_id,task_id,unit_id,asset_json,byte_size) VALUES(?,?,?,?,?)",saved.assetId(),p.taskId(),saved.unitId(),encode(saved),saved.file().size());
        sql.jdbc.update("UPDATE media_local_renders SET asset_json=? WHERE task_id=? AND unit_id=? AND input_hash=?",encode(saved),p.taskId(),saved.unitId(),hash);
    }
    /** 批准后的统一截止包括等待／停机，不因逐镜头开始而重新获得二十分钟。 */
    private void checkDeadline(TaskLease lease){
        var deadline=sql.jdbc.queryForObject("SELECT media_deadline FROM ai_tasks WHERE id=?",Timestamp.class,lease.task().taskId());
        if(deadline==null||!deadline.toInstant().isAfter(Instant.now()))throw new LabException("BUDGET_EXCEEDED","媒体总执行与外部等待期限耗尽");
    }
    /** 执行权复核需要锁定控制行，不能使用只读事务；复用素材仍受共同截止限制。 */
    @Transactional
    public Instant deadline(TaskLease lease){tasks.valid(lease);checkDeadline(lease);return sql.jdbc.queryForObject("SELECT media_deadline FROM ai_tasks WHERE id=?",Timestamp.class,lease.task().taskId()).toInstant();}
    /** 原操作持久快照只供合法执行者读取，包含批准的能力版本。 */
    @Transactional
    public Media.Submission submission(TaskLease lease,String id){tasks.valid(lease);return decode((String)operation(lease,id).get("submission_json"),Media.Submission.class);}
    /** 在最终登记前验证全部必要付费操作已完成，真正视频合成文件与来源同事务发布。 */
    @Transactional
    public void publishVideo(TaskLease lease,Media.FileFact file) {
        tasks.valid(lease);var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(LabException::denied);
        if(!lease.request().taskType().equals("NOTES_VIDEO")||!file.mime().equals("video/mp4")||file.size()<1
                ||operations(lease.actor(),lease.task().taskId()).stream().anyMatch(o->!o.state().equals("SUCCEEDED")))throw conflict();
        checkDeadline(lease);
        var finalAsset=loadAssets(p.taskId()).stream().filter(a->a.kind().equals("FINAL_VIDEO")&&a.file().equals(file)).findFirst().orElseThrow(this::conflict);
        long id=Objects.requireNonNull(finalAsset.artifactId());
        sql.jdbc.update("UPDATE ai_tasks SET artifact_id=? WHERE id=?",id,p.taskId());this.yield(lease,"WAITING_MEDIA_REVIEW",null);
    }
    /** 资产列表来自数据库，不以目录中的孤立文件推断生成成功。 */
    private List<Media.Asset> loadAssets(long taskId){return sql.jdbc.query("SELECT asset_json FROM media_assets WHERE task_id=? ORDER BY unit_id",(r,n)->decode(r.getString(1),Media.Asset.class),taskId);}
    /** 全部子操作完成才发布；PPT素材就绪独立于最终PPTX，视频还需原生音轨检查与镜头拼接完成。 */
    @Transactional
    public void yield(TaskLease lease,String state,String errorCode) {
        tasks.valid(lease);
        if(!Set.of("WAITING_APPROVAL","WAITING_EXTERNAL","NEEDS_RECONCILIATION","MEDIA_READY","SUCCEEDED","WAITING_MEDIA_REVIEW").contains(state)) throw LabException.invalid("未知媒体状态");
        boolean terminal=Set.of("MEDIA_READY","SUCCEEDED","WAITING_MEDIA_REVIEW").contains(state);
        if(terminal) {
            var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(LabException::denied);
            if(!p.status().equals("APPROVED") || operations(lease.actor(),lease.task().taskId()).stream().anyMatch(o->!o.state().equals("SUCCEEDED"))) throw conflict();
            // 已批准的脚本／逐页内容及出处清单同样是私人产物，为S10提供可复用事实。
            sql.jdbc.update("INSERT IGNORE INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum,kind,revision,unit_id,preview_version) VALUES(?,?,'preview.json','application/json',?,?,?,'PREVIEW_JSON',?,'PREVIEW',?)",lease.actor().userId(),p.taskId(),encode(p),encode(p.sourceDependencies()),SqlSupport.hash(encode(p)),p.previewVersion(),p.previewVersion());
            sql.jdbc.update("UPDATE artifacts SET published=TRUE WHERE task_id=? AND preview_version=?",p.taskId(),p.previewVersion());
            // 跨预览复用的原素材保留原operation／费用，只开放当前批准实际引用的资产。
            sql.jdbc.update("UPDATE artifacts a JOIN media_preview_operations x ON x.operation_id=a.media_operation_id SET a.published=TRUE WHERE x.task_id=? AND x.preview_version=?",p.taskId(),p.previewVersion());
            if(!state.equals("WAITING_MEDIA_REVIEW"))sql.jdbc.update("UPDATE task_step_progress SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6) WHERE task_id=? AND step_id='publish'",p.taskId());
        }
        sql.jdbc.update("UPDATE ai_tasks SET status=?,media_phase=?,error_code=?,state_version=state_version+1,lease_until=NULL,worker_id=NULL,total_execution_seconds=total_execution_seconds+TIMESTAMPDIFF(SECOND,claimed_at,CURRENT_TIMESTAMP(6)),claimed_at=NULL WHERE id=?",state,state,errorCode,lease.task().taskId());
    }
    /** 人工验收独立留痕，拒绝不自动触发返工／重购，公开下载始终只对本人。 */
    @Transactional
    public void review(UserContext actor,long taskId,int version,boolean accepted,String note){
        sql.actor(actor,true);var task=tasks.read(actor,taskId);var p=preview(actor,taskId).orElseThrow(LabException::denied);
        if(!task.status().equals("WAITING_MEDIA_REVIEW")||p.previewVersion()!=version||!p.status().equals("APPROVED")||note==null||note.isBlank()||note.length()>2000)throw conflict();
        sql.jdbc.update("INSERT INTO media_human_reviews(task_id,preview_version,actor_id,accepted,note,approval_hash) VALUES(?,?,?,?,?,?)",taskId,version,actor.userId(),accepted,note,p.hash());
        sql.jdbc.update("UPDATE ai_tasks SET status=?,media_phase=?,state_version=state_version+1 WHERE id=?",accepted?"SUCCEEDED":"PAUSED",accepted?"HUMAN_ACCEPTED":"HUMAN_REJECTED",taskId);
        if(accepted)sql.jdbc.update("UPDATE task_step_progress SET status='SUCCEEDED',completed_at=CURRENT_TIMESTAMP(6) WHERE task_id=? AND step_id='publish'",taskId);
    }
    /** 继续已批准任务复核来源与配置；过期只拦新提交，不重购已受理操作。 */
    @Transactional
    public Media.Preview approved(TaskLease lease,String configurationHash) {
        tasks.valid(lease); var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(LabException::denied);
        if(!p.status().equals("APPROVED")) throw new LabException("PREVIEW_CHANGED","批准已经变化");
        return p;
    }
    /** 文件扩展名由已校验MIME确定，不接受模型路径或任意文件名。 */
    private String extension(String mime) { return switch(mime) { case "image/png"->".png"; case "image/jpeg"->".jpg"; case "video/mp4"->".mp4"; case "audio/wav"->".wav";case "application/x-subrip"->".srt";case Presentation.MIME->".pptx"; default->throw LabException.invalid("未知媒体类型"); }; }
    /** 当前租约范围中读取操作，跨任务operationId无法获取外部ID或发送许可。 */
    private Map<String,Object> operation(TaskLease lease,String id) {
        var rows=sql.jdbc.queryForList("SELECT * FROM media_operations WHERE operation_id=? AND task_id=? FOR UPDATE",id,lease.task().taskId());
        if(rows.isEmpty()) throw LabException.denied(); return rows.get(0);
    }
    /** 生命周期事实独立于计划步骤，状态变化递增taskVersion。 */
    private void phase(TaskLease lease,String phase) { sql.jdbc.update("UPDATE ai_tasks SET media_phase=?,state_version=state_version+1 WHERE id=?",phase,lease.task().taskId()); }
    /** 保存完整私有预览，不自动变成批准；同版不可覆盖。 */
    private void insertPreview(Media.Preview p) { sql.jdbc.update("INSERT INTO generation_previews(task_id,preview_version,approval_id,preview_hash,config_hash,status,source_json,preview_json,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",p.taskId(),p.previewVersion(),p.approvalId(),p.hash(),p.configurationHash(),p.status(),encode(p.sourceDependencies()),encode(p),Timestamp.from(p.expiresAt())); }
    /** 只修改服务端状态／资产视图，保持批准参数与来源不可变。 */
    private Media.Preview copy(Media.Preview p,String status,List<Media.Asset> assets) { return new Media.Preview(p.taskId(),p.previewVersion(),p.planVersion(),p.hash(),status,p.approvalId(),p.expiresAt(),p.configurationHash(),p.currency(),p.estimatedAmount(),p.maximumAmount(),p.units(),p.sourceDependencies(),p.coverage(),p.catalogs(),assets,p.qualityStatus(),p.storyboard()); }
    /** 管理员衍生产物也复核当前真实来源，降级后不能沿用ALL。 */
    private void verify(UserContext actor,List<SourceDependency> sources) { docs.verifySources(new AuthorizedKnowledgeScope(actor,actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,List.of(),null,Instant.now()),sources); }
    /** Instant按UTC事实保存，不用执行主机本地时区。 */
    private Instant instant(ResultSet r,String field) throws SQLException { var t=r.getTimestamp(field); return t==null?null:t.toInstant(); }
    /** 固定类型JSON存储，不包含任意可执行对象。 */
    private String encode(Object v) { try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("媒体事实序列化失败");} }
    /** 固定DTO反序列化，损坏事实拒绝恢复而非重新购买。 */
    private <T>T decode(String s,Class<T> type) { try{return json.readValue(s,type);}catch(Exception e){throw new LabException("MEDIA_VALIDATION_FAILED","媒体持久事实损坏");} }
    /** 冲突稳定脱敏，不能解释为可安全重发生成。 */
    private LabException conflict() { return new LabException("OPERATION_CONFLICT","媒体状态或参数事实冲突"); }

    /** 只启动本地导出，明确阻止未完成／未知购买和原总期限耗尽，不能借此续购。 */
    @Transactional
    public void requestPresentation(UserContext actor,long taskId,int version){
        sql.actor(actor,true);var t=tasks.read(actor,taskId);var p=preview(actor,taskId).orElseThrow(this::conflict);
        if(!t.taskType().equals("NOTES_PPT")||p.previewVersion()!=version||!p.status().equals("APPROVED")
                ||!Set.of("MEDIA_READY","FAILED","PAUSED").contains(t.status())||operations(actor,taskId).stream().anyMatch(o->!o.state().equals("SUCCEEDED")))throw conflict();
        var deadline=sql.jdbc.queryForObject("SELECT media_deadline FROM ai_tasks WHERE id=?",Timestamp.class,taskId);
        if(deadline==null||!deadline.toInstant().isAfter(Instant.now()))throw new LabException("BUDGET_EXCEEDED","原媒体截止已耗尽，不允许导出重置共享期限");
        sql.jdbc.update("UPDATE ai_tasks SET status='QUEUED',media_phase='PPT_EXPORT_QUEUED',artifact_id=NULL,error_code=NULL,fencing_token=fencing_token+1,state_version=state_version+1 WHERE id=?",taskId);
        sql.jdbc.update("UPDATE task_step_progress SET status='PENDING',completed_at=NULL WHERE task_id=? AND step_id='publish'",taskId);
        sql.jdbc.update("UPDATE artifacts SET published=FALSE WHERE task_id=? AND kind IN ('PPTX','PAGE_PREVIEW','PPT_CHECK')",taskId);
    }
    /** 原批准／原执行权之下持久消费本地两次额度，损坏重建不修改外部操作或费用。 */
    @Transactional
    public Optional<Presentation.Bundle> presentationStart(TaskLease lease,String hash,boolean rebuild){
        tasks.valid(lease);checkDeadline(lease);var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(this::conflict);
        if(!lease.request().taskType().equals("NOTES_PPT")||!p.status().equals("APPROVED")||!hash.matches("[a-f0-9]{64}")
                ||operations(lease.actor(),p.taskId()).stream().anyMatch(o->!o.state().equals("SUCCEEDED")))throw conflict();
        var rows=sql.jdbc.queryForList("SELECT input_hash,bundle_json FROM presentation_exports WHERE task_id=? AND preview_version=?",p.taskId(),p.previewVersion());
        if(!rows.isEmpty()){
            if(!hash.equals(rows.get(0).get("input_hash")))throw new LabException("PREVIEW_CHANGED","导出器或输入变化，需要新版批准");
            if(!rebuild&&rows.get(0).get("bundle_json")!=null)return Optional.of(decode((String)rows.get(0).get("bundle_json"),Presentation.Bundle.class));
        }else sql.jdbc.update("INSERT INTO presentation_exports(task_id,preview_version,input_hash) VALUES(?,?,?)",p.taskId(),p.previewVersion(),hash);
        if(sql.jdbc.update("UPDATE presentation_exports SET attempt=attempt+1,bundle_json=NULL WHERE task_id=? AND preview_version=? AND attempt<2",p.taskId(),p.previewVersion())!=1)throw new LabException("PPT_EXPORT_EXHAUSTED","同批准输入本地导出最多两次");
        sql.jdbc.update("UPDATE artifacts SET published=FALSE WHERE task_id=? AND preview_version=? AND kind IN ('PPTX','PAGE_PREVIEW','PPT_CHECK')",p.taskId(),p.previewVersion());
        phase(lease,"PPT_EXPORTING");return Optional.empty();
    }
    /** 已重开校验的候选一次登记；事务失败不会公开半份PPTX或预览。 */
    @Transactional
    public void presentationComplete(TaskLease lease,Presentation.Bundle bundle){
        tasks.valid(lease);checkDeadline(lease);var p=preview(lease.actor(),lease.task().taskId()).orElseThrow(this::conflict);var c=bundle.check();
        var hashes=sql.jdbc.queryForList("SELECT input_hash FROM presentation_exports WHERE task_id=? AND preview_version=?",String.class,p.taskId(),p.previewVersion());
        if(!lease.request().taskType().equals("NOTES_PPT")||!p.status().equals("APPROVED")||hashes.size()!=1||!hashes.get(0).equals(c.inputHash())
                ||c.taskId()!=p.taskId()||c.previewVersion()!=p.previewVersion()||c.planVersion()!=p.planVersion()||!c.approvalHash().equals(p.hash())
                ||!c.sources().equals(p.sourceDependencies())||!c.coverage().equals(p.coverage())||!c.structuralStatus().equals("PASSED")
                ||!c.qualityStatus().equals("REQUIRES_HUMAN_REVIEW")||!bundle.pptx().mime().equals(Presentation.MIME)
                ||c.slideCount()!=p.units().size()+1||bundle.pages().size()!=c.slideCount()||c.slideCount()>12)throw conflict();
        var operations=operations(lease.actor(),p.taskId());
        if(operations.stream().anyMatch(o->!o.state().equals("SUCCEEDED")))throw conflict();
        for(var image:c.images()){
            var original=p.assets().stream().filter(a->a.assetId().equals(image.assetId())&&a.unitId().equals(image.unitId())&&a.kind().equals(image.kind())
                    &&a.file().checksum().equals(image.checksum())&&Objects.equals(a.webSource(),image.source())&&Objects.equals(a.operationId(),image.operationId())).findFirst().orElseThrow(this::conflict);
            if(original.kind().equals("GENERATED")&&operations.stream().noneMatch(o->o.assetId().equals(original.assetId())&&o.unitId().equals(original.unitId())))throw conflict();
        }
        if(c.images().size()!=p.units().stream().filter(u->!u.imageMode().equals("NONE")).count())throw conflict();
        long size=bundle.pptx().size()+bundle.pages().stream().mapToLong(page->page.preview().size()).sum();
        long used=sql.jdbc.queryForObject("SELECT COALESCE(SUM(byte_size),0) FROM media_assets WHERE task_id=?",Long.class,p.taskId());
        if(size<1||size+used>209715200L)throw new LabException("BUDGET_EXCEEDED","PPT及图片共享200MB空间");
        phase(lease,"PPT_CHECKING");long id=presentationArtifact(lease,p,"PPTX","PRESENTATION",bundle.pptx());var pages=new ArrayList<Presentation.Page>();
        for(int i=0;i<bundle.pages().size();i++){
            var page=bundle.pages().get(i);
            if(page.number()!=i+1||!page.preview().mime().equals("image/png"))throw conflict();
            long artifact=presentationArtifact(lease,p,"PAGE_PREVIEW","PAGE-"+page.number(),page.preview());pages.add(new Presentation.Page(page.number(),page.unitId(),page.preview(),artifact));
        }
        var saved=new Presentation.Bundle(bundle.pptx(),id,pages,c);
        sql.jdbc.update("UPDATE presentation_exports SET bundle_json=? WHERE task_id=? AND preview_version=? AND input_hash=?",encode(saved),p.taskId(),p.previewVersion(),c.inputHash());
        String content=encode(c);
        sql.jdbc.update("INSERT INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum,kind,revision,unit_id,preview_version,published) VALUES(?,?,'presentation-check.json','application/json',?,?,?,'PPT_CHECK',?,'CHECK',?,FALSE) ON DUPLICATE KEY UPDATE content=VALUES(content),checksum=VALUES(checksum),published=FALSE",lease.actor().userId(),p.taskId(),content,encode(p.sourceDependencies()),SqlSupport.hash(content),p.previewVersion(),p.previewVersion());
        sql.jdbc.update("UPDATE ai_tasks SET artifact_id=? WHERE id=?",id,p.taskId());this.yield(lease,"WAITING_MEDIA_REVIEW",null);
        phaseAfterReview(p.taskId());
    }
    /** 同版修复损坏文件只更新同类私人产物，不生成新购买或新批准。 */
    private long presentationArtifact(TaskLease lease,Media.Preview p,String kind,String unit,Media.FileFact f){
        if(f.size()<1||f.checksum()==null||!f.checksum().matches("[a-f0-9]{64}"))throw conflict();
        sql.jdbc.update("INSERT INTO artifacts(requester_user_id,task_id,filename,mime,content,source_json,checksum,kind,revision,unit_id,preview_version,storage_key,byte_size,published) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,FALSE) ON DUPLICATE KEY UPDATE storage_key=VALUES(storage_key),byte_size=VALUES(byte_size),checksum=VALUES(checksum),published=FALSE",lease.actor().userId(),p.taskId(),unit+extension(f.mime()),f.mime(),"",encode(p.sourceDependencies()),f.checksum(),kind,p.previewVersion(),unit,p.previewVersion(),f.storageKey(),f.size());
        return sql.jdbc.queryForObject("SELECT id FROM artifacts WHERE task_id=? AND kind=? AND revision=? AND unit_id=?",Long.class,p.taskId(),kind,p.previewVersion(),unit);
    }
    /** 本地结构通过后展示明确的PPT质量待人工阶段。 */
    private void phaseAfterReview(long id){sql.jdbc.update("UPDATE ai_tasks SET media_phase='PPT_WAITING_REVIEW' WHERE id=?",id);}
    /** 当前版本事实只有本人／当前来源可以读取，编辑后旧检查不会当作新检查。 */
    @Transactional(readOnly=true)
    public Optional<Presentation.Bundle> presentationCheck(UserContext actor,long taskId){
        var p=preview(actor,taskId).orElseThrow(this::conflict);var t=tasks.read(actor,taskId);
        if(!t.taskType().equals("NOTES_PPT"))throw LabException.invalid("只有PPT任务有演示文稿检查");
        var rows=sql.jdbc.queryForList("SELECT bundle_json FROM presentation_exports WHERE task_id=? AND preview_version=? AND bundle_json IS NOT NULL",String.class,taskId,p.previewVersion());
        return rows.stream().map(s->decode(s,Presentation.Bundle.class)).findFirst();
    }
}
