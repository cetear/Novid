package com.example.ailab.ai.rag;

import java.util.*;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/** 恢复正文中的显式章节目次；普通步骤列表不据此建立章节。 */
public final class NumberedOutline {
    public record Heading(int start,int level,String title) { }
    private static final Pattern NAMED=Pattern.compile("^(第[零〇一二三四五六七八九十百千万两0-9]+([篇编部章节])|(?i:part|chapter)\\s+[A-Z0-9IVX]+)[\\s、:：.．]*(.*)$");
    private static final Pattern DECIMAL=Pattern.compile("^(\\d+(?:\\.\\d+){0,4})[.．]?\\s+([^\\d\\s].*)$");
    private NumberedOutline() { }
    public static List<Heading> find(String text,IntPredicate allowed) {
        var named=new ArrayList<Heading>();var numeric=new ArrayList<Heading>();var numbers=new ArrayList<String>();
        int start=0;boolean fenced=false;String previous="";
        for(String line:text.split("\n",-1)) {
            String title=line.strip();
            if(title.startsWith("```")||title.startsWith("~~~"))fenced=!fenced;
            if(!fenced&&allowed.test(start)&&title.length()<=100&&!title.matches(".*[。！？；|].*")&&!title.matches(".*[.·…]{2,}\\s*\\d+\\s*$")&&!line.startsWith("    ")&&!title.startsWith(">")) {
                var explicit=NAMED.matcher(title);var decimal=DECIMAL.matcher(title);
                if(explicit.matches()&&!explicit.group(3).isBlank()) {
                    String unit=explicit.group(2);int level="节".equals(unit)?3:"章".equals(unit)||title.toLowerCase(Locale.ROOT).startsWith("chapter")?2:1;
                    named.add(new Heading(start,level,title));
                } else if(decimal.matches()&&(start==0||previous.isBlank())
                        &&!decimal.group(2).matches("(?i).*(版本|version|步骤|step).*")) {
                    numbers.add(decimal.group(1));numeric.add(new Heading(start,decimal.group(1).split("\\.").length,title));
                }
            }
            start+=line.length()+1;previous=line;
        }
        if(!named.isEmpty())return withoutRepeatedContents(text,named);
        // 至少有两个同父编号，并有可核验的父编号；单纯 1. / 2. 步骤清单不算目录。
        var valid=new HashSet<String>();
        for(String number:numbers)if(number.contains(".")) {
            String parent=number.substring(0,number.lastIndexOf('.'));
            long siblings=numbers.stream().filter(n->n.startsWith(parent+".")&&n.lastIndexOf('.')==parent.length()).count();
            if(numbers.contains(parent)&&siblings>=2){valid.add(parent);valid.add(number);}
        }
        var result=new ArrayList<Heading>();for(int i=0;i<numbers.size();i++)if(valid.contains(numbers.get(i)))result.add(numeric.get(i));
        return List.copyOf(result);
    }
    private static List<Heading> withoutRepeatedContents(String text,List<Heading> headings) {
        var marker=Pattern.compile("(?im)^\\s*(目录|contents|table of contents)\\s*$").matcher(text);
        if(!marker.find()||headings.get(0).start()<marker.end())return List.copyOf(headings);
        var seen=new HashSet<String>();
        for(int i=0;i<headings.size();i++)if(!seen.add(headings.get(i).title())) {
            String contents=text.substring(marker.end(),headings.get(i).start());
            for(int j=0;j<i;j++)contents=contents.replace(headings.get(j).title(),"");
            contents=contents.replaceAll("(?m)^.*[.·…]{2,}\\s*\\d+\\s*$","");
            if(contents.isBlank())return List.copyOf(headings.subList(i,headings.size()));
            break;
        }
        return List.copyOf(headings);
    }
}
