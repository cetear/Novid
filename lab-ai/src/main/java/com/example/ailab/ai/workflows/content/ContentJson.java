package com.example.ailab.ai.workflows.content;
import com.example.ailab.ai.tools.ToolSchema;
import com.example.ailab.contract.dto.Learning;
/** 与MySQL JSON字段排序无关的规范摘要。 */
public final class ContentJson {
    private ContentJson() { }
    public static String encode(Object value) {
        try {return ToolSchema.JSON.writeValueAsString(ToolSchema.JSON.convertValue(value,Object.class));}
        catch(Exception e){throw new IllegalArgumentException("资料工作流JSON无效",e);}
    }
    public static String hash(Object value){return Learning.digest(encode(value));}
}
