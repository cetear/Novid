package com.example.ailab.ai.workflows.content;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.dto.LearningContent.*;
import java.util.*;
import java.util.stream.Collectors;
import com.example.ailab.ai.rag.NumberedOutline;

/** 来源目录负责定位，事实只在选定的原文章范围持有一次。 */
final class DocumentQuizChapters {
    private DocumentQuizChapters() { }

    static boolean reliable(LearningRuntime r) { return sections(r).size()>1; }

    private static List<SectionSnapshot> sections(LearningRuntime r) {
        var doc=r.reading().documents().get(0);if(doc.sections().size()>1)return doc.sections();
        // 兼容旧解析代次；只读本任务冻结的全文，原阅读片段和证据坐标不变。
        String text=r.reading().fragments().stream().map(f->r.fragmentText(f.source().id())).collect(Collectors.joining());
        var headings=NumberedOutline.find(text,at->r.reading().fragments().stream().noneMatch(f->f.source().startOffset()<=at&&at<f.source().endOffset()&&Set.of("CODE","LIST","QUOTE","TABLE").contains(f.blockType())));
        if(headings.isEmpty())return doc.sections();
        var root=doc.sections().get(0);var result=new ArrayList<SectionSnapshot>();result.add(root);
        var stack=new ArrayList<Integer>();
        for(int i=0;i<headings.size();i++) {
            var h=headings.get(i);while(!stack.isEmpty()&&headings.get(stack.get(stack.size()-1)).level()>=h.level())stack.remove(stack.size()-1);
            var parent=stack.isEmpty()?root:result.get(stack.get(stack.size()-1)+1);
            var ancestors=new ArrayList<>(parent.ancestorSectionIds());ancestors.add(parent.sectionId());int end=doc.textLength();
            for(int j=i+1;j<headings.size();j++)if(headings.get(j).level()<=h.level()){end=headings.get(j).start();break;}
            result.add(new SectionSnapshot(root.sectionId()+"n"+(i+1),parent.sectionId(),ancestors,parent.headingPath()+" / "+h.title(),i+1,h.start(),end));stack.add(i);
        }
        return List.copyOf(result);
    }

    static List<Chapter> build(LearningRuntime r) {
        var doc=r.reading().documents().get(0);
        var nodes=sections(r);var byId=nodes.stream().collect(Collectors.toMap(SectionSnapshot::sectionId,s->s));
        var root=nodes.get(0);var wrappers=new HashSet<String>();wrappers.add(root.sectionId());
        // 只剥离确为文档总标题的包装，单章或唯一主题不能因此消失。
        for(var node:nodes)if(!node.sectionId().equals(root.sectionId())&&wrappers.contains(node.parentSectionId())
                &&label(node,byId).equals(doc.title())&&nodes.stream().filter(s->Objects.equals(s.parentSectionId(),node.parentSectionId())).count()==1)
            wrappers.add(node.sectionId());
        var units=new HashSet<String>();
        for(var node:nodes) {
            if(wrappers.contains(node.sectionId()))continue;
            String title=label(node,byId);
            if(chapter(title))units.add(node.sectionId());
            else if(wrappers.contains(node.parentSectionId())) {
                // 篇级标题由其原文章承担命题；普通主题树默认在较上层聚合。
                if(nodes.stream().noneMatch(s->s.ancestorSectionIds().contains(node.sectionId())&&chapter(label(s,byId))))units.add(node.sectionId());
            }
        }
        // 同分支只在最外层真实章持有后代事实；独立子章可由明确 FOCUS 选中。
        var owners=new HashMap<String,String>();
        for(var node:nodes) {
            var path=new ArrayList<>(node.ancestorSectionIds());path.add(node.sectionId());
            String owner=path.stream().filter(units::contains).findFirst().orElse(node.sectionId());
            owners.put(node.sectionId(),owner);
        }
        var assigned=new LinkedHashMap<String,List<String>>();nodes.forEach(n->assigned.put(n.sectionId(),new ArrayList<>()));
        for(var fact:r.facts().values()) {
            int offset=r.quoteOffset(fact);
            var source=nodes.stream().filter(n->n.startOffset()<=offset&&offset<n.endOffset()).reduce((a,b)->b).orElse(root);
            assigned.get(owners.get(source.sectionId())).add(fact.id());
        }
        var kept=nodes.stream().filter(n->!wrappers.contains(n.sectionId())||!assigned.get(n.sectionId()).isEmpty()).map(SectionSnapshot::sectionId).collect(Collectors.toSet());
        var result=new ArrayList<Chapter>();
        for(var node:nodes)if(kept.contains(node.sectionId())) {
            String parent=node.parentSectionId();while(parent!=null&&!kept.contains(parent))parent=byId.get(parent).parentSectionId();
            var ranges=new ArrayList<Range>();
            for(var f:r.reading().fragments()) {
                int a=Math.max(f.source().startOffset(),node.startOffset()),b=Math.min(f.source().endOffset(),node.endOffset());
                if(a<b)ranges.add(new Range(f.source().id(),f.sectionId(),a,b));
            }
            result.add(new Chapter(node.sectionId(),parent==null?"":parent,result.size(),node.headingPath(),"PARSED",ranges,assigned.get(node.sectionId())));
        }
        return List.copyOf(result);
    }

    static List<Chapter> focus(LearningRuntime r,List<Chapter> chapters,List<String> selected) {
        var assigned=new LinkedHashMap<String,List<String>>();chapters.forEach(c->assigned.put(c.id(),new ArrayList<>()));
        for(var original:chapters)for(String id:original.itemIds()) {
            var fact=r.facts().get(id);int offset=r.quoteOffset(fact);
            // 重叠选区归属最外层选中节点，配额和题位都只计一次。
            var owner=chapters.stream().filter(c->selected.contains(c.id())&&c.ranges().stream().anyMatch(x->x.fragmentId().equals(fact.sourceId())&&x.startOffset()<=offset&&offset<x.endOffset())).findFirst().orElse(original);
            assigned.get(owner.id()).add(id);
        }
        return chapters.stream().map(c->new Chapter(c.id(),c.parentId(),c.order(),c.title(),c.origin(),c.ranges(),assigned.get(c.id()))).toList();
    }
    private static boolean chapter(String title){return title.matches("^(第[零〇一二三四五六七八九十百千万两0-9]+章|(?i:chapter)\\s+[A-Z0-9IVX]+).*" );}
    private static String label(SectionSnapshot node,Map<String,SectionSnapshot> byId) {
        var parent=byId.get(node.parentSectionId());String path=node.headingPath();
        return parent!=null&&path.startsWith(parent.headingPath())?path.substring(parent.headingPath().length()).replaceFirst("^\\s*/\\s*","").strip():path;
    }
}
