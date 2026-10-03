package com.example.ailab.contract.dto;

import java.util.List;

/** 页摘要和实际输入位置同事务保存；同任务／文档／页次只成功一次。 */
public record TaskPageCheckpoint(int pageIndex, SectionPage page, String summary,
                                 List<SourceDependency> sourceDependencies) {
    /** 来源防御复制，恢复不能改变已经读取的版本事实。 */
    public TaskPageCheckpoint { sourceDependencies = List.copyOf(sourceDependencies); }
}
