package com.example.ailab.ai.orchestration.rag;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
/** CommonMark AST 决定结构归属，围栏代码里的井号不会变成标题。 */
@Component
public class StructureParser {
    private final RagProperties config;
    private record HeadingAt(int start,int level,String title) {}
    private record Range(int start,int end) {}
    /** 使用唯一 lab.rag 参数表。 */
    public StructureParser(RagProperties config){this.config=config;}
    /** 版本化 ID 纳入文档、内容版本、处理代次与结构序号。 */
    public ParsedDocument parse(IngestionLease lease){
        String text=lease.text(),prefix="d"+lease.documentId()+"v"+lease.documentVersion()+"r"+lease.processingRevision();
        var headings=new ArrayList<HeadingAt>();var boundaries=new TreeSet<Integer>();boundaries.add(0);boundaries.add(text.length());
        if(!lease.format().equals("txt")){
            Node ast=Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(text);
            for(Node block=ast.getFirstChild();block!=null;block=block.getNext()){
                var spans=block.getSourceSpans();if(spans.isEmpty())continue;
                int start=spans.get(0).getInputIndex();var last=spans.get(spans.size()-1);int end=last.getInputIndex()+last.getLength();
                boundaries.add(start);boundaries.add(end);
                if(block instanceof Heading h)headings.add(new HeadingAt(start,h.getLevel(),TextContentRenderer.builder().build().render(h).strip()));
            }
        }
        var sections=new ArrayList<SectionSnapshot>();var parents=new ArrayList<ParentSnapshot>();var chunks=new ArrayList<ChunkSnapshot>();
        sections.add(new SectionSnapshot(prefix+"s0",null,List.of(),lease.title(),0,0,text.length()));
        var stack=new ArrayList<Integer>();stack.add(0);
        var ownRanges=new LinkedHashMap<Integer,Range>();int rootEnd=headings.isEmpty()?text.length():headings.get(0).start();ownRanges.put(0,new Range(0,rootEnd));
        for(int i=0;i<headings.size();i++){
            var h=headings.get(i);while(stack.size()>1&&headings.get(stack.get(stack.size()-1)-1).level()>=h.level())stack.remove(stack.size()-1);
            int number=i+1,parent=stack.get(stack.size()-1);var ancestors=stack.stream().map(n->prefix+"s"+n).toList();
            String path=sections.get(parent).headingPath()+" / "+h.title();int subtreeEnd=text.length();
            for(int j=i+1;j<headings.size();j++)if(headings.get(j).level()<=h.level()){subtreeEnd=headings.get(j).start();break;}
            sections.add(new SectionSnapshot(prefix+"s"+number,prefix+"s"+parent,ancestors,path,number,h.start(),subtreeEnd));
            ownRanges.put(number,new Range(h.start(),i+1<headings.size()?headings.get(i+1).start():text.length()));stack.add(number);
        }
        for(var entry:ownRanges.entrySet()){
            var section=sections.get(entry.getKey());var range=entry.getValue();String heading=take(section.headingPath(),config.headingPrefixMaxTokens()-1)+"\n";
            int parentOrdinal=0,sectionChunk=0,position=range.start();
            while(position<range.end()){
                int end=cut(text,position,range.end(),config.parentMaxTokens(),boundaries);String parentId=section.sectionId()+"p"+parentOrdinal;
                parents.add(new ParentSnapshot(parentId,section.sectionId(),parentOrdinal++,position,end));
                int cursor=position,index=0;while(cursor<end){
                    int next=cut(text,cursor,end,config.maxChunkTokens()-bytes(heading),boundaries);
                    String raw=text.substring(cursor,next);if(!raw.isBlank()){
                        String id=parentId+"c"+index;chunks.add(new ChunkSnapshot(id,section.sectionId(),parentId,sectionChunk++,index++,cursor,next,raw,heading+raw,hash(heading+raw)));
                        if(chunks.size()>5000)throw new LabException("DOCUMENT_LIMIT_EXCEEDED","切片超过 5000");
                    }
                    if(next==end)break;
                    // 仅在同章节／父段内重叠，边界始终落在 Unicode 码点上。
                    int overlap=back(text,next,cursor,config.overlapTokens());cursor=overlap>cursor?overlap:next;
                }
                position=end;
            }
        }
        if(chunks.isEmpty())throw new LabException("DOCUMENT_PARSE_FAILED","文档没有有效文本");
        return new ParsedDocument(sections,parents,chunks,hash("parser-v1/chunk-v1/UTF8_BYTE_ESTIMATED/"+config));
    }
    /** 先用语法块边界，再用完整行／中文句子，最后安全码点兜底。 */
    private int cut(String text,int start,int end,int byteLimit,NavigableSet<Integer> boundaries){
        int max=start,bytes=0;while(max<end){int cp=text.codePointAt(max),width=Character.charCount(cp),size=bytes(new String(Character.toChars(cp)));if(bytes+size>byteLimit)break;bytes+=size;max+=width;}
        if(max==start)throw new LabException("DOCUMENT_LIMIT_EXCEEDED","标题前缀未留下正文预算");if(max==end)return end;
        Integer block=boundaries.floor(max);if(block!=null&&block>start)return block;
        for(int i=max;i>start;i--){char c=text.charAt(i-1);if(c=='\n'||c=='。'||c=='！'||c=='？')return i;}
        return max;
    }
    /** 有限重叠不能割裂代理对，最多配置字节上界。 */
    private int back(String text,int end,int start,int limit){int p=end,size=0;while(p>start){int cp=text.codePointBefore(p),n=bytes(new String(Character.toChars(cp)));if(size+n>limit)break;size+=n;p-=Character.charCount(cp);}return p;}
    /** 截取标题前缀时保留完整 Unicode 字符。 */
    private String take(String s,int limit){int end=0,n=0;while(end<s.length()){int cp=s.codePointAt(end),size=bytes(new String(Character.toChars(cp)));if(n+size>limit)break;n+=size;end+=Character.charCount(cp);}return s.substring(0,end);}
    /** 已验证的保守计数口径，不声称是精确模型 Token。 */
    private int bytes(String s){return s.getBytes(StandardCharsets.UTF_8).length;}
    /** 同批次重试内容 hash 保持稳定。 */
    private String hash(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
