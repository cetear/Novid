package com.example.ailab.ai.orchestration.media;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.ai.model.MediaModelGateway;
import java.util.*;
import java.time.*;

/** 批准后的程序媒体执行；一次领取做有限工作，外部等待由数据库调度。 */
public final class MediaExecution {
    private final MediaStorePort store;private final MediaProviderPort provider;private final MediaFilePort files;
    private final PresentationPort presentation;
    /** 仅端口依赖，不另建视频任务、支付客户端或轮询线程。 */
    public MediaExecution(MediaStorePort store,MediaProviderPort provider,MediaFilePort files){this(store,provider,files,null);}
    /** 正式Worker必须注入导出器，旧媒体协议测试仍可独立验证素材链。 */
    public MediaExecution(MediaStorePort store,MediaProviderPort provider,MediaFilePort files,PresentationPort presentation){this.store=store;this.provider=provider;this.files=files;this.presentation=presentation;}
    /** 逐镜头直接生成视频及API原生音轨；已有原ID仅查询，无ID发送中恢复封存UNKNOWN。 */
    public void execute(TaskLease lease,String configurationHash){
        var p=store.approved(lease,configurationHash);boolean video=lease.request().taskType().equals("NOTES_VIDEO");
        if(video&&p.storyboard()==null)throw new LabException("PREVIEW_CHANGED","历史整片批准不能直接用于分镜执行");
        var operations=store.operations(lease.actor(),p.taskId());
        for(var operation:operations){
            store.deadline(lease);
            if(operation.capability().equals("AUDIO_GENERATION"))throw new LabException("MEDIA_TTS_DISABLED","历史独立配音操作已移除，请重新规划审批");
            if(operation.state().equals("SUCCEEDED"))continue;
            if(operation.state().equals("FAILED"))throw new LabException("MEDIA_PROVIDER_FAILED","提供方生成失败，重新生成须新批准");
            if(operation.state().equals("UNKNOWN")||operation.state().equals("SENDING")&&operation.providerJobId()==null){
                if(operation.state().equals("SENDING"))store.received(operation.operationId(),unknown("MEDIA_SUBMISSION_UNKNOWN"));
                store.yield(lease,"NEEDS_RECONCILIATION","MEDIA_SUBMISSION_UNKNOWN");return;
            }
            if(operation.state().equals("RESERVED")){
                if(video)StoryboardRules.singleVideoApi(p.storyboard().shots().stream().map(Media.Shot::video).toList());
                if(!p.configurationHash().equals(configurationHash))throw new LabException("PREVIEW_CHANGED","新付费发送前配置已变化，需要重新批准");
                var original=store.submission(lease,operation.operationId());if(original.video()!=null)provider.verifyVideo(original.video(),true);
                // sending原子提交一次性许可；只有拿到许可的当前执行者可以发出请求。
                var submission=store.sending(lease,operation.operationId());
                Media.ProviderResult known=null;
                try{
                    known=provider.submit(submission,store.deadline(lease));store.received(operation.operationId(),known);
                }catch(RuntimeException failure){
                    // 远程已可能计费，任何本地保存／响应异常均不能变成重新发送许可。
                    // 已拿到原ID时只重试本地事实登记，禁止用UNKNOWN覆盖刚收到的原响应。
                    try{store.received(operation.operationId(),known!=null?known:unknown(failure instanceof LabException e?e.code():"MEDIA_SUBMISSION_UNKNOWN"));}catch(RuntimeException localFailure){failure.addSuppressed(localFailure);}
                    throw failure;
                }
            }
            var current=store.operations(lease.actor(),p.taskId()).stream().filter(o->o.operationId().equals(operation.operationId())).findFirst().orElseThrow();
            if(current.state().equals("WAITING_EXTERNAL")||current.state().equals("SENDING")&&current.providerJobId()!=null){
                if(current.nextPollAt()!=null&&current.nextPollAt().isAfter(Instant.now()))continue;
                var original=store.submission(lease,current.operationId());
                if(current.pollCount()>=provider.queryLimit(original.video()))throw new LabException("MEDIA_QUERY_EXHAUSTED","原API查询次数耗尽");
                store.polling(lease,current.operationId());
                try{store.received(current.operationId(),provider.query(original,current.providerJobId(),store.deadline(lease)));}
                catch(LabException error){
                    if(Set.of("MEDIA_AUTH_FAILED","MEDIA_PARAMETERS_REJECTED","MEDIA_CAPABILITY_UNAVAILABLE").contains(error.code()))throw error;
                    // 暂时错误保留原ID及已消费查询次数，下一次只查询原任务。
                    continue;
                }
                current=store.operations(lease.actor(),p.taskId()).stream().filter(o->o.operationId().equals(operation.operationId())).findFirst().orElseThrow();
            }
            if(current.state().equals("REMOTE_READY")){
                var response=store.downloading(lease,current.operationId());
                String kind=current.capability().equals("IMAGE_GENERATION")?"IMAGE":"VIDEO";
                var file=files.fetch(current.operationId(),response.urls().get(0),kind);
                store.publishAsset(lease,current.operationId(),new Media.Asset(current.operationId(),current.unitId(),kind.equals("IMAGE")?"GENERATED":"VIDEO_CLIP",current.operationId(),file,null,null));
            }
        }
        // 原生音轨不购买TTS，必须真实检测到声音，镜头实测仍单独保存。
        if(video)for(var shot:store.shots(lease.actor(),p.taskId()))if(shot.videoAssetId()!=null&&shot.audioDurationMs()==null){
            var approved=p.storyboard().shots().stream().filter(s->s.shotId().equals(shot.shotId())).findFirst().orElseThrow();
            StoryboardRules.audioPolicy(approved.video(),approved.narration());
            if(files.durationMs(asset(lease,shot.videoAssetId()).file(),"video")>approved.maximumDurationSeconds()*1000L+100)
                throw new LabException("MEDIA_REAPPROVAL_REQUIRED","实际视频超过批准时长及编码容差");
            store.measured(lease,shot.shotId(),files.durationMs(asset(lease,shot.videoAssetId()).file(),"audio"));
        }
        var pending=store.operations(lease.actor(),p.taskId());
        if(pending.stream().anyMatch(o->Set.of("UNKNOWN","SENDING").contains(o.state())&&o.providerJobId()==null)){store.yield(lease,"NEEDS_RECONCILIATION","MEDIA_SUBMISSION_UNKNOWN");return;}
        if(pending.stream().anyMatch(o->o.state().equals("FAILED")))throw new LabException("MEDIA_PROVIDER_FAILED","生成失败，需本人新批准");
        if(pending.stream().anyMatch(o->!o.state().equals("SUCCEEDED"))){store.yield(lease,"WAITING_EXTERNAL",null);return;}
        if(!video){
            if(presentation==null)store.yield(lease,"MEDIA_READY",null);
            else new PresentationExecution(store,presentation).execute(lease,store.approved(lease,configurationHash));
            return;
        }
        if(video)StoryboardRules.singleVideoApi(p.storyboard().shots().stream().map(Media.Shot::video).toList());
        assemble(lease,p);
    }
    /** 成功素材的checksum形成本地输入键；片段／字幕／成品分别持久复用。 */
    private void assemble(TaskLease lease,Media.Preview p){
        var deadline=store.deadline(lease);
        var clips=new ArrayList<Media.FileFact>();long cursor=0;
        for(var shot:store.shots(lease.actor(),p.taskId())){
            var approved=p.storyboard().shots().stream().filter(s->s.shotId().equals(shot.shotId())).findFirst().orElseThrow();
            StoryboardRules.audioPolicy(approved.video(),approved.narration());
            if(shot.audioAssetId()!=null)throw new LabException("MEDIA_TTS_DISABLED","历史独立配音合成已移除，请重新规划审批");
            var video=asset(lease,shot.videoAssetId());
            String hash=MediaModelGateway.hash("native-clip-v3:"+p.taskId()+":"+p.hash()+":"+shot.shotId()+":"+video.file().checksum()+":"+cursor);
            var existing=store.rendering(lease,shot.shotId(),hash);Media.Asset rendered;
            if(existing.isPresent())rendered=existing.get();else{
                String id=stable(hash);var file=video.file();
                long length=timelineLength(file,p.storyboard().shots().size()>1);
                rendered=new Media.Asset(id,shot.shotId(),"RENDERED_SHOT",null,file,null,null);store.rendered(lease,hash,rendered,cursor,cursor+length);
            }
            clips.add(rendered.file());cursor+=timelineLength(rendered.file(),p.storyboard().shots().size()>1);
        }
        String srt=StoryboardRules.srt(p.storyboard().shots(),store.shots(lease.actor(),p.taskId()));String subtitleHash=MediaModelGateway.hash(p.taskId()+":"+p.hash()+":"+srt);
        var subtitle=store.rendering(lease,"SUBTITLES",subtitleHash).orElseGet(()->{
            String id=stable(subtitleHash);var a=new Media.Asset(id,"SUBTITLES","SUBTITLES",null,files.subtitles(id,srt),null,null);store.rendered(lease,subtitleHash,a,null,null);return a;
        });
        String hash=MediaModelGateway.hash("final-v2:"+p.taskId()+":"+p.hash()+":"+clips+":"+subtitle.file().checksum()+":"+lease.request().videoOptions().burnSubtitles());
        var complete=store.rendering(lease,"FINAL",hash).orElseGet(()->{
            String id=stable(hash);boolean burn=lease.request().videoOptions().burnSubtitles();
            var file=clips.size()==1&&!burn?clips.get(0):files.concatenate(id,clips,subtitle.file(),deadline,remaining(lease),burn);
            var a=new Media.Asset(id,"FINAL","FINAL_VIDEO",null,file,null,null);store.rendered(lease,hash,a,null,null);return a;
        });
        store.publishVideo(lease,complete.file());
    }
    /** AAC编码尾部可能比末帧长几十毫秒；播放区间包含全部音轨，不把有效原声误判为截断。 */
    private long timelineLength(Media.FileFact file,boolean concatenating){
        long milliseconds=Math.max(files.durationMs(file,"video"),files.durationMs(file,"audio"));
        // 多片段拼接会将短流补齐到30fps整帧边界，字幕使用同一量化，单片段保留原播放长度。
        return concatenating?(((milliseconds*30+999)/1000)*1000+29)/30:milliseconds;
    }
    /** 稳定受控文件名使SQL提交前崩溃不制造新的付费素材。 */
    private String stable(String hash){return UUID.nameUUIDFromBytes(hash.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    /** 只有当前合法任务的已登记素材可以进入本地工具。 */
    private Media.Asset asset(TaskLease lease,String id){return store.assets(lease).stream().filter(a->a.assetId().equals(id)).findFirst().orElseThrow(()->new LabException("MEDIA_VALIDATION_FAILED","镜头素材尚未就绪"));}
    /** 所有镜头中间件及最终文件共用同一200MB限额。 */
    private long remaining(TaskLease lease){return 209715200L-store.assets(lease).stream().mapToLong(a->a.file().size()).sum();}
    /** 未知不带假ID、假用量或自动重购标志。 */
    private Media.ProviderResult unknown(String code){return new Media.ProviderResult(null,null,null,"UNKNOWN",List.of(),List.of(),null,code);}
}
