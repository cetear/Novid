package com.example.ailab.contract.dto;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** 可确定的数字约束；不推断主体关系、中文数词或开放语义。 */
public final class ContentTextRules {
    private ContentTextRules() { }
    private static final Pattern NUMBERS=Pattern.compile("[+-]?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?(?:%|‰)?");
    public static Set<String> numbers(String text) {
        var result=new LinkedHashSet<String>();
        var matcher=NUMBERS.matcher(Normalizer.normalize(text,Normalizer.Form.NFKC));
        while(matcher.find()) {
            String value=matcher.group().replace(",","");
            String suffix=value.endsWith("%")?"%":value.endsWith("‰")?"‰":"";
            result.add(new BigDecimal(value.substring(0,value.length()-suffix.length())).stripTrailingZeros().toPlainString()+suffix);
        }
        return Collections.unmodifiableSet(result);
    }
    public static boolean supportedNumbers(String text,String evidence) {
        return numbers(evidence).containsAll(numbers(text));
    }
}
