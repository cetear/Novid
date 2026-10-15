package com.example.ailab.ai.workflows.content;

import com.example.ailab.contract.error.LabException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** 根包等待在编排线程；所有模型子任务共享固定线程池，回调只派发而不占线程等待子任务。 */
final class ReadingTasks implements AutoCloseable {
    private final ExecutorService pool;
    private final Instant deadline;
    private final Runnable check;
    private final Set<Future<?>> pending=ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final Map<String,String> logging=org.slf4j.MDC.getCopyOfContextMap();
    ReadingTasks(ExecutorService pool,Instant deadline,Runnable check){this.pool=pool;this.deadline=deadline;this.check=check;}
    <T>CompletableFuture<T> submit(Supplier<T> work) {
        var result=new CompletableFuture<T>();
        var task=new FutureTask<T>(()->{
            if(closed.get())throw new LabException("REQUEST_CANCELLED","阅读分支已取消");
            var previous=org.slf4j.MDC.getCopyOfContextMap();
            try{if(logging==null)org.slf4j.MDC.clear();else org.slf4j.MDC.setContextMap(logging);check.run();return work.get();}
            finally{if(previous==null)org.slf4j.MDC.clear();else org.slf4j.MDC.setContextMap(previous);}
        }) {
            @Override protected void done(){
                pending.remove(this);
                try{if(isCancelled())result.cancel(false);else result.complete(get());}
                catch(ExecutionException e){result.completeExceptionally(e.getCause());}
                catch(InterruptedException e){Thread.currentThread().interrupt();result.completeExceptionally(e);}
            }
        };
        pending.add(task);
        if(closed.get())task.cancel(true);
        else try{pool.execute(task);}catch(RejectedExecutionException e){task.cancel(true);result.completeExceptionally(e);}
        return result;
    }
    static <T>CompletableFuture<T> both(CompletableFuture<T> first,CompletableFuture<T> second,BinaryOperator<T> merge){
        var result=first.thenCombine(second,merge);
        first.whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);});
        second.whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);});
        return result;
    }
    static <T>CompletableFuture<List<T>> collect(List<CompletableFuture<T>> futures){
        var result=CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored->futures.stream().map(CompletableFuture::join).toList());
        for(var future:futures)future.whenComplete((v,e)->{if(e!=null)result.completeExceptionally(e);});
        return result;
    }
    <T>T await(CompletableFuture<T> result) {
        try {
            while(true) {
                check.run();long remaining=Duration.between(Instant.now(),deadline).toNanos();
                if(remaining<=0)throw new LabException("BUDGET_EXCEEDED","阅读分支超过父任务期限");
                try{return result.get(Math.min(remaining,TimeUnit.SECONDS.toNanos(1)),TimeUnit.NANOSECONDS);}
                catch(TimeoutException ignored){ }
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new LabException("REQUEST_CANCELLED","阅读分支已中断");}
        catch(CancellationException e){throw new LabException("REQUEST_CANCELLED","阅读分支已取消");}
        catch(ExecutionException e){
            Throwable cause=e.getCause();while(cause instanceof CompletionException&&cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof CancellationException||cause instanceof InterruptedException)throw new LabException("REQUEST_CANCELLED","阅读分支已取消");
            if(cause instanceof RuntimeException failure)throw failure;
            if(cause instanceof Error failure)throw failure;
            throw new IllegalStateException("阅读分支失败",cause);
        }
    }
    @Override public void close(){closed.set(true);for(var task:pending)task.cancel(true);}
}
