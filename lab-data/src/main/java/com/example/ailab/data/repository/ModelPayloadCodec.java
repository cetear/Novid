package com.example.ailab.data.repository;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.*;

/** 每块最多1MiB原文；解压长度及校验和核对后才能交付。 */
public final class ModelPayloadCodec {
    public static final int BLOCK_BYTES = 1048576;
    private ModelPayloadCodec() { }
    public record Block(String codec, byte[] content, int rawLength, String checksum) { }
    public static int end(String text, int start) {
        int end=start,bytes=0;
        while(end<text.length()) {
            int cp=text.codePointAt(end),n=cp<=127?1:cp<=2047?2:cp<=65535?3:4;
            if(bytes+n>BLOCK_BYTES)break;
            bytes+=n;end+=Character.charCount(cp);
        }
        return end;
    }
    public static Block encode(String text) {
        byte[] raw=text.getBytes(StandardCharsets.UTF_8);
        if(raw.length>BLOCK_BYTES)throw new IllegalArgumentException("正文块超限");
        try {
            var buffer=new ByteArrayOutputStream();
            try(var gzip=new GZIPOutputStream(buffer)){gzip.write(raw);}
            byte[] compressed=buffer.toByteArray();
            return new Block(compressed.length<raw.length?"GZIP":"RAW",compressed.length<raw.length?compressed:raw,raw.length,hash(raw));
        }catch(IOException e){throw new IllegalStateException("正文编码失败");}
    }
    public static byte[] decode(Block block) {
        if(block.rawLength()<0||block.rawLength()>BLOCK_BYTES||block.content().length>BLOCK_BYTES)throw new IllegalStateException("正文块损坏");
        try {
            byte[] raw;
            if(block.codec().equals("RAW"))raw=block.content();
            else if(block.codec().equals("GZIP")) {
                try(var stream=new GZIPInputStream(new ByteArrayInputStream(block.content()))) {
                    raw=stream.readNBytes(block.rawLength()+1);
                }
            }else throw new IllegalStateException("正文编码未知");
            if(raw.length!=block.rawLength()||!hash(raw).equals(block.checksum()))throw new IllegalStateException("正文校验失败");
            return raw;
        }catch(IOException e){throw new IllegalStateException("正文解码失败");}
    }
    public static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(Exception e){throw new IllegalStateException("摘要计算失败");}
    }
}
