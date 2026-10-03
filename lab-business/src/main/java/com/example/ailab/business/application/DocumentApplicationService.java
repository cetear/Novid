package com.example.ailab.business.application;
import com.example.ailab.business.domain.KnowledgeAccessPolicy;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.DocumentStorePort;
import com.example.ailab.contract.error.LabException;
import org.springframework.stereotype.Service;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
/** 白名单 TXT/Markdown 上传，有限原文保存在 MySQL。 */
@Service
public class DocumentApplicationService {
    private final KnowledgeAccessPolicy policy;private final DocumentStorePort documents;
    /** 使用统一策略，不在上传组件另写管理员规则。 */
    public DocumentApplicationService(KnowledgeAccessPolicy policy,DocumentStorePort documents){this.policy=policy;this.documents=documents;}
    /** 字节、UTF-8、码点及格式检查完成后才登记入库。 */
    public DocumentSnapshot upload(UserContext actor,long baseId,String filename,String mime,byte[] bytes,String key){
        policy.writable(actor,baseId);policy.readable(actor,baseId);
        if(bytes.length==0||bytes.length>10485760)throw new LabException("DOCUMENT_LIMIT_EXCEEDED","文件须为 1 字节～10 MB");
        if(filename==null||filename.length()>200||filename.contains("/")||filename.contains("\\")||filename.indexOf(0)>=0)throw LabException.invalid("文件名不合法");
        int dot=filename.lastIndexOf('.');String format=dot<0?"":filename.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!Set.of("txt","md","markdown").contains(format))throw new LabException("UNSUPPORTED_DOCUMENT_TYPE","仅支持 TXT/Markdown");
        if(mime!=null&&!Set.of("text/plain","text/markdown","text/x-markdown","application/octet-stream").contains(mime.toLowerCase(Locale.ROOT)))throw new LabException("UNSUPPORTED_DOCUMENT_TYPE","文件 MIME 不匹配");
        String text;try{text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){throw new LabException("DOCUMENT_PARSE_FAILED","必须使用 UTF-8 文本");}
        if(text.startsWith("\uFEFF"))text=text.substring(1);validateText(filename,text);validateKey(key);
        return documents.create(actor,new UploadCommand(baseId,filename,format,text,key));
    }
    /** 范围由服务端计算，不直接传客户端 owner。 */
    public List<DocumentSnapshot> list(UserContext actor,ScopeRequest request,int page,int size){AccountApplicationService.page(page,size);return documents.list(policy.authorize(actor,request),page*size,size);}
    /** 显式资料详情允许 ADMIN 跨库，仍核验衍生来源。 */
    public DocumentContent read(UserContext actor,long id){return documents.read(policy.authorize(actor,new ScopeRequest(actor.role()==UserContext.Role.ADMIN?ScopeRequest.Mode.ALL:ScopeRequest.Mode.SELF,List.of(),null)),id);}
    /** 保留服务端来源约束，不允许编辑清除来源。 */
    public DocumentSnapshot revise(UserContext actor,long id,int version,String title,String text){validateText(title,text);var d=read(actor,id);policy.writable(actor,d.document().knowledgeBaseId());return documents.revise(actor,id,version,title,text);}
    /** 删除仅需当前身份、所有权和版本；不依赖正文及衍生来源的读取许可。
     * 数据端在同一事务内锁定 actor/base，并核验 owner/CAS，禁用库仍允许本人清理。
     */
    public void delete(UserContext actor,long id,int version){
        if(id<=0||version<=0)throw LabException.invalid("文档 ID 和版本必须为正数");
        documents.delete(actor,id,version);
    }
    /** 真实程序统计。 */
    public KnowledgeStatistics statistics(UserContext actor,ScopeRequest request){return documents.statistics(policy.authorize(actor,request));}
    /** 规范化文本不能包含二进制控制字符或无界正文。 */
    public static void validateText(String title,String text){if(title==null||title.isBlank()||title.length()>200||text==null||text.isBlank())throw LabException.invalid("标题正文不能为空");if(text.codePointCount(0,text.length())>1000000||text.getBytes(StandardCharsets.UTF_8).length>10485760)throw new LabException("DOCUMENT_LIMIT_EXCEEDED","正文超出限制");if(text.codePoints().anyMatch(c->c==0||c<32&&c!=10&&c!=13&&c!=9))throw new LabException("DOCUMENT_PARSE_FAILED","含二进制控制字符");}
    /** 去重键按账号与 API 命名空间隔离。 */
    public static void validateKey(String key){if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,128}"))throw LabException.invalid("需要有效 Idempotency-Key");}
}
