package com.example.ailab.ai.rag;

import com.example.ailab.contract.dto.*;
import com.example.ailab.contract.error.LabException;
import org.commonmark.node.*;
import org.commonmark.parser.*;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.stereotype.Component;

import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

/**
 * CommonMark 建树，原文不做换行／空白清洗；映射 v2 可直接还原 UTF-16 和原始行号。
 */
@Component
public class StructureParser {
    public static final String PARSER_VERSION = "commonmark-0.24-v2";
    public static final String SPLIT_VERSION = "structure-chunk-v2";
    public static final String MAPPING_VERSION = "utf16-lines-blocks-v2";
    private final RagProperties config;

    private record HeadingAt(int start, int level, String title) {
    }

    private record Range(int start, int end) {
    }

    private record Block(int start, int end, String type, String id, int headerEnd) {
    }

    /**
     * 唯一参数源控制解析、分片与扩展，不另存一组默认值。
     */
    public StructureParser(RagProperties config) {
        this.config = config;
    }

    /**
     * ID 绑定文档／内容版本／处理代次，重复标题不合并；重试同批次保持稳定。
     */
    public ParsedDocument parse(IngestionLease lease) {
        String text = lease.text(), prefix = "d" + lease.documentId() + "v" + lease.documentVersion() + "r" + lease.processingRevision();
        int[] lineStarts = java.util.stream.IntStream.range(0, text.length()).filter(i -> i == 0 || text.charAt(i - 1) == '\n').toArray();
        var headings = new ArrayList<HeadingAt>();
        var blocks = blocks(text, lease.format(), prefix, headings);
        var boundaries = new TreeSet<Integer>();
        boundaries.add(0);
        boundaries.add(text.length());
        blocks.forEach(b -> {
            boundaries.add(b.start());
            boundaries.add(b.end());
        });
        var sections = new ArrayList<SectionSnapshot>();
        var parents = new ArrayList<ParentSnapshot>();
        var chunks = new ArrayList<ChunkSnapshot>();
        var parts = new HashMap<String, Integer>();
        sections.add(new SectionSnapshot(prefix + "s0", null, List.of(), lease.title(), 0, 0, text.length()));
        var stack = new ArrayList<Integer>();
        stack.add(0);
        var ownRanges = new LinkedHashMap<Integer, Range>();
        ownRanges.put(0, new Range(0, headings.isEmpty() ? text.length() : headings.get(0).start()));
        for (int i = 0; i < headings.size(); i++) {
            var h = headings.get(i);
            while (stack.size() > 1 && headings.get(stack.get(stack.size() - 1) - 1).level() >= h.level())
                stack.remove(stack.size() - 1);
            int number = i + 1, parent = stack.get(stack.size() - 1), subtreeEnd = text.length();
            for (int j = i + 1; j < headings.size(); j++)
                if (headings.get(j).level() <= h.level()) {
                    subtreeEnd = headings.get(j).start();
                    break;
                }
            sections.add(new SectionSnapshot(prefix + "s" + number, prefix + "s" + parent,
                    stack.stream().map(n -> prefix + "s" + n).toList(), sections.get(parent).headingPath() + " / " + h.title(), number, h.start(), subtreeEnd));
            ownRanges.put(number, new Range(h.start(), i + 1 < headings.size() ? headings.get(i + 1).start() : text.length()));
            stack.add(number);
        }
        for (var entry : ownRanges.entrySet()) {
            var section = sections.get(entry.getKey());
            var range = entry.getValue();
            String heading = config.includeHeadingPath() ? take(section.headingPath(), config.headingPrefixMaxTokens() - 1) + "\n" : "";
            int parentOrdinal = 0, sectionChunk = 0, position = range.start();
            while (position < range.end()) {
                int end = cut(text, position, range.end(), config.parentMaxTokens(), boundaries, blocks, config.minChunkTokens());
                String parentId = section.sectionId() + "p" + parentOrdinal;
                parents.add(new ParentSnapshot(parentId, section.sectionId(), parentOrdinal++, position, end));
                int cursor = position, index = 0;
                while (cursor < end) {
                    final int segmentStart = cursor;
                    Block table = blocks.stream().filter(b -> b.type().equals("TABLE") && b.start() <= segmentStart && b.end() > segmentStart).findFirst().orElse(null);
                    // 表头是检索输入的重复上下文，rawText 仍为连续原文，不能给引用制造假偏移。
                    String header = table != null && cursor >= table.headerEnd() && cursor < table.end() && config.preserveTableRows()
                            ? text.substring(table.start(), table.headerEnd()) : "";
                    int limit = config.maxChunkTokens() - TextWindow.count(heading) - TextWindow.count(header);
                    if (limit < 4) {
                        header = "";
                        limit = config.maxChunkTokens() - TextWindow.count(heading);
                    }
                    int next = cut(text, cursor, end, limit, boundaries, blocks, config.minChunkTokens());
                    String raw = text.substring(cursor, next), embedding = heading + header + raw;
                    if (!raw.isBlank()) {
                        var mappings = new ArrayList<TextMapping>();
                        if (!header.isEmpty())
                            mappings.add(mapping(lineStarts, table.start(), table.headerEnd(), heading.length(), table, 0, true));
                        var intersecting = blocks.stream().filter(b -> b.start() < next && b.end() > segmentStart).toList();
                        for (var b : intersecting) {
                            int start = Math.max(cursor, b.start()), stop = Math.min(next, b.end());
                            int part = parts.merge(b.id(), 1, Integer::sum) - 1;
                            mappings.add(mapping(lineStarts, start, stop, heading.length() + header.length() + start - cursor, b, part, false));
                        }
                        String type = intersecting.size() == 1 ? intersecting.get(0).type() : "MIXED";
                        String blockId = intersecting.size() == 1 ? intersecting.get(0).id() : null;
                        int part = mappings.stream().filter(m -> !m.repeatedHeader()).findFirst().map(TextMapping::partIndex).orElse(0);
                        if (TextWindow.count(embedding) > config.maxChunkTokens())
                            throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "切片输入超过硬预算");
                        chunks.add(new ChunkSnapshot(parentId + "c" + index, section.sectionId(), parentId, sectionChunk++, index++, cursor, next,
                                raw, embedding, hash(embedding), type, blockId, part, mappings, TextWindow.count(embedding), TextWindow.COUNT_SOURCE));
                        if (chunks.size() > 5000) throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "切片超过 5000");
                    }
                    if (next == end) break;
                    // 代码／表格按块或行拆，不用任意重叠破坏块关系；普通正文仍在父段内有界重叠。
                    boolean structured = blocks.stream().anyMatch(b -> b.start() < next && b.end() > segmentStart && (b.type().equals("CODE") || b.type().equals("TABLE")));
                    int overlap = structured ? next : back(text, next, cursor, config.overlapTokens());
                    cursor = overlap > cursor ? overlap : next;
                }
                position = end;
            }
        }
        if (chunks.isEmpty()) throw new LabException("DOCUMENT_PARSE_FAILED", "文档没有有效文本");
        return new ParsedDocument(sections, parents, chunks, hash(PARSER_VERSION + "/" + SPLIT_VERSION + "/" + MAPPING_VERSION + "/" + TextWindow.COUNT_SOURCE + "/" + config),
                PARSER_VERSION, SPLIT_VERSION, MAPPING_VERSION, "UTF8_BYTE_UPPER_BOUND", TextWindow.COUNT_SOURCE);
    }

    /**
     * AST 顶层块保留源跨度；Markdown 表格只识别带合法分隔行的连续行组，不把代码中的管道符当表格。
     */
    private List<Block> blocks(String text, String format, String prefix, List<HeadingAt> headings) {
        var result = new ArrayList<Block>();
        int ordinal = 0, cursor = 0;
        if (!format.equals("txt")) {
            Node ast = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS).build().parse(text);
            for (Node node = ast.getFirstChild(); node != null; node = node.getNext()) {
                var spans = node.getSourceSpans();
                if (spans.isEmpty()) continue;
                int start = spans.get(0).getInputIndex();
                var last = spans.get(spans.size() - 1);
                int end = last.getInputIndex() + last.getLength();
                if (end < text.length() && text.charAt(end) == '\r') end++;
                if (end < text.length() && text.charAt(end) == '\n') end++;
                if (start > cursor) result.add(new Block(cursor, start, "WHITESPACE", prefix + "b" + ordinal++, 0));
                String type = node instanceof FencedCodeBlock || node instanceof IndentedCodeBlock ? "CODE"
                        : node instanceof ListBlock ? "LIST" : node instanceof Heading ? "HEADING" : node instanceof BlockQuote ? "QUOTE" : "PARAGRAPH";
                int headerEnd = start;
                if (type.equals("PARAGRAPH")) {
                    int line1 = text.indexOf('\n', start), line2 = line1 < 0 ? -1 : text.indexOf('\n', line1 + 1);
                    if (line1 > start && line1 < end) {
                        int delimiterEnd = line2 < 0 || line2 > end ? end : line2;
                        String delimiter = text.substring(line1 + 1, delimiterEnd).strip();
                        if (text.substring(start, line1).contains("|") && delimiter.matches("\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?")) {
                            type = "TABLE";
                            headerEnd = line2 < 0 || line2 >= end ? end : line2 + 1;
                        }
                    }
                }
                result.add(new Block(start, end, type, prefix + "b" + ordinal++, headerEnd));
                cursor = end;
                if (node instanceof Heading h)
                    headings.add(new HeadingAt(start, h.getLevel(), TextContentRenderer.builder().build().render(h).strip()));
            }
        }
        if (cursor < text.length())
            result.add(new Block(cursor, text.length(), format.equals("txt") ? "PARAGRAPH" : "WHITESPACE", prefix + "b" + ordinal, 0));
        return result;
    }

    /**
     * 保留可容纳的完整代码／表格行；过长才按安全码点拆，软最小片只影响可合并的正文。
     */
    private int cut(String text, int start, int end, int limit, NavigableSet<Integer> boundaries, List<Block> blocks, int min) {
        int max = TextWindow.end(text, start, end, limit);
        if (max == start) throw new LabException("DOCUMENT_LIMIT_EXCEEDED", "前缀未留下正文预算");
        if (max == end) return end;
        for (var b : blocks) {
            if (b.start() < max && b.end() > max && b.start() > start && preserved(b)
                    && TextWindow.count(text.substring(b.start(), b.end())) <= limit) return b.start();
        }
        Integer boundary = boundaries.floor(max);
        if (boundary != null && boundary > start && TextWindow.count(text.substring(start, boundary)) >= min)
            return boundary;
        for (int i = max; i > start; i--) {
            char c = text.charAt(i - 1);
            if (c == '\n' || c == '。' || c == '！' || c == '？') {
                if (TextWindow.count(text.substring(start, i)) >= min || blocks.stream().anyMatch(b -> b.start() <= start && b.end() >= max && preserved(b)))
                    return i;
            }
        }
        return max;
    }

    /**
     * 保留开关实际控制结构边界，关闭时允许递归正文切法。
     */
    private boolean preserved(Block b) {
        return b.type().equals("CODE") && config.preserveCodeBlocks() || b.type().equals("TABLE") && config.preserveTableRows();
    }

    /**
     * 正文重叠不跨父段，代理对始终完整。
     */
    private int back(String text, int end, int start, int limit) {
        int cursor = end, used = 0;
        while (cursor > start) {
            int cp = text.codePointBefore(cursor), size = TextWindow.count(new String(Character.toChars(cp)));
            if (used + size > limit) break;
            used += size;
            cursor -= Character.charCount(cp);
        }
        return cursor;
    }

    /**
     * 有界标题前缀不丢完整路径元数据。
     */
    private String take(String text, int limit) {
        return text.substring(0, TextWindow.end(text, 0, text.length(), limit));
    }

    /**
     * 每段包含实际原文位置与一基行号，重复表头仍指向真实最初表头。
     */
    private TextMapping mapping(int[] lineStarts, int start, int end, int embeddingStart, Block block, int part, boolean repeated) {
        return new TextMapping(embeddingStart, embeddingStart + end - start, start, end, line(lineStarts, start), line(lineStarts, Math.max(start, end - 1)), block.type(), block.id(), part, repeated);
    }

    /**
     * 原始 CRLF 中只计算 LF，不重复增加行号。
     */
    private int line(int[] lineStarts, int offset) {
        int index = Arrays.binarySearch(lineStarts, offset);
        return index >= 0 ? index + 1 : -index - 1;
    }

    /**
     * 版本及真实参数参与 hash，不靠标题合并内容。
     */
    private String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
