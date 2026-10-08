package com.example.ailab.ai.model;

import com.example.ailab.contract.dto.FeePrice;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 价格没有默认虚构值；金额上限与词元硬限额独立配置并形成启动快照。
 */
@ConfigurationProperties("lab.fees")
public record FeeProperties(String currency, BigDecimal onlineLimit, BigDecimal taskLimit, BigDecimal ingestionLimit,
                            long onlineTokens, long taskTokens, long ingestionTokens, Map<String, FeePrice> prices) {
    /**
     * 缺报价仍使用有限硬额度，费用显示UNKNOWN；非法币种或无限上限启动失败。
     */
    public FeeProperties {
        currency = currency == null ? "CNY" : currency;
        onlineLimit = onlineLimit == null ? new BigDecimal("10") : onlineLimit;
        taskLimit = taskLimit == null ? new BigDecimal("30") : taskLimit;
        ingestionLimit = ingestionLimit == null ? new BigDecimal("30") : ingestionLimit;
        onlineTokens = onlineTokens == 0 ? 300000 : onlineTokens;
        taskTokens = taskTokens == 0 ? 300000 : taskTokens;
        ingestionTokens = ingestionTokens == 0 ? 2500000 : ingestionTokens;
        prices = prices == null ? Map.of() : Map.copyOf(prices);
        if (!currency.matches("[A-Z]{3}") || !valid(onlineLimit) || !valid(taskLimit) || !valid(ingestionLimit)
                || onlineTokens < 1 || onlineTokens > 10000000 || taskTokens < 1 || taskTokens > 10000000
                || ingestionTokens < 1 || ingestionTokens > 10000000)
            throw new IllegalArgumentException("费用预算配置无效");
        String configuredCurrency = currency;
        prices.forEach((key, price) -> {
            if (!key.equals(price.ref()) || !price.currency().equals(configuredCurrency))
                throw new IllegalArgumentException("价格引用／币种不一致");
        });
    }

    /**
     * 配置金额只接受八位以内的正十进制有限值。
     */
    private static boolean valid(BigDecimal value) {
        return value.signum() > 0 && value.scale() <= 8 && value.compareTo(new BigDecimal("1000000")) <= 0;
    }
}
