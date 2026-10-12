package com.example.ailab.contract.dto;

import java.math.BigDecimal;
import java.util.List;
import com.example.ailab.contract.error.LabException;

/** 手动断点续跑；重做质量验收不属于 CONTINUE。 */
public final class ContentTaskRetry {
    private ContentTaskRetry() { }
    public record Request(String mode,long expectedStateVersion,int additionalExecutionSeconds,int additionalTurns,
                          int additionalAttempts,int additionalTools,int additionalRepairs,long additionalTokens,
                          BigDecimal additionalAmount) {
        public Request {
            mode=mode==null?"CONTINUE":mode;
            additionalAmount=additionalAmount==null?BigDecimal.ZERO:additionalAmount;
            if(!mode.equals("CONTINUE"))throw new LabException("RETRY_MODE_UNAVAILABLE","本轮仅支持 CONTINUE 断点续跑");
            if(expectedStateVersion<1||additionalExecutionSeconds<0||additionalExecutionSeconds>86400
                    ||additionalTurns<0||additionalTurns>10000||additionalAttempts<0||additionalAttempts>20000
                    ||additionalTools<0||additionalTools>10000||additionalRepairs<0||additionalRepairs>10000
                    ||additionalTokens<0||additionalTokens>10000000||additionalAmount.signum()<0
                    ||additionalAmount.scale()>8||additionalAmount.compareTo(new BigDecimal("1000000"))>0)
                throw LabException.invalid("追加额度或任务版本无效");
        }
    }
    public record Plan(long taskId,long stateVersion,boolean canContinue,String errorCode,List<String> blockers,
                       long remainingExecutionSeconds,int remainingTurns,int remainingAttempts,int remainingTools,
                       int remainingRepairs,long completedNodes,long pendingNodes,long acceptedPackages,long pendingPackages,
                       String currency,BigDecimal remainingAmount,long remainingTokens) { }
}
