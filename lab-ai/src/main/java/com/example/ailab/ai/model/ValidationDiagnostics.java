package com.example.ailab.ai.model;

import com.example.ailab.contract.dto.*;
import com.example.ailab.ai.runtime.TracePayloadCapture;
import com.fasterxml.jackson.core.*;
import java.util.*;

/** 私有原文定位；异常与纠正提示仍只使用安全的 ValidationIssue。 */
final class ValidationDiagnostics {
    private record Location(int start,int end,String type) { }
    private ValidationDiagnostics() { }
    static ValidationDiagnostic build(String schema,String origin,String raw,List<ValidationIssue> issues) {
        if(raw==null)raw="";
        var locations=new HashMap<String,Location>();Integer line=null,column=null;int syntax=-1;
        int start=0,end=raw.length();
        while(start<end&&Character.isWhitespace(raw.charAt(start)))start++;
        while(end>start&&Character.isWhitespace(raw.charAt(end-1)))end--;
        if(raw.startsWith("```",start)) {
            int newline=raw.indexOf('\n',start);
            if(newline>=0&&raw.substring(0,end).endsWith("```")) { start=newline+1;end-=3; }
        }
        try(var parser=new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(raw.length()>524288?"":raw.substring(start,end))) {
            if(parser.nextToken()!=null)scan(parser,"",start,locations,0);
            if(parser.nextToken()!=null)throw new IllegalArgumentException();
        } catch(JsonProcessingException error) {
            var location=error.getLocation();
            syntax=(int)Math.min(raw.length(),Math.max(0,start+location.getCharOffset()));
            line=1;column=1;
            for(int i=0;i<syntax;i++) { if(raw.charAt(i)=='\n') {line++;column=1;}else column++; }
        } catch(Exception ignored) { }
        var items=new ArrayList<ValidationDiagnostic.Item>();
        for(var issue:issues) {
            String pointer=pointer(issue.path());var location=locations.get(pointer);
            String basis="TOKEN_RANGE";String type="MISSING";Integer from=null,to=null;
            if(issue.rule().equals("JSON_SYNTAX")&&syntax>=0) {from=Math.max(0,syntax-120);to=Math.min(raw.length(),syntax+120);type="INVALID_JSON";basis="SYNTAX_WINDOW";}
            else if(location!=null) {from=location.start();to=location.end();type=location.type();}
            else {
                int slash=pointer.lastIndexOf('/');var parent=locations.get(slash<0?"":pointer.substring(0,slash));
                if(parent!=null) {from=parent.start();to=parent.end();basis="PARENT_RANGE";}
                else {from=0;to=raw.length();type="UNKNOWN";basis="ROOT_FALLBACK";}
            }
            int a=Math.max(0,Math.min(raw.length(),from)),b=Math.max(a,Math.min(raw.length(),to));
            if(a>0&&a<raw.length()&&Character.isLowSurrogate(raw.charAt(a)))a--;
            if(b>0&&b<raw.length()&&Character.isHighSurrogate(raw.charAt(b-1)))b++;
            boolean truncated=b-a>500;
            int head=Math.min(b,a+300),tail=Math.max(a,b-180);
            if(head>0&&head<raw.length()&&Character.isHighSurrogate(raw.charAt(head-1)))head--;
            if(tail>0&&tail<raw.length()&&Character.isLowSurrogate(raw.charAt(tail)))tail++;
            String excerpt=truncated?raw.substring(a,head)+"\n…\n"+raw.substring(tail,b):raw.substring(a,b);
            items.add(new ValidationDiagnostic.Item(issue,pointer,type,TracePayloadCapture.redact(excerpt),(long)a,(long)b,truncated,
                    issue.rule().equals("JSON_SYNTAX")?line:null,issue.rule().equals("JSON_SYNTAX")?column:null,basis));
        }
        return new ValidationDiagnostic(schema,origin,items,issues.size()>=32,issues.stream().anyMatch(i->Set.of("JSON_TYPE","JSON_FIELDS","JSON_SYNTAX","JSON_SIZE").contains(i.rule())));
    }
    private static void scan(JsonParser parser,String pointer,int base,Map<String,Location> locations,int depth) throws java.io.IOException {
        int start=(int)parser.currentTokenLocation().getCharOffset()+base;
        JsonToken token=parser.currentToken();
        if(depth>64)parser.skipChildren();
        else if(token==JsonToken.START_OBJECT) {
            while(parser.nextToken()!=JsonToken.END_OBJECT) {
                if(parser.currentToken()==null)throw new java.io.IOException();
                String field=parser.currentName();parser.nextToken();scan(parser,pointer+"/"+field.replace("~","~0").replace("/","~1"),base,locations,depth+1);
            }
        } else if(token==JsonToken.START_ARRAY) {
            int index=0;while(parser.nextToken()!=JsonToken.END_ARRAY) { if(parser.currentToken()==null)throw new java.io.IOException();scan(parser,pointer+"/"+index++,base,locations,depth+1); }
        } else parser.getText();
        locations.put(pointer,new Location(start,(int)parser.currentLocation().getCharOffset()+base,
                token==JsonToken.START_OBJECT?"OBJECT":token==JsonToken.START_ARRAY?"ARRAY":token==JsonToken.VALUE_STRING?"STRING":token==JsonToken.VALUE_NULL?"NULL":token.isNumeric()?"NUMBER":"BOOLEAN"));
    }
    private static String pointer(String path) {
        if(path.equals("$"))return "";
        return "/"+path.replaceFirst("^\\$\\.","").replaceAll("\\[([0-9]+)\\]", ".$1").replace(".","/");
    }
}
