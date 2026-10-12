package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ModelCalls.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.port.ModelCallArchivePort;
import com.example.ailab.data.persistence.mapper.ModelCallMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** 独立归档：每块一次短写入，READY 只在全部块提交后发布。 */
@Repository
public class ModelCallArchiveRepository implements ModelCallArchivePort {
    private final SqlSupport sql;
    private final ModelCallMapper mapper;
    private final DocumentSqlRepository documents;
    // MySQL JSON 小数按 DOUBLE 存储；日期使用字符串，避免纳秒丢失导致调用身份比较失败。
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private org.springframework.transaction.support.TransactionTemplate writes;
    /** 即使调用者已有事务，每块仍独立短提交；归档不参与业务成果事务。 */
    @org.springframework.beans.factory.annotation.Autowired
    public void transactions(org.springframework.transaction.PlatformTransactionManager manager) {
        writes=new org.springframework.transaction.support.TransactionTemplate(manager);
        writes.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setTimeout(2);
    }
    private int write(java.util.function.IntSupplier statement) {
        return writes==null?statement.getAsInt():Objects.requireNonNull(writes.execute(status->statement.getAsInt()));
    }
    @org.springframework.beans.factory.annotation.Value("${lab.model-call-archive.enabled:true}")
    private boolean enabled = true;
    @org.springframework.beans.factory.annotation.Value("${lab.model-call-archive.maximum-bytes:134217728}")
    private long maximum = 134217728;
    @org.springframework.beans.factory.annotation.Value("${lab.model-call-archive.retention-days:7}")
    private int retention = 7;
    public ModelCallArchiveRepository(SqlSupport sql, ModelCallMapper mapper, DocumentSqlRepository documents) {
        this.sql=sql; this.mapper=mapper; this.documents=documents;
    }
    public boolean enabled() { return enabled; }
    private Instant expiry(CallRecord call) { return call.startedAt().plus(Duration.ofDays(Math.max(1,retention))); }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (IOException failure) { throw new IllegalStateException("调用登记编码失败"); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value,type); }
        catch (IOException failure) { throw new IllegalStateException("调用登记读取失败"); }
    }
    public void begin(CallRecord call) {
        var c=call.context();
        var previous=mapper.call(new Object[]{c.traceId(),c.actor().userId(),call.callId()});
        if(!previous.isEmpty()) {
            var old=decode(previous.get(0).string("record_json"),CallRecord.class);
            if(!old.context().equals(c)||!Objects.equals(old.modelId(),call.modelId())||old.attempt()!=call.attempt()
                    ||!Objects.equals(old.spanId(),call.spanId())||!old.startedAt().equals(call.startedAt())
                    ||old.endedAt()!=null&&!old.equals(call))throw new IllegalStateException("调用身份或终态不允许覆盖");
            return;
        }
        write(()->mapper.begin(new Object[]{call.callId(),c.traceId(),call.spanId(),c.businessNodeId(),c.actor().userId(),
                c.taskId(),encode(c.sources()),encode(call),Timestamp.from(call.startedAt()),Timestamp.from(expiry(call))}));
    }
    public Detail finish(CallRecord call,String input,String output,String outputState) {
        begin(call);
        if(write(()->mapper.finish(new Object[]{encode(call),call.callId(),call.context().actor().userId(),call.context().traceId()}))!=1)
            throw new IllegalStateException("调用登记未提交");
        return new Detail(call,saveSide(call,"INPUT",input,"NOT_CAPTURED"),saveSide(call,"OUTPUT",output,outputState));
    }
    private static final class PayloadConflict extends IllegalStateException {
        PayloadConflict() {super("调用正文不允许覆盖");}
    }
    /** 存储错误按侧降级，身份与已就绪正文冲突仍明确拒绝。 */
    private PayloadRef saveSide(CallRecord call,String direction,String text,String absent) {
        try {return save(call,direction,text,absent);}
        catch(PayloadConflict conflict) {throw conflict;}
        catch(RuntimeException unavailable) {
            // 描述查询／初始化也可能失败；仅补失败状态，不覆盖已经发布的正文。
            try {
                var rows=mapper.payload(new Object[]{call.callId(),direction});
                if(rows.isEmpty()) {
                    var ref=new PayloadRef(UUID.randomUUID().toString(),"SAVE_FAILED",0,0,0,0,null,null,false,expiry(call));
                    write(()->mapper.startPayload(new Object[]{ref.payloadId(),call.callId(),direction,ref.state(),encode(ref),Timestamp.from(ref.expiresAt())}));
                } else if("WRITING".equals(rows.get(0).string("state"))) {
                    String id=rows.get(0).string("payload_id");
                    var ref=new PayloadRef(id,"SAVE_FAILED",0,0,0,0,null,null,false,expiry(call));
                    write(()->mapper.readyPayload(new Object[]{ref.state(),encode(ref),id}));
                }
            }catch(RuntimeException ignored) { }
            return PayloadRef.unavailable("SAVE_FAILED",expiry(call));
        }
    }
    private PayloadRef save(CallRecord call,String direction,String text,String absent) {
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        var existing=mapper.payload(new Object[]{call.callId(),direction});
        if(!existing.isEmpty()) {
            var ref=decode(existing.get(0).string("ref_json"),PayloadRef.class);
            if(text!=null&&"READY".equals(ref.state())) {
                try {
                    var digest=java.security.MessageDigest.getInstance("SHA-256");
                    if(ref.charCount()!=text.length())throw new PayloadConflict();
                    for(int start=0;start<text.length();) {int end=ModelPayloadCodec.end(text,start);digest.update(text.substring(start,end).getBytes(StandardCharsets.UTF_8));start=end;}
                    if(!HexFormat.of().formatHex(digest.digest()).equals(ref.checksum()))throw new PayloadConflict();
                } catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
            }
            return ref;
        }
        var expires=expiry(call);String id=UUID.randomUUID().toString();
        var initial=new PayloadRef(id,text==null?absent:"WRITING",0,0,text==null?0:text.length(),0,null,null,false,expires);
        write(()->mapper.startPayload(new Object[]{id,call.callId(),direction,initial.state(),encode(initial),Timestamp.from(expires)}));
        if(text==null)return initial;
        long bytes=0,stored=0;int count=0;
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            // 先测量再写；避免超限正文留下大量不可用块。
            for(int offset=0;offset<text.length();) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("归档处理超时");
                int end=ModelPayloadCodec.end(text,offset);
                bytes+=text.substring(offset,end).getBytes(StandardCharsets.UTF_8).length;
                if(bytes>maximum) {
                    var ref=new PayloadRef(id,"SIZE_LIMIT",bytes,0,text.length(),0,null,null,false,expires);
                    write(()->mapper.readyPayload(new Object[]{ref.state(),encode(ref),id})); return ref;
                }
                offset=end;
            }
            for(int offset=0;offset<text.length();) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("归档处理超时");
                int end=ModelPayloadCodec.end(text,offset);String part=text.substring(offset,end);
                var block=ModelPayloadCodec.encode(part);digest.update(part.getBytes(StandardCharsets.UTF_8));
                int number=count,position=offset;
                if(write(()->mapper.chunk(new Object[]{id,number,position,part.length(),block.rawLength(),block.codec(),block.checksum(),block.content()}))!=1)
                    throw new IllegalStateException("调用正文块未提交");
                stored+=block.content().length;count++;offset=end;
            }
            if(direction.equals("OUTPUT"))saveTextIndex(id,text,bytes,deadline);
            int previewEnd=Math.min(1000,text.length());
            if(previewEnd>0&&previewEnd<text.length()&&Character.isHighSurrogate(text.charAt(previewEnd-1)))previewEnd--;
            var ref=new PayloadRef(id,"READY",bytes,stored,text.length(),count,HexFormat.of().formatHex(digest.digest()),text.substring(0,previewEnd),text.contains("[REDACTED]"),expires);
            if(write(()->mapper.readyPayload(new Object[]{ref.state(),encode(ref),id}))!=1)throw new IllegalStateException("调用正文未就绪");
            return ref;
        } catch(Exception failure) {
            var ref=new PayloadRef(id,"SAVE_FAILED",bytes,stored,text.length(),count,null,null,false,expires);
            try { write(()->mapper.readyPayload(new Object[]{ref.state(),encode(ref),id})); } catch(RuntimeException ignored) { }
            return ref;
        }
    }
    private void saveTextIndex(String payloadId,String raw,long rawBytes,long deadline) {
        try {
            var view=rawBytes<=16777216?ModelTextIndex.build(raw,deadline):null;
            if(view==null) {write(()->mapper.readyText(new Object[]{"NOT_AVAILABLE",0L,payloadId}));return;}
            for(int start=0;start<view.segments().size();start+=128) {
                if(System.nanoTime()>deadline)throw new IllegalStateException("文本索引处理超时");
                var batch=view.segments().subList(start,Math.min(view.segments().size(),start+128));
                if(write(()->mapper.textSegments(payloadId,batch))!=batch.size())throw new IllegalStateException("文本索引未提交");
            }
            if(write(()->mapper.readyText(new Object[]{"READY",view.charCount(),payloadId}))!=1)throw new IllegalStateException("文本索引未就绪");
        } catch(IOException|RuntimeException failure) {
            try {write(()->mapper.readyText(new Object[]{"SAVE_FAILED",0L,payloadId}));}catch(RuntimeException ignored) { }
        }
    }
    private Detail read(SqlRow row,UserContext actor,boolean requireContent) {
        var call=decode(row.string("record_json"),CallRecord.class);
        boolean readable=true;
        try { documents.verifySources(new AuthorizedKnowledgeScope(actor,
                actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,List.of(),null,Instant.now()),call.context().sources()); }
        catch(LabException denied) { if(!Set.of("ACCESS_DENIED","CONTEXT_MAPPING_INVALID").contains(denied.code()))throw denied;readable=false; }
        if(!readable) {
            if(requireContent)throw LabException.denied();
            var c=call.context();var hidden=new Context(c.actor(),c.traceId(),c.taskId(),c.taskType(),c.stage(),c.operation(),c.businessNodeId(),c.schemaId(),c.schemaVersion(),c.contractHash(),List.of());
            return new Detail(new CallRecord(call.callId(),hidden,call.spanId(),call.parentSpanId(),call.modelId(),call.attempt(),call.sendState(),call.callStatus(),call.validationStatus(),call.errorCode(),call.httpMillis(),call.validationMillis(),null,call.startedAt(),call.endedAt()),PayloadRef.unavailable("ACCESS_REVOKED"),PayloadRef.unavailable("ACCESS_REVOKED"));
        }
        if(!Instant.now().isBefore(row.timestamp("expires_at").toInstant()))
            return new Detail(call.withoutDiagnostic(),PayloadRef.unavailable("EXPIRED"),PayloadRef.unavailable("EXPIRED"));
        var input=ref(call.callId(),"INPUT");var output=ref(call.callId(),"OUTPUT");
        return new Detail("EXPIRED".equals(output.state())?call.withoutDiagnostic():call,input,output);
    }
    private PayloadRef ref(String callId,String direction) {
        var rows=mapper.payload(new Object[]{callId,direction});
        if(rows.isEmpty())return PayloadRef.unavailable("NOT_CAPTURED");
        var ref=decode(rows.get(0).string("ref_json"),PayloadRef.class);
        if(!Instant.now().isBefore(rows.get(0).timestamp("expires_at").toInstant()))return PayloadRef.unavailable("EXPIRED");
        return ref;
    }
    public List<Detail> list(UserContext actor,String traceId,String nodeId,int page,int size) {
        if(page<0||page>100000||size<1||size>50)throw LabException.invalid("调用分页范围无效");
        sql.actor(actor,false);
        return mapper.calls(new Object[]{traceId,actor.userId(),nodeId,size,(long)page*size}).stream().map(r->read(r,actor,false)).toList();
    }
    private Detail authorized(UserContext actor,String traceId,String callId,boolean content) {
        sql.actor(actor,false);
        var rows=mapper.call(new Object[]{traceId,actor.userId(),callId});
        if(rows.size()!=1)throw LabException.denied();
        return read(rows.get(0),actor,content);
    }
    public Detail detail(UserContext actor,String traceId,String callId) { return authorized(actor,traceId,callId,false); }
    private PayloadRef ready(Detail detail,String direction) {
        var ref=switch(direction) { case "INPUT"->detail.input();case "OUTPUT"->detail.output();default->throw LabException.invalid("正文方向无效"); };
        if(!"READY".equals(ref.state()))throw new LabException("MODEL_CALL_PAYLOAD_UNAVAILABLE","调用全文不可用："+ref.state());
        return ref;
    }
    private String block(SqlRow row) {
        try {
            String text=new String(ModelPayloadCodec.decode(new ModelPayloadCodec.Block(row.string("codec"),(byte[])row.value("content"),row.intValue("raw_length"),row.string("checksum"))),StandardCharsets.UTF_8);
            if(text.length()!=row.intValue("char_length"))throw new IllegalStateException("正文字符长度不符");
            return text;
        }
        catch(Exception corrupt) { throw new LabException("MODEL_CALL_PAYLOAD_CORRUPT","调用正文完整性校验失败"); }
    }
    public PayloadPage page(UserContext actor,String traceId,String callId,String direction,String cursor,int limit) {
        if(limit<2||limit>16384)throw LabException.invalid("正文分页大小无效");
        var ref=ready(authorized(actor,traceId,callId,true),direction);long offset=0;
        try { if(cursor!=null&&!cursor.isBlank())offset=Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII)); }
        catch(RuntimeException invalid) { throw LabException.invalid("正文游标无效"); }
        if(offset<0||offset>ref.charCount())throw LabException.invalid("正文游标越界");
        StringBuilder text=new StringBuilder();long next=offset;
        for(var row:mapper.chunkPage(new Object[]{ref.payloadId(),offset})) {
            String part=block(row);int start=(int)Math.max(0,next-row.longValue("char_offset"));
            if(start>part.length()||row.longValue("char_offset")>next)throw new LabException("MODEL_CALL_PAYLOAD_CORRUPT","调用正文块缺失");
            if(start>0&&start<part.length()&&Character.isLowSurrogate(part.charAt(start)))throw LabException.invalid("正文游标不是字符边界");
            int end=Math.min(part.length(),start+limit-text.length());
            if(end>start&&end<part.length()&&Character.isHighSurrogate(part.charAt(end-1)))end--;
            text.append(part,start,end);next+=end-start;if(text.length()>=limit-1)break;
        }
        if(next<ref.charCount()&&text.isEmpty())throw new LabException("MODEL_CALL_PAYLOAD_CORRUPT","调用正文块缺失");
        String nextCursor=next<ref.charCount()?Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(next).getBytes(StandardCharsets.US_ASCII)):null;
        return new PayloadPage(ref,text.toString(),nextCursor,"HTTP_BODY");
    }
    /** 按持久位置索引读取当前页，正文仍只保留在原始响应块中。 */
    public PayloadPage modelText(UserContext actor,String traceId,String callId,String cursor,int limit) {
        if(limit<2||limit>16384)throw LabException.invalid("正文分页大小无效");
        var ref=ready(authorized(actor,traceId,callId,true),"OUTPUT");
        var views=mapper.textView(new Object[]{ref.payloadId()});
        if(views.size()!=1||!"READY".equals(views.get(0).string("model_text_state")))
            throw new LabException("MODEL_CALL_PAYLOAD_UNAVAILABLE","模型文本索引不可用，请查看原始响应");
        long length=views.get(0).longValue("model_text_chars"),offset=0;
        if(length<0||length>ref.charCount())throw new LabException("MODEL_CALL_PAYLOAD_CORRUPT","模型文本长度越界");
        try {if(cursor!=null&&!cursor.isBlank())offset=Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.US_ASCII));}
        catch(RuntimeException invalid) {throw LabException.invalid("正文游标无效");}
        if(offset<0||offset>length)throw LabException.invalid("正文游标越界");
        var cache=new TreeMap<Long,String>();var value=new StringBuilder();long next=offset;
        try {
            for(var segment:mapper.textPage(new Object[]{ref.payloadId(),offset,Math.min(length,offset+limit),(long)limit+1})) {
                long rawStart=segment.longValue("raw_start"),rawEnd=segment.longValue("raw_end"),position=segment.longValue("char_offset");
                String part=rawStart==-1&&rawEnd==-1?"\n":ModelTextIndex.decode(rawRange(ref,rawStart,rawEnd,cache));
                if(part.length()!=segment.intValue("char_length")||!ModelTextIndex.hash(part).equals(segment.string("checksum"))
                        ||position>next||next-position>part.length())throw new IOException("模型文本索引损坏");
                int start=Math.toIntExact(next-position);
                if(start>0&&start<part.length()&&Character.isLowSurrogate(part.charAt(start)))throw LabException.invalid("正文游标不是字符边界");
                int end=Math.min(part.length(),start+limit-value.length());
                if(end<part.length()&&end>start&&Character.isHighSurrogate(part.charAt(end-1)))end--;
                value.append(part,start,end);next+=end-start;
                if(value.length()>=limit-1)break;
            }
            if(next<Math.min(length,offset+limit-1))throw new IOException("模型文本索引缺失");
            return new PayloadPage(ref,value.toString(),next<length?Base64.getUrlEncoder().withoutPadding().encodeToString(Long.toString(next).getBytes(StandardCharsets.US_ASCII)):null,"MODEL_TEXT");
        } catch(IOException failure) {throw new LabException("MODEL_CALL_PAYLOAD_CORRUPT","模型文本完整性校验失败");}
    }
    private String rawRange(PayloadRef ref,long start,long end,NavigableMap<Long,String> cache) throws IOException {
        if(start<0||end<start||end>ref.charCount()||end-start>65536)throw new IOException("模型文本位置越界");
        var result=new StringBuilder();long next=start;
        while(next<end) {
            var entry=cache.floorEntry(next);
            if(entry==null||entry.getKey()+entry.getValue().length()<=next) {
                for(var row:mapper.chunkPage(new Object[]{ref.payloadId(),next}))cache.put(row.longValue("char_offset"),block(row));
                entry=cache.floorEntry(next);
                if(entry==null||entry.getKey()+entry.getValue().length()<=next)throw new IOException("调用正文块缺失");
            }
            int from=Math.toIntExact(next-entry.getKey()),to=Math.toIntExact(Math.min(entry.getValue().length(),end-entry.getKey()));
            result.append(entry.getValue(),from,to);next+=to-from;
        }
        return result.toString();
    }
    public void download(UserContext actor,String traceId,String callId,String direction,OutputStream output) throws IOException {
        var ref=ready(authorized(actor,traceId,callId,true),direction);
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");long chars=0,bytes=0;
            for(int i=0;i<ref.chunkCount();i++) {
                ready(authorized(actor,traceId,callId,true),direction);
                var rows=mapper.chunks(new Object[]{ref.payloadId(),i});
                if(rows.size()!=1||rows.get(0).longValue("char_offset")!=chars)throw new IOException("调用正文块缺失");
                String part=block(rows.get(0));byte[] raw=part.getBytes(StandardCharsets.UTF_8);
                chars+=part.length();bytes+=raw.length;digest.update(raw);output.write(raw);
            }
            if(chars!=ref.charCount()||bytes!=ref.rawBytes()||!HexFormat.of().formatHex(digest.digest()).equals(ref.checksum()))throw new IOException("调用正文完整性校验失败");
        } catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    /** 每次最多32个载荷、每个最多32块及128个位置段；不会持有长清理事务。 */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${lab.model-call-archive.cleanup-ms:60000}")
    public void purge() {
        var now=Timestamp.from(Instant.now());
        for(var row:mapper.expired(new Object[]{now})) {
            String id=row.string("payload_id");mapper.expire(new Object[]{id});mapper.deleteChunks(new Object[]{id});mapper.deleteTextSegments(new Object[]{id});
            mapper.deletePayload(new Object[]{id});mapper.deleteCall(new Object[]{row.string("call_id"),now});
        }
        mapper.deleteExpiredCalls(new Object[]{now});
    }
}
