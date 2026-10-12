package com.example.ailab.business.application;

import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ModelCalls.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.io.*;

/** 私人调用全文按次读取，身份和来源在数据端口再次核验。 */
@Service
public class ModelCallApplicationService {
    private final KnowledgeAccessPolicy policy;
    private final ModelCallArchivePort archive;
    private final TraceRecordPort traces;
    public ModelCallApplicationService(KnowledgeAccessPolicy policy,ModelCallArchivePort archive,TraceRecordPort traces) {
        this.policy=policy;this.archive=archive;this.traces=traces;
    }
    private void id(String id) {if(id==null||!id.matches("[0-9a-fA-F-]{36}"))throw LabException.invalid("调用标识无效");}
    public TraceNode node(UserContext actor,String trace,String node) {id(trace);id(node);return traces.node(policy.current(actor),trace,node);}
    public List<Detail> list(UserContext actor,String trace,String node,int page,int size) {id(trace);return archive.list(policy.current(actor),trace,node,page,size);}
    public Detail detail(UserContext actor,String trace,String call) {id(trace);id(call);return archive.detail(policy.current(actor),trace,call);}
    public PayloadPage page(UserContext actor,String trace,String call,String direction,String cursor,int limit) {id(trace);id(call);return archive.page(policy.current(actor),trace,call,direction,cursor,limit);}
    public PayloadPage modelText(UserContext actor,String trace,String call,String cursor,int limit) {id(trace);id(call);return archive.modelText(policy.current(actor),trace,call,cursor,limit);}
    public void download(UserContext actor,String trace,String call,String direction,OutputStream output) throws IOException {id(trace);id(call);archive.download(policy.current(actor),trace,call,direction,output);}
}
