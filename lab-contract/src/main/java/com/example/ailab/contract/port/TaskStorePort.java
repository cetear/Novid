package com.example.ailab.contract.port;
import java.util.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
/** TaskStorePort，状态和并发条件由 MySQL 实现。 */
public interface TaskStorePort{
    /** 创建与请求去重同事务提交。 */ TaskSnapshot create(UserContext actor,TaskRequest request);
    /** 仅本人读取，不提供 ADMIN 旁路。 */ TaskSnapshot read(UserContext actor,long id);
    /** 原子暂停／恢复／取消，终态不能普通 resume。 */ TaskSnapshot action(UserContext actor,long id,String action);
    /** 领取或恢复过期租约，返回当前可信请求者。 */ Optional<TaskLease> claim(String workerId);
    /** 续租核验用户、worker、fencing 与状态。 */ boolean renew(TaskLease lease);
    /** 真实模型提交前持久消费尝试预算，重启不清零。 */ void reserveModelAttempt(TaskLease lease);
    /** 每次逻辑生成前持久消费轮数，恢复不重置六轮额度。 */ void reserveModelTurn(TaskLease lease);
    /** 保存实际完成检查点，重复 step 不覆盖既有成功。 */ void checkpoint(TaskLease lease,TaskCheckpoint checkpoint);
    /** 返回本执行可用的成功检查点。 */ List<TaskCheckpoint> checkpoints(TaskLease lease);
    /** 执行完毕原子创建私人产物并进入 SUCCEEDED/PARTIAL。 */ void publish(TaskLease lease,TaskCheckpoint report);
    /** 安全边界处理失败，不覆盖更晚的执行者。 */ void fail(TaskLease lease,String code);
}
