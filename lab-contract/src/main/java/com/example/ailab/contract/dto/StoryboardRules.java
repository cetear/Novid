package com.example.ailab.contract.dto;

import com.example.ailab.contract.error.LabException;
import java.util.*;

/** 纯程序时长／字幕规则；不依赖模型估时，也不接任意执行表达式。 */
public final class StoryboardRules {
    /** 静态规则不持有用户状态。 */
    private StoryboardRules() { }
    /** 文字分镜从已校验角色单位生成；当前目标为文生视频，不伪造图生视频参考资产。 */
    public static Media.Storyboard create(int version,List<Media.Unit> units,List<Integer> tiers) {
        if(version<1||version>10||units.isEmpty()||units.size()>6||tiers.isEmpty())throw invalid();
        var shots=new ArrayList<Media.Shot>();var ids=new HashSet<String>();
        for(var u:units){
            if(!u.unitId().matches("shot-[1-9][0-9]?")||!ids.add(u.unitId())||u.text()==null
                    ||u.text().codePointCount(0,u.text().length())>1024||u.seconds()<1||u.seconds()>Collections.max(tiers)
                    ||u.references().isEmpty())throw invalid();
            shots.add(new Media.Shot(u.unitId(),u.text(),u.imagePrompt(),u.notes(),"TEXT_TO_VIDEO",List.of(),u.references(),u.seconds()*1000L,Collections.max(tiers)));
        }
        // 长度前缀保证台词中的分隔符不能产生相同批准摘要；目录／来源／价格另由预览绑定。
        try(var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes)){
            out.writeInt(version);out.writeUTF("16:9");out.writeUTF("MINIMUM_FIT_TRIM_VIDEO_KEEP_AUDIO_V1");
            out.writeInt(tiers.size());for(int tier:tiers)out.writeInt(tier);
            out.writeInt(shots.size());for(var s:shots){out.writeUTF(s.shotId());out.writeUTF(s.narration());out.writeUTF(s.visualPrompt());out.writeUTF(s.motionPrompt());out.writeLong(s.estimatedDurationMs());out.writeInt(s.sourceRefs().size());for(String ref:s.sourceRefs())out.writeUTF(ref);}
            out.flush();String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            return new Media.Storyboard(version,hash,"16:9","MINIMUM_FIT_TRIM_VIDEO_KEEP_AUDIO_V1",tiers,shots);
        }catch(Exception e){throw invalid();}
    }
    /** 找到容纳完整旁白的最小批准档位；超长必须先重新审批，禁止截断台词。 */
    public static int duration(long audioMs,List<Integer> tiers,int approvedMaximum) {
        if(audioMs<=0||tiers==null||tiers.isEmpty()||tiers.size()>90||tiers.stream().anyMatch(t->t==null||t<1||t>90))
            throw new LabException("MEDIA_DURATION_MISMATCH","实测时长或提供方档位无效");
        return tiers.stream().distinct().sorted().filter(t->t<=approvedMaximum&&t*1000L>=audioMs).findFirst()
                .orElseThrow(()->new LabException("MEDIA_REAPPROVAL_REQUIRED","完整旁白超过已批准的视频时长范围"));
    }
    /** 每镜头独立绑定Planner选择；公开配置摘要和具体参数一同进入批准摘要。 */
    public static Media.Storyboard routed(int version,List<Media.Unit> units,List<VideoApi.Selection> choices){
        if(choices.size()!=units.size()||choices.stream().anyMatch(Objects::isNull))throw invalid();
        singleVideoApi(choices);
        for(int i=0;i<units.size();i++)audioPolicy(choices.get(i),units.get(i).text());
        var tiers=choices.stream().flatMap(c->c.capability().durations().stream()).distinct().sorted().toList();
        var base=create(version,units,tiers);var shots=new ArrayList<Media.Shot>();
        try(var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes)){
            out.writeUTF(base.hash());for(int i=0;i<units.size();i++){
                var s=base.shots().get(i);var choice=choices.get(i);if(units.get(i).seconds()>choice.seconds())throw invalid();
                out.writeUTF(choice.capability().configurationHash());out.writeUTF(choice.resolution());out.writeUTF(choice.audioMode());out.writeInt(choice.seconds());out.writeUTF(choice.reason());
                shots.add(new Media.Shot(s.shotId(),s.narration(),s.visualPrompt(),s.motionPrompt(),s.generationType(),s.referenceAssetIds(),s.sourceRefs(),s.estimatedDurationMs(),choice.seconds(),choice));
            }
            out.flush();String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
            return new Media.Storyboard(version,hash,"16:9","APPROVED_PER_SHOT_AUDIO_V2",tiers,shots);
        }catch(Exception e){throw invalid();}
    }
    /** 同一成片只准一个登记API及不可变版本；各镜头费用与结果仍独立，不能自动跨API降级。 */
    public static void singleVideoApi(List<VideoApi.Selection> choices){
        if(choices.isEmpty()||choices.stream().anyMatch(Objects::isNull)
                ||choices.stream().map(VideoApi.Selection::capability).distinct().count()!=1)
            throw new LabException("MEDIA_SINGLE_VIDEO_API_REQUIRED","同一成片必须使用同一个视频API及配置版本，请重新规划并由本人审批");
    }
    /** 有声／无声是批准参数；独立配音已移除，有台词的无声镜头必须在付费前拒绝。 */
    public static void audioPolicy(VideoApi.Selection choice,String narration){
        if(choice==null||!Set.of("NATIVE","NONE").contains(choice.audioMode()))
            throw new LabException("MEDIA_TTS_DISABLED","独立配音及合轨功能已移除，请选择API原声或无声输出");
        if(narration==null||choice.audioMode().equals("NONE")&&!narration.isBlank())
            throw new LabException("MEDIA_AUDIO_REQUIRED","脚本存在台词，不能选择无声视频输出");
    }
    /** 重建时只沿用当前批准的路由，不调用Planner或用最新配置覆盖旧镜头。 */
    public static Media.Storyboard recreate(int version,List<Media.Unit> units,Media.Storyboard old){
        return old.shots().stream().allMatch(s->s.video()!=null)?routed(version,units,old.shots().stream().map(Media.Shot::video).toList()):create(version,units,old.durationTiers());
    }
    /** 字幕只采用已制作片段的整数毫秒时间轴，限制输入避免注入字幕样式或额外条目。 */
    public static String srt(List<Media.Shot> shots,List<Media.ShotExecution> executions) {
        if(shots.isEmpty()||shots.size()>6||executions.size()!=shots.size())throw invalid();
        var output=new StringBuilder();long cursor=0;int index=0;
        for(var shot:shots){
            var e=executions.get(index++);
            if(!e.shotId().equals(shot.shotId())||e.timelineStartMs()==null||e.timelineEndMs()==null
                    ||e.timelineStartMs()!=cursor||e.timelineEndMs()<=cursor||e.audioDurationMs()==null
                    ||e.timelineEndMs()-cursor<e.audioDurationMs()||e.timelineEndMs()>540000)throw invalid();
            String narration=shot.narration().replaceAll("[\\r\\n\\p{Cntrl}]"," ").replace("-->","→").replaceAll("[<>]","");
            if(narration.isBlank()){cursor=e.timelineEndMs();continue;}
            output.append(index).append('\n').append(timestamp(cursor)).append(" --> ").append(timestamp(e.timelineEndMs()))
                    .append('\n').append(narration).append("\n\n");cursor=e.timelineEndMs();
        }
        return output.isEmpty()?"\n":output.toString();
    }
    /** 无浮点运算或本机区域差异，跨镜头累计时间保持精确。 */
    private static String timestamp(long ms){return String.format(Locale.ROOT,"%02d:%02d:%02d,%03d",ms/3600000,ms/60000%60,ms/1000%60,ms%1000);}
    /** 时间轴损坏不能生成假字幕交付。 */
    private static LabException invalid(){return new LabException("MEDIA_VALIDATION_FAILED","镜头顺序或实测时间轴不完整");}
}
