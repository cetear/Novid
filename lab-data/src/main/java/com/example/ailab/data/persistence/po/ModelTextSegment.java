package com.example.ailab.data.persistence.po;

/** 已解码文本片段在原始 HTTP 正文中的 UTF-16 位置，不含正文副本。 */
public record ModelTextSegment(int number,long charOffset,int charLength,long rawStart,long rawEnd,String checksum) { }
