package com.example.ailab.ai.tools;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.*;
import com.example.ailab.contract.error.LabException;
import com.example.ailab.ai.model.ExecutionBudget;
import org.springframework.stereotype.Component;
import java.util.*;
/** 固定注册工具白名单；不会反射暴露全部 Service 方法。 */
@Component
public class ToolExecutionService {
    public record Descriptor(String name,String version,String type,boolean enabled) {}
    private final KnowledgeCapabilityPort knowledge;private final KnowledgeSearchPort search;
    private final List<Descriptor> registry=List.of(new Descriptor("search_knowledge","v1","READ",true),new Descriptor("get_document","v1","READ",true),new Descriptor("get_knowledge_statistics","v1","READ",true),new Descriptor("save_generated_note","v1","WRITE",false));
    /** 独立业务能力端口不会回调问答用例。 */
    public ToolExecutionService(KnowledgeCapabilityPort knowledge,KnowledgeSearchPort search){this.knowledge=knowledge;this.search=search;}
    /** 每次暴露工具先重新核验当前身份。 */
    public List<Descriptor> definitions(UserContext actor){knowledge.authorize(actor,ScopeRequest.self());return registry;}
    /** 受控统计工具只读取真实数值。 */
    public KnowledgeStatistics statistics(UserContext actor,ScopeRequest scope,ExecutionBudget budget){check("get_knowledge_statistics",budget);return knowledge.statistics(actor,scope);}
    /** 搜索向量由 ModelGateway 产生，模型不能提交伪造 scope。 */
    public List<EvidenceBundle> search(UserContext actor,ScopeRequest scope,String question,List<Float> vector,String version,int bytes,ExecutionBudget budget){check("search_knowledge",budget);var authorized=knowledge.authorize(actor,scope);var hits=search.search(authorized,question,vector,version);return search.expand(authorized,hits,bytes);}
    /** 最终交付前重新读取全部证据版本和真实原文范围。 */
    public void verify(UserContext actor,ScopeRequest scope,List<EvidenceBundle> evidence){
        knowledge.authorize(actor,scope);for(var e:evidence){var content=knowledge.document(actor,scope,e.document().id());var d=content.document();if(d.documentVersion()!=e.document().documentVersion()||!Objects.equals(d.activeProcessingRevision(),e.processingRevision())||e.startOffset()<0||e.endOffset()>content.text().length()||!content.text().substring(e.startOffset(),e.endOffset()).equals(e.text()))throw new LabException("CONTEXT_MAPPING_INVALID","证据版本或原文范围已变化");}
    }
    /** 注册、启用、预算是程序约束，未知工具不能执行任意代码。 */
    private void check(String name,ExecutionBudget budget){var d=registry.stream().filter(t->t.name().equals(name)).findFirst().orElseThrow(()->new LabException("UNKNOWN_TOOL","未知工具"));if(!d.enabled())throw new LabException("TOOL_DISABLED","工具未启用");budget.tool();}
}
