package com.example.ailab.ai.memory;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.port.MemoryStorePort;
import org.springframework.stereotype.Component;
/** 长期偏好仅读取用户明确授权保存的记录，不从知识资料自动推断写入。 */
@Component
public class ProfileMemoryService {
    private final MemoryStorePort store;
    /** 注入隔离的本人存储端口。 */
    public ProfileMemoryService(MemoryStorePort store){this.store=store;}
    /** 有限偏好窗口；删除后立即从后续请求消失。 */
    public String preferences(UserContext actor){var text=new StringBuilder();for(var m:store.list(actor)){if(text.length()+m.content().length()>2000)break;text.append(m.content()).append("\n");}return text.toString();}
}
