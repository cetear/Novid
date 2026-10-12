package com.example.ailab.contract.port;
import com.example.ailab.contract.context.UserContext;
import com.example.ailab.contract.dto.*;

public interface ContentTaskRetryPort {
    ContentTaskRetry.Plan retryPlan(UserContext actor,long taskId);
    TaskSnapshot retry(UserContext actor,long taskId,ContentTaskRetry.Request request,String key);
}
