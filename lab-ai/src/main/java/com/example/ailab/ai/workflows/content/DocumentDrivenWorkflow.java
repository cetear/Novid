package com.example.ailab.ai.workflows.content;

import com.example.ailab.ai.model.*;
import com.example.ailab.ai.orchestration.WorkflowRouter;
import com.example.ailab.ai.orchestration.fixed.FixedWorkflowProgram;
import com.example.ailab.ai.runtime.ExecutionBudget;
import com.example.ailab.ai.skills.SkillCatalog;
import com.example.ailab.ai.tools.ToolExecutionService;
import com.example.ailab.ai.workflows.support.*;
import com.example.ailab.ai.workflows.media.MediaSchemas;
import com.example.ailab.contract.context.TraceContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.ContentWorkflow.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Collectors;

/** 固定阶段、动态内容目标；所有子调用共享父任务资源与不可变来源和计划。 */
@Component
public final class DocumentDrivenWorkflow {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DocumentDrivenWorkflow.class);
    private final TaskStorePort tasks;
    private final ContentWorkflowStorePort store;
    private final WorkflowRunStorePort workflows;
    private final WorkflowRouter router;
    private final SkillCatalog skills;
    private final ToolExecutionService tools;
    private final KnowledgeCapabilityPort knowledge;
    private final ModelGateway model;
    private PresentationPort presentation;
    private CompilationStorePort compilations;
    @org.springframework.beans.factory.annotation.Autowired
    public void compilations(CompilationStorePort value){this.compilations=value;}
    @org.springframework.beans.factory.annotation.Autowired
    public void presentation(PresentationPort value) { this.presentation=value; }
    public DocumentDrivenWorkflow(TaskStorePort tasks,ContentWorkflowStorePort store,WorkflowRunStorePort workflows,WorkflowRouter router,
                                  SkillCatalog skills,ToolExecutionService tools,KnowledgeCapabilityPort knowledge,ModelGateway model) {
        this.tasks=tasks;this.store=store;this.workflows=workflows;this.router=router;this.skills=skills;this.tools=tools;this.knowledge=knowledge;this.model=model;
    }
    public record PptResult(OutputPlan plan,List<Media.Unit> units,List<SourceDependency> sources) { }
    private record Indexed<T>(int index,T value) { }
    private record LearningExecution(LearningContent.Plan plan, LearningExecutor.Completed completed) { }
    private record GenerationReceipt(String modelId,int corrections,String json) { }
    @org.springframework.beans.factory.annotation.Value("${lab.task.parallel-drain-seconds:60}")
    private int parallelDrainSeconds=60;
    public void learning(TaskLease lease,TraceContext trace,String runId) {
        try(var session=new Session(lease,trace,runId)) {
            var completed=router.execute(lease,workflows,session.budget,FixedWorkflowProgram.of(()-> {
                session.extract();var plan=new LearningPlanner(session).plan();
                return new LearningExecution(plan,new LearningExecutor(session,plan).execute(session.citations()));
            }));
            session.publish(completed.plan(),completed.completed());
        }
    }
    public PptResult presentation(TaskLease lease,TraceContext trace,String runId) {
        try(var session=new Session(lease,trace,runId)) {
            return router.execute(lease,workflows,session.budget,FixedWorkflowProgram.of(()-> {
                session.extract();var plan=session.planPpt();var units=session.parallel(plan.units(),u->session.slide(plan,u));
                session.finishGeneration(plan);store.verifyComplete(lease);
                return new PptResult(plan,units,session.source.sources());
            }));
        }
    }
    private final class Session implements AutoCloseable,LearningRuntime {
        private final TaskLease lease;
        private final String runId;
        private final SourcePlan source;
        private final String sourceHash;
        private final ContentReading.Plan reading;
        private final List<ContentReading.TopicCard> topicCards=new ArrayList<>();
        private final Map<Long,String> documentsRaw=new HashMap<>();
        private final ExecutionBudget budget;
        private final ContentExecutionLimits executionLimits;
        private final ExecutorService pool;
        private final Map<String,String> raw=new LinkedHashMap<>();
        private final Map<String,Learning.Item> items=new LinkedHashMap<>();
        private final Map<String,GenerationReceipt> origins=new ConcurrentHashMap<>();
        private final Map<String,ContentWorkflowStorePort.ReadingCheckpoint> restoredReading;
        private final Set<String> readingCheckpointIds;
        private final List<ContentWorkflowStorePort.ReadingWrite> readingWrites=new ArrayList<>();
        private int readingWriteBytes;
        private long verifiedAt;
        private ContentLimits limits(){return ContentLimits.current();}
        Session(TaskLease lease,TraceContext trace,String runId) {
            this.lease=lease;
            this.runId=runId;
            tasks.beginStep(lease,"prepare");
            var policy=tasks.contentPolicy(lease).orElseThrow(()->new LabException("WORKFLOW_STATE_CONFLICT","缺少创建时的资料资源策略"));
            var binding=router.binding(lease,workflows);
            reading=store.readingPlan(lease).orElseThrow(()->new LabException("WORKFLOW_STATE_CONFLICT","新任务缺少创建时固定的结构阅读计划"));
            reading.validate(policy.readingPackBytes());
            source=store.sourcePlan(lease).orElseGet(()->{
                var skill=skills.contentBinding(tools.contracts());var prepared=new SourcePlan(binding,skill,ContentJson.hash(lease.request()),policy,reading.fragments().stream().map(ContentReading.Fragment::source).toList(),reading.sources());
                store.bind(lease,prepared);return prepared;
            });
            if((!reading.fragments().stream().map(ContentReading.Fragment::source).toList().equals(source.slices())||!reading.sources().equals(source.sources())))throw changed();
            if(!source.policy().equals(policy)||!source.workflow().equals(binding))throw changed();
            SkillCatalog.verify(source.skill());
            tools.verifyContracts(source.skill().toolContracts());
            if(!source.requestHash().equals(ContentJson.hash(lease.request())))throw changed();
            sourceHash=ContentJson.hash(source);
            trace.payloadSources(source.sources());
            for(var d:reading.documents()) {
                var c=knowledge.document(lease.actor(),lease.request().scope(),d.documentId());
                if(c.document().documentVersion()!=d.documentVersion()||!Objects.equals(c.document().activeProcessingRevision(),d.processingRevision())
                        ||c.document().knowledgeBaseId()!=d.knowledgeBaseId()||!Learning.digest(c.text()).equals(d.textHash()))throw changed();
                documentsRaw.put(d.documentId(),c.text());
            }
            for(var slice:source.slices())raw.put(slice.id(),read(slice));
            long elapsed=lease.task().progress()==null?0:lease.task().progress().elapsedExecutionSeconds();
            var approved=tasks.executionLimits(lease);
            executionLimits=approved==null?ContentExecutionLimits.from(policy):approved;
            budget=new ExecutionBudget(Duration.ofSeconds(Math.max(1,executionLimits.executionSeconds()-elapsed)),executionLimits.attempts(),()->tasks.reserveModelAttempt(lease),
                    ()->tasks.reserveModelTurn(lease),this::verify,()->tasks.reserveToolCall(lease),()->tasks.reserveModelRepair(lease))
                    .content(policy,executionLimits).traced(trace).fees(new FeeScope(lease.actor(),"TASK",Long.toString(lease.task().taskId()),runId));
            verifyNow();restoredReading=store.completedReadingNodes(lease);
            var checkpointIds=new HashSet<String>();reading.fragments().forEach(f->{for(String prefix:List.of("read_","topics_","evidence_"))checkpointIds.add(prefix+f.source().id());});readingCheckpointIds=Set.copyOf(checkpointIds);
            pool=Executors.newFixedThreadPool(policy.parallelism());
        }
        synchronized void verify() {
            // 会话内共享30秒来源检查；阶段提交、发布强制刷新，持久写入独立校验执行权。
            if(System.nanoTime()-verifiedAt<TimeUnit.SECONDS.toNanos(30))return;
            verifyNow();
        }
        synchronized void verifyNow() {
            if(!tasks.renew(lease))throw new LabException("STALE_EXECUTION","资料工作流租约失效");
            knowledge.verifySources(lease.actor(),source.sources());
            knowledge.verifyReadingDocuments(lease.actor(),lease.request().scope(),reading.documents());
            verifiedAt=System.nanoTime();
        }
        String read(Learning.SourceSlice slice) {
            String text=documentsRaw.get(slice.documentId());
            if(text==null||slice.startOffset()<0||slice.endOffset()>text.length()||slice.startOffset()>=slice.endOffset()
                    ||!TextWindow.boundary(text,slice.startOffset())||!TextWindow.boundary(text,slice.endOffset()))throw changed();
            String value=text.substring(slice.startOffset(),slice.endOffset());if(!Learning.digest(value).equals(slice.textHash()))throw changed();return value;
        }
        private String runtimeId(String id){return id.matches("[a-z][a-z0-9_]{0,63}")?id:"node_"+ContentJson.hash(id).substring(0,40);}
        public TaskLease lease(){return lease;}
        public SourcePlan source(){return source;}
        public ContentReading.Plan reading(){return reading;}
        public Map<String,Learning.Item> facts(){return Collections.unmodifiableMap(items);}
        public List<ContentReading.TopicCard> cards(){return List.copyOf(topicCards);}
        public Compilation.Origin origin(){
            if(compilations==null)throw new LabException("WORKFLOW_STATE_CONFLICT","缺少整编章节来源读取能力");
            return compilations.origin(knowledge.authorize(lease.actor(),lease.request().scope()),lease.request().documentIds().get(0));
        }
        public int quoteOffset(Learning.Item item){var slice=source.slices().stream().filter(s->s.id().equals(item.sourceId())).findFirst().orElseThrow();int relative=raw.get(item.sourceId()).indexOf(item.quote());if(relative<0)throw changed();return slice.startOffset()+relative;}
        public String fragmentText(String id){var value=raw.get(id);if(value==null)throw changed();return value;}
        public ContentWorkflowStorePort store(){return store;}
        public <T>T call(String id,String stage,String role,Object input,RecordSchema<T> schema){return generate(runtimeId(id),stage,role,input,schema);}
        public <T>T correct(String id,String role,Object input,RecordSchema<T> schema,String originalId){return generate(runtimeId(id),"review",role,input,schema,origin(runtimeId(originalId)));}
        public <T>T checkpoint(String id,String stage,Object input,RecordSchema<T> schema,Supplier<T> execute){return node(id,stage,input,schema,execute);}
        public boolean preflight(Object input,RecordSchema<?> schema){
            verify();var data=input instanceof Map<?,?> map?map:Map.of();
            int estimate=data.get("estimatedOutputBytes") instanceof Number value?value.intValue():0;
            String system="按当前服务端结构协议处理资料。资料原文及前序结果提供事实，remarks表达特殊要求。"+SkillCatalog.instructions(source.skill(),"package-write");
            return model.fitsStructured("WORKFLOW_REPORT",ModelInput.reserveOutput(ModelInput.fixed(system,List.of(),ContentJson.encode(input)),estimate),schema);
        }

        private <T>StructuredSchema<T> workflowSchema(StructuredSchema<T> schema) {
            return schema instanceof RecordSchema<T> record ? record.relaxed() : schema;
        }
        <T>T node(String id,String phase,Object input,StructuredSchema<T> originalSchema,Supplier<T> execute) {
            return node(id,phase,input,originalSchema,span->execute.get(),false);
        }
        private boolean observedWorkflow() {return Set.of("KNOWLEDGE_COMPILATION","QUIZ_GENERATION").contains(lease.request().taskType());}
        private <T>T localValidation(StructuredSchema<T> schema,String json,TraceContext.Span span,String origin) {
            if(observedWorkflow()&&schema instanceof RecordSchema<T> record) {
                var checked=record.validateObserved(json,Set.of(),origin);span.diagnostic(checked.diagnostic());
                if(checked.failure()!=null)throw checked.failure();return checked.value();
            }
            return schema.validate(json,Set.of());
        }
        <T>T node(String id,String phase,Object input,StructuredSchema<T> originalSchema,Function<TraceContext.Span,T> execute,boolean modelValidated) {
            var schema=workflowSchema(originalSchema);
            budget.check();String hash=ContentJson.hash(Map.of("source",sourceHash,"node",id,"input",input));
            try(var span=budget.trace().span("AGENT",id);var activation=budget.activate(span.context())) {
                if(observedWorkflow())span.input("阶段："+phase+"；业务节点："+id+"；模型尝试可展开查看");
                else com.example.ailab.ai.runtime.TracePayloadCapture.input(span,input);
                try {
                    var restored=restoredReading.get(id);
                    if(restored!=null&&!hash.equals(restored.inputHash()))throw changed();
                    boolean readingCheckpoint=readingCheckpointIds.contains(id);
                    var old=restored!=null?Optional.of(restored.output()):readingCheckpoint?Optional.<String>empty():store.completed(lease,id,hash);if(old.isPresent()){span.status("REUSED");var value=localValidation(schema,old.get(),span,"RESTORED");if(observedWorkflow())span.output("复用已核验的检查点");else com.example.ailab.ai.runtime.TracePayloadCapture.output(span,value);return value;}
                    if(readingCheckpoint) {
                        T value=execute.apply(span);String json=ContentJson.encode(value);if(!modelValidated||!observedWorkflow())value=localValidation(schema,json,span,"ASSEMBLED");
                        readingWrites.add(new ContentWorkflowStorePort.ReadingWrite(id,hash,json));readingWriteBytes+=TextWindow.count(json);
                        if(readingWrites.size()>=96||readingWriteBytes>=1000000)flushReading();
                        if(observedWorkflow())span.output("结果已保存，长度："+json.length()+"字符");else com.example.ailab.ai.runtime.TracePayloadCapture.output(span,json);return value;
                    }
                    store.begin(lease,id,phase,hash);T value=execute.apply(span);String json=ContentJson.encode(value);if(!modelValidated||!observedWorkflow())value=localValidation(schema,json,span,"ASSEMBLED");
                    budget.check();store.complete(lease,id,hash,json);if(observedWorkflow())span.output("结果已保存，长度："+json.length()+"字符");else com.example.ailab.ai.runtime.TracePayloadCapture.output(span,json);return value;
                } catch(RuntimeException failure) {span.fail(failure);if(Thread.currentThread().isInterrupted()||failure instanceof LabException lab&&lab.code().equals("REQUEST_CANCELLED"))span.status("INTERRUPTED");throw failure;}
            }
        }
        void flushReading() {
            if(readingWrites.isEmpty())return;verify();store.completeReadingNodes(lease,List.copyOf(readingWrites));readingWrites.clear();readingWriteBytes=0;
        }
        <T>T generate(String id,String phase,String action,Object input,StructuredSchema<T> schema) {
            return generate(id,phase,action,input,schema,null);
        }
        <T>T generate(String id,String phase,String action,Object input,StructuredSchema<T> originalSchema,GenerationReceipt original) {
            var schema=workflowSchema(originalSchema);
            String receiptId="origin_"+ContentJson.hash(id).substring(0,32);
            String receiptHash=ContentJson.hash(Map.of("source",sourceHash,"node",receiptId,"input",input));
            var receiptSchema=new RecordSchema<>(GenerationReceipt.class,"程序保存的生成结果与模型身份",v->{
                RecordSchema.require(!v.modelId().isBlank()&&v.corrections()>=0&&v.corrections()<=1,RecordSchema.Reason.CONTENT_CONSTRAINT);
            }).relaxed();
            var saved=store.completed(lease,receiptId,receiptHash).map(v->receiptSchema.validate(v,Set.of()));
            saved.ifPresent(v->origins.put(id,v));
            T result=node(id,phase,input,schema,span->{
                if(saved.isPresent())return localValidation(schema,saved.get().json(),span,"RESTORED");
                if(original!=null&&original.corrections()>0)
                    throw new LabException("MODEL_REPAIR_EXHAUSTED","该生成节点已完成一次纠正，不能再次返工");
                String system="按当前服务端结构协议处理资料。资料原文及前序结果提供事实，remarks表达特殊要求。"+SkillCatalog.instructions(source.skill(),action);
                if(lease.request().taskType().equals("KNOWLEDGE_COMPILATION")&&action.contains("review"))
                    system+="\n效率优先验收：只因事实错误、主要知识遗漏、关键条件或冲突丢失、错误来源而要求REPAIR。措辞偏好、格式、排列顺序和轻微重复不阻止ACCEPT，不要求重写已经可用的内容。";
                String instructions=system;
                var prepared=ModelInput.fixed(system,List.of(),ContentJson.encode(input));
                ModelInput verified=target->{
                    verify();
                    try{return prepared.prepare(target);}catch(LabException failure){
                        if(original==null||!failure.code().equals("MODEL_CONTEXT_INSUFFICIENT")||!(input instanceof Map<?,?> correction)
                                ||!(correction.get("context") instanceof Map<?,?> context)||!context.containsKey("target"))throw failure;
                        var reduced=new LinkedHashMap<Object,Object>(correction);reduced.remove("result");
                        reduced.put("draftStatus","DRAFT_OMITTED_FOR_CONTEXT：保留全部原参数与错误诊断，从原证据重新生成完整结果");
                        return ModelInput.fixed(instructions,List.of(),ContentJson.encode(reduced)).prepare(target);
                    }
                };
                Map<?,?> data=input instanceof Map<?,?> parameters?parameters:Map.of();
                if(data.get("context") instanceof Map<?,?> context)data=context;
                int estimate=data.get("estimatedOutputBytes") instanceof Number value?value.intValue():0;
                var capacity=ModelInput.reserveOutput(verified,estimate);
                String task=action.contains("review")?"WORKFLOW_REVIEW":"WORKFLOW_REPORT";
                var context=observedWorkflow()?new com.example.ailab.contract.dto.ModelCalls.Context(lease.actor(),runId,lease.task().taskId(),lease.request().taskType(),
                        phase,action,id,schema instanceof RecordSchema<?> record?record.schemaId():schema.getClass().getName(),"1",
                        ContentJson.hash(Map.of("schema",schema.instruction(Set.of()),"input",input,"source",sourceHash)),source.sources()):null;
                var turn=context!=null?model.structuredObserved(task,ModelRegistry.Selection.auto(),capacity,budget,schema,context,original==null?null:original.modelId())
                        :original==null?model.structured(action.contains("review")?"WORKFLOW_REVIEW":"WORKFLOW_REPORT",ModelRegistry.Selection.auto(),capacity,budget,schema)
                        :model.structuredPinned(action.contains("review")?"WORKFLOW_REVIEW":"WORKFLOW_REPORT",ModelRegistry.Selection.auto(),capacity,budget,schema,original.modelId());
                boolean corrected=original!=null||turn.turn().route()!=null&&turn.turn().route().attempts().stream().anyMatch(a->a.outcome().equals("MODEL_STRUCTURED_INVALID"));
                var receipt=new GenerationReceipt(turn.turn().modelId(),corrected?1:0,ContentJson.encode(turn.value()));
                budget.check();store.begin(lease,receiptId,phase,receiptHash);store.complete(lease,receiptId,receiptHash,ContentJson.encode(receipt));
                origins.put(id,receipt);return turn.value();
            },true);
            if(!origins.containsKey(id))throw new LabException("WORKFLOW_STATE_CONFLICT","生成节点缺少模型回执");
            return result;
        }
        GenerationReceipt origin(String id) {
            var value=origins.get(id);
            if(value==null){var saved=store.completedNode(lease,"origin_"+ContentJson.hash(id).substring(0,32));if(saved.isPresent()){
                value=new RecordSchema<>(GenerationReceipt.class,"保存的模型生成回执",v->RecordSchema.require(!v.modelId().isBlank()&&v.corrections()>=0&&v.corrections()<=1,RecordSchema.Reason.CONTENT_CONSTRAINT)).relaxed().validate(saved.get(),Set.of());origins.put(id,value);
            }}
            if(value==null)throw new LabException("WORKFLOW_STATE_CONFLICT","生成节点缺少模型回执");
            return value;
        }
        public <A,B>List<B> parallel(List<A> input,Function<A,B> execute) {
            var context=budget.trace();
            return ContentParallel.run(pool,input,source.policy().parallelism(),budget.deadline(),
                    Duration.ofSeconds(Math.max(0,Math.min(300,parallelDrainSeconds))),item->{
                        try(var ignored=budget.activate(context)){budget.check();return execute.apply(item);}
                    },budget::check);
        }
        public ContentExecutionLimits executionLimits(){return executionLimits;}
        void extract() {extractStructured();}
        void extractStructured() {
            long phaseStarted=System.nanoTime();
            var fragments=reading.fragments().stream().collect(Collectors.toMap(f->f.source().id(),Function.identity()));
            var packages=parallel(reading.packs(),pack->{
                var group=pack.fragmentIds().stream().map(fragments::get).toList();
                return node("readpack_"+pack.packId(),"extract",Map.of("protocol",reading.protocol(),"pack",pack,"fragments",group),
                        new RecordSchema<>(ContentReading.Batch.class,"程序合并的完整阅读结果",v->{
                            RecordSchema.require("COMPLETE".equals(v.status())&&v.fragments().stream().map(ContentReading.Observation::fragmentId).toList().equals(pack.fragmentIds()),RecordSchema.Reason.CONTENT_CONSTRAINT);
                            for(var observation:v.fragments()) {
                                var id=observation.fragmentId();ContentSchemas.facts(id,id,raw.get(id),source.policy().maximumItems(),true,limits())
                                        .validate(ContentJson.encode(new Facts(observation.items(),"COMPLETE")),Set.of());
                            }
                        }),()->readPack(pack.packId(),group));
            });
            LOG.info("event=content.read_phase phase=MODEL_READING fragments={} packs={} durationMs={}",fragments.size(),packages.size(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-phaseStarted));
            phaseStarted=System.nanoTime();
            var readFacts=new ArrayList<Facts>();var readCards=new ArrayList<ContentReading.TopicCard>();
            for(var batch:packages)for(var observation:batch.fragments()) {
                String id=observation.fragmentId();var f=fragments.get(id);var slice=f.source();
                var facts=node("read_"+id,"extract",Map.of("fragment",f,"observation",observation),
                        ContentSchemas.facts(id,id,raw.get(id),source.policy().maximumItems(),true,limits()),()->new Facts(observation.items(),"COMPLETE"));
                readFacts.add(facts);
                var cards=node("topics_"+id,"extract",Map.of("fragment",f,"topics",observation.topics()),
                        new RecordSchema<>(ContentReading.Cards.class,"程序绑定主题卡位置和计数",v->{}),
                        ()->new ContentReading.Cards(observation.topics().stream().map(t->new ContentReading.TopicCard(t.id(),id,f.sectionId(),f.headingPath(),t,t.itemIds().size(),TextWindow.count(raw.get(id)))).toList()));
                readCards.addAll(cards.cards());
                node("evidence_"+id,"extract",Map.of("fragment",f,"items",facts.items()),
                        new RecordSchema<>(ContentReading.Evidences.class,"程序定位原文证据",v->{}),()->new ContentReading.Evidences(facts.items().stream().map(item->{
                            int start=raw.get(id).indexOf(item.quote());if(start<0)throw changed();
                            return new ContentReading.Evidence(item.id(),id,slice.startOffset()+start,slice.startOffset()+start+item.quote().length(),Learning.digest(item.quote()));
                        }).toList()));
                if(readFacts.size()%100==0||readFacts.size()==fragments.size())
                    LOG.info("event=content.read_phase phase=CHECKPOINTS completedFragments={} fragments={} durationMs={}",readFacts.size(),fragments.size(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-phaseStarted));
            }
            phaseStarted=System.nanoTime();
            var required=source.slices().stream().map(s->"read_"+s.id()).toList();
            flushReading();
            verifyNow();
            // 既有阅读检查点仍归属extract；用户阶段在全文证据核验通过后才顺序切换。
            store.finish(lease,"prepare",required);
            tasks.beginStep(lease,"extract");
            readFacts.forEach(facts->facts.items().forEach(item->{if(items.putIfAbsent(item.id(),item)!=null)throw changed();}));
            topicCards.addAll(readCards);
            if(items.isEmpty())throw new LabException("WORKFLOW_NEEDS_INPUT","资料没有可用于当前工作流的知识");
            if(items.size()>source.policy().maximumItems())throw new LabException("WORKFLOW_INPUT_TOO_LARGE","事实条目超过保存的资源策略");
            store.finish(lease,"extract",required);
            LOG.info("event=content.read_phase phase=READING_PROOF fragments={} durationMs={}",fragments.size(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-phaseStarted));
        }
        ContentReading.Batch readPack(String id,List<ContentReading.Fragment> group) {
            try {
                var input=group.stream().map(f->{
                    var s=f.source();String full=documentsRaw.get(s.documentId());
                    return Map.of("fragmentId",s.id(),"headingPath",f.headingPath(),"blockType",f.blockType(),"ownedText",raw.get(s.id()),
                            "background",f.contexts().stream().map(c->Map.of("role",c.role(),"text",full.substring(c.startOffset(),c.endOffset()))).toList());
                }).toList();
                var batch=generate("reading_"+id,"extract","read",Map.of("protocol",reading.protocol(),"fragments",input),ReadingSchemas.batch(group,raw,limits()));
                if("COMPLETE".equals(batch.status()))return batch;
            } catch(LabException failure) {
                if(!capacityFailure(failure)&&RecordSchema.reason(failure).orElse(null)!=RecordSchema.Reason.FACT_QUOTE)throw failure;
            }
            if(group.size()>1) {
                int mid=group.size()/2;var first=readPack(id+"_a",group.subList(0,mid));var second=readPack(id+"_b",group.subList(mid,group.size()));
                var merged=new ArrayList<>(first.fragments());merged.addAll(second.fragments());return new ContentReading.Batch(merged,"COMPLETE");
            }
            var fragment=group.get(0);var slice=fragment.source();var facts=extractPart(slice,raw.get(slice.id()),0);
            var topics=new ArrayList<ContentReading.TopicDraft>();int index=0;
            for(var portion:partition(facts.items(),source.policy().indexBytes(),ContentJson::encode)) {
                String prefix=slice.id()+"_g"+(index++);
                var result=generate("topicdraft_"+prefix,"extract","read",Map.of("headingPath",fragment.headingPath(),"items",portion),ReadingSchemas.topics(prefix,portion));
                topics.addAll(result.topics());
            }
            return new ContentReading.Batch(List.of(new ContentReading.Observation(slice.id(),facts.items(),topics)),"COMPLETE");
        }
        Facts extractPart(Learning.SourceSlice slice,String text,int offset) {
            String prefix=slice.id()+"_"+offset;
            LabException quoteFailure=null;
            try {
                var result=generate("extract_"+prefix+"_"+text.length(),"extract","extract",Map.of("sourceId",slice.id(),"prefix",prefix,"text",text),ContentSchemas.facts(prefix,slice.id(),text,32,false,limits()));
                if(result.status().equals("COMPLETE"))return result;
            } catch(LabException failure) {
                if(RecordSchema.reason(failure).orElse(null)==RecordSchema.Reason.FACT_QUOTE)quoteFailure=failure;
                else if(!capacityFailure(failure))throw failure;
            }
            if(TextWindow.count(text)<=500) {
                if(quoteFailure!=null)throw quoteFailure;
                throw new LabException("WORKFLOW_EXTRACTION_CAPACITY_EXCEEDED","高密度资料最小分片仍超出事实协议容量");
            }
            LOG.info("event=content.extract_split sourceId={} offset={} bytes={} reason={}",slice.id(),offset,TextWindow.count(text),quoteFailure==null?"CAPACITY":"FACT_QUOTE");
            int split=TextWindow.end(text,0,text.length(),Math.max(1,TextWindow.count(text)/2));if(split<=0||split>=text.length())throw changed();
            var first=extractPart(slice,text.substring(0,split),offset);var second=extractPart(slice,text.substring(split),offset+split);
            var merged=new ArrayList<>(first.items());merged.addAll(second.items());return new Facts(merged,"COMPLETE");
        }
        record IndexNode(String id,Summary summary,List<String> itemIds) { }
        OutputPlan planPpt() {
            var leaves=new ArrayList<IndexNode>();
            var cardGroups=partition(topicCards,source.policy().indexBytes(),ContentJson::encode);
            var groups=parallel(java.util.stream.IntStream.range(0,cardGroups.size()).boxed().toList(),i->{
                String id="index_0_"+i;
                var summary=generate(id,"organize","index",Map.of("topicCards",cardGroups.get(i)),ContentSchemas.summary(limits()));
                return new IndexNode(id,summary,cardGroups.get(i).stream().flatMap(c->c.topic().itemIds().stream()).distinct().toList());
            });leaves.addAll(groups);List<IndexNode> level=List.copyOf(leaves);int depth=1;
            while(TextWindow.count(ContentJson.encode(level.stream().map(this::overview).toList()))>source.policy().indexBytes()) {
                var chunks=partition(level,source.policy().indexBytes(),n->ContentJson.encode(overview(n)));int current=depth++;
                if(chunks.size()>=level.size()||depth>16)throw new LabException("WORKFLOW_INDEX_CAPACITY_EXCEEDED","知识索引无法在保存策略中收敛");
                level=parallel(java.util.stream.IntStream.range(0,chunks.size()).boxed().toList(),i->{
                    var group=chunks.get(i);var summary=generate("index_"+current+"_"+i,"organize","index",Map.of("children",group.stream().map(this::overview).toList()),ContentSchemas.summary(limits()));
                    return new IndexNode("index_"+current+"_"+i,summary,group.stream().flatMap(n->n.itemIds().stream()).toList());
                });
            }
            var intentInput=Map.of("taskType",lease.request().taskType(),"remarks",lease.request().instructions(),"options",options(),
                    "knowledge",level.stream().map(this::overview).toList());
            var intent=generate("intent","organize","intent",intentInput,ContentSchemas.intent(source.policy().maximumUnits(),limits()));
            if(leaves.size()>source.policy().maximumUnits())throw new LabException("WORKFLOW_PLAN_BUDGET_EXCEEDED","知识工作包数量超过资源策略，请调整资料范围或策略");
            int maxPerLeaf=Math.max(1,source.policy().maximumUnits()/leaves.size());
            var local=parallel(java.util.stream.IntStream.range(0,leaves.size()).boxed().toList(),i->{
                var group=leaves.get(i).itemIds().stream().map(items::get).toList();int imageSlots=lease.request().taskType().equals("NOTES_PPT")?8/leaves.size()+(i<8%leaves.size()?1:0):0;
                return node("planned_"+i,"organize",Map.of("intent",intent,"items",group),
                         ContentSchemas.units(lease.request().taskType(),intent,group,lease.request(),maxPerLeaf,imageSlots,source.policy().unitInputBytes(),limits()),
                        ()->planPart("plan_"+i,intent,group,maxPerLeaf,imageSlots));
            });
            local=merge(intent,local);
            var targets=new ArrayList<UnitPlan>();var omissions=new ArrayList<Omission>();
            for(var batch:local) {
                for(var draft:batch.units())targets.add(new UnitPlan(String.format(Locale.ROOT,"u%04d",targets.size()+1),draft.kind(),draft.themeId(),draft.title(),draft.purpose(),draft.relation(),draft.imageMode(),draft.imagePrompt(),draft.itemIds()));
                if(!batch.omittedItemIds().isEmpty())omissions.add(new Omission(batch.omittedItemIds(),batch.omissionReason()));
            }
            if(targets.isEmpty())throw new LabException("WORKFLOW_NEEDS_INPUT","资料未形成可交付的内容目标，请调整要求");
            var counts=counts(targets);boolean ppt=lease.request().taskType().equals("NOTES_PPT");
            try{validateRequested(intent,counts);}catch(LabException failure){
                if(!failure.code().equals("WORKFLOW_REQUIREMENTS_UNSATISFIED"))throw failure;
                var schema=ContentSchemas.units(lease.request().taskType(),intent,List.copyOf(items.values()),lease.request(),source.policy().maximumUnits(),ppt?8:0,source.policy().unitInputBytes(),limits())
                        .withChecks(v->validateRequested(intent,counts(targets(v.units()))));
                var revised=generate("correct_plan","organize","plan",Map.of("context",intentInput,"intent",intent,"result",local,
                        "issues",failure.validationIssues(),"requirement","纠正候选目标数量；保持全部事实归属、主题身份和用户要求"),schema,origin("intent"));
                targets=targets(revised.units());omissions.clear();
                if(!revised.omittedItemIds().isEmpty())omissions.add(new Omission(revised.omittedItemIds(),revised.omissionReason()));
                counts=counts(targets);validateRequested(intent,counts);
            }
            int estimate=targets.size()*6;int output=targets.size()*16000+items.size()*3500;
            var plan=new OutputPlan(2,lease.request().taskType(),ContentJson.hash(source),intent,targets,omissions,counts,targets.stream().map(u->"accepted_"+u.id()).toList(),estimate,estimate*2,output);
            store.accept(lease,plan);store.finish(lease,"organize",List.of("intent"));return plan;
        }
        ArrayList<UnitPlan> targets(List<UnitDraft> drafts) {
            var result=new ArrayList<UnitPlan>();for(var draft:drafts)result.add(new UnitPlan(String.format(Locale.ROOT,"u%04d",result.size()+1),draft.kind(),draft.themeId(),draft.title(),draft.purpose(),draft.relation(),draft.imageMode(),draft.imagePrompt(),draft.itemIds()));return result;
        }
        Counts counts(List<UnitPlan> targets) {
            int images=(int)targets.stream().filter(u->!u.imageMode().equals("NONE")).count();int sourcePages=Math.max(1,(source.sources().size()+images+11)/12);
            return new Counts(0,0,0,targets.size(),sourcePages,targets.size()+sourcePages);
        }
        boolean capacityFailure(LabException failure){return Set.of("MODEL_TRUNCATED","MODEL_CONTEXT_INSUFFICIENT").contains(failure.code());}
        UnitBatch combine(UnitBatch first,UnitBatch second) {
            var units=new ArrayList<>(first.units());units.addAll(second.units());
            var omitted=new ArrayList<>(first.omittedItemIds());omitted.addAll(second.omittedItemIds());
            String reason=java.util.stream.Stream.of(first.omissionReason(),second.omissionReason()).filter(s->!s.isBlank()).distinct().collect(Collectors.joining("；"));
            return new UnitBatch(units,omitted,reason);
        }
        UnitBatch planPart(String id,Intent intent,List<Learning.Item> group,int maximum,int images) {
            try {return generate(id,"organize","plan",Map.of("taskType",lease.request().taskType(),"intent",intent,"options",options(),"items",group,"maximumImages",images),
                    ContentSchemas.units(lease.request().taskType(),intent,group,lease.request(),maximum,images,source.policy().unitInputBytes(),limits()));}
            catch(LabException capacity) {
                if(!capacityFailure(capacity))throw capacity;
                if(group.size()<2||maximum<2)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","最小规划工作包仍超过模型容量，请调整模型或资料粒度");
                int split=group.size()/2;
                return combine(planPart(id+"_a",intent,group.subList(0,split),maximum/2,images/2),planPart(id+"_b",intent,group.subList(split,group.size()),maximum-maximum/2,images-images/2));
            }
        }
        List<UnitBatch> merge(Intent intent,List<UnitBatch> batches) {
            var candidates=batches.stream().flatMap(b->b.units().stream()).toList();
            var packs=new ArrayList<List<UnitDraft>>();
            for(var theme:intent.themes()) {
                var matching=candidates.stream().filter(u->u.themeId().equals(theme.id())).sorted(Comparator.comparing(UnitDraft::title).thenComparing(UnitDraft::purpose)).toList();
                packs.addAll(partition(matching,source.policy().indexBytes(),ContentJson::encode));
            }
            var merged=parallel(java.util.stream.IntStream.range(0,packs.size()).boxed().toList(),i->{
                var pack=packs.get(i);var ids=pack.stream().flatMap(u->u.itemIds().stream()).collect(Collectors.toSet());
                var evidence=ids.stream().sorted().map(items::get).toList();
                int imageSlots=(int)pack.stream().filter(u->!u.imageMode().equals("NONE")).count();
                return node("merged_"+i,"organize",Map.of("intent",intent,"candidates",pack),
                         ContentSchemas.units(lease.request().taskType(),intent,evidence,lease.request(),pack.size(),imageSlots,source.policy().unitInputBytes(),limits()),
                        ()->mergePart("merge_"+i,intent,pack));
            });
            var selected=merged.stream().flatMap(b->b.units().stream()).flatMap(u->u.itemIds().stream()).collect(Collectors.toSet());
            var result=new ArrayList<UnitBatch>();
            merged.forEach(b->result.add(new UnitBatch(b.units(),List.of(),"")));
            var omitted=new LinkedHashSet<String>();var reasons=new LinkedHashSet<String>();
            java.util.stream.Stream.concat(batches.stream(),merged.stream()).forEach(b->{
                b.omittedItemIds().stream().filter(id->!selected.contains(id)).forEach(omitted::add);
                if(!b.omittedItemIds().isEmpty())reasons.add(b.omissionReason());
            });
            if(!omitted.isEmpty())result.add(new UnitBatch(List.of(),List.copyOf(omitted),String.join("；",reasons)));
            return List.copyOf(result);
        }
        UnitBatch mergePart(String id,Intent intent,List<UnitDraft> pack) {
            var ids=pack.stream().flatMap(u->u.itemIds().stream()).collect(Collectors.toSet());var evidence=ids.stream().sorted().map(items::get).toList();
            int images=(int)pack.stream().filter(u->!u.imageMode().equals("NONE")).count();
            try {return generate(id,"organize","merge",Map.of("taskType",lease.request().taskType(),"intent",intent,"options",options(),"candidates",pack),
                    ContentSchemas.units(lease.request().taskType(),intent,evidence,lease.request(),pack.size(),images,source.policy().unitInputBytes(),limits()));}
            catch(LabException capacity) {
                if(!capacityFailure(capacity))throw capacity;
                if(pack.size()<2)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","最小归并目标仍超过模型容量");
                int split=pack.size()/2;return combine(mergePart(id+"_a",intent,pack.subList(0,split)),mergePart(id+"_b",intent,pack.subList(split,pack.size())));
            }
        }
        Object overview(IndexNode node){return Map.of("id",node.id(),"title",node.summary().title(),"summary",node.summary().summary(),"facts",node.itemIds().size());}
        Object options(){return lease.request().presentationOptions();}
        void validateRequested(Intent intent,Counts counts) {
            int requested=lease.request().presentationOptions().pageCount();
            if(requested==0&&!intent.requestedUnits().equals("AUTO"))requested=Integer.parseInt(intent.requestedUnits());
            if(requested>0&&requested!=counts.totalSlides())throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","PPT总页数与显式要求不一致",List.of(new ValidationIssue("counts","EXACT_COUNT",counts.totalSlides(),requested,"ITEMS","PPT总页数包含来源页")));
        }
        Map<String,Object> context(OutputPlan plan,UnitPlan target) {
            var facts=target.itemIds().stream().map(items::get).toList();
            if(TextWindow.count(ContentJson.encode(facts))>source.policy().unitInputBytes())throw new LabException("WORKFLOW_UNIT_TOO_LARGE","局部目标证据超过模型工作包容量");
            var theme=plan.intent().themes().stream().filter(t->t.id().equals(target.themeId())).findFirst().orElseThrow();
            return Map.of("planHash",ContentJson.hash(plan),"title",plan.intent().title(),"theme",theme,"requirements",plan.intent().requirements(),"remarks",lease.request().instructions(),"target",target,"items",facts,"options",options());
        }
        <T>T checked(OutputPlan plan,UnitPlan target,String action,RecordSchema<T> schema,UnaryOperator<T> checks) {
            var input=context(plan,target);
            var validated=schema.withChecks(checks::apply);
            T result=generate("generate_"+target.id(),"generate",action,input,validated);
            var review=review(plan,target,"review_"+target.id(),input,result);
            if(review.decision().equals("REPAIR")) {
                result=generate("repair_"+target.id(),"review",action,Map.of("context",input,"result",result,"issues",review.issues()),validated,origin("generate_"+target.id()));
                review=review(plan,target,"review_repair_"+target.id(),input,result);
            }
            if(!review.decision().equals("ACCEPT"))throw new LabException("WORKFLOW_REVIEW_REJECTED","局部目标有限修复后仍未满足证据与用户要求");
            if(result instanceof Media.Unit unit && presentation != null && !presentation.validateLayout(List.of(unit)).isEmpty())
                throw new LabException("PPT_TEXT_OVERFLOW","页面版式预检未通过，请调整备注或资料范围");
            final T accepted=result;
            return node("accepted_"+target.id(),"review",Map.of("planHash",ContentJson.hash(plan),"target",target,"result",accepted),schema,()->accepted);
        }
        <T>Learning.Review review(OutputPlan plan,UnitPlan target,String id,Map<String,Object> input,T result) {
            var layout=result instanceof Media.Unit unit && presentation!=null?presentation.validateLayout(List.of(unit)):List.<Presentation.LayoutIssue>of();
            var review=generate(id,"review","review",Map.of("context",input,"result",result,"layoutIssues",layout),LearningSchemas.review(Set.of(target.id()),limits()));
            if(layout.isEmpty())return review;
            var issues=new ArrayList<>(review.issues());
            layout.forEach(i->issues.add(new Learning.Issue(target.id(),"BAD_ORGANIZATION",i.code(),"保持页面目标和证据，缩短页面正文并将解释放入备注")));
            return new Learning.Review("REPAIR",issues);
        }
        Media.Unit slide(OutputPlan plan,UnitPlan target) {
            var references=target.itemIds().stream().map(items::get).map(i->source.slices().stream().filter(s->s.id().equals(i.sourceId())).findFirst().orElseThrow())
                    .map(s->"D"+s.documentId()+"v"+s.documentVersion()).collect(Collectors.toSet());
            var schema=ContentSchemas.slide(target,references,limits());
            return checked(plan,target,"generate",schema,u->{MediaSchemas.validateImagePolicy(List.of(u),lease.request().presentationOptions().imagePolicy());return u;});
        }
        List<Learning.Citation> citations() {
            var slices=source.slices().stream().collect(Collectors.toMap(Learning.SourceSlice::id,Function.identity()));
            return items.values().stream().map(i->{var slice=slices.get(i.sourceId());int start=slice.startOffset()+raw.get(slice.id()).indexOf(i.quote());return new Learning.Citation(i.id(),slice,start,start+i.quote().length(),i.quote());}).toList();
        }
        void finishGeneration(OutputPlan plan) {
            store.finish(lease,"generate",plan.units().stream().map(u->"generate_"+u.id()).toList());
            store.finish(lease,"review",plan.requiredNodes());
        }
        void publish(LearningContent.Plan plan,LearningExecutor.Completed completed) {
            verifyNow();
            store.verifyComplete(lease);var value=completed.result();
            var result=(com.fasterxml.jackson.databind.node.ObjectNode)com.example.ailab.ai.tools.ToolSchema.JSON.valueToTree(value);
            result.set("contentPlan",com.example.ailab.ai.tools.ToolSchema.JSON.valueToTree(plan));
            result.set("coverageReport",com.example.ailab.ai.tools.ToolSchema.JSON.valueToTree(completed.coverage()));
            String json=ContentJson.encode(result);String hash=ContentJson.hash(Map.of("plan",plan,"result",json));
            var old=store.completed(lease,"result",hash);
            if(old.isEmpty()){store.begin(lease,"result","publish",hash);store.complete(lease,"result",hash,json);}
            else if(!old.get().equals(json))throw changed();
            StringBuilder coverage=new StringBuilder("\n\n覆盖报告：\n");
            for(var c:completed.coverage())coverage.append("- ").append(c.kind()).append(" ").append(c.id()).append(": ").append(c.selected()).append("/").append(c.available()).append("；").append(c.reason()).append("\n");
            store.publish(lease,json,LearningRenderer.render(value)+coverage);
        }
        public void close(){pool.shutdownNow();try{pool.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    private static <T>List<List<T>> partition(List<T> input,int maximum,Function<T,String> encode) {
        var output=new ArrayList<List<T>>();var current=new ArrayList<T>();int bytes=2;
        for(var item:input){int size=TextWindow.count(encode.apply(item))+1;if(size+2>maximum)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","单条知识超过工作包容量，请降低事实粒度");
            if(bytes+size>maximum&&!current.isEmpty()){output.add(List.copyOf(current));current.clear();bytes=2;}current.add(item);bytes+=size;}
        if(!current.isEmpty())output.add(List.copyOf(current));return List.copyOf(output);
    }
    private static LabException changed(){return new LabException("CONTEXT_VERSION_CONFLICT","资料、处理代次、请求或执行绑定发生变化");}
}
