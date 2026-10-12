package com.example.ailab.data.repository;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import com.example.ailab.data.persistence.po.ModelTextSegment;

/** 只保存 JSON 字符串在原始响应中的位置和文本校验和，不复制模型正文。 */
public final class ModelTextIndex {
    private static final int SEGMENT_CHARS=8192;
    private static final ObjectMapper JSON=new ObjectMapper();
    public record View(long charCount,List<ModelTextSegment> segments) { }
    private ModelTextIndex() { }

    public static View build(String raw,long deadline) throws IOException {
        List<ModelTextSegment> chat=null;
        var responses=new LinkedHashMap<String,List<ModelTextSegment>>();var types=new HashMap<String,String>();
        try(var parser=new JsonFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).createParser(raw)) {
            while(parser.nextToken()!=null) {
                if(System.nanoTime()>deadline)throw new IOException("文本索引处理超时");
                if(parser.currentToken()!=JsonToken.VALUE_STRING)continue;
                String path=parser.getParsingContext().pathAsPointer().toString();
                if(path.equals("/choices/0/message/content"))chat=segments(parser,raw,deadline);
                else if(path.matches("/output/[0-9]+/content/[0-9]+/text"))responses.put(parent(path),segments(parser,raw,deadline));
                else if(path.matches("/output/[0-9]+/content/[0-9]+/type"))types.put(parent(path),parser.getText());
            }
        }
        var parts=new ArrayList<List<ModelTextSegment>>();
        if(chat!=null)parts.add(chat);
        else responses.forEach((path,segments)->{if("output_text".equals(types.get(path)))parts.add(segments);});
        if(parts.isEmpty())return null;
        var result=new ArrayList<ModelTextSegment>();long offset=0;
        for(int i=0;i<parts.size();i++) {
            if(i>0) {result.add(new ModelTextSegment(result.size(),offset++,1,-1,-1,hash("\n")));}
            for(var segment:parts.get(i)) {
                result.add(new ModelTextSegment(result.size(),offset,segment.charLength(),segment.rawStart(),segment.rawEnd(),segment.checksum()));
                offset+=segment.charLength();
            }
        }
        return new View(offset,List.copyOf(result));
    }
    private static String parent(String path) {return path.substring(0,path.lastIndexOf('/'));}
    private static List<ModelTextSegment> segments(JsonParser parser,String raw,long deadline) throws IOException {
        long tokenStart=parser.currentTokenLocation().getCharOffset();String value=parser.getText();
        int rawOffset=Math.toIntExact(tokenStart)+1;var segments=new ArrayList<ModelTextSegment>();
        for(int start=0;start<value.length();) {
            if(System.nanoTime()>deadline)throw new IOException("文本索引处理超时");
            int end=Math.min(value.length(),start+SEGMENT_CHARS);
            if(end<value.length()&&Character.isHighSurrogate(value.charAt(end-1)))end--;
            int rawStart=rawOffset;
            for(int at=start;at<end;at++)rawOffset=raw.charAt(rawOffset)=='\\'?rawOffset+(raw.charAt(rawOffset+1)=='u'?6:2):rawOffset+1;
            segments.add(new ModelTextSegment(segments.size(),start,end-start,rawStart,rawOffset,hash(value.substring(start,end))));start=end;
        }
        return segments;
    }
    public static String decode(String raw) throws IOException {return JSON.readValue("\""+raw+"\"",String.class);}
    public static String hash(String value) {return ModelPayloadCodec.hash(value.getBytes(StandardCharsets.UTF_8));}
}
