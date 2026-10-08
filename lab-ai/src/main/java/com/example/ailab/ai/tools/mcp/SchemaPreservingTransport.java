package com.example.ailab.ai.tools.mcp;

import com.example.ailab.ai.tools.ToolSchema;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.*;
import dev.langchain4j.mcp.protocol.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import java.util.concurrent.*;

/** SDK类型化转换会丢失部分Schema断言；在标准transport接口保留发现响应原文。 */
final class SchemaPreservingTransport implements McpTransport {
    private final McpTransport delegate;
    private final Map<String,JsonNode> schemas=new ConcurrentHashMap<>();
    SchemaPreservingTransport(McpTransport delegate){this.delegate=delegate;}
    Map<String,JsonNode> schemas(){return Map.copyOf(schemas);}
    public void start(McpOperationHandler handler){delegate.start(handler);}
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request){return bounded(delegate.sendInitializeRequest(request),false);}
    public CompletableFuture<String> sendRequest(McpCallContext context){return bounded(delegate.sendRequest(context),context.message() instanceof McpListToolsRequest);}
    public CompletableFuture<String> sendRequest(McpClientMessage message){return bounded(delegate.sendRequest(message),message instanceof McpListToolsRequest);}
    private CompletableFuture<String> bounded(CompletableFuture<String> request,boolean tools) {
        return request.thenApply(text->{
            if(text.length()>1048576)throw new IllegalArgumentException("MCP响应超限");
            if(tools)try {
                var root=ToolSchema.JSON.readTree(text);
                for(var value:root.path("result").path("tools")) {
                    if(schemas.size()>=1000)throw new IllegalArgumentException("MCP工具目录超限");
                    String name=value.path("name").asText();var schema=value.get("inputSchema");
                    if(name.isBlank()||schema==null||schemas.putIfAbsent(name,schema.deepCopy())!=null)throw new IllegalArgumentException("MCP工具定义缺失或重名");
                }
            }catch(java.io.IOException error){throw new IllegalArgumentException("MCP工具响应无效");}
            return text;
        });
    }
    public void sendMessage(McpClientMessage message){delegate.sendMessage(message);}
    public void sendMessage(McpCallContext context){delegate.sendMessage(context);}
    public void checkHealth(){delegate.checkHealth();}
    public void onFailure(Runnable callback){delegate.onFailure(callback);}
    public void setModernProtocol(boolean modern){delegate.setModernProtocol(modern);}
    public void setProtocolVersion(String version){delegate.setProtocolVersion(version);}
    public boolean requiresCancellationNotification(){return delegate.requiresCancellationNotification();}
    public void close()throws java.io.IOException{delegate.close();}
}
