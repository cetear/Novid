package com.example.ailab.ai.workflows.content;

import com.example.ailab.contract.error.LabException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** 首次局部失败后停止派发，限时收集已启动节点；成功检查点由各节点独立提交。 */
final class ContentParallel {
    private record Indexed<T>(int index,T value) { }
    private static final Set<String> STOP_NOW=Set.of("REQUEST_CANCELLED","STALE_EXECUTION","BUDGET_EXCEEDED",
            "CONTEXT_VERSION_CONFLICT","FORBIDDEN","ACCESS_DENIED");
    static <A,B>List<B> run(ExecutorService pool,List<A> input,int parallelism,Instant deadline,Duration drain,Function<A,B> execute) {
        return run(pool,input,parallelism,deadline,drain,execute,()->{});
    }
    static <A,B>List<B> run(ExecutorService pool,List<A> input,int parallelism,Instant deadline,Duration drain,Function<A,B> execute,Runnable check) {
        if(input.isEmpty())return List.of();
        var completion=new ExecutorCompletionService<Indexed<B>>(pool);
        var futures=new ArrayList<Future<Indexed<B>>>();
        var output=new ArrayList<B>(Collections.nCopies(input.size(),null));
        RuntimeException failure=null;Instant until=deadline;int next=0,done=0;
        try {
            for(;next<Math.min(input.size(),parallelism);next++)submit(completion,futures,input,next,execute);
            while(done<futures.size()) {
                check.run();
                long wait=Duration.between(Instant.now(),until).toNanos();
                var completed=wait<=0?null:completion.poll(Math.min(wait,TimeUnit.SECONDS.toNanos(1)),TimeUnit.NANOSECONDS);
                if(completed==null){
                    if(Instant.now().isBefore(until))continue;
                    if(failure==null)failure=new LabException("BUDGET_EXCEEDED","并发执行超过父任务期限");break;
                }
                done++;
                try {var value=completed.get();output.set(value.index(),value.value());}
                catch(ExecutionException error) {
                    var cause=error.getCause();
                    var current=cause instanceof RuntimeException runtime?runtime:new IllegalStateException("资料子任务失败",cause);
                    if(current instanceof LabException lab&&STOP_NOW.contains(lab.code())){failure=current;break;}
                    if(failure==null){failure=current;var limit=Instant.now().plus(drain);until=limit.isBefore(deadline)?limit:deadline;}
                }
                if(failure==null&&next<input.size())submit(completion,futures,input,next++,execute);
            }
            if(failure!=null)throw failure;
            return List.copyOf(output);
        }catch(InterruptedException error){Thread.currentThread().interrupt();throw new LabException("REQUEST_CANCELLED","资料并发执行已中断");}
        finally{for(var future:futures)if(!future.isDone())future.cancel(true);}
    }
    private static <A,B>void submit(ExecutorCompletionService<Indexed<B>> completion,List<Future<Indexed<B>>> futures,
                                    List<A> input,int index,Function<A,B> execute) {
        futures.add(completion.submit(()->new Indexed<>(index,execute.apply(input.get(index)))));
    }
}
