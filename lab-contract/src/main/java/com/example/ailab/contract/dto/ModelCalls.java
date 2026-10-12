package com.example.ailab.contract.dto;

import com.example.ailab.contract.context.UserContext;
import java.time.Instant;
import java.util.List;

/** 服务端绑定的调用身份和有界查询结果；大正文不进入运行图。 */
public final class ModelCalls {
    private ModelCalls() { }
    public record Context(UserContext actor, String traceId, long taskId, String taskType,
            String stage, String operation, String businessNodeId, String schemaId,
            String schemaVersion, String contractHash, List<SourceDependency> sources) {
        public Context {
            sources = List.copyOf(sources);
            if(actor == null || traceId == null || !traceId.matches("[0-9a-fA-F-]{36}")
                    || taskId <= 0 || !java.util.Set.of("KNOWLEDGE_COMPILATION", "QUIZ_GENERATION").contains(taskType))
                throw new IllegalArgumentException("调用身份无效");
        }
    }
    public record CallRecord(String callId, Context context, String spanId, String parentSpanId,
            String modelId, int attempt, String sendState, String callStatus, String validationStatus,
            String errorCode, long httpMillis, long validationMillis, ValidationDiagnostic diagnostic,
            Instant startedAt, Instant endedAt) {
        public CallRecord withoutDiagnostic() {
            return new CallRecord(callId,context,spanId,parentSpanId,modelId,attempt,sendState,callStatus,
                    validationStatus,errorCode,httpMillis,validationMillis,null,startedAt,endedAt);
        }
    }
    public record PayloadRef(String payloadId, String state, long rawBytes, long storedBytes,
            long charCount, int chunkCount, String checksum, String preview, boolean redacted,
            Instant expiresAt) {
        public static PayloadRef unavailable(String state) {
            return unavailable(state,null);
        }
        public static PayloadRef unavailable(String state,Instant expiresAt) {
            return new PayloadRef(null, state, 0, 0, 0, 0, null, null, false, expiresAt);
        }
        public PayloadRef readableAt(Instant now) {
            return expiresAt!=null&&!now.isBefore(expiresAt)?unavailable("EXPIRED"):this;
        }
        public PayloadRef withoutPreview() {
            return new PayloadRef(payloadId,state,rawBytes,storedBytes,charCount,chunkCount,checksum,null,redacted,expiresAt);
        }
    }
    public record Observation(String callId, String callStatus, String validationStatus,
            String schemaId, long httpMillis, long validationMillis, long archiveMillis,
            PayloadRef input, PayloadRef output) {
        public Observation hidden() {
            return new Observation(null,callStatus,validationStatus,schemaId,httpMillis,validationMillis,
                    archiveMillis,null,null);
        }
        public Observation readableAt(Instant now) {
            return new Observation(callId,callStatus,validationStatus,schemaId,httpMillis,validationMillis,archiveMillis,
                    input==null?null:input.readableAt(now),output==null?null:output.readableAt(now));
        }
        public Observation withoutPreviews() {
            return new Observation(callId,callStatus,validationStatus,schemaId,httpMillis,validationMillis,archiveMillis,
                    input==null?null:input.withoutPreview(),output==null?null:output.withoutPreview());
        }
        public boolean expired() {
            return input!=null&&"EXPIRED".equals(input.state())||output!=null&&"EXPIRED".equals(output.state());
        }
    }
    public record Detail(CallRecord call, PayloadRef input, PayloadRef output) { }
    public record PayloadPage(PayloadRef payload, String content, String nextCursor, String contentKind) { }
}
