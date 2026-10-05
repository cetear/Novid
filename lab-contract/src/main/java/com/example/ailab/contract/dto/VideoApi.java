package com.example.ailab.contract.dto;

import java.util.*;

/** 视频能力公开契约；只暴露规划和审批所需信息，不包含地址、密钥或协议模板。 */
public final class VideoApi {
    /** 固定契约集合不能实例化。 */
    private VideoApi() { }
    public record Capability(String id,int version,String configurationHash,String provider,String accountNamespace,
                             String model,String region,List<Integer> durations,List<String> resolutions,
                             List<String> audioModes,Map<String,FeePrice> prices,String verificationStatus,int pollIntervalSeconds,int maxPolls) {
        /** 规划候选是不可变服务端快照，模型不能改写支持范围和报价。 */
        public Capability {durations=List.copyOf(durations);resolutions=List.copyOf(resolutions);audioModes=List.copyOf(audioModes);prices=Map.copyOf(prices);}
    }
    public record Recommendation(String shotId,String profileId,String resolution,String audioMode,int seconds,String reason) { }
    public record Recommendations(List<Recommendation> shots) {
        /** 每镜头恰好一个建议，数量和能力由程序另行复核。 */
        public Recommendations {shots=List.copyOf(shots);}
    }
    public record Selection(Capability capability,String resolution,String audioMode,int seconds,String reason) {
        /** 批准的具体路由；整个快照进入摘要，后台不能悄悄换成新配置。 */
        public Selection {
            if(capability==null||!capability.resolutions().contains(resolution)||!capability.audioModes().contains(audioMode)
                    ||!capability.durations().contains(seconds)||reason==null||reason.isBlank()||reason.length()>1000)
                throw new IllegalArgumentException("视频选择不符合登记能力");
        }
        /** 计价随批准的分辨率选择，无价保持未知。 */
        public FeePrice price(){return capability.prices().get(resolution);}
    }
}
