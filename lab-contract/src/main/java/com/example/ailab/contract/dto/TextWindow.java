package com.example.ailab.contract.dto;

import java.nio.charset.StandardCharsets;

/** 未注册与实际供应商匹配的精确 tokenizer 时，用 UTF-8 字节上界守住输入，不当作用量结算。 */
public final class TextWindow {
    public static final String COUNT_SOURCE = "ESTIMATED_UTF8_BYTES";
    /** 工具类不保存状态。 */
    private TextWindow() { }
    /** 当前供应商没有公开匹配计数器；显式估计比假精确值安全。 */
    public static int count(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }
    /** 游标始终落在完整码点边界，硬预算不能靠切断代理对满足。 */
    public static int end(String text, int start, int end, int limit) {
        int cursor = start, used = 0;
        while (cursor < end) {
            int cp = text.codePointAt(cursor), size = count(new String(Character.toChars(cp)));
            if (used + size > limit) break;
            used += size;
            cursor += Character.charCount(cp);
        }
        return cursor;
    }
    /** 拒绝半个代理对和非法位置，避免旧引用误高亮。 */
    public static boolean boundary(String text, int offset) {
        return offset >= 0 && offset <= text.length() && (offset == 0 || offset == text.length()
                || !Character.isHighSurrogate(text.charAt(offset - 1)) || !Character.isLowSurrogate(text.charAt(offset)));
    }
}
