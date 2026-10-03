package com.example.ailab.web.controller;
import com.example.ailab.business.application.*;
import com.example.ailab.contract.dto.*;
import com.example.ailab.web.dto.Requests;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.http.MediaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.*;
import java.io.IOException;
/** 只有通过汇聚的模型内容才进入 JSON/SSE；私人资源无 ADMIN 旁路。 */
@RestController @RequestMapping("/api/v1")
public class AssistantController {
    private final AssistantApplicationService assistant;private final PersonalApplicationService personal;private final ObjectMapper json;
    /** 不直接注入模型 SDK 或 AI 实现。 */
    public AssistantController(AssistantApplicationService a,PersonalApplicationService p,ObjectMapper j){assistant=a;personal=p;json=j;}
    /** 单轮知识问答。 */
    @PostMapping("/chat") public AiResult chat(Authentication a,@RequestBody AiRequest r){return assistant.answer(CurrentUser.from(a),r);}
    /** 默认先缓存全文并校验，再分块发送；done 只发送一次。 */
    @PostMapping(value="/chat/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) public SseEmitter stream(Authentication a,@RequestBody AiRequest r)throws IOException{
        var emitter=new SseEmitter(60000L);AiResult result;
        try{result=assistant.answer(CurrentUser.from(a),r);}catch(com.example.ailab.contract.error.LabException e){if(java.util.Set.of("AUTH_REQUIRED","ACCESS_DENIED","INVALID_ARGUMENTS").contains(e.code()))throw e;emitter.send(SseEmitter.event().id("1").name("error").data(Map.of("code",e.code(),"message",e.getMessage())));emitter.complete();return emitter;}
        long seq=1;
        emitter.send(SseEmitter.event().id(Long.toString(seq++)).name("progress").data(Map.of("stage","validated")));
        for(int i=0;i<result.answer().length();i+=512)emitter.send(SseEmitter.event().id(Long.toString(seq++)).name("delta").data(Map.of("text",result.answer().substring(i,Math.min(i+512,result.answer().length())))));
        for(var c:result.citations())emitter.send(SseEmitter.event().id(Long.toString(seq++)).name("citation").data(c));
        emitter.send(SseEmitter.event().id(Long.toString(seq)).name("done").data(result));emitter.complete();return emitter;
    }
    /** 笔记准备返回完整来源与待写内容，没有保存成功含义。 */
    @PostMapping("/notes/prepare") public ApprovalSnapshot prepare(Authentication a,@Valid @RequestBody Requests.Note r){return personal.prepare(CurrentUser.from(a),r.knowledgeBaseId(),r.title(),r.content(),r.sourceDependencies().stream().map(Requests.NoteSource::toDependency).toList());}
    /** 本人预览重新核验所有来源。 */
    @GetMapping("/approvals/{id}") public ApprovalSnapshot approval(Authentication a,@PathVariable String id){return personal.approval(CurrentUser.from(a),id);}
    /** 决定 API 不接收参数覆盖字段。 */
    @PostMapping("/approvals/{id}/decision") public ApprovalSnapshot decision(Authentication a,@PathVariable String id,@Valid @RequestBody Requests.Decision r){return personal.decide(CurrentUser.from(a),id,r.approved());}
    /** 本人偏好列表。 */
    @GetMapping("/memories") public List<MemorySnapshot> memories(Authentication a){return personal.memories(CurrentUser.from(a));}
    /** 显式用户命令保存偏好。 */
    @PostMapping("/memories") public MemorySnapshot createMemory(Authentication a,@Valid @RequestBody Requests.MemoryCreate r){return personal.createMemory(CurrentUser.from(a),r.content());}
    /** 本人版本化更正。 */
    @PatchMapping("/memories/{id}") public MemorySnapshot updateMemory(Authentication a,@PathVariable long id,@Valid @RequestBody Requests.MemoryUpdate r){return personal.updateMemory(CurrentUser.from(a),id,r.version(),r.content());}
    /** 删除后不再读取。 */
    @DeleteMapping("/memories/{id}") public void deleteMemory(Authentication a,@PathVariable long id,@RequestParam long version){personal.deleteMemory(CurrentUser.from(a),id,version);}
    /** 运行列表只返回本人。 */
    @GetMapping("/runs") public List<TraceSnapshot> runs(Authentication a,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size){return personal.runs(CurrentUser.from(a),page,size);}
    /** 本人运行详情。 */
    @GetMapping("/runs/{id}") public TraceSnapshot run(Authentication a,@PathVariable String id){return personal.run(CurrentUser.from(a),id);}
}
