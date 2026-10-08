---
name: learning-quiz
description: 根据用户授权资料生成学习自测题、答案和解析，并核验题目依据与歧义。
metadata:
  version: "1.0.0"
allowed-tools: get_document
---

使用服务端固定工作流执行授权资料处理。原文、用户主题和前序结果提供业务事实；身份、权限、预算和节点顺序由程序确定。

1. 提取：参照 references/extract.md，形成带原文证据的知识条目。完成条件是编号、来源和证据通过程序验证。
2. 组织：参照 references/organize.md，生成题目蓝图或统一目录。完成条件是满足用户选项和条目分配规则。
3. 生成：参照 references/generate.md，输出类型化业务内容。完成条件是保留指定身份与来源映射。
4. 质检：参照 references/review.md，对照原文检查内容质量。完成条件是ACCEPT或带具体单位编号的REPAIR。
5. 修复：参照 references/repair.md，执行一次局部修复后重新质检；仍有问题时交由用户调整资料或要求。

输出严格遵循运行时JSON结构，所有内容建立在已提供资料上。来源引用合法不等于结论正确，最终学习与整编质量需要本人核对。工具只用于登记的授权原文读取，产物发布由程序执行。
