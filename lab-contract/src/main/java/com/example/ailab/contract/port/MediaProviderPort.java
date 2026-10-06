package com.example.ailab.contract.port;
import com.example.ailab.contract.dto.*;
import java.util.List;

/** 提供方能力声明与类型化协议；可按ID查询不等于提交幂等。 */
public interface MediaProviderPort {
    /** 服务端登记的候选能力，不含秘密和协议地址。 */
    default List<VideoApi.Capability> videoCapabilities(){return List.of();}
    /** 将Planner建议绑定服务端快照，并验证目录兼容性。 */
    default VideoApi.Selection selectVideo(VideoApi.Recommendation recommendation,List<Media.CatalogItem> catalogs){throw new UnsupportedOperationException();}
    /** 批准及发送前复核同版本配置；原ID查询可使用保留的历史版本。 */
    default void verifyVideo(VideoApi.Selection selection,boolean submitting){throw new UnsupportedOperationException();}
    /** 生图提交所需协议能力在审批前核验，异步结果查询必须同时可用。 */
    default void verifyImage(){ }
    /** 查询上限和间隔来自原配置，仍受整任务上限约束。 */
    default int queryLimit(VideoApi.Selection selection){return 60;}
    default int queryInterval(VideoApi.Selection selection){return 15;}
    /** 统一截止覆盖网络等待；旧图片协议保留既有入口。 */
    default Media.ProviderResult submit(Media.Submission submission,java.time.Instant deadline){return submit(submission);}
    /** 查询绑定原操作路由，不重新由Planner选择提供方。 */
    default Media.ProviderResult query(Media.Submission submission,String id,java.time.Instant deadline){return query(id);}
    /** 缺配置或登记选择不兼容明确拒绝，不能忽略配音／人物。 */
    void validate(TaskRequest request);
    /** 凭证不参与公开快照，配置和目录映射版本参与审批摘要。 */
    String configurationHash(String capability);
    /** 返回已核验且当前生效的媒体计费单位；无价返回null。 */
    FeePrice price(String capability);
    /** 只公开已登记展示选项；不暴露密钥或端点。 */
    List<Media.CatalogItem> catalogs();
    /** 模型由服务端固定，计划不得自行挑选付费目标。 */
    String modelId(String capability);
    /** 官方协议明确支持的时长档位，未知目标不得猜测；不是账户可用性证明。 */
    default List<Integer> videoDurationTiers() { return List.of(); }
    /** 调用一次提交，没有自动重试；UNKNOWN由持久操作控制。 */
    Media.ProviderResult submit(Media.Submission submission);
    /** 仅后端保存的原providerJobId进入GET单路径段，不能使用request_id。 */
    Media.ProviderResult query(String providerJobId);
}
