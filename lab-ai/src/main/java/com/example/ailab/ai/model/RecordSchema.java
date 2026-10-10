package com.example.ailab.ai.model;

import com.example.ailab.contract.error.LabException;
import com.example.ailab.contract.dto.ValidationIssue;
import com.example.ailab.contract.dto.TextWindow;
import com.fasterxml.jackson.databind.*;
import dev.langchain4j.model.chat.request.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

/** record形状和字段限制同时用于提示与本地验证；诊断仅引用程序定义的字段。 */
public final class RecordSchema<T> implements StructuredSchema<T> {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RecordSchema.class);
    public enum Reason { JSON_SIZE, JSON_SYNTAX, JSON_FIELDS, JSON_TYPE, CONTENT_CONSTRAINT,
        FACT_STATUS, FACT_COUNT, FACT_ID, FACT_SOURCE, FACT_QUOTE, FACT_TEXT_SIZE }
    private static final ObjectMapper JSON=new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
    private static final ThreadLocal<List<ValidationIssue>> ACTIVE=new ThreadLocal<>();
    private static final int MAX_ISSUES=32;
    private static final class Violation extends IllegalArgumentException {
        final ValidationIssue issue;
        Violation(ValidationIssue issue){this.issue=issue;}
    }
    public record FieldRule(String path,int maximum,String unit,boolean nonBlank,List<String> allowed) {
        public FieldRule {
            if(path==null||!path.matches("\\$|[a-zA-Z][a-zA-Z0-9]*(\\[\\])?(\\.[a-zA-Z][a-zA-Z0-9]*(\\[\\])?)*")||maximum<0)
                throw new IllegalArgumentException("字段限制路径无效");
            allowed=List.copyOf(allowed);
        }
        public static FieldRule utf8(String path,int maximum){return new FieldRule(path,maximum,"UTF-8_BYTES",true,List.of());}
        public static FieldRule optionalUtf8(String path,int maximum){return new FieldRule(path,maximum,"UTF-8_BYTES",false,List.of());}
        public static FieldRule items(String path,int maximum){return new FieldRule(path,maximum,"ITEMS",false,List.of());}
        public static FieldRule choices(String path,Set<String> allowed){return new FieldRule(path,0,"ENUM",false,new TreeSet<>(allowed).stream().toList());}
    }
    public static void require(boolean condition,Reason reason){require(condition,reason,"$","遵守当前业务协议");}
    public static void require(boolean condition,Reason reason,String path,String expected) {
        if(!condition)add(new ValidationIssue(path,reason.name(),null,null,"",expected));
    }
    public static void maximum(boolean condition,Reason reason,String path,int actual,int maximum,String unit) {
        if(!condition)add(new ValidationIssue(path,reason.name(),actual,maximum,unit,"不得超过上限"));
    }
    private static void add(ValidationIssue issue) {
        var issues=ACTIVE.get();
        if(issues==null)throw new Violation(issue);
        if(issues.size()<MAX_ISSUES)issues.add(issue);
    }
    private final Class<T> type;
    private final String instruction;
    private final Consumer<T> validate;
    private final List<FieldRule> rules;
    private final String contract;
    public RecordSchema(Class<T> type,String instruction,Consumer<T> validate){this(type,instruction,List.of(),validate);}
    public RecordSchema(Class<T> type,String instruction,List<FieldRule> rules,Consumer<T> validate) {
        this.type=type;this.instruction=instruction;this.rules=List.copyOf(rules);this.validate=validate;
        this.contract=contract(type).toString();
    }
    public RecordSchema<T> withChecks(Consumer<T> checks){return new RecordSchema<>(type,instruction,rules,validate.andThen(checks));}
    public List<FieldRule> fieldRules(){return rules;}
    private JsonNode contract(Type type) {
        var node=JSON.createObjectNode();
        if(type==int.class||type==Integer.class)return node.put("type","integer");
        if(type==String.class)return node.put("type","string");
        if(type instanceof ParameterizedType list&&list.getRawType()==List.class){node.put("type","array");node.set("items",contract(list.getActualTypeArguments()[0]));return node;}
        if(type instanceof Class<?> record&&record.isRecord()){
            node.put("type","object").put("additionalProperties",false);
            var fields=node.putObject("properties");var required=node.putArray("required");
            for(var field:record.getRecordComponents()){fields.set(field.getName(),contract(field.getGenericType()));required.add(field.getName());}
            return node;
        }
        throw new IllegalArgumentException("未支持的固定工作流JSON字段类型");
    }
    public JsonSchema schema(){return JsonSchema.builder().name(type.getSimpleName()).rootElement(element(type)).build();}
    private JsonSchemaElement element(Type type) {
        if(type==int.class||type==Integer.class)return JsonIntegerSchema.builder().build();
        if(type==String.class)return JsonStringSchema.builder().build();
        if(type instanceof ParameterizedType list&&list.getRawType()==List.class)return JsonArraySchema.builder().items(element(list.getActualTypeArguments()[0])).build();
        if(type instanceof Class<?> record&&record.isRecord()){
            var object=JsonObjectSchema.builder().additionalProperties(false);var names=new ArrayList<String>();
            if(type==this.type&&!rules.isEmpty())object.description("字段限制（明确区分UTF-8字节、UTF-16长度与项数）："+rulesJson());
            for(var field:record.getRecordComponents()){names.add(field.getName());object.addProperty(field.getName(),element(field.getGenericType()));}
            return object.required(names).build();
        }
        throw new IllegalArgumentException("未支持的固定工作流JSON字段类型");
    }
    private void check(JsonNode value,Type type,String path) {
        if(value==null||value.isNull()){require(false,Reason.JSON_TYPE,path,"非null的必填字段");return;}
        if(type==int.class||type==Integer.class){require(value.isIntegralNumber()&&value.canConvertToInt(),Reason.JSON_TYPE,path,"integer");return;}
        if(type==String.class){require(value.isTextual(),Reason.JSON_TYPE,path,"string");return;}
        if(type instanceof ParameterizedType list&&list.getRawType()==List.class){
            if(!value.isArray()){require(false,Reason.JSON_TYPE,path,"array");return;}
            for(int i=0;i<value.size();i++)check(value.get(i),list.getActualTypeArguments()[0],path+"["+i+"]");return;
        }
        if(type instanceof Class<?> record&&record.isRecord()){
            if(!value.isObject()){require(false,Reason.JSON_TYPE,path,"object");return;}
            require(value.size()==record.getRecordComponents().length,Reason.JSON_FIELDS,path,"仅允许这些必填字段："+Arrays.stream(record.getRecordComponents()).map(RecordComponent::getName).toList());
            for(var field:record.getRecordComponents())check(value.get(field.getName()),field.getGenericType(),path.equals("$")?field.getName():path+"."+field.getName());return;
        }
        throw new IllegalArgumentException();
    }
    private void field(JsonNode node,String[] segments,int position,String path,FieldRule rule) {
        if(position==segments.length){
            int size=rule.unit().equals("ITEMS")?node.size():rule.unit().equals("UTF-8_BYTES")?TextWindow.count(node.isContainerNode()?node.toString():node.asText()):node.asText().length();
            if(rule.nonBlank()&&node.asText().isBlank())add(new ValidationIssue(path,"NON_BLANK",size,rule.maximum(),rule.unit(),"不能为空"));
            if(!rule.unit().equals("ENUM")&&size>rule.maximum())add(new ValidationIssue(path,"MAXIMUM",size,rule.maximum(),rule.unit(),"不得超过上限"));
            if(rule.unit().equals("ENUM")&&!rule.allowed().contains(node.asText()))add(new ValidationIssue(path,"ENUM",null,null,"ENUM",String.join("/",rule.allowed())));
            return;
        }
        String segment=segments[position];boolean array=segment.endsWith("[]");String name=array?segment.substring(0,segment.length()-2):segment;
        var child=node.get(name);if(child==null||child.isNull())return;String next=path.isEmpty()?name:path+"."+name;
        if(array){for(int i=0;i<child.size();i++)field(child.get(i),segments,position+1,next+"["+i+"]",rule);}
        else field(child,segments,position+1,next,rule);
    }
    private String rulesJson(){try{return JSON.writeValueAsString(rules);}catch(Exception failure){throw new IllegalStateException(failure);}}
    public String instruction(Set<String> references) {
        return instruction+"\n仅输出满足以下JSON Schema的对象，全部字段必填，不允许额外字段、null或Markdown围栏：\n"+contract
                +(rules.isEmpty()?"":"\n字段限制（UTF-8_BYTES为UTF-8字节，UTF-16_UNITS为Java字符数，ITEMS为数组项数）：\n"+rulesJson());
    }
    public T validate(String text,Set<String> references) {
        var previous=ACTIVE.get();var issues=new ArrayList<ValidationIssue>();ACTIVE.set(issues);
        try {
            if(text==null||text.length()>200000){maximum(false,Reason.JSON_SIZE,"$",text==null?0:text.length(),200000,"UTF-16_UNITS");throw invalid(Reason.JSON_SIZE,issues);}
            var tree=JSON.readTree(text);check(tree,type,"$");
            if(!issues.isEmpty())throw invalid(Reason.valueOf(issues.get(0).rule()),issues);
            for(var rule:rules){if(rule.path().equals("$"))field(tree,new String[0],0,"$",rule);else field(tree,rule.path().split("\\."),0,"",rule);}
            var value=JSON.readValue(text,type);validate.accept(value);
            if(!issues.isEmpty())throw invalid(issueReason(issues.get(0)),issues);
            return value;
        } catch(LabException failure){throw failure;}
        catch(Violation failure){throw invalid(issueReason(failure.issue),List.of(failure.issue));}
        catch(com.fasterxml.jackson.core.JsonProcessingException failure){throw invalid(Reason.JSON_SYNTAX,List.of(new ValidationIssue("$","JSON_SYNTAX",null,null,"","合法且无重复字段的单个JSON对象")));}
        catch(Exception failure){throw invalid(Reason.CONTENT_CONSTRAINT,issues.isEmpty()?List.of(new ValidationIssue("$","CONTENT_CONSTRAINT",null,null,"","遵守当前业务协议")):issues);}
        finally{if(previous==null)ACTIVE.remove();else ACTIVE.set(previous);}
    }
    private Reason issueReason(ValidationIssue issue){
        if(type.getSimpleName().equals("Facts")) {
            if(issue.path().equals("items")&&issue.rule().equals("MAXIMUM"))return Reason.FACT_COUNT;
            if(issue.path().equals("status")&&issue.rule().equals("ENUM"))return Reason.FACT_STATUS;
            if(issue.path().matches("items\\[[0-9]+\\]\\.(category|content|quote)")&&Set.of("MAXIMUM","NON_BLANK").contains(issue.rule()))return Reason.FACT_TEXT_SIZE;
        }
        try{return Reason.valueOf(issue.rule());}catch(IllegalArgumentException unknown){return Reason.CONTENT_CONSTRAINT;}
    }
    private LabException invalid(Reason reason,List<ValidationIssue> issues) {
        LOG.warn("event=model.structured_invalid schema={} reason={} fields={}",type.getSimpleName(),reason,issues.stream().map(ValidationIssue::path).toList());
        try{return new LabException("MODEL_STRUCTURED_INVALID","固定工作流输出结构、范围或来源不合法 reason="+reason+" details="+JSON.writeValueAsString(issues),issues);}
        catch(Exception failure){throw new IllegalStateException(failure);}
    }
    public String repairInstruction(LabException failure) {
        var reason=reason(failure).orElse(Reason.CONTENT_CONSTRAINT);
        String hint=switch(reason){
            case FACT_QUOTE->"quote必须逐字复制输入中的连续原文，保留空格与换行，不可改写或拼接。";
            case FACT_ID->"id必须从指定prefix_i1起连续编号且不重复。";
            case FACT_SOURCE->"sourceId必须与本次输入指定的sourceId完全一致。";
            case FACT_COUNT->"items不得超过本批上限，容量不足时返回OVERFLOW。";
            default->"按同一JSON Schema重新生成，修正全部列出的问题，保持既定身份、来源和目标。";
        };
        try{return "\n上次输出校验失败 reason="+reason+"。"+hint+"\n字段问题："+JSON.writeValueAsString(failure.validationIssues());}
        catch(Exception invalid){throw new IllegalStateException(invalid);}
    }
    public static Optional<Reason> reason(LabException failure) {
        if(!failure.code().equals("MODEL_STRUCTURED_INVALID"))return Optional.empty();
        return Arrays.stream(Reason.values()).filter(r->failure.getMessage()!=null&&(failure.getMessage().equals("固定工作流输出结构、范围或来源不合法 reason="+r)
                ||failure.getMessage().startsWith("固定工作流输出结构、范围或来源不合法 reason="+r+" details="))).findFirst();
    }
}
