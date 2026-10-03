package com.example.ailab.contract.port;
import java.util.*;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
/** DocumentIngestionStorePort，所有状态／执行权由权威数据库复核。 */
public interface DocumentIngestionStorePort {
    /** 有界扫描并领取或恢复过期租约，fencing 单调增加。 */ Optional<IngestionLease> claim(String workerId);
    /** 续租必须匹配执行者及 fencing。 */ boolean renew(IngestionLease lease);
    /** 版本化结构原子保存，重试覆盖本批次，不影响已激活批次。 */ void saveStructure(IngestionLease lease,ParsedDocument parsed);
    /** 远程成功后 CAS 激活；迟到执行者不能提交。 */ void activate(IngestionLease lease,int expectedChunks);
    /** 持久化真实失败码，有限重试，不能标为 READY。 */ void fail(IngestionLease lease,String code);
    /** 请求 owner 技术重处理，不伪造内容版本。 */ void reprocess(UserContext actor,long documentId);
}
