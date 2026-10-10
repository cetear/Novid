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
    @org.springframework.beans.factory.annotation.Autowired
    public void presentation(PresentationPort value) { this.presentation=value; }
    public DocumentDrivenWorkflow(TaskStorePort tasks,ContentWorkflowStorePort store,WorkflowRunStorePort workflows,WorkflowRouter router,
                                  SkillCatalog skills,ToolExecutionService tools,KnowledgeCapabilityPort knowledge,ModelGateway model) {
        this.tasks=tasks;this.store=store;this.workflows=workflows;this.router=router;this.skills=skills;this.tools=tools;this.knowledge=knowledge;this.model=model;
    }
    public record PptResult(OutputPlan plan,List<Media.Unit> units,List<SourceDependency> sources) { }
    private record Indexed<T>(int index,T value) { }
    private record LearningExecution(OutputPlan plan, Learning.Result result) { }
    private record GenerationReceipt(String modelId,int corrections,String json) { }
    public void learning(TaskLease lease,TraceContext trace,String runId) {
        try(var session=new Session(lease,trace,runId)) {
            var completed=router.execute(lease,workflows,session.budget,FixedWorkflowProgram.of(()-> {
                session.extract();var plan=session.plan();
                if(lease.request().taskType().equals("QUIZ_GENERATION")) {
                    var questions=session.parallel(plan.units(),u->session.question(plan,u));
                    var quiz=new Learning.Quiz(plan.intent().title(),questions);
                    quiz=session.checkQuiz(plan,quiz);
                    return new LearningExecution(plan,new Learning.Result("learning-quiz",quiz.title(),quiz,null,List.of(),session.citations(),true,"MODEL_REVIEW_PASSED_PENDING_HUMAN"));
                } else {
                    var sections=session.parallel(plan.units(),u->session.section(plan,u));
                    var chapters=new ArrayList<Learning.Chapter>();var chapterPlans=new ArrayList<Learning.ChapterPlan>();
                    for(var theme:plan.intent().themes()) {
                        var assigned=plan.units().stream().filter(u->u.themeId().equals(theme.id())).toList();
                        if(assigned.isEmpty())continue;
                        var groups=assigned.stream().map(u->new Learning.Group(u.id(),u.title(),u.relation(),u.itemIds())).toList();
                        chapterPlans.add(new Learning.ChapterPlan(theme.id(),theme.title(),groups));
                        var byId=sections.stream().collect(Collectors.toMap(Learning.Section::groupId,Function.identity()));
                        chapters.add(new Learning.Chapter(theme.id(),theme.title(),assigned.stream().map(u->byId.get(u.id())).toList()));
                    }
                    var outline=new Learning.Outline(plan.intent().title(),chapterPlans);
                    return new LearningExecution(plan,new Learning.Result("knowledge-compilation",outline.title(),null,outline,chapters,session.citations(),true,"MODEL_REVIEW_PASSED_PENDING_HUMAN"));
                }
            }));
            session.publish(completed.plan(),completed.result());
        }
    }
    public PptResult presentation(TaskLease lease,TraceContext trace,String runId) {
        try(var session=new Session(lease,trace,runId)) {
            return router.execute(lease,workflows,session.budget,FixedWorkflowProgram.of(()-> {
                session.extract();var plan=session.plan();var units=session.parallel(plan.units(),u->session.slide(plan,u));
                session.finishGeneration(plan);store.verifyComplete(lease);
                return new PptResult(plan,units,session.source.sources());
            }));
        }
    }
    private final class Session implements AutoCloseable {
        private final TaskLease lease;
        private final SourcePlan source;
        private final ExecutionBudget budget;
        private final ExecutorService pool;
        private final Map<String,String> raw=new LinkedHashMap<>();
        private final Map<String,Learning.Item> items=new LinkedHashMap<>();
        private final Map<String,GenerationReceipt> origins=new ConcurrentHashMap<>();
        private ContentLimits limits(){return ContentLimits.current();}
        Session(TaskLease lease,TraceContext trace,String runId) {
            this.lease=lease;
            tasks.beginStep(lease,"prepare");
            var policy=tasks.contentPolicy(lease).orElseThrow(()->new LabException("WORKFLOW_STATE_CONFLICT","缺少创建时的资料资源策略"));
            var binding=router.binding(lease,workflows);
            source=store.sourcePlan(lease).orElseGet(()->{
                var skill=skills.contentBinding(tools.contracts());var prepared=prepare(lease,binding,skill,policy);store.bind(lease,prepared);return prepared;
            });
            if(!source.policy().equals(policy)||!source.workflow().equals(binding))throw changed();
            SkillCatalog.verify(source.skill());
            trace.payloadSources(source.sources());
            for(var slice:source.slices())raw.put(slice.id(),read(slice));
            long elapsed=lease.task().progress()==null?0:lease.task().progress().elapsedExecutionSeconds();
            budget=new ExecutionBudget(Duration.ofSeconds(Math.max(1,policy.executionSeconds()-elapsed)),policy.attempts(),()->tasks.reserveModelAttempt(lease),
                    ()->tasks.reserveModelTurn(lease),this::verify,()->tasks.reserveToolCall(lease),()->tasks.reserveModelRepair(lease))
                    .content(policy).traced(trace).fees(new FeeScope(lease.actor(),"TASK",Long.toString(lease.task().taskId()),runId));
            pool=Executors.newFixedThreadPool(policy.parallelism());
            tasks.completePreparation(lease);
        }
        void verify() {
            if(!tasks.renew(lease))throw new LabException("STALE_EXECUTION","资料工作流租约失效");
            knowledge.authorize(lease.actor(),lease.request().scope());tools.verifyContracts(source.skill().toolContracts());
            if(!source.requestHash().equals(ContentJson.hash(lease.request())))throw changed();
            var docs=new HashMap<Long,DocumentContent>();
            for(var dependency:source.sources()) {
                var d=docs.computeIfAbsent(dependency.documentId(),id->knowledge.document(lease.actor(),lease.request().scope(),id)).document();
                if(d.documentVersion()!=dependency.documentVersion()||d.knowledgeBaseId()!=dependency.knowledgeBaseId())throw changed();
            }
            for(var slice:source.slices()) {
                var doc=docs.computeIfAbsent(slice.documentId(),id->knowledge.document(lease.actor(),lease.request().scope(),id));
                if(!Objects.equals(doc.document().activeProcessingRevision(),slice.processingRevision())||slice.endOffset()>doc.text().length()
                        ||!Learning.digest(doc.text().substring(slice.startOffset(),slice.endOffset())).equals(slice.textHash()))throw changed();
            }
        }
        String read(Learning.SourceSlice slice) {
            var content=knowledge.document(lease.actor(),lease.request().scope(),slice.documentId());
            if(content.document().documentVersion()!=slice.documentVersion()||!Objects.equals(content.document().activeProcessingRevision(),slice.processingRevision())
                    ||!TextWindow.boundary(content.text(),slice.endOffset())||slice.startOffset()>=slice.endOffset())throw changed();
            var value=content.text().substring(slice.startOffset(),slice.endOffset());if(!Learning.digest(value).equals(slice.textHash()))throw changed();return value;
        }

        <T>T node(String id,String phase,Object input,StructuredSchema<T> schema,Supplier<T> execute) {
            budget.check();String hash=ContentJson.hash(Map.of("source",ContentJson.hash(source),"node",id,"input",input));
            try(var span=budget.trace().span("AGENT",id);var activation=budget.activate(span.context())) {
                com.example.ailab.ai.runtime.TracePayloadCapture.input(span,input);
                try {
                    var old=store.completed(lease,id,hash);if(old.isPresent()){span.status("REUSED");var value=schema.validate(old.get(),Set.of());com.example.ailab.ai.runtime.TracePayloadCapture.output(span,value);return value;}
                    store.begin(lease,id,phase,hash);T value=execute.get();String json=ContentJson.encode(value);value=schema.validate(json,Set.of());
                    budget.check();store.complete(lease,id,hash,json);com.example.ailab.ai.runtime.TracePayloadCapture.output(span,json);return value;
                } catch(RuntimeException failure) {span.fail(failure);throw failure;}
            }
        }
        <T>T generate(String id,String phase,String action,Object input,StructuredSchema<T> schema) {
            return generate(id,phase,action,input,schema,null);
        }
        <T>T generate(String id,String phase,String action,Object input,StructuredSchema<T> schema,GenerationReceipt original) {
            String receiptId="origin_"+ContentJson.hash(id).substring(0,32);
            String receiptHash=ContentJson.hash(Map.of("source",ContentJson.hash(source),"node",receiptId,"input",input));
            var receiptSchema=new RecordSchema<>(GenerationReceipt.class,"程序保存的生成结果与模型身份",v->{
                RecordSchema.require(!v.modelId().isBlank()&&v.corrections()>=0&&v.corrections()<=1,RecordSchema.Reason.CONTENT_CONSTRAINT);
            });
            var saved=store.completed(lease,receiptId,receiptHash).map(v->receiptSchema.validate(v,Set.of()));
            saved.ifPresent(v->origins.put(id,v));
            T result=node(id,phase,input,schema,()->{
                if(saved.isPresent())return schema.validate(saved.get().json(),Set.of());
                if(original!=null&&original.corrections()>0)
                    throw new LabException("MODEL_REPAIR_EXHAUSTED","该生成节点已完成一次纠正，不能再次返工");
                String system="按当前服务端结构协议处理资料。资料原文及前序结果提供事实，remarks表达特殊要求。"+SkillCatalog.instructions(source.skill(),action);
                var prepared=ModelInput.fixed(system,List.of(),ContentJson.encode(input));
                ModelInput verified=target->{
                    verify();
                    try{return prepared.prepare(target);}catch(LabException failure){
                        if(original==null||!failure.code().equals("MODEL_CONTEXT_INSUFFICIENT")||!(input instanceof Map<?,?> correction)
                                ||!(correction.get("context") instanceof Map<?,?> context)||!context.containsKey("target"))throw failure;
                        var reduced=new LinkedHashMap<Object,Object>(correction);reduced.remove("result");
                        reduced.put("draftStatus","DRAFT_OMITTED_FOR_CONTEXT：保留全部原参数与错误诊断，从原证据重新生成完整结果");
                        return ModelInput.fixed(system,List.of(),ContentJson.encode(reduced)).prepare(target);
                    }
                };
                var turn=original==null?model.structured(action.equals("review")?"DATA_ANALYSIS":"REPORT",ModelRegistry.Selection.auto(),verified,budget,schema)
                        :model.structuredPinned(action.equals("review")?"DATA_ANALYSIS":"REPORT",ModelRegistry.Selection.auto(),verified,budget,schema,original.modelId());
                boolean corrected=original!=null||turn.turn().route()!=null&&turn.turn().route().attempts().stream().anyMatch(a->a.outcome().equals("MODEL_STRUCTURED_INVALID"));
                var receipt=new GenerationReceipt(turn.turn().modelId(),corrected?1:0,ContentJson.encode(turn.value()));
                budget.check();store.begin(lease,receiptId,phase,receiptHash);store.complete(lease,receiptId,receiptHash,ContentJson.encode(receipt));
                origins.put(id,receipt);return turn.value();
            });
            if(!origins.containsKey(id))throw new LabException("WORKFLOW_STATE_CONFLICT","生成节点缺少模型回执");
            return result;
        }
        GenerationReceipt origin(String id) {
            var value=origins.get(id);
            if(value==null)throw new LabException("WORKFLOW_STATE_CONFLICT","生成节点缺少模型回执");
            return value;
        }
        <A,B>List<B> parallel(List<A> input,Function<A,B> execute) {
            if(input.isEmpty())return List.of();var context=budget.trace();var futures=new ArrayList<Future<Indexed<B>>>();
            var completion=new ExecutorCompletionService<Indexed<B>>(pool);
            try {
                for(int i=0;i<input.size();i++){int index=i;var item=input.get(i);futures.add(completion.submit(()->{try(var ignored=budget.activate(context)){budget.check();return new Indexed<>(index,execute.apply(item));}}));}
                var output=new ArrayList<B>(Collections.nCopies(input.size(),null));
                for(int i=0;i<input.size();i++){var value=completion.take().get();output.set(value.index(),value.value());}return List.copyOf(output);
            } catch(InterruptedException e){Thread.currentThread().interrupt();throw new LabException("REQUEST_CANCELLED","资料工作流已取消");}
            catch(ExecutionException e){if(e.getCause() instanceof RuntimeException r)throw r;throw new IllegalStateException("资料子任务失败",e.getCause());}
            finally{futures.forEach(f->{if(!f.isDone())f.cancel(true);});}
        }
        void extract() {
            var batches=parallel(source.slices(),slice->{
                var input=Map.of("source",slice,"text",raw.get(slice.id()));
                return node("read_"+slice.id(),"extract",input,ContentSchemas.facts(slice.id(),slice.id(),raw.get(slice.id()),source.policy().maximumItems(),true,limits()),
                        ()->extractPart(slice,raw.get(slice.id()),0));
            });
            batches.forEach(b->b.items().forEach(i->{if(items.putIfAbsent(i.id(),i)!=null)throw changed();}));
            if(items.isEmpty())throw new LabException("WORKFLOW_NEEDS_INPUT","资料没有可用于当前工作流的知识，请补充有效资料");
            if(items.size()>source.policy().maximumItems())throw new LabException("WORKFLOW_INPUT_TOO_LARGE","事实条目超过保存的资源策略，请缩小范围或调整策略");
            store.finish(lease,"extract",source.slices().stream().map(s->"read_"+s.id()).toList());
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
        OutputPlan plan() {
            var leaves=new ArrayList<IndexNode>();
            var ordered=items.values().stream().sorted(Comparator.comparing(Learning.Item::category).thenComparing(Learning.Item::content).thenComparing(Learning.Item::id)).toList();
            var factGroups=partition(ordered,source.policy().indexBytes(),ContentJson::encode);
            var groups=parallel(java.util.stream.IntStream.range(0,factGroups.size()).boxed().toList(),i->{
                String id="index_0_"+i;var group=factGroups.get(i);
                var summary=generate(id,"organize","index",Map.of("items",group),ContentSchemas.summary(limits()));
                return new IndexNode(id,summary,group.stream().map(Learning.Item::id).toList());
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
            boolean ppt=lease.request().taskType().equals("NOTES_PPT"),quiz=lease.request().taskType().equals("QUIZ_GENERATION");
            int images=(int)targets.stream().filter(u->!u.imageMode().equals("NONE")).count();int sourcePages=ppt?Math.max(1,(source.sources().size()+images+11)/12):0;
            return new Counts(quiz?targets.size():0,!quiz&&!ppt?(int)targets.stream().map(UnitPlan::themeId).distinct().count():0,!quiz&&!ppt?targets.size():0,ppt?targets.size():0,sourcePages,ppt?targets.size()+sourcePages:0);
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
        Object options(){return switch(lease.request().taskType()){case "QUIZ_GENERATION"->lease.request().quizOptions();case "KNOWLEDGE_COMPILATION"->lease.request().compilationOptions();default->lease.request().presentationOptions();};}
        void validateRequested(Intent intent,Counts counts) {
            int requested=intent.requestedUnits().equals("AUTO")?0:Integer.parseInt(intent.requestedUnits());
            int actual=switch(lease.request().taskType()){case "NOTES_PPT"->counts.totalSlides();case "KNOWLEDGE_COMPILATION"->counts.chapters();default->counts.questions();};
            if(lease.request().taskType().equals("QUIZ_GENERATION")&&lease.request().quizOptions().questionCount()>0)requested=lease.request().quizOptions().questionCount();
            if(lease.request().taskType().equals("NOTES_PPT")&&lease.request().presentationOptions().pageCount()>0)requested=lease.request().presentationOptions().pageCount();
            if(requested>0&&requested!=actual)throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","资料规划数量与显式数量要求不一致",List.of(new ValidationIssue("counts","EXACT_COUNT",actual,requested,"ITEMS","必须等于用户要求数量；PPT总页数包含来源页")));
            if(lease.request().taskType().equals("KNOWLEDGE_COMPILATION")&&lease.request().compilationOptions().maximumChapters()>0
                    &&counts.chapters()>lease.request().compilationOptions().maximumChapters())throw new LabException("WORKFLOW_REQUIREMENTS_UNSATISFIED","资料目录超过显式章节上限",List.of(new ValidationIssue("counts.chapters","MAXIMUM",counts.chapters(),lease.request().compilationOptions().maximumChapters(),"ITEMS","不得超过用户章节上限")));
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
            if(lease.request().taskType().equals("QUIZ_GENERATION"))return accepted;
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
        Learning.Question question(OutputPlan plan,UnitPlan target) {
            return checked(plan,target,"generate",questionSchema(plan,target),UnaryOperator.identity());
        }
        RecordSchema<Learning.Question> questionSchema(OutputPlan plan,UnitPlan target) {
            var expected=new Learning.Target(target.id(),target.kind(),target.itemIds());var original=LearningSchemas.quiz(List.of(expected),limits());
            var fields=new ArrayList<RecordSchema.FieldRule>();
            original.fieldRules().stream().filter(f->f.path().startsWith("questions[].")).forEach(f->fields.add(new RecordSchema.FieldRule(f.path().substring("questions[].".length()),f.maximum(),f.unit(),f.nonBlank(),f.allowed())));
            fields.add(RecordSchema.FieldRule.optionalUtf8("$",limits().question()));
            var schema=new RecordSchema<>(Learning.Question.class,"输出一题，id="+target.id()+"，type="+target.kind()+"，itemIds与目标完全一致。单选四个选项，答案A/B/C/D；简答options为空。答案和explanation有证据。"
                    ,fields,q->{
                LearningSchemas.validateQuestion(q,expected,"");
                RecordSchema.require(LearningSchemas.withLocalQuizChecks(new Learning.Quiz(plan.intent().title(),List.of(q)),new Learning.Review("ACCEPT",List.of())).decision().equals("ACCEPT"),RecordSchema.Reason.CONTENT_CONSTRAINT,"options","选项文本互不重复");
            });
            return schema;
        }
        Learning.Quiz checkQuiz(OutputPlan plan,Learning.Quiz quiz) {
            var questions=new ArrayList<>(quiz.questions());
            var local=LearningSchemas.withLocalQuizChecks(quiz,new Learning.Review("ACCEPT",List.of()));
            for(var issue:local.issues()) {
                var target=plan.units().stream().filter(u->u.id().equals(issue.unitId())).findFirst().orElseThrow();
                int index=java.util.stream.IntStream.range(0,questions.size()).filter(i->questions.get(i).id().equals(target.id())).findFirst().orElseThrow();
                var others=questions.stream().filter(q->!q.id().equals(target.id())).toList();
                var context=context(plan,target);var original=origins.get("repair_"+target.id());if(original==null)original=origin("generate_"+target.id());
                var schema=questionSchema(plan,target).withChecks(q->{
                    var candidates=new ArrayList<>(others);candidates.add(q);
                    var check=LearningSchemas.withLocalQuizChecks(new Learning.Quiz(quiz.title(),candidates),new Learning.Review("ACCEPT",List.of()));
                    RecordSchema.require(check.issues().stream().noneMatch(i->i.unitId().equals(q.id())),RecordSchema.Reason.CONTENT_CONSTRAINT,"stem/options","题干与其他题不同，选项互不重复");
                });
                var fixed=generate("global_repair_"+target.id(),"review","generate",Map.of("context",context,"result",questions.get(index),"issues",List.of(issue),
                        "otherQuestions",others.stream().map(q->Map.of("id",q.id(),"stem",q.stem())).toList()),schema,original);
                if(!review(plan,target,"review_global_repair_"+target.id(),context,fixed).decision().equals("ACCEPT"))
                    throw new LabException("WORKFLOW_REVIEW_REJECTED","跨题纠正后仍未通过内容质检");
                questions.set(index,fixed);
            }
            var accepted=new Learning.Quiz(quiz.title(),questions);
            if(!LearningSchemas.withLocalQuizChecks(accepted,new Learning.Review("ACCEPT",List.of())).decision().equals("ACCEPT"))
                throw new LabException("WORKFLOW_REVIEW_REJECTED","跨题纠正后仍存在重复或歧义");
            for(var target:plan.units()) {
                var value=questions.stream().filter(q->q.id().equals(target.id())).findFirst().orElseThrow();
                node("accepted_"+target.id(),"review",Map.of("planHash",ContentJson.hash(plan),"target",target,"result",value),questionSchema(plan,target),()->value);
            }
            return accepted;
        }
        Learning.Section section(OutputPlan plan,UnitPlan target) {
            var schema=new RecordSchema<>(Learning.Section.class,"输出groupId="+target.id()+"、body与itemIds。正文完整表达目标事实、条件及例外，itemIds保持计划顺序。",
                    List.of(RecordSchema.FieldRule.utf8("body",limits().section())),s->{
                RecordSchema.require(s.groupId().equals(target.id()),RecordSchema.Reason.CONTENT_CONSTRAINT,"groupId",target.id());
                RecordSchema.require(s.itemIds().equals(target.itemIds()),RecordSchema.Reason.CONTENT_CONSTRAINT,"itemIds","保持目标事实编号与顺序");
            });return checked(plan,target,"generate",schema,UnaryOperator.identity());
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
        void publish(OutputPlan plan,Learning.Result value) {
            finishGeneration(plan);store.verifyComplete(lease);
            var result=com.example.ailab.ai.tools.ToolSchema.JSON.valueToTree(value);
            ((com.fasterxml.jackson.databind.node.ObjectNode)result).set("contentPlan",com.example.ailab.ai.tools.ToolSchema.JSON.valueToTree(plan));
            String json=ContentJson.encode(result);String hash=ContentJson.hash(Map.of("plan",plan,"result",json));
            var old=store.completed(lease,"result",hash);
            if(old.isEmpty()){store.begin(lease,"result","publish",hash);store.complete(lease,"result",hash,json);}
            else if(!old.get().equals(json))throw changed();
            String coverage="\n\n产出计划："+plan.counts().questions()+"题，"+plan.counts().chapters()+"章，"+plan.counts().sections()+"节。\n"
                    +"选材与数量理由："+plan.intent().reason()+"\n采用的要求："+plan.intent().requirements()+"\n";
            for(var omission:plan.omissions())coverage+="未纳入当前产出："+omission.itemIds()+"；原因："+omission.reason()+"\n";
            store.publish(lease,json,LearningRenderer.render(value)+coverage);
        }
        public void close(){pool.shutdownNow();try{pool.awaitTermination(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
    }
    private SourcePlan prepare(TaskLease lease,WorkflowExecutionBinding binding,TaskExecutionBinding skill,Policy policy) {
        knowledge.authorize(lease.actor(),lease.request().scope());var slices=new ArrayList<Learning.SourceSlice>();var sources=new LinkedHashSet<SourceDependency>();long bytes=0;
        for(long id:lease.request().documentIds().stream().distinct().sorted().toList()) {
            var content=knowledge.document(lease.actor(),lease.request().scope(),id);var doc=content.document();
            if(doc.activeProcessingRevision()==null)throw new LabException("INDEX_NOT_READY","资料须先完成结构入库");
            if(content.text().isBlank())throw new LabException("WORKFLOW_NEEDS_INPUT","所选资料正文为空");
            bytes+=TextWindow.count(content.text());if(bytes>policy.sourceBytes())throw new LabException("WORKFLOW_INPUT_TOO_LARGE","资料超过创建时保存的原文容量，请调整范围或服务器策略");
            sources.add(new SourceDependency(doc.knowledgeBaseId(),id,doc.documentVersion()));sources.addAll(content.sourceDependencies());
            int start=0;while(start<content.text().length()) {
                int end=TextWindow.end(content.text(),start,content.text().length(),policy.pageBytes());if(end<=start)throw changed();
                slices.add(new Learning.SourceSlice(String.format(Locale.ROOT,"p%04d",slices.size()+1),doc.knowledgeBaseId(),id,doc.documentVersion(),doc.activeProcessingRevision(),doc.title(),start,end,Learning.digest(content.text().substring(start,end))));start=end;
            }
        }
        if(sources.size()>32)throw new LabException("WORKFLOW_INPUT_TOO_LARGE","派生来源闭包超过32项");
        return new SourcePlan(binding,skill,ContentJson.hash(lease.request()),policy,slices,List.copyOf(sources));
    }
    private static <T>List<List<T>> partition(List<T> input,int maximum,Function<T,String> encode) {
        var output=new ArrayList<List<T>>();var current=new ArrayList<T>();int bytes=2;
        for(var item:input){int size=TextWindow.count(encode.apply(item))+1;if(size+2>maximum)throw new LabException("WORKFLOW_UNIT_TOO_LARGE","单条知识超过工作包容量，请降低事实粒度");
            if(bytes+size>maximum&&!current.isEmpty()){output.add(List.copyOf(current));current.clear();bytes=2;}current.add(item);bytes+=size;}
        if(!current.isEmpty())output.add(List.copyOf(current));return List.copyOf(output);
    }
    private static LabException changed(){return new LabException("CONTEXT_VERSION_CONFLICT","资料、处理代次、请求或执行绑定发生变化");}
}
