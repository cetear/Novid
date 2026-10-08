package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import java.util.*;

/** 媒体持久状态图；远程调用不位于这些短事务内。 */
public interface MediaStorePort {
    /** 迁移前创建的任务保留旧流程，新任务才采用Skill基线。 */
    default boolean skillBindingEligible(TaskLease lease){return false;}
    /** 在有效执行租约下读取已固定的Skill基线；旧任务无基线返回空。 */
    default Optional<TaskExecutionBinding> executionBinding(TaskLease lease){return Optional.empty();}
    /** 首次规划之前原子建立基线；已有基线必须相同，禁止恢复时覆盖。 */
    default void bindExecution(TaskLease lease,TaskExecutionBinding binding){throw new UnsupportedOperationException("未实现任务Skill绑定");}
    /** 旧素材就绪任务可显式排队本地导出；原截止、批准和购买事实保持不变。 */
    default void requestPresentation(UserContext actor,long taskId,int version){throw new UnsupportedOperationException();}
    /** 当前批准输入本地导出最多两次，重建不重置预算或外部操作。 */
    default Optional<Presentation.Bundle> presentationStart(TaskLease lease,String hash,boolean rebuild){throw new UnsupportedOperationException();}
    /** 程序结构检查通过后登记私人候选及逐页PNG，仍等待本人质量验收。 */
    default void presentationComplete(TaskLease lease,Presentation.Bundle bundle){throw new UnsupportedOperationException();}
    /** 当前本人版本的检查结果及认证下载ID，无检查返回空。 */
    default Optional<Presentation.Bundle> presentationCheck(UserContext actor,long taskId){return Optional.empty();}
    /** 整任务当前截止时间；重审批不得沿用旧镜头的过期截止或重置共享预算。 */
    java.time.Instant deadline(TaskLease lease);
    /** 从原操作读取持久提交快照，查询不得改用当前Planner候选。 */
    Media.Submission submission(TaskLease lease,String operationId);
    /** 本人观看／听取候选成品后验收，独立于生成前的费用批准。 */
    void review(UserContext actor,long taskId,int previewVersion,boolean accepted,String note);
    /** 本人读取最新预览并重核来源。 */
    Optional<Media.Preview> preview(UserContext actor, long taskId);
    /** 保存不可变计划版本，至多初版和一次局部重规划。 */
    void plan(TaskLease lease, Media.PlanSnapshot plan);
    /** 本人查询完整版本计划，不伪造静态计划。 */
    List<Media.PlanSnapshot> plans(UserContext actor, long taskId);
    /** 当前执行读取版本化成功结果，输入摘要变化时不得复用。 */
    List<Media.WorkerResult> results(TaskLease lease, int version);
    /** 真实成功角色结果短事务提交，旧fencing禁止提交。 */
    void result(TaskLease lease, int version, Media.WorkerResult result);
    /** 单调消费语义返工或局部重规划预算。 */
    void consume(TaskLease lease, String kind);
    /** 保存目录快照及可编辑预览，并释放审批等待租约。 */
    Media.Preview prepare(TaskLease lease, Media.Preview preview);
    /** 本人修改增加预览版本，保留来源与已核验资产；参数变化使旧批准失效。 */
    Media.Preview edit(UserContext actor, long taskId, int version, List<Media.Unit> units, String configurationHash);
    /** 本人调整候选路由时生成新版预览和新批准，不原地更换已发送操作。 */
    Media.Preview edit(UserContext actor,long taskId,int version,List<Media.Unit> units,String configurationHash,List<VideoApi.Selection> selections);
    /** 批准、费用预留、稳定媒体意图与队列状态同事务提交；拒绝不付费。 */
    Media.Preview decide(UserContext actor, String approvalId, boolean approved, String configurationHash,
                         java.util.Map<String,FeePrice> prices, java.util.Map<String,String> modelIds);
    /** 有界下载意图只取原响应地址，最多三次，不能变成重新生成许可。 */
    Media.ProviderResult downloading(TaskLease lease, String operationId);
    /** 私人操作查询不接受客户端外部任务ID。 */
    List<Media.Operation> operations(UserContext actor, long taskId);
    /** 一次性发送许可与可靠attempt、费用及任务预算同事务；恢复不重复许可。 */
    Media.Submission sending(TaskLease lease, String operationId);
    /** 即使租约已撤销仍补原操作外部ID／费用事实，不能授予发布权限。 */
    void received(String operationId, Media.ProviderResult response);
    /** 发出前持久消费查询次数和时间，旧worker不能开始查询。 */
    void polling(TaskLease lease, String operationId);
    /** 下载成功事实与私人产物登记仅在有效执行／来源／批准下提交。 */
    void publishAsset(TaskLease lease, String operationId, Media.Asset asset);
    /** 搜索候选核验后保存，预览绑定原checksum，审批后不悄悄更换图片。 */
    void webAsset(TaskLease lease, Media.Asset asset);
    /** 查询任务当前已校验资产，供恢复及最终视频合成复用。 */
    List<Media.Asset> assets(TaskLease lease);
    /** 实测音频及派生档位关联原批准，不能修改批准Storyboard。 */
    void measured(TaskLease lease,String shotId,long audioDurationMs);
    /** 本人读取实际时间轴；没有实测结果保持null。 */
    List<Media.ShotExecution> shots(UserContext actor,long taskId);
    /** 同输入本地制作至多两次，成功文件可以跨领取复用。 */
    Optional<Media.Asset> rendering(TaskLease lease,String unitId,String inputHash);
    /** 已完成本地制作可靠登记，不消费或补造外部模型费用。 */
    void rendered(TaskLease lease,String inputHash,Media.Asset asset,Long startMs,Long endMs);
    /** 真实视频和真实配音合成完成后私人发布，禁止只返回外部链接。 */
    void publishVideo(TaskLease lease, Media.FileFact file);
    /** 外部等待释放线程和租约；UNKNOWN明确停止自动重购。 */
    void yield(TaskLease lease, String state, String errorCode);
    /** 已批准版本及目录、来源仍有效才允许继续执行。 */
    Media.Preview approved(TaskLease lease, String configurationHash);
}
