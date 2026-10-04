package com.example.ailab.contract.dto;

/** 已配对工具事件，只保存净化后的结果和合法参数，不保存模型隐藏思维。 */
public record ToolExchange(String toolCallId, String toolName, String version, String arguments, String result) { }
