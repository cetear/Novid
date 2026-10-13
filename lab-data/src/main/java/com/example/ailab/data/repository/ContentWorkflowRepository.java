package com.example.ailab.data.repository;

import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ContentWorkflow.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.data.persistence.mapper.ContentWorkflowMapper;
import com.example.ailab.data.persistence.po.SqlRow;
import com.example.ailab.data.persistence.po.SqlParameters;
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
    private static final int READING_NODE_QUERY_SIZE=1024;
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
    public Optional<ContentReading.Plan> readingPlan(TaskLease lease) {
        valid(lease);var plan=reading(lease.task().taskId());
        if(plan.isEmpty())throw conflict();
        plan.ifPresent(p->verifyReading(lease.actor(),lease.request().scope(),p,tasks.contentPolicy(lease).orElseThrow(ContentWorkflowRepository::conflict)));
        return plan;
    }
    private Optional<ContentReading.Plan> reading(long id) {
        var rows=mapper.reading(new Object[]{id});
        if(rows.isEmpty()||rows.get(0).get("reading_protocol")==null)return Optional.empty();
        var r=rows.get(0);
        if(!ContentReading.supported(r.string("reading_protocol"))||r.get("plan_json")==null)throw conflict();
        var plan=decode(r.string("plan_json"),r.string("plan_hash"),ContentReading.Plan.class);
        if(!plan.protocol().equals(r.string("reading_protocol")))throw conflict();return Optional.of(plan);
    }
    private void verifyReading(UserContext actor,ScopeRequest request,ContentReading.Plan p,Policy policy) {
        p.validate(policy.readingPackBytes());
        var scope=new AuthorizedKnowledgeScope(actor,request.mode(),request.knowledgeBaseIds(),request.ownerUserId(),Instant.now());
        documents.verifySources(scope,p.sources());
        for(var d:p.documents()) {
            var c=documents.read(scope,d.documentId());var current=c.document();
            if(current.documentVersion()!=d.documentVersion()||current.knowledgeBaseId()!=d.knowledgeBaseId()
                    ||!Objects.equals(current.activeProcessingRevision(),d.processingRevision())||c.text().length()!=d.textLength()
                    ||!Learning.digest(c.text()).equals(d.textHash()))throw new LabException("CONTEXT_VERSION_CONFLICT","创建时固定的正文版本或解析代次已变化");
        }
    }
    public Optional<ContentReading.Snapshot> readingPlan(UserContext actor,long id) {
        tasks.read(actor,id);var frozen=reading(id);if(frozen.isEmpty())return Optional.empty();
        var p=frozen.get();
        // 即使尚未运行也复核全部来源权限，避免泄露章节标题及主题卡。
        var scope=new AuthorizedKnowledgeScope(actor,actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,List.of(),null,Instant.now());
        documents.verifySources(scope,p.sources());
        var nodes=readingNodes(id,p).values().stream().filter(n->"COMPLETED".equals(n.string("status"))).collect(Collectors.toMap(n->n.string("node_id"),n->n));
        var completed=new HashSet<String>();var cards=new ArrayList<ContentReading.TopicCard>();
        for(var f:p.fragments()) {
            String fragment=f.source().id();var n=nodes.get("read_"+fragment);var t=nodes.get("topics_"+fragment);var e=nodes.get("evidence_"+fragment);
            if(n!=null&&t!=null&&e!=null) {
                output(n);output(e);
                try{cards.addAll(JSON.readValue(output(t),ContentReading.Cards.class).cards());}
                catch(Exception invalid){throw conflict();}
                completed.add(fragment);
            }
        }
        int packs=(int)p.packs().stream().filter(pack->completed.containsAll(pack.fragmentIds())&&nodes.containsKey("readpack_"+pack.packId())).count();
        if(completed.size()==p.fragments().size()&&packs==p.packs().size())verifyReadingProof(actor,null,p,nodes);
        return Optional.of(new ContentReading.Snapshot(p,hash(p),completed.size(),packs,completed.size()==p.fragments().size()&&packs==p.packs().size(),cards));
    }
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
        readingPlan(lease).ifPresent(p->{if(!p.fragments().stream().map(ContentReading.Fragment::source).toList().equals(value.slices())||!p.sources().equals(value.sources()))throw conflict();});
        mapper.bind(new Object[]{lease.task().taskId(),hash(value),encode(value)});
    }
    public Optional<String> completed(TaskLease lease,String id,String inputHash) {
        valid(lease); identity(id,inputHash);
        var rows=mapper.node(new Object[]{lease.task().taskId(),id});
        if(rows.isEmpty())return Optional.empty();
        var row=rows.get(0); if(!inputHash.equals(row.string("input_hash")))throw conflict();
        return row.string("status").equals("COMPLETED") ? Optional.of(output(row)) : Optional.empty();
    }
    public Map<String,ReadingCheckpoint> completedReadingNodes(TaskLease lease) {
        valid(lease);var p=reading(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);
        var result=new HashMap<String,ReadingCheckpoint>();
        for(var row:readingNodes(lease.task().taskId(),p).values())if("COMPLETED".equals(row.string("status")))
            result.put(row.string("node_id"),new ReadingCheckpoint(row.string("input_hash"),output(row)));
        return Map.copyOf(result);
    }
    public void completeReadingNodes(TaskLease lease,List<ReadingWrite> nodes) {
        valid(lease);if(nodes.isEmpty()||nodes.size()>96)throw conflict();
        var plan=reading(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);var allowed=new HashSet<String>();
        plan.fragments().forEach(f->{for(String prefix:List.of("read_","topics_","evidence_"))allowed.add(prefix+f.source().id());});
        int maximum=resultBytes(lease.task().taskId());var ids=new HashSet<String>();var pending=new ArrayList<Object[]>();
        for(var node:nodes) {
            identity(node.nodeId(),node.inputHash());if(!allowed.contains(node.nodeId())||!ids.add(node.nodeId()))throw conflict();
            String output=canonical(node.output());if(TextWindow.count(output)>maximum)throw conflict();
            pending.add(new Object[]{lease.task().taskId(),node.nodeId(),node.inputHash(),SqlSupport.hash(output),output});
        }
        var existing=mapper.nodesByIds(lease.task().taskId(),List.copyOf(ids)).stream().collect(Collectors.toMap(r->r.string("node_id"),r->r));
        var writes=new ArrayList<Object[]>();
        for(var row:pending) {
            var old=existing.get((String)row[1]);if(old==null)continue;
            if(!row[2].equals(old.string("input_hash"))||!"extract".equals(old.string("stage")))throw conflict();
            if("COMPLETED".equals(old.string("status"))&&!row[4].equals(output(old)))throw conflict();
            if(!Set.of("PENDING","COMPLETED").contains(old.string("status")))throw conflict();
        }
        for(var row:pending){var old=existing.get((String)row[1]);if(old==null||!"COMPLETED".equals(old.string("status")))writes.add(row);}
        if(writes.isEmpty())return;
        var parameters=writes.stream().map(row->new SqlParameters()
                .addValue("taskId",row[0]).addValue("nodeId",row[1]).addValue("inputHash",row[2])
                .addValue("outputHash",row[3]).addValue("outputJson",row[4])).toList();
        if(mapper.completeReadingNodes(parameters)<writes.size())throw conflict();mapper.progress(new Object[]{lease.task().taskId()});
    }
    public void begin(TaskLease lease,String id,String stage,String inputHash) {
        valid(lease);identity(id,inputHash);
        resultBytes(lease.task().taskId());
        if(!TaskProgress.stepIds(lease.request().taskType()).contains(stage))throw conflict();
        var rows=mapper.node(new Object[]{lease.task().taskId(),id});
        if(!rows.isEmpty()){if(!rows.get(0).string("input_hash").equals(inputHash)||!rows.get(0).string("stage").equals(stage))throw conflict();return;}
        mapper.begin(new Object[]{lease.task().taskId(),id,stage,inputHash});
        // extract保留既有阅读节点身份，用户可见阶段由执行器在全文核验后显式启动。
        if(!stage.equals("extract"))tasks.beginStep(lease,stage);
    }
    public void complete(TaskLease lease,String id,String inputHash,String text) {
        valid(lease);identity(id,inputHash);
        String value=canonical(text);
        if(TextWindow.count(value)>resultBytes(lease.task().taskId()))throw conflict();
        var rows=mapper.node(new Object[]{lease.task().taskId(),id});
        if(rows.isEmpty()||!rows.get(0).string("input_hash").equals(inputHash))throw conflict();
        if(rows.get(0).string("status").equals("COMPLETED")){if(!output(rows.get(0)).equals(value))throw conflict();return;}
        if(mapper.complete(new Object[]{SqlSupport.hash(value),value,lease.task().taskId(),id,inputHash})!=1)throw conflict();
        mapper.progress(new Object[]{lease.task().taskId()});
    }
    private int resultBytes(long taskId) {
        // 冻结来源在绑定和会话初始化时完整验证；逐节点只投影需要的固定容量。
        var rows=mapper.boundResultBytes(new Object[]{taskId});
        if(rows.size()!=1||rows.get(0).intValue("result_bytes")<=0)throw conflict();
        return rows.get(0).intValue("result_bytes");
    }
    public Optional<String> completedNode(TaskLease lease,String id) {
        valid(lease);var rows=mapper.node(new Object[]{lease.task().taskId(),id});
        return rows.isEmpty()||!"COMPLETED".equals(rows.get(0).string("status"))?Optional.empty():Optional.of(output(rows.get(0)));
    }
    public void accept(TaskLease lease,OutputPlan plan) {
        valid(lease);
        if(!"NOTES_PPT".equals(lease.request().taskType()))throw conflict();
        var row=run(lease.task().taskId());var source=source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);
        verify(documents,lease.actor(),lease.request().scope(),source);
        if(row.get("plan_hash")!=null){if(!hash(plan).equals(row.string("plan_hash")))throw conflict();return;}
        requireNodes(lease.task().taskId(),source.slices().stream().map(s->"read_"+s.id()).toList());
        verifyReadingProof(lease.task().taskId(),lease.actor(),lease.request().scope(),reading(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict));
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
            selected.addAll(unit.itemIds());
        }
        for(var omission:plan.omissions()) {
            if(omission.reason()==null||omission.reason().isBlank()||!facts.containsAll(omission.itemIds())||!Collections.disjoint(selected,omission.itemIds()))throw conflict();
            selected.addAll(omission.itemIds());
        }
        if(!selected.equals(facts))throw conflict();
        int sourcePages=Math.max(1,(source.sources().size()+imageCount+11)/12);
        var counts=new Counts(0,0,0,index,sourcePages,index+sourcePages);
        if(!counts.equals(plan.counts())||imageCount>8)throw conflict();
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
        if(stage.equals("prepare")||stage.equals("extract")) {
            Optional.of(reading(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict)).ifPresent(p->{
                p.validate(source.policy().readingPackBytes());var required=new ArrayList<String>();
                p.packs().forEach(pack->required.add("readpack_"+pack.packId()));
                p.fragments().forEach(f->{required.add("topics_"+f.source().id());required.add("evidence_"+f.source().id());});
                requireNodes(lease.task().taskId(),required);
                verifyReadingProof(lease.task().taskId(),lease.actor(),lease.request().scope(),p);
            });
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
        var plan=contentPlan(row,lease.request().taskType());
        requireNodes(lease.task().taskId(),plan.requiredNodes());
        if(plan instanceof LearningContent.Plan learning){
            requireNodes(lease.task().taskId(),List.of("global_accepted"));var packs=packages(lease).stream().collect(Collectors.toMap(p->p.workPackage().id(),p->p));
            requireNodes(lease.task().taskId(),learning.compilation()!=null?learning.compilation().directory().chapters().stream().map(c->"chapter_accepted_"+c.id()).toList():learning.quiz().chapters().stream().map(c->"chapter_accepted_"+c.id()).toList());
            if(learning.execution().packages().stream().anyMatch(p->!resolved(p.id(),packs,new HashSet<>())))throw conflict();
        }
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
        var plan=contentPlan(row,tasks.read(actor,id).taskType());
        var nodes=mapper.nodes(new Object[]{id}).stream().filter(n->n.string("status").equals("COMPLETED")).peek(ContentWorkflowRepository::output).map(n->n.string("node_id")).collect(Collectors.toSet());
        int chapters=plan instanceof LearningContent.Plan learning?learning.counts().chapters():0;
        return Optional.of(new Snapshot(plan,row.string("plan_hash"),(int)plan.requiredNodes().stream().filter(nodes::contains).count(),true,(int)nodes.stream().filter(n->n.startsWith("chapter_accepted_")).count(),chapters));
    }
    public String result(UserContext actor,long id) {
        var task=tasks.read(actor,id);if(!Learning.supports(task.taskType())||!task.status().equals("SUCCEEDED"))throw new LabException("WORKFLOW_RESULT_UNAVAILABLE","学习结果尚未发布");
        var plan=readPlan(actor,id).orElseThrow(ContentWorkflowRepository::conflict);requireNodes(id,plan.plan().requiredNodes());
        var rows=mapper.node(new Object[]{id,"result"});if(rows.isEmpty())throw conflict();return output(rows.get(0));
    }
    public List<SourceDependency> publishedSources(UserContext actor,long id) {
        var task=tasks.read(actor,id);
        if(!Learning.supports(task.taskType())||!"SUCCEEDED".equals(task.status()))
            throw new LabException("WORKFLOW_RESULT_UNAVAILABLE","学习结果尚未发布");
        var source=source(id).orElseThrow(ContentWorkflowRepository::conflict);
        documents.verifySources(new AuthorizedKnowledgeScope(actor,actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,
                List.of(),null,Instant.now()),source.sources());
        return source.sources();
    }
    private SqlRow run(long id) {var rows=mapper.run(new Object[]{id});if(rows.isEmpty())throw conflict();return rows.get(0);}
    private static ContentPlan contentPlan(SqlRow row,String type) {
        return Learning.supports(type)?decode(row.string("plan_json"),row.string("plan_hash"),LearningContent.Plan.class)
                :decode(row.string("plan_json"),row.string("plan_hash"),OutputPlan.class);
    }
    public Optional<LearningContent.Plan> acceptedLearningPlan(TaskLease lease) {
        valid(lease);var rows=mapper.run(new Object[]{lease.task().taskId()});
        if(rows.isEmpty()||rows.get(0).get("plan_hash")==null)return Optional.empty();
        return Optional.of((LearningContent.Plan)contentPlan(rows.get(0),lease.request().taskType()));
    }
    public void accept(TaskLease lease,LearningContent.Plan plan) {
        valid(lease);var source=source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict);var row=run(lease.task().taskId());
        if(row.get("plan_hash")!=null){if(!hash(plan).equals(row.string("plan_hash")))throw conflict();return;}
        verify(documents,lease.actor(),lease.request().scope(),source);var p=readingPlan(lease).orElseThrow(ContentWorkflowRepository::conflict);
        verifyReadingProof(lease.task().taskId(),lease.actor(),lease.request().scope(),p);
        var facts=new HashSet<String>();var nodes=mapper.nodes(new Object[]{lease.task().taskId()}).stream().collect(Collectors.toMap(n->n.string("node_id"),n->n));
        for(var slice:source.slices())readingNode(nodes,"read_"+slice.id(),Facts.class).items().forEach(i->facts.add(i.id()));
        LearningPlanRules.validate(plan,lease.request(),facts,source.policy());
        if(plan.quiz()!=null){var fragments=p.fragments().stream().collect(Collectors.toMap(f->f.source().id(),f->f));var evidence=new HashMap<String,ContentReading.Evidence>();
            for(var f:p.fragments())readingNode(nodes,"evidence_"+f.source().id(),ContentReading.Evidences.class).evidence().forEach(e->evidence.put(e.itemId(),e));
            for(var chapter:plan.quiz().chapters()) {
                if(chapter.ranges().isEmpty()&&!chapter.itemIds().isEmpty())throw conflict();
                for(var range:chapter.ranges()){var f=fragments.get(range.fragmentId());if(f==null||!range.sectionId().equals(f.sectionId())||range.startOffset()<f.source().startOffset()||range.endOffset()>f.source().endOffset()||range.startOffset()>=range.endOffset())throw conflict();}
                for(String id:chapter.itemIds()){var e=evidence.get(id);if(e==null||chapter.ranges().stream().noneMatch(range->range.fragmentId().equals(e.fragmentId())&&range.startOffset()<=e.quoteStartOffset()&&e.quoteEndOffset()<=range.endOffset()))throw conflict();}
            }
        }
        if(!plan.sourceHash().equals(hash(source))||plan.estimatedTurns()>tasks.remainingModelTurns(lease)||plan.estimatedAttempts()>tasks.remainingModelAttempts(lease))
            throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","业务计划与执行包超过剩余父任务预算");
        if(mapper.accept(new Object[]{hash(plan),encode(plan),lease.task().taskId()})!=1)throw conflict();
        for(var pack:plan.execution().packages())savePackage(lease,pack,plan.execution().parts().stream().filter(part->pack.partIds().contains(part.id())).toList());
        mapper.progress(new Object[]{lease.task().taskId()});
    }
    private void savePackage(TaskLease lease,LearningContent.WorkPackage pack,List<LearningContent.Part> parts) {
        mapper.savePackage(new Object[]{lease.task().taskId(),pack.id(),pack.parentId(),hash(Map.of("package",pack,"parts",parts)),encode(pack),encode(parts)});
    }
    public List<LearningContent.PackageStatus> packages(TaskLease lease) {valid(lease);return packageRows(lease.task().taskId());}
    public List<LearningContent.PackageStatus> packages(UserContext actor,long id,int offset,int limit) {
        tasks.read(actor,id);if(offset<0||limit<1||limit>100)throw LabException.invalid("工作包分页范围无效");
        var source=source(id);if(source.isEmpty())return List.of();verify(documents,actor,null,source.get());
        return packageRows(id,mapper.packagePage(new Object[]{id,offset,limit}),false);
    }
    private List<LearningContent.PackageStatus> packageRows(long id) {
        return packageRows(id,mapper.packages(new Object[]{id}),true);
    }
    private record PackageRow(SqlRow row,LearningContent.WorkPackage pack,List<LearningContent.Part> parts) { }
    private List<LearningContent.PackageStatus> packageRows(long id,List<SqlRow> rows,boolean all) {
        if(rows.isEmpty())return List.of();
        var decoded=rows.stream().map(r->{try {
            var pack=JSON.readValue(r.string("package_json"),LearningContent.WorkPackage.class);
            List<LearningContent.Part> parts=JSON.readValue(r.string("parts_json"),JSON.getTypeFactory().constructCollectionType(List.class,LearningContent.Part.class));
            if(!hash(Map.of("package",pack,"parts",parts)).equals(r.string("input_hash")))throw conflict();
            return new PackageRow(r,pack,parts);
        }catch(Exception invalid){throw conflict();}}).toList();
        var nodeIds=decoded.stream().flatMap(p->p.pack().partIds().stream()).distinct().map(part->"partaccepted_"+part).toList();
        var accepted=nodeIds.isEmpty()?Set.<String>of():mapper.completedNodeIds(id,nodeIds).stream().map(n->n.string("node_id")).collect(Collectors.toSet());
        var links=all?rows:mapper.packageChildren(id,decoded.stream().map(p->p.pack().id()).toList());
        var children=links.stream().collect(Collectors.groupingBy(r->r.string("parent_id"),Collectors.mapping(r->r.string("package_id"),Collectors.toList())));
        return decoded.stream().map(p->{var r=p.row();var pack=p.pack();return new LearningContent.PackageStatus(pack,p.parts(),r.string("status"),r.intValue("attempts"),r.string("result_hash"),r.string("error_code"),
                children.getOrDefault(pack.id(),List.of()),(int)pack.partIds().stream().filter(part->accepted.contains("partaccepted_"+part)).count());}).toList();
    }
    private LearningContent.PackageStatus packageById(TaskLease lease,String id) {
        return packageRows(lease.task().taskId(),mapper.packageRow(new Object[]{lease.task().taskId(),id}),false).stream().findFirst().orElseThrow(ContentWorkflowRepository::conflict);
    }
    private boolean resolved(String id,Map<String,LearningContent.PackageStatus> rows,Set<String> visiting) {
        if(!visiting.add(id))throw conflict();var p=rows.get(id);if(p==null)throw conflict();
        boolean ready=p.status().equals("ACCEPTED")||p.status().equals("REPLACED")&&!p.children().isEmpty()&&p.children().stream().allMatch(child->resolved(child,rows,new HashSet<>(visiting)));return ready;
    }
    public void beginPackage(TaskLease lease,String id) {
        valid(lease);var p=packageById(lease,id);
        if(p.status().equals("ACCEPTED"))return;if(p.status().equals("REPLACED"))throw conflict();
        if(!p.workPackage().dependencies().isEmpty()) {
            var rows=packageRows(lease.task().taskId()).stream().collect(Collectors.toMap(v->v.workPackage().id(),v->v));
            if(!p.workPackage().dependencies().stream().allMatch(dep->resolved(dep,rows,new HashSet<>())))throw conflict();
        }
        if(mapper.beginPackage(new Object[]{lease.task().taskId(),id})!=1)throw conflict();
    }
    public void completePackage(TaskLease lease,String id,String resultHash) {
        valid(lease);identity(id,resultHash);var p=packageById(lease,id);
        if(p.status().equals("ACCEPTED")){if(!resultHash.equals(p.resultHash()))throw conflict();return;}
        var nodeIds=p.workPackage().partIds().stream().map(part->"partaccepted_"+part).toList();
        var rows=mapper.nodesByIds(lease.task().taskId(),nodeIds).stream().collect(Collectors.toMap(n->n.string("node_id"),n->n));
        var results=new HashMap<String,String>();
        for(var part:p.parts())try{
            String result=output(rows.get("partaccepted_"+part.id()));results.put(part.id(),result);var receipt=JSON.readTree(result);
            if(!part.id().equals(receipt.path("partId").asText())||receipt.path("producerId").asText().isBlank())throw conflict();
            var value=JSON.readTree(receipt.path("json").asText());var items=JSON.convertValue(value.path("itemIds"),JSON.getTypeFactory().constructCollectionType(List.class,String.class));
            if(!part.itemIds().equals(items)||!(part.kind().equals("QUESTION")?part.logicalId().equals(value.path("id").asText()):part.id().equals(value.path("partId").asText())))throw conflict();
        }catch(Exception invalid){throw conflict();}
        var expected=hash(p.workPackage().partIds().stream().map(results::get).toList());if(!expected.equals(resultHash))throw conflict();
        if(mapper.completePackage(new Object[]{lease.task().taskId(),id,resultHash})!=1)throw conflict();mapper.progress(new Object[]{lease.task().taskId()});
    }
    public void failPackage(TaskLease lease,String id,String code){
        valid(lease);if(code==null||!code.matches("[A-Z0-9_]{1,64}"))throw conflict();var pack=packageById(lease,id);
        if(pack.status().equals("FAILED")&&Objects.equals(code,pack.errorCode()))return;
        if(!pack.status().equals("RUNNING")||mapper.failPackage(new Object[]{lease.task().taskId(),id,code})!=1)throw conflict();
        mapper.progress(new Object[]{lease.task().taskId()});
    }
    public void splitPackage(TaskLease lease,String id,List<LearningContent.Part> parts,List<LearningContent.WorkPackage> children) {
        valid(lease);var parent=packageById(lease,id);var plan=acceptedLearningPlan(lease).orElseThrow(ContentWorkflowRepository::conflict);
        if(parent.status().equals("ACCEPTED")||children.size()<2||parent.workPackage().depth()>=plan.execution().maximumSplitDepth())throw conflict();
        LearningPlanRules.split(parent.parts(),parts,parent.workPackage(),children);
        var policy=source(lease.task().taskId()).orElseThrow(ContentWorkflowRepository::conflict).policy();
        if(children.stream().anyMatch(child->child.inputBytes()>policy.unitInputBytes()||child.outputBytes()>policy.packageOutputBytes()))throw conflict();
        if(parent.status().equals("REPLACED")){
            if(!new HashSet<>(parent.children()).equals(children.stream().map(LearningContent.WorkPackage::id).collect(Collectors.toSet())))throw conflict();
            for(var child:children){var found=packageById(lease,child.id());if(!found.workPackage().equals(child)||!found.parts().equals(parts.stream().filter(p->child.partIds().contains(p.id())).toList()))throw conflict();}return;
        }
        if(mapper.packageCount(new Object[]{lease.task().taskId()}).get(0).intValue("package_count")+children.size()>plan.execution().maximumPackages())throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","追加执行包超过父任务限制");
        for(var child:children)savePackage(lease,child,parts.stream().filter(p->child.partIds().contains(p.id())).toList());
        if(mapper.replacePackage(new Object[]{lease.task().taskId(),id})!=1)throw conflict();mapper.progress(new Object[]{lease.task().taskId()});
    }
    private void requireNodes(long taskId,List<String> ids) {
        if(ids.isEmpty())return;
        var rows=mapper.nodesByIds(taskId,ids);var nodes=new HashMap<String,SqlRow>();rows.forEach(n->nodes.put(n.string("node_id"),n));
        for(String id:ids){var row=nodes.get(id);if(row==null)throw conflict();output(row);}
    }
    private void valid(TaskLease lease) {tasks.valid(lease);if(!lease.request().documentDriven())throw conflict();}
    private Map<String,SqlRow> readingNodes(long taskId,ContentReading.Plan p) {
        var ids=new ArrayList<String>();
        p.fragments().forEach(f->{String id=f.source().id();ids.add("read_"+id);ids.add("topics_"+id);ids.add("evidence_"+id);});
        p.packs().forEach(pack->ids.add("readpack_"+pack.packId()));
        var nodes=new HashMap<String,SqlRow>();
        // 大资料可能有数千个片段；复用已有定向查询，并限制每条SQL的参数数量。
        for(int start=0;start<ids.size();start+=READING_NODE_QUERY_SIZE) {
            mapper.nodesByIds(taskId,ids.subList(start,Math.min(ids.size(),start+READING_NODE_QUERY_SIZE))).forEach(n->nodes.put(n.string("node_id"),n));
        }
        return nodes;
    }
    private void verifyReadingProof(long taskId,UserContext actor,ScopeRequest request,ContentReading.Plan p) {
        verifyReadingProof(actor,request,p,readingNodes(taskId,p));
    }
    private void verifyReadingProof(UserContext actor,ScopeRequest request,ContentReading.Plan p,Map<String,SqlRow> nodes) {
        var scope=request==null?new AuthorizedKnowledgeScope(actor,actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,List.of(),null,Instant.now())
                :new AuthorizedKnowledgeScope(actor,request.mode(),request.knowledgeBaseIds(),request.ownerUserId(),Instant.now());
        var texts=new HashMap<Long,String>();var facts=new HashMap<String,Facts>();var allFacts=new HashSet<String>();
        for(var d:p.documents()) {
            var c=documents.read(scope,d.documentId());
            if(c.document().documentVersion()!=d.documentVersion()||!Objects.equals(c.document().activeProcessingRevision(),d.processingRevision())
                    ||!Learning.digest(c.text()).equals(d.textHash()))throw conflict();texts.put(d.documentId(),c.text());
        }
        for(var f:p.fragments()) {
            var s=f.source();String id=s.id(),text=texts.get(s.documentId());
            Facts value=readingNode(nodes,"read_"+id,Facts.class);facts.put(id,value);
            if(!"COMPLETE".equals(value.status()))throw conflict();
            var owned=new HashMap<String,Learning.Item>();
            for(var item:value.items())if(!allFacts.add(item.id())||!item.sourceId().equals(id)||owned.put(item.id(),item)!=null
                    ||item.quote().isBlank()||!text.substring(s.startOffset(),s.endOffset()).contains(item.quote()))throw conflict();
            var covered=new HashSet<String>();
            for(var card:readingNode(nodes,"topics_"+id,ContentReading.Cards.class).cards()) {
                if(!card.fragmentId().equals(id)||!card.sectionId().equals(f.sectionId())||!card.headingPath().equals(f.headingPath())
                        ||!card.id().equals(card.topic().id())||card.factCount()!=card.topic().itemIds().size()
                        ||card.sourceBytes()!=TextWindow.count(text.substring(s.startOffset(),s.endOffset()))||card.topic().itemIds().isEmpty()
                        ||!owned.keySet().containsAll(card.topic().itemIds())||!owned.keySet().containsAll(card.topic().dependencyItemIds()))throw conflict();
                for(String fact:card.topic().itemIds())if(!covered.add(fact))throw conflict();
            }
            if(!covered.equals(owned.keySet()))throw conflict();var evidenced=new HashSet<String>();
            for(var e:readingNode(nodes,"evidence_"+id,ContentReading.Evidences.class).evidence()) {
                var item=owned.get(e.itemId());
                if(item==null||!evidenced.add(e.itemId())||!id.equals(e.fragmentId())||e.quoteStartOffset()<s.startOffset()
                        ||e.quoteEndOffset()>s.endOffset()||e.quoteStartOffset()>=e.quoteEndOffset()
                        ||!text.substring(e.quoteStartOffset(),e.quoteEndOffset()).equals(item.quote())||!Learning.digest(item.quote()).equals(e.quoteHash()))throw conflict();
            }
            if(!evidenced.equals(owned.keySet()))throw conflict();
        }
        for(var pack:p.packs()) {
            var batch=readingNode(nodes,"readpack_"+pack.packId(),ContentReading.Batch.class);
            if(!"COMPLETE".equals(batch.status())||!batch.fragments().stream().map(ContentReading.Observation::fragmentId).toList().equals(pack.fragmentIds()))throw conflict();
            for(var observation:batch.fragments())if(!observation.items().equals(facts.get(observation.fragmentId()).items())
                    ||!observation.topics().equals(readingNode(nodes,"topics_"+observation.fragmentId(),ContentReading.Cards.class).cards().stream().map(ContentReading.TopicCard::topic).toList()))throw conflict();
        }
    }
    private static <T>T readingNode(Map<String,SqlRow> nodes,String id,Class<T> type) {
        var row=nodes.get(id);if(row==null)throw conflict();try{return JSON.readValue(output(row),type);}catch(Exception invalid){throw conflict();}
    }
    private static void identity(String id,String hash) {if(id==null||!id.matches("[a-z][a-z0-9_]{0,63}")||hash==null||!hash.matches("[a-f0-9]{64}"))throw conflict();}
    private static String output(SqlRow row){if(!row.string("status").equals("COMPLETED"))throw conflict();String v=canonical(row.string("output_json"));if(!SqlSupport.hash(v).equals(row.string("output_hash")))throw conflict();return v;}
    private static String canonical(String text){try{return JSON.writeValueAsString(JSON.readValue(text,Object.class));}catch(Exception e){throw conflict();}}
    private static String encode(Object value){try{return canonical(JSON.writeValueAsString(value));}catch(Exception e){throw conflict();}}
    static String readingJson(ContentReading.Plan value){return encode(value);}
    private static String hash(Object value){return SqlSupport.hash(encode(value));}
    private static <T>T decode(String text,String hash,Class<T> type){try{if(!SqlSupport.hash(canonical(text)).equals(hash))throw conflict();return JSON.readValue(text,type);}catch(Exception e){throw conflict();}}
    public static void verifyArtifactBaseline(SqlRow row,UserContext actor,DocumentSqlRepository documents) {
        if(row.get("content_baseline")==null)return;
        verify(documents,actor,null,decode(row.string("content_baseline"),row.string("content_baseline_hash"),SourcePlan.class));
    }
    public static void verifyPublication(SqlRow row,List<SqlRow> rows,TaskLease lease,TaskCheckpoint result,DocumentSqlRepository documents) {
        var source=decode(row.string("source_json"),row.string("source_hash"),SourcePlan.class);
        verify(documents,lease.actor(),lease.request().scope(),source);
        var plan=contentPlan(row,lease.request().taskType());
        if(result.partial()||!result.sourceDependencies().equals(source.sources())||!plan.sourceHash().equals(row.string("source_hash")))throw conflict();
        var nodes=rows.stream().collect(Collectors.toMap(n->n.string("node_id"),n->n));
        var required=new ArrayList<>(plan.requiredNodes());if(plan instanceof LearningContent.Plan learning){required.add("global_accepted");required.addAll(learning.compilation()!=null?learning.compilation().directory().chapters().stream().map(c->"chapter_accepted_"+c.id()).toList():learning.quiz().chapters().stream().map(c->"chapter_accepted_"+c.id()).toList());}source.slices().forEach(s->required.add("read_"+s.id()));required.add("result");
        for(String id:required){var node=nodes.get(id);if(node==null)throw conflict();output(node);}
        try {if(plan instanceof LearningContent.Plan&&!"ACCEPT".equals(JSON.readTree(output(nodes.get("global_accepted"))).path("decision").asText()))throw conflict();for(String id:required)if(id.startsWith("chapter_accepted_")&&!"ACCEPT".equals(JSON.readTree(output(nodes.get(id))).path("decision").asText()))throw conflict();var saved=JSON.readTree(output(nodes.get("result")));if(!SqlSupport.hash(canonical(saved.get("contentPlan").toString())).equals(row.string("plan_hash")))throw conflict();}
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
