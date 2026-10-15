package com.example.ailab.ai.model;

import com.example.ailab.contract.dto.ContentTextRules;
import com.example.ailab.contract.dto.TextWindow;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.regex.Pattern;

/** 保守保留关键原句与显式条件；这是有限规则校验，不是开放语义等价证明。 */
final class SummaryTextGuard {
    record RequiredFact(String id,String text,boolean wholeClause) { }
    private static final Pattern CONDITIONS=Pattern.compile(
            "不|未|无|仅|只|除|若|如果|当|否则|必须|应当|需|例外|冲突|优先|随后|再|"
            +"\\b(if|unless|except|only|not|never|without|must|before|after)\\b",Pattern.CASE_INSENSITIVE);
    private final List<RequiredFact> required;
    private final List<RecordSchema.SummaryFact> facts;
    private final String evidence;

    SummaryTextGuard(String original,JsonNode context,List<RecordSchema.SummaryFact> facts) {
        this.facts=List.copyOf(facts);
        evidence=facts.isEmpty()?original:String.join("\n",facts.stream().map(f->f.content()+"\n"+f.quote()).toList());
        var texts=new LinkedHashMap<String,Boolean>();
        for(String clause:original.split("(?<=[。！？；!?;\\n])|(?<=\\.)(?=\\s|$)")) {
            String text=clause.strip().replaceAll("[。！？；.!?;]+$","");
            if((!ContentTextRules.numbers(text).isEmpty()||CONDITIONS.matcher(text).find())
                    &&ContentTextRules.supportedNumbers(text,evidence))texts.put(text,true);
        }
        for(String field:List.of("conditions","relations"))
            for(var value:context.path(field))if(value.isTextual()&&!value.asText().isBlank())texts.putIfAbsent(value.asText().strip(),false);
        var selected=new ArrayList<RequiredFact>();
        texts.forEach((text,whole)->selected.add(new RequiredFact("r"+(selected.size()+1),text,whole)));
        required=List.copyOf(selected);
    }
    List<RequiredFact> requiredFacts(){return required;}
    List<String> missing(String value) {
        return required.stream().filter(f->!contains(value,f)).map(RequiredFact::id).toList();
    }
    private static boolean contains(String value,RequiredFact fact) {
        for(int from=0;(from=value.indexOf(fact.text(),from))>=0;from++) {
            int end=from+fact.text().length();
            int before=from-1,after=end;
            while(before>=0&&Character.isWhitespace(value.charAt(before))&&!boundary(value.charAt(before)))before--;
            while(after<value.length()&&Character.isWhitespace(value.charAt(after))&&!boundary(value.charAt(after)))after++;
            if(!fact.wholeClause()||((before<0||boundary(value.charAt(before)))
                    &&(after==value.length()||boundary(value.charAt(after)))))return true;
        }
        return false;
    }
    private static boolean boundary(char value){return "。！？；.!?;\n\r".indexOf(value)>=0;}
    boolean supportedNumbers(String value){return ContentTextRules.supportedNumbers(value,evidence);}

    /** 只使用完整的绑定事实、原文与必需原句，不裁切、不让模型选择删去哪条事实。 */
    Optional<String> fallback(int maximum) {
        if(facts.isEmpty())return Optional.empty();
        var text=new ArrayList<String>();
        for(var fact:facts) {
            if(fact.content().isBlank()||fact.quote().isBlank())return Optional.empty();
            if(fact.quote().contains(fact.content()))text.add(fact.quote());
            else {
                text.add(fact.content());
                if(!fact.content().contains(fact.quote()))text.add("原文："+fact.quote());
            }
        }
        required.forEach(f->{if(text.stream().noneMatch(t->contains(t,f)))text.add(f.text());});
        String result=String.join("；",text);
        return TextWindow.count(result)<=maximum&&missing(result).isEmpty()&&supportedNumbers(result)
                ?Optional.of(result):Optional.empty();
    }
}
