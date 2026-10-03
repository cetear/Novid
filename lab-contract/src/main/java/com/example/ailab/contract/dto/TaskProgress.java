package com.example.ailab.contract.dto;

import java.time.Instant;
import java.util.List;

/** 进度按实际完成的固定步骤计算；百分比不估计模型生成量或剩余时间。 */
public record TaskProgress(String stage, String message, boolean workerEnabled, boolean executionActive,
                           int completedSteps, int totalSteps, int percent, List<String> currentSteps,
                           List<TaskStepSnapshot> steps, Instant startedAt, Instant updatedAt,
                           Instant lastHeartbeatAt, long elapsedExecutionSeconds, int pollAfterMillis) {
    /** FAQ/研究报告共享五个确定步骤，两个分析角色允许并行。 */
    public static List<String> stepIds() {
        return List.of("prepare", "research", "analysis", "report", "publish");
    }

    /** 标签由程序生成，客户端和模型均不能指定用户可见执行步骤。 */
    public static String label(String id) {
        return switch (id) {
            case "prepare" -> "读取并核验资料";
            case "research" -> "整理资料要点";
            case "analysis" -> "统计与分析";
            case "report" -> "生成 FAQ／报告";
            case "publish" -> "校验并发布结果";
            default -> throw new IllegalArgumentException("未知报告步骤");
        };
    }

    /** 根据持久事实区分排队、停用、执行、租约恢复及终态，不用计时动画假装进度。 */
    public static TaskProgress from(String status, boolean workerEnabled, boolean leaseActive,
                                    List<TaskStepSnapshot> steps, Instant startedAt, Instant updatedAt,
                                    Instant heartbeatAt, long elapsedSeconds) {
        steps = List.copyOf(steps);
        int completed = (int) steps.stream().filter(step -> step.status().equals("SUCCEEDED")).count();
        boolean active = status.equals("RUNNING") && leaseActive;
        var current = active ? steps.stream().filter(step -> step.status().equals("RUNNING"))
                .map(TaskStepSnapshot::stepId).toList() : List.<String>of();
        String stage;
        String message;
        switch (status) {
            case "QUEUED" -> {
                stage = workerEnabled ? "QUEUED" : "WAITING_FOR_WORKER";
                message = workerEnabled ? "任务已登记，等待后台领取"
                        : "任务已登记，但报告 Worker 未启用，尚未开始处理";
            }
            case "RUNNING" -> {
                if (!active) {
                    stage = "WAITING_FOR_RECOVERY";
                    message = "执行租约已过期，等待后台恢复；已完成步骤保留";
                } else {
                    stage = current.contains("publish") ? "PUBLISHING"
                            : current.contains("report") ? "GENERATING_REPORT"
                            : current.contains("research") || current.contains("analysis") ? "ANALYZING"
                            : current.contains("prepare") ? "PREPARING" : "STARTING";
                    message = current.isEmpty() ? "后台已领取任务，正在准备执行"
                            : "正在执行：" + String.join("、", current.stream().map(TaskProgress::label).toList());
                }
            }
            case "PAUSED" -> { stage = "PAUSED"; message = "任务已暂停，已完成步骤保留"; }
            case "CANCELLED" -> { stage = "CANCELLED"; message = "任务已取消，不再发布新结果"; }
            case "FAILED" -> {
                stage = "FAILED";
                var failed = steps.stream().filter(step -> step.status().equals("FAILED")).map(TaskStepSnapshot::label).toList();
                message = failed.isEmpty() ? "任务执行失败，请查看错误码" : "任务执行失败：" + String.join("、", failed);
            }
            case "PARTIAL" -> { stage = "PARTIAL"; message = "处理已结束，已发布有限覆盖结果，请查看覆盖说明"; }
            case "SUCCEEDED" -> { stage = "SUCCEEDED"; message = "任务已完成，结果可下载"; }
            default -> throw new IllegalArgumentException("未知任务状态");
        }
        boolean terminal = List.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(status);
        return new TaskProgress(stage, message, workerEnabled, active, completed, steps.size(),
                steps.isEmpty() ? 0 : completed * 100 / steps.size(), current, steps, startedAt, updatedAt,
                heartbeatAt, Math.max(0, elapsedSeconds), terminal || status.equals("PAUSED") ? 0 : 2000);
    }
}
