package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ContentWorkflow.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.persistence.mapper.ContentWorkflowMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.fasterxml.jackson.databind.*;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** 父任务租约下的短事务；来源证明、计划及独立节点的完成观察不可覆盖。 */
@Repository
@Transactional
public class ContentWorkflowRepository implements ContentWorkflowStorePort {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final TaskRepository tasks;
    private final DocumentSqlRepository documents;
    private final WorkflowRunStorePort workflows;
    private final ContentWorkflowMapper mapper;
    public ContentWorkflowRepository(TaskRepository tasks, DocumentSqlRepository documents, WorkflowRunStorePort workflows, SqlSupport sql) {
        this.tasks=tasks; this.documents=documents; this.workflows=workflows; this.mapper=sql.mapper(ContentWorkflowMapper.class);
    }
    public Optional<SourcePlan> sourcePlan(TaskLease lease) { valid(lease); return source(lease.task().taskId()); }
    private Optional<SourcePlan> source(long id) {
        var rows=mapper.run(new Object[]{id});
        return rows.isEmpty() ? Optional.empty() : Optional.of(decode(rows.get(0).string("source_json"),rows.get(0).string("source_hash"),SourcePlan.class));
    }
    public void bind(TaskLease lease, SourcePlan value) {
        valid(lease);
        var old=source(lease.task().taskId());
        if(old.isPresent()){if(!old.get().equals(value))throw conflict();return;}
        if(!workflows.binding(lease).filter(value.workflow()::equals).isPresent() || value.workflow().architecture()!=ExecutionArchitecture.FIXED
                || !tasks.contentPolicy(lease).filter(value.policy()::equals).isPresent() || value.slices().isEmpty()
                || !value.slices().stream().map(Learning.SourceSlice::documentId).collect(Collectors.toSet()).equals(new HashSet<>(lease.request().documentIds())))throw conflict();
        verify(documents,lease.actor(),lease.request().scope(),value);
        mapper.bind(new Object[]{lease.task().taskId(),hash(value),encode(value)});
    }
    public Optional<String> completed(TaskLease lease,String id,String inputHash) {
        valid(lease); identity(id,inputHash);
        var rows=mapper.node(new Object[]{lease.task().taskId(),id});
        if(rows.isEmpty())return Optional.empty();
        var row=rows.get(0); if(!inputHash.equals(row.string("input_hash")))throw conflict();
        return row.string("status").equals("COMPLETED") ? Optional.of(output(row)) : Optional.empty();
    }
    public void begin(TaskLease lease,String id,String stage,String inputHash) {
        valid(lease);identity(id,inputHash);
        if(source(lease.task().taskId()).isEmpty() || !TaskProgress.stepIds(lease.request().taskType()).contains(stage))throw conflict();
        var rows=mapper.node(new Object[]{lease.task().taskId(),id});
        if(!rows.isEmpty()){if(!rows.get(0).string("input_hash").equals(inputHash)||!rows.get(0).string("stage").equals(stage))throw conflict();return;}
        mapper.begin(new Object[]{lease.task().taskId(),id,stage,inputHash});tasks.beginStep(lease,stage);
    }
    public void complete(TaskLease lease,String id,String inputHash,String text) {
        valid(lease);identity(id,inputHash);
        String value=canonical(text);
        if(TextWindow.count(value)>source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict).policy().resultBytes())throw conflict();
        var rows=mapper.node(new Object[]{lease.task().taskId(),id});
        if(rows.isEmpty()||!rows.get(0).string("input_hash").equals(inputHash))throw conflict();
        if(rows.get(0).string("status").equals("COMPLETED")){if(!output(rows.get(0)).equals(value))throw conflict();return;}
        if(mapper.complete(new Object[]{SqlSupport.hash(value),value,lease.task().taskId(),id,inputHash})!=1)throw conflict();
        mapper.progress(new Object[]{lease.task().taskId()});
    }
    public void accept(TaskLease lease,OutputPlan plan) {
        valid(lease);
        var row=run(lease.task().taskId());var source=source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);
        verify(documents,lease.actor(),lease.request().scope(),source);
        if(row.get("plan_hash")!=null){if(!hash(plan).equals(row.string("plan_hash")))throw conflict();return;}
        requireNodes(lease.task().taskId(),source.slices().stream().map(s->"read_"+s.id()).toList());
        if(plan.version()!=2 || !plan.taskType().equals(lease.request().taskType()) || !plan.sourceHash().equals(row.string("source_hash"))
                || plan.units().isEmpty() || plan.units().size()>source.policy().maximumUnits()
                || plan.requiredNodes().size()!=new HashSet<>(plan.requiredNodes()).size()
                || !plan.requiredNodes().equals(plan.units().stream().map(u->"accepted_"+u.id()).toList()))throw conflict();
        Set<String> facts=new HashSet<>();
        for(var slice:source.slices()) {
            var node=mapper.node(new Object[]{lease.task().taskId(),"read_"+slice.id()}).get(0);
            try{JSON.readValue(output(node),Facts.class).items().forEach(i->facts.add(i.id()));}catch(Exception e){throw conflict();}
        }
        Set<String> selected=new HashSet<>();
        Set<String> themeIds=plan.intent().themes().stream().map(Theme::id).collect(Collectors.toSet());int index=0;int imageCount=0;
        for(var unit:plan.units()) {
            if(!unit.id().equals(String.format(Locale.ROOT,"u%04d",++index))||!themeIds.contains(unit.themeId())||unit.itemIds().isEmpty()
                    ||new HashSet<>(unit.itemIds()).size()!=unit.itemIds().size()||!facts.containsAll(unit.itemIds()))throw conflict();
            if(!"NONE".equals(unit.imageMode()))imageCount++;
            if("KNOWLEDGE_COMPILATION".equals(plan.taskType())&&!Collections.disjoint(selected,unit.itemIds()))throw conflict();
            selected.addAll(unit.itemIds());
        }
        for(var omission:plan.omissions()) {
            if(omission.reason()==null||omission.reason().isBlank()||!facts.containsAll(omission.itemIds())||!Collections.disjoint(selected,omission.itemIds()))throw conflict();
            selected.addAll(omission.itemIds());
        }
        if(!selected.equals(facts))throw conflict();
        boolean ppt="NOTES_PPT".equals(plan.taskType()),quiz="QUIZ_GENERATION".equals(plan.taskType());
        int sourcePages=ppt?Math.max(1,(source.sources().size()+imageCount+11)/12):0;
        var counts=new Counts(quiz?index:0,!ppt&&!quiz?(int)plan.units().stream().map(UnitPlan::themeId).distinct().count():0,
                !ppt&&!quiz?index:0,ppt?index:0,sourcePages,ppt?index+sourcePages:0);
        if(!counts.equals(plan.counts())||imageCount>8||!ppt&&imageCount>0||!ppt&&!quiz&&!plan.omissions().isEmpty())throw conflict();
        if(plan.estimatedTurns()<plan.units().size()*2 || plan.estimatedAttempts()<plan.estimatedTurns()
                || plan.estimatedTurns()>tasks.remainingModelTurns(lease) || plan.estimatedAttempts()>tasks.remainingModelAttempts(lease)
                || plan.estimatedOutputBytes()>source.policy().resultBytes())
            throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","内容计划超过剩余资源，请调整资料范围或详略要求");
        if(mapper.accept(new Object[]{hash(plan),encode(plan),lease.task().taskId()})!=1)throw conflict();
        mapper.progress(new Object[]{lease.task().taskId()});
    }
    public void finish(TaskLease lease,String stage,List<String> nodes) {
        valid(lease);requireNodes(lease.task().taskId(),nodes);
        if(!TaskProgress.stepIds(lease.request().taskType()).contains(stage))throw conflict();
        var source=source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);
        if(stage.equals("extract")) {
            var expected=source.slices().stream().map(s->"read_"+s.id()).toList();
            if(!new HashSet<>(nodes).equals(new HashSet<>(expected)))throw conflict();
            for(var entry:source.slices().stream().collect(Collectors.groupingBy(Learning.SourceSlice::documentId)).entrySet()) {
                var slices=entry.getValue();var last=slices.get(slices.size()-1);
                mapper.coverage(new Object[]{lease.task().taskId(),entry.getKey(),last.documentVersion(),last.processingRevision(),slices.size(),last.endOffset()});
            }
        }
        mapper.stage(new Object[]{lease.task().taskId(),stage});mapper.progress(new Object[]{lease.task().taskId()});
    }
    public void verifyComplete(TaskLease lease) {
        valid(lease);var source=source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);
        verify(documents,lease.actor(),lease.request().scope(),source);
        var row=run(lease.task().taskId());if(row.get("plan_hash")==null)throw conflict();
        var plan=decode(row.string("plan_json"),row.string("plan_hash"),OutputPlan.class);
        requireNodes(lease.task().taskId(),plan.requiredNodes());
    }
    public void publish(TaskLease lease,String resultJson,String markdown) {
        verifyComplete(lease);
        var rows=mapper.node(new Object[]{lease.task().taskId(),"result"});
        if(rows.isEmpty()||!output(rows.get(0)).equals(canonical(resultJson)))throw conflict();
        var source=source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);
        tasks.publish(lease,new TaskCheckpoint("result",markdown,source.sources(),false));
    }
    public Optional<Snapshot> readPlan(UserContext actor,long id) {
        tasks.read(actor,id);var rows=mapper.run(new Object[]{id});
        if(rows.isEmpty()||rows.get(0).get("plan_hash")==null)return Optional.empty();
        var row=rows.get(0);var source=decode(row.string("source_json"),row.string("source_hash"),SourcePlan.class);
        verify(documents,actor,null,source);
        var plan=decode(row.string("plan_json"),row.string("plan_hash"),OutputPlan.class);
        var nodes=mapper.nodes(new Object[]{id}).stream().filter(n->n.string("status").equals("COMPLETED")).peek(ContentWorkflowRepository::output).map(n->n.string("node_id")).collect(Collectors.toSet());
        return Optional.of(new Snapshot(plan,row.string("plan_hash"),(int)plan.requiredNodes().stream().filter(nodes::contains).count(),true));
    }
    public String result(UserContext actor,long id) {
        var task=tasks.read(actor,id);if(!Learning.supports(task.taskType())||!task.status().equals("SUCCEEDED"))throw new LabException("WORKFLOW_RESULT_UNAVAILABLE","学习结果尚未发布");
        var plan=readPlan(actor,id).orElseThrow(ContentWorkflowRepository::conflict);requireNodes(id,plan.plan().requiredNodes());
        var rows=mapper.node(new Object[]{id,"result"});if(rows.isEmpty())throw conflict();return output(rows.get(0));
    }
    private SqlRow run(long id) {var rows=mapper.run(new Object[]{id});if(rows.isEmpty())throw conflict();return rows.get(0);}
    private void requireNodes(long taskId,List<String> ids) {
        var rows=mapper.nodes(new Object[]{taskId});var nodes=new HashMap<String,SqlRow>();rows.forEach(n->nodes.put(n.string("node_id"),n));
        for(String id:ids){var row=nodes.get(id);if(row==null)throw conflict();output(row);}
    }
    private void valid(TaskLease lease) {tasks.valid(lease);if(!lease.request().documentDriven())throw conflict();}
    private static void identity(String id,String hash) {if(id==null||!id.matches("[a-z][a-z0-9_]{0,63}")||hash==null||!hash.matches("[a-f0-9]{64}"))throw conflict();}
    private static String output(SqlRow row){if(!row.string("status").equals("COMPLETED"))throw conflict();String v=canonical(row.string("output_json"));if(!SqlSupport.hash(v).equals(row.string("output_hash")))throw conflict();return v;}
    private static String canonical(String text){try{return JSON.writeValueAsString(JSON.readValue(text,Object.class));}catch(Exception e){throw conflict();}}
    private static String encode(Object value){try{return canonical(JSON.writeValueAsString(value));}catch(Exception e){throw conflict();}}
    private static String hash(Object value){return SqlSupport.hash(encode(value));}
    private static <T>T decode(String text,String hash,Class<T> type){try{if(!SqlSupport.hash(canonical(text)).equals(hash))throw conflict();return JSON.readValue(text,type);}catch(Exception e){throw conflict();}}
    public static void verifyArtifactBaseline(SqlRow row,UserContext actor,DocumentSqlRepository documents) {
        if(row.get("content_baseline")==null)return;
        verify(documents,actor,null,decode(row.string("content_baseline"),row.string("content_baseline_hash"),SourcePlan.class));
    }
    public static void verifyPublication(SqlRow row,List<SqlRow> rows,TaskLease lease,TaskCheckpoint result,DocumentSqlRepository documents) {
        var source=decode(row.string("source_json"),row.string("source_hash"),SourcePlan.class);
        verify(documents,lease.actor(),lease.request().scope(),source);
        var plan=decode(row.string("plan_json"),row.string("plan_hash"),OutputPlan.class);
        if(result.partial()||!result.sourceDependencies().equals(source.sources())||!plan.sourceHash().equals(row.string("source_hash")))throw conflict();
        var nodes=rows.stream().collect(Collectors.toMap(n->n.string("node_id"),n->n));
        var required=new ArrayList<>(plan.requiredNodes());source.slices().forEach(s->required.add("read_"+s.id()));required.add("result");
        for(String id:required){var node=nodes.get(id);if(node==null)throw conflict();output(node);}
        try {var saved=JSON.readTree(output(nodes.get("result")));if(!SqlSupport.hash(canonical(saved.get("contentPlan").toString())).equals(row.string("plan_hash")))throw conflict();}
        catch(Exception invalid){throw conflict();}
    }
    private static void verify(DocumentSqlRepository documents,UserContext actor,ScopeRequest request,SourcePlan source) {
        var scope=request==null?new AuthorizedKnowledgeScope(actor,actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,List.of(),null,Instant.now())
                :new AuthorizedKnowledgeScope(actor,request.mode(),request.knowledgeBaseIds(),request.ownerUserId(),Instant.now());
        documents.verifySources(scope,source.sources());var content=new HashMap<Long,DocumentContent>();var cursors=new HashMap<Long,Integer>();int bytes=0;
        for(var slice:source.slices()) {
            var doc=content.computeIfAbsent(slice.documentId(),id->documents.read(scope,id));var d=doc.document();
            if(d.knowledgeBaseId()!=slice.knowledgeBaseId()||d.documentVersion()!=slice.documentVersion()||!Objects.equals(d.activeProcessingRevision(),slice.processingRevision())
                    ||slice.startOffset()!=cursors.getOrDefault(slice.documentId(),0)||!TextWindow.boundary(doc.text(),slice.startOffset())||!TextWindow.boundary(doc.text(),slice.endOffset())
                    ||slice.endOffset()<=slice.startOffset()||slice.endOffset()>doc.text().length())throw conflict();
            String text=doc.text().substring(slice.startOffset(),slice.endOffset());bytes=Math.addExact(bytes,TextWindow.count(text));
            if(!Learning.digest(text).equals(slice.textHash())||TextWindow.count(text)>source.policy().pageBytes())throw conflict();cursors.put(slice.documentId(),slice.endOffset());
        }
        if(bytes>source.policy().sourceBytes()||source.sources().size()>32)throw conflict();
        for(var e:cursors.entrySet())if(e.getValue()!=content.get(e.getKey()).text().length())throw conflict();
    }
    private static LabException conflict(){return new LabException("WORKFLOW_STATE_CONFLICT","资料工作流来源、计划或节点不一致");}
}
