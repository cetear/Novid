package com.example.ailab.app;

import com.example.ailab.contract.error.LabException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.concurrent.*;

/** 在事务拦截器之前限制资料仓库并发，等待许可期间不借数据库连接。 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseAdmissionAspect {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(DatabaseAdmissionAspect.class);
    private final Semaphore content;
    private final long waitMillis;
    public DatabaseAdmissionAspect(@Value("${lab.database.content-concurrency:4}") int concurrency,
            @Value("${lab.database.admission-wait-ms:5000}") long waitMillis,
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") int poolSize) {
        if(concurrency<1||concurrency>32||poolSize<3||waitMillis<1||waitMillis>10000)
            throw new IllegalArgumentException("数据库资料访问限额配置无效");
        content=new Semaphore(Math.min(concurrency,poolSize-2),true);this.waitMillis=waitMillis;
    }
    @Around("execution(public * com.example.ailab.data.repository.ContentWorkflowRepository.*(..))")
    public Object content(ProceedingJoinPoint invocation) throws Throwable {
        // 已处于外层事务的内部调用沿用该事务，避免持连接等待新许可。
        if(TransactionSynchronizationManager.isActualTransactionActive())return invocation.proceed();
        long waitingAt=System.nanoTime();
        boolean acquired;
        try { acquired=content.tryAcquire(waitMillis,TimeUnit.MILLISECONDS); }
        catch(InterruptedException stopped) {
            Thread.currentThread().interrupt();throw new LabException("REQUEST_CANCELLED","资料数据库访问等待已中断");
        }
        if(!acquired) {
            LOG.warn("event=db.content_admission_timeout operation={} waitMs={}",invocation.getSignature().getName(),waitMillis);
            throw new LabException("DATABASE_TEMPORARILY_UNAVAILABLE","资料数据库访问繁忙，请稍后重试");
        }
        long admittedAt=System.nanoTime();boolean success=false;
        try { var result=invocation.proceed();success=true;return result; }
        finally {
            content.release();
            long admissionMillis=TimeUnit.NANOSECONDS.toMillis(admittedAt-waitingAt);
            long operationMillis=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-admittedAt);
            if(admissionMillis+operationMillis>=1000)
                LOG.warn("event=db.content_slow operation={} admissionMs={} operationMs={} success={}",invocation.getSignature().getName(),admissionMillis,operationMillis,success);
        }
    }
}
