package com.example.ailab.web.controller;

import com.example.ailab.business.application.ModelCallApplicationService;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ModelCalls.*;
import com.example.ailab.web.security.CurrentUser;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.List;

/** 有界预览和按需全文接口；不接受客户端覆盖所有者。 */
@RestController
@RequestMapping("/api/v1/runs/{trace}")
public class ModelCallController {
    private final ModelCallApplicationService calls;
    public ModelCallController(ModelCallApplicationService calls) {this.calls=calls;}
    private <T> ResponseEntity<T> privateResult(T body) {return ResponseEntity.ok().header("Cache-Control","no-store").body(body);}
    @GetMapping("/nodes/{node}")
    public ResponseEntity<TraceNode> node(Authentication actor,@PathVariable String trace,@PathVariable String node) {return privateResult(calls.node(CurrentUser.from(actor),trace,node));}
    @GetMapping("/calls")
    public ResponseEntity<List<Detail>> list(Authentication actor,@PathVariable String trace,@RequestParam(required=false) String nodeId,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) {return privateResult(calls.list(CurrentUser.from(actor),trace,nodeId,page,size));}
    @GetMapping("/calls/{call}")
    public ResponseEntity<Detail> detail(Authentication actor,@PathVariable String trace,@PathVariable String call) {return privateResult(calls.detail(CurrentUser.from(actor),trace,call));}
    @GetMapping("/calls/{call}/payloads/{direction}")
    public ResponseEntity<PayloadPage> page(Authentication actor,@PathVariable String trace,@PathVariable String call,@PathVariable String direction,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="8192") int limit,@RequestParam(defaultValue="raw") String view) {
        if(!java.util.Set.of("raw","model").contains(view)||view.equals("model")&&!direction.equals("OUTPUT"))throw com.example.ailab.contract.error.LabException.invalid("正文视图无效");
        return privateResult(view.equals("model")?calls.modelText(CurrentUser.from(actor),trace,call,cursor,limit):calls.page(CurrentUser.from(actor),trace,call,direction,cursor,limit));
    }
    @GetMapping("/calls/{call}/payloads/{direction}/download")
    public ResponseEntity<StreamingResponseBody> download(Authentication actor,@PathVariable String trace,@PathVariable String call,@PathVariable String direction) {
        var current=CurrentUser.from(actor);var detail=calls.detail(current,trace,call);
        if(!java.util.Set.of("INPUT","OUTPUT").contains(direction))throw com.example.ailab.contract.error.LabException.invalid("正文方向无效");
        var payload=direction.equals("INPUT")?detail.input():detail.output();
        if(!"READY".equals(payload.state()))throw new com.example.ailab.contract.error.LabException("MODEL_CALL_PAYLOAD_UNAVAILABLE","调用全文不可用");
        StreamingResponseBody body=out->calls.download(current,trace,call,direction,out);
        return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Type","text/plain;charset=UTF-8")
                .header("Content-Disposition","attachment; filename=\"model-call-"+call+"-"+direction+".txt\"").body(body);
    }
}
