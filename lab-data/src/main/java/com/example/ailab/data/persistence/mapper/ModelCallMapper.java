package com.example.ailab.data.persistence.mapper;

import com.example.ailab.data.persistence.po.SqlRow;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 固定 SQL 投影和二进制参数绑定；不将全文拼入 SQL。 */
public interface ModelCallMapper {
    int begin(@Param("args") Object[] args);
    int finish(@Param("args") Object[] args);
    List<SqlRow> calls(@Param("args") Object[] args);
    List<SqlRow> call(@Param("args") Object[] args);
    List<SqlRow> payload(@Param("args") Object[] args);
    int startPayload(@Param("args") Object[] args);
    int readyPayload(@Param("args") Object[] args);
    int chunk(@Param("args") Object[] args);
    List<SqlRow> chunks(@Param("args") Object[] args);
    List<SqlRow> chunkPage(@Param("args") Object[] args);
    int textSegments(@Param("payloadId") String payloadId,@Param("segments") List<com.example.ailab.data.persistence.po.ModelTextSegment> segments);
    int readyText(@Param("args") Object[] args);
    List<SqlRow> textView(@Param("args") Object[] args);
    List<SqlRow> textPage(@Param("args") Object[] args);
    int deleteTextSegments(@Param("args") Object[] args);
    List<SqlRow> expired(@Param("args") Object[] args);
    int expire(@Param("args") Object[] args);
    int deleteChunks(@Param("args") Object[] args);
    int deletePayload(@Param("args") Object[] args);
    int deleteCall(@Param("args") Object[] args);
    int deleteExpiredCalls(@Param("args") Object[] args);
}
