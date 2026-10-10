package com.example.ailab.contract.dto;

import java.util.*;
import java.util.function.Function;

/** 层级只决定展示顺序；容器章不取得子章事实的主要所有权。 */
public final class LearningHierarchy {
    private LearningHierarchy() { }
    public static Set<String> ancestors(Set<String> used,Map<String,String> parents) {
        var result=new LinkedHashSet<>(used);
        for(String id:used){var seen=new HashSet<String>();String parent=parents.getOrDefault(id,"");
            while(!parent.isEmpty()){LearningPlanRules.check(seen.add(parent)&&parents.containsKey(parent));result.add(parent);parent=parents.get(parent);}}
        return result;
    }
    public static <T> List<T> ordered(List<T> entries,Function<T,String> id,Function<T,String> parent) {
        var result=new ArrayList<T>();var visited=new HashSet<String>();
        append(entries,"",id,parent,result,visited);LearningPlanRules.check(result.size()==entries.size());return List.copyOf(result);
    }
    private static <T> void append(List<T> entries,String owner,Function<T,String> id,Function<T,String> parent,List<T> result,Set<String> visited) {
        for(var entry:entries)if(parent.apply(entry).equals(owner)){LearningPlanRules.check(visited.add(id.apply(entry)));result.add(entry);append(entries,id.apply(entry),id,parent,result,visited);}
    }
    public static int depth(String id,Map<String,String> parents) {
        int depth=0;var seen=new HashSet<String>();while(!id.isEmpty()){LearningPlanRules.check(seen.add(id)&&parents.containsKey(id));depth++;id=parents.get(id);}return depth;
    }
}
