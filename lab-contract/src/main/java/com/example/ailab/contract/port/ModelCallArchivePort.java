package com.example.ailab.contract.port;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.ModelCalls.*;
import java.util.List;
import java.io.OutputStream;
import java.io.IOException;

/** 全文归档独立于可丢的图队列；实现封装分块、压缩、权限和完整性检查。 */
public interface ModelCallArchivePort {
    boolean enabled();
    void begin(CallRecord call);
    Detail finish(CallRecord call, String input, String output, String outputState);
    List<Detail> list(UserContext actor, String traceId, String nodeId, int page, int size);
    Detail detail(UserContext actor, String traceId, String callId);
    PayloadPage page(UserContext actor, String traceId, String callId, String direction, String cursor, int limit);
    default PayloadPage modelText(UserContext actor,String traceId,String callId,String cursor,int limit) {
        throw new com.example.ailab.contract.error.LabException("MODEL_CALL_PAYLOAD_UNAVAILABLE","模型输出文本视图不可用");
    }
    void download(UserContext actor, String traceId, String callId, String direction, OutputStream output) throws IOException;
}
