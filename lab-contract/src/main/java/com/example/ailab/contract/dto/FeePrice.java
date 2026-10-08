package com.example.ailab.contract.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * 不可变价格快照；提供方词元总数已包含缓存／推理，禁止再次加子分类。
 */
public record FeePrice(String ref, String version, String currency, String unit, Instant effectiveAt,
                       BigDecimal inputRate, BigDecimal outputRate) {
    /**
     * 当前只支持核验后的总输入／总输出同价桶；其他计价规则必须标未知。
     */
    public FeePrice {
        if (ref == null || !ref.matches("[A-Za-z0-9_.-]{1,64}") || version == null || !version.matches("[A-Za-z0-9_.-]{1,64}")
                || currency == null || !currency.matches("[A-Z]{3}") || !java.util.Set.of("PER_MILLION_TOKENS", "PER_IMAGE", "PER_VIDEO", "PER_SECOND", "PER_CHARACTER").contains(unit)
                || effectiveAt == null || effectiveAt.isBefore(Instant.parse("2000-01-01T00:00:00Z"))
                || effectiveAt.isAfter(Instant.parse("2037-12-31T00:00:00Z")) || inputRate == null || outputRate == null
                || inputRate.signum() < 0 || outputRate.signum() < 0 || inputRate.scale() > 8 || outputRate.scale() > 8
                || inputRate.compareTo(new BigDecimal("1000000")) > 0 || outputRate.compareTo(new BigDecimal("1000000")) > 0)
            throw new IllegalArgumentException("价格版本／币种／单位或费率无效");
    }

    /**
     * 精确十进制计算到八位，预留及费用都向上舍入，不制造浮点退款。
     */
    public BigDecimal amount(long input, long output) {
        if (input < 0 || output < 0) throw new IllegalArgumentException("词元不能为负数");
        // 媒体使用图片数／视频数／秒数／字符数，绝不能冒充词元。
        if (!unit.equals("PER_MILLION_TOKENS")) {
            if (output != 0 || outputRate.signum() != 0) throw new IllegalArgumentException("媒体仅允许明确单位单价");
            return inputRate.multiply(BigDecimal.valueOf(input)).setScale(8, RoundingMode.CEILING);
        }
        return inputRate.multiply(BigDecimal.valueOf(input)).add(outputRate.multiply(BigDecimal.valueOf(output)))
                .divide(BigDecimal.valueOf(1000000), 8, RoundingMode.CEILING);
    }
}
