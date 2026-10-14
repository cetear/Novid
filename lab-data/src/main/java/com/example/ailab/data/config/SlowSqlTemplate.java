package com.example.ailab.data.config;

/** 只展示模板：删除注释及字面量；不确定的引号／字符语法整条省略，避免错误脱敏。 */
final class SlowSqlTemplate {
    private SlowSqlTemplate() { }

    static String sanitize(String sql) {
        if (sql == null) return null;
        var safe = new StringBuilder();
        for (int i = 0; i < sql.length();) {
            char ch = sql.charAt(i);
            if (Character.isWhitespace(ch)) { safe.append(' '); i++; }
            else if (ch == '#' || (ch == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-')) {
                while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
                safe.append(' ');
            } else if (ch == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                if (end < 0) return null;
                i = end + 2;
                safe.append(' ');
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                char quote = ch;
                boolean closed = false;
                int start = ++i;
                while (i < sql.length()) {
                    char current = sql.charAt(i++);
                    // 反斜杠转义受 MySQL sql_mode 影响，不猜测当前会话模式。
                    if (current == '\\') return null;
                    if (current == quote) {
                        if (i < sql.length() && sql.charAt(i) == quote) { i++; continue; }
                        closed = true;
                        break;
                    }
                }
                if (!closed) return null;
                if (quote == '`') {
                    String identifier = sql.substring(start, i - 1);
                    if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
                    safe.append('`').append(identifier).append('`');
                } else safe.append('?');
            } else if (ch >= '0' && ch <= '9') {
                // 包含十六进制／科学计数数字，不保留字面量内容。
                i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || ".+-".indexOf(sql.charAt(i)) >= 0)) i++;
                safe.append('?');
            } else if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z') || ch == '_') {
                int start = i++;
                while (i < sql.length() && (Character.isLetterOrDigit(sql.charAt(i)) || sql.charAt(i) == '_')) {
                    if (sql.charAt(i) > 127) return null;
                    i++;
                }
                safe.append(sql, start, i);
            } else if ("?,.()=<>!+-*/%|&^~:;".indexOf(ch) >= 0) { safe.append(ch); i++; }
            else return null;
        }
        return safe.toString().replaceAll(" +", " ").trim();
    }
}
