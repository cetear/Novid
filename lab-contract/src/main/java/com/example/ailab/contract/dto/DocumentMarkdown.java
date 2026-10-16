package com.example.ailab.contract.dto;

import org.commonmark.node.*;
import org.commonmark.parser.*;
import java.util.*;

/** 仅调整正文顶层标题的相对深度，源代码、引用及普通正文保持原样。 */
public final class DocumentMarkdown {
    private DocumentMarkdown() { }
    public static String below(String body,int parentLevel) {
        var ast=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(body);
        var headings=new ArrayList<Heading>();
        for(Node node=ast.getFirstChild();node!=null;node=node.getNext())if(node instanceof Heading h)headings.add(h);
        if(headings.isEmpty())return body;
        int minimum=headings.stream().mapToInt(Heading::getLevel).min().orElseThrow();var out=new StringBuilder(body);
        for(int i=headings.size()-1;i>=0;i--) {
            var h=headings.get(i);var spans=h.getSourceSpans();int start=spans.get(0).getInputIndex();
            var last=spans.get(spans.size()-1);int end=last.getInputIndex()+last.getLength();
            String raw=body.substring(start,end);String title=raw.split("\\R",2)[0].strip().replaceFirst("^#{1,6}\\s+","").replaceFirst("\\s+#+\\s*$","");
            int level=parentLevel+1+h.getLevel()-minimum;
            out.replace(start,end,level<=6?"#".repeat(level)+" "+title:"**"+title+"**");
        }
        return out.toString();
    }
}
