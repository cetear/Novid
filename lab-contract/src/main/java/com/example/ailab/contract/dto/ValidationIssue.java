package com.example.ailab.contract.dto;

/** 仅包含程序定义的字段路径、规则和计数，不携带未校验正文或异常栈。 */
public record ValidationIssue(String path, String rule, Integer actual, Integer maximum, String unit, String expected) { }
