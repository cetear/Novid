package com.example.ailab.ai.orchestration.media;

import com.example.ailab.ai.model.StructuredSchema;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;
import java.util.*;

/** 计划／角色／质检程序验证，模型不能定义执行代码、权限、支付或任意URL。 */
public final class MediaSchemas {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final Map<String,String> ROLES=Map.of("research","ResearchWorker","content","PresentationContentWorker",
            "layout","PresentationLayoutWorker","visual","VisualResearchWorker","script","VideoScriptWorker",
            "direction","SceneDirectorWorker","review","TeachingReviewWorker");
    /** Schema工具类不持模型、用户或可变上下文。 */
    private MediaSchemas(){ }
    /** 有限动作、角色、拓扑及必要依赖都由程序核验。 */
    public static List<Media.Step> validatePlan(Media.Plan plan,String taskType) {
        if(plan.planVersion()<1||plan.planVersion()>2||!plan.schemaVersion().equals("media-plan-s09-v1")||plan.steps().size()>8||plan.steps().size()<3)throw invalid();
        var ids=new HashMap<String,Media.Step>();var actions=new HashMap<String,Media.Step>();
        boolean video=taskType.equals("NOTES_VIDEO");
        Set<String> allowed=video?Set.of("research","script","direction","review"):Set.of("research","content","layout","visual","review");
        for(var s:plan.steps()) {
            if(!s.stepId().matches("[a-z][a-z0-9_]{0,31}")||!allowed.contains(s.action())||!Objects.equals(ROLES.get(s.action()),s.agentId())
                    ||ids.put(s.stepId(),s)!=null||actions.put(s.action(),s)!=null||s.dependsOn().size()>7||new HashSet<>(s.dependsOn()).size()!=s.dependsOn().size()
                    ||s.inputRefs().size()>8||!Set.of("ALWAYS","HAS_WEB_IMAGES").contains(s.when())||!s.completionCondition().equals("VALID_TYPED_RESULT"))throw invalid();
            if(!s.when().equals("ALWAYS")&&!s.action().equals("visual"))throw invalid();
        }
        String first=video?"script":"content",second=video?"direction":"layout";
        if(!actions.keySet().containsAll(Set.of(first,second,"review")))throw invalid();
        if(!actions.get(second).dependsOn().contains(actions.get(first).stepId()))throw invalid();
        for(var s:plan.steps()) {
            if(s.dependsOn().contains(s.stepId())||s.dependsOn().stream().anyMatch(d->!ids.containsKey(d))
                    ||s.inputRefs().stream().anyMatch(r->!r.equals("SOURCE")&&!s.dependsOn().contains(r)))throw invalid();
        }
        var ordered=new ArrayList<Media.Step>();var done=new HashSet<String>();
        while(ordered.size()<plan.steps().size()) {
            var ready=plan.steps().stream().filter(s->!done.contains(s.stepId())&&done.containsAll(s.dependsOn())).toList();
            if(ready.isEmpty())throw invalid();for(var s:ready){ordered.add(s);done.add(s.stepId());}
        }
        var review=actions.get("review");
        for(var s:plan.steps())if(!s.action().equals("review")&&!ancestors(review,ids,new HashSet<>()).contains(s.stepId()))throw invalid();
        return List.copyOf(ordered);
    }
    /** 质检必须汇合真实所有前序结果，不能仅看无关步骤后批准草稿。 */
    private static Set<String> ancestors(Media.Step step,Map<String,Media.Step> ids,Set<String> out) {
        for(String id:step.dependsOn())if(out.add(id))ancestors(ids.get(id),ids,out);return out;
    }
    /** 每页／镜头稳定ID、白名单版式和有效引用，PPT最多12页，视频最多6镜头。 */
    public static void validateUnits(List<Media.Unit> units,String taskType,Set<String> refs) {
        if(units.isEmpty()||units.size()>(taskType.equals("NOTES_VIDEO")?6:12))throw invalid();
        var ids=new HashSet<String>();
        for(var u:units) {
            if(!u.unitId().matches("(slide|shot)-[1-9][0-9]?")||!ids.add(u.unitId())||u.title()==null||u.title().isBlank()||u.title().length()>200
                    ||u.text()==null||!taskType.equals("NOTES_VIDEO")&&u.text().isBlank()||u.text().length()>3000||u.notes()==null||u.notes().length()>1500
                    ||!Set.of("TITLE","TEXT","TWO_COLUMN","IMAGE_TEXT","SCENE").contains(u.layout())
                    ||!Set.of("NONE","GENERATED","WEB_SEARCH").contains(u.imageMode())||u.imagePrompt()==null||u.imagePrompt().length()>1000
                    ||u.references().isEmpty()||u.references().stream().anyMatch(r->!refs.contains(r))||u.seconds()<0||u.seconds()>90)throw invalid();
            if(taskType.equals("NOTES_VIDEO")&&(!u.unitId().startsWith("shot-")||u.seconds()<1||!u.imageMode().equals("NONE")))throw invalid();
            if(!taskType.equals("NOTES_VIDEO")&&!u.unitId().startsWith("slide-"))throw invalid();
        }
        if(units.stream().filter(u->!u.imageMode().equals("NONE")).count()>8)throw invalid();
    }
    /** 真实Planner Schema，包含动作和依赖而非只返回大纲。 */
    public static final class PlanSchema implements StructuredSchema<Media.Plan> {
        private final String task;private final int version;
        /** 服务端绑定类型和计划版本，模型不能借新版扩大预算。 */
        public PlanSchema(String task,int version){this.task=task;this.version=version;}
        /** 框架提供方Schema与本地严格校验共用。 */
        public JsonSchema schema(){return JsonSchema.builder().name("media_plan_s09").rootElement(JsonObjectSchema.builder()
                .addIntegerProperty("planVersion").addStringProperty("schemaVersion").addProperty("steps",JsonArraySchema.builder().items(stepSchema()).build())
                .required("planVersion","schemaVersion","steps").additionalProperties(false).build()).build();}
        /** 指令只给白名单协议，不返回隐藏思维链。 */
        public String instruction(Set<String> refs){return "只输出JSON：planVersion="+version+",schemaVersion=media-plan-s09-v1,steps数组最多8。"
                +"节点字段仅stepId,action,agentId,dependsOn,inputRefs,when,completionCondition。角色映射："+ROLES
                +"。PPT必需content/layout/review，视频必需script/direction/review；research及visual按需要选择。"
                +"layout必须依赖content，direction必须依赖script；review汇合所有前序。stepId稳定英文标识，inputRefs仅SOURCE或依赖stepId；"
                +"when仅ALWAYS或visual可用HAS_WEB_IMAGES；completionCondition=VALID_TYPED_RESULT。按需求选择不同动作、依赖和输入，不输出权限、URL、密钥、批准、预算或代码。";}
        /** 重复键、未知字段、循环或非法动作一律结构错误，受统一一次结构修复上限约束。 */
        public Media.Plan validate(String text,Set<String> refs){try{var p=JSON.readValue(text,Media.Plan.class);if(p.planVersion()!=version||text.length()>16000)throw invalid();validatePlan(p,task);return p;}catch(Exception e){throw invalid();}}
    }
    /** 内容／布局／脚本／导演与Reviewer使用有版本类型化结果。 */
    public static final class ResultSchema implements StructuredSchema<Media.WorkerResult> {
        private final Media.Step step;private final String task,inputHash;private final Set<String> references;private final List<Media.Step> plan;
        /** 程序绑定角色及输入摘要，不让模型伪造已执行事实。 */
        public ResultSchema(Media.Step step,String task,String inputHash,Set<String> references,List<Media.Step> plan){this.step=step;this.task=task;this.inputHash=inputHash;this.references=references;this.plan=plan;}
        /** units及review字段结构明确，所有未知字段拒绝。 */
        public JsonSchema schema(){return JsonSchema.builder().name("media_worker_s09").rootElement(JsonObjectSchema.builder()
                .addStringProperty("stepId").addStringProperty("agentId").addStringProperty("inputHash")
                .addProperty("units",JsonArraySchema.builder().items(unitSchema()).build()).addProperty("review",reviewSchema())
                .required("stepId","agentId","inputHash","units","review").additionalProperties(false).build()).build();}
        /** 稳定slideId／shotId协作，Reviewer只评价它实际收到的文字证据。 */
        public String instruction(Set<String> refs){return "只输出JSON字段stepId="+step.stepId()+",agentId="+step.agentId()+",inputHash="+inputHash
                +",units数组,review对象。单位字段仅unitId,title,text,notes,layout,imageMode,imagePrompt,references,seconds。"
                +"PPT unitId=slide-1等，视频shot-1等，与前序ID对应。版式TITLE/TEXT/TWO_COLUMN/IMAGE_TEXT/SCENE；imageMode=NONE/GENERATED/WEB_SEARCH，视频用NONE。"
                +"text为逐页正文或完整台词，notes为备注或镜头动作，references为合法标签字符串数组："+references
                +"。所有生成角色review={decision:ACCEPT,issues:[]}；review角色units=[]，review={decision:ACCEPT/REPAIR/NEEDS_USER/FAIL,issues:[{code,stepId,unitId,evidence,suggestion}]}。"
                +"定位具体计划stepId和单位ID，至多8问题；不声称看过图片／视频，不批准付费。布局／导演输出必须覆盖对应内容／脚本的全部稳定ID。";}
        /** 结构与来源守卫优先于模型自报ACCEPT，非法质检定位不能触发任意节点重放。 */
        public Media.WorkerResult validate(String text,Set<String> refs){try{
            if(text.length()>50000)throw invalid();var r=JSON.readValue(text,Media.WorkerResult.class);
            if(!r.stepId().equals(step.stepId())||!r.agentId().equals(step.agentId())||!r.inputHash().equals(inputHash)||r.review()==null||!r.webCandidates().isEmpty()||!r.sourceDependencies().isEmpty())throw invalid();
            if(step.action().equals("review")) {
                if(!r.units().isEmpty()||!Set.of("ACCEPT","REPAIR","NEEDS_USER","FAIL").contains(r.review().decision())||r.review().issues().size()>8)throw invalid();
                for(var i:r.review().issues())if(!i.code().matches("[A-Z_]{1,64}")||plan.stream().noneMatch(s->s.stepId().equals(i.stepId())&&!s.action().equals("review"))
                        ||!i.unitId().matches("(slide|shot)-[1-9][0-9]?")||i.suggestion()==null||i.suggestion().length()>1000||i.evidence()==null||i.evidence().length()>1000)throw invalid();
                if(r.review().decision().equals("REPAIR")&&r.review().issues().isEmpty())throw invalid();
            } else {validateUnits(r.units(),task,references);if(!r.review().decision().equals("ACCEPT")||!r.review().issues().isEmpty())throw invalid();}
            return r;
        }catch(Exception e){throw invalid();}}
    }
    /** 服务端固定节点Schema，无法引入任意动作实现。 */
    private static JsonObjectSchema stepSchema(){return JsonObjectSchema.builder().addStringProperty("stepId").addStringProperty("action").addStringProperty("agentId")
            .addProperty("dependsOn",strings()).addProperty("inputRefs",strings()).addStringProperty("when").addStringProperty("completionCondition")
            .required("stepId","action","agentId","dependsOn","inputRefs","when","completionCondition").additionalProperties(false).build();}
    /** 白名单单位结构，二进制素材地址不由模型决定。 */
    private static JsonObjectSchema unitSchema(){return JsonObjectSchema.builder().addStringProperty("unitId").addStringProperty("title").addStringProperty("text").addStringProperty("notes")
            .addStringProperty("layout").addStringProperty("imageMode").addStringProperty("imagePrompt").addProperty("references",strings()).addIntegerProperty("seconds")
            .required("unitId","title","text","notes","layout","imageMode","imagePrompt","references","seconds").additionalProperties(false).build();}
    /** 固定质检问题字段，不接受执行表达式。 */
    private static JsonObjectSchema reviewSchema(){return JsonObjectSchema.builder().addStringProperty("decision").addProperty("issues",JsonArraySchema.builder().items(JsonObjectSchema.builder()
            .addStringProperty("code").addStringProperty("stepId").addStringProperty("unitId").addStringProperty("evidence").addStringProperty("suggestion")
            .required("code","stepId","unitId","evidence","suggestion").additionalProperties(false).build()).build()).required("decision","issues").additionalProperties(false).build();}
    /** 有限字符串引用数组，语义由程序复核。 */
    private static JsonArraySchema strings(){return JsonArraySchema.builder().items(JsonStringSchema.builder().build()).build();}
    /** 所有结构问题统一进入原预算的一次修复，不放宽业务规则。 */
    private static LabException invalid(){return new LabException("MODEL_STRUCTURED_INVALID","媒体计划、引用、单位或角色结果结构不合法");}
}
