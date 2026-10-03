package com.example.ailab.business.application;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.port.AiGatewayPort;
import org.springframework.stereotype.Service;
/** 通过公共端口调用 AI，不依赖 SDK。 */
@Service
public class AssistantApplicationService {
    private final AiGatewayPort ai;
    /** 注入运行时网关。 */
    public AssistantApplicationService(AiGatewayPort ai){this.ai=ai;}
    /** 身份独立于请求文本。 */
    public AiResult answer(UserContext actor,AiRequest request){return ai.answer(actor,request);}
}
