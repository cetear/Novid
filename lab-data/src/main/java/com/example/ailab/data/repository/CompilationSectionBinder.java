package com.example.ailab.data.repository;

import com.example.ailab.contract.dto.*;
import java.util.*;

/** 将逻辑小节与解析章节的自有区间相交；嵌套标题不产生重复父章覆盖。 */
public final class CompilationSectionBinder {
    private CompilationSectionBinder() { }
    public static List<Compilation.ParsedBinding> bind(String text,List<Compilation.SectionMapping> logical,
            List<SectionSnapshot> parsed,long revision,String mappingHash) {
        var ordered=parsed.stream().sorted(Comparator.comparingInt(SectionSnapshot::ordinal)).toList();
        if(ordered.isEmpty()||ordered.get(0).startOffset()!=0||ordered.get(0).endOffset()!=text.length())throw ContentReading.invalid();
        var result=new ArrayList<Compilation.ParsedBinding>();var ids=new HashSet<String>();int last=0;
        for(var m:logical) {
            if(!ids.add(m.sectionId())||m.startOffset()<last||m.endOffset()<=m.startOffset()||m.endOffset()>text.length()
                    ||!TextWindow.boundary(text,m.startOffset())||!TextWindow.boundary(text,m.endOffset()))throw ContentReading.invalid();
            int cursor=m.startOffset();
            for(int i=0;i<ordered.size();i++) {
                var s=ordered.get(i);int end=i+1<ordered.size()?ordered.get(i+1).startOffset():text.length();
                if(end<s.startOffset()||end>s.endOffset())throw ContentReading.invalid();
                int from=Math.max(m.startOffset(),s.startOffset()),to=Math.min(m.endOffset(),end);
                if(from>=to)continue;
                if(cursor!=from)throw ContentReading.invalid();
                result.add(new Compilation.ParsedBinding(m.chapterId(),m.sectionId(),s.sectionId(),revision,from,to,mappingHash));cursor=to;
            }
            if(cursor!=m.endOffset())throw ContentReading.invalid();last=m.endOffset();
        }
        return List.copyOf(result);
    }
}
