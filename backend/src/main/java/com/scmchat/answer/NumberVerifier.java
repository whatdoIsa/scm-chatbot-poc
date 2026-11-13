package com.scmchat.answer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 답변 문장의 숫자가 조회 결과나 질문에서 온 것인지 확인한다(PRD 10.5).
 * 숫자의 출처만 본다. 숫자를 엉뚱한 대상에 붙였는지, 부호가 맞는지는 보지 않는다.
 */
@Component
public class NumberVerifier {

    /** SKU-007, LOC-01 같은 식별자. 안의 숫자는 수량이 아니므로 문장에서 뽑지 않는다. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z]+-\\d+");
    /** 쉼표와 소수점을 포함한 아라비아 숫자. 뒤에 %나 퍼센트가 붙으면 백분율이다. */
    private static final Pattern NUMBER =
            Pattern.compile("(?<![\\d.])((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?)(\\s?%|\\s?퍼센트)?");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    public Verification verify(String answer, VerificationContext context) {
        List<BigDecimal> exact = exactValues(context);
        List<BigDecimal> cells = numericCells(context);

        List<String> unverified = new ArrayList<>();
        for (Token token : tokensOf(IDENTIFIER.matcher(answer).replaceAll(" "))) {
            if (!isAllowed(token, exact, cells)) {
                unverified.add(token.text());
            }
        }
        return new Verification(unverified.isEmpty(), List.copyOf(unverified));
    }

    private static boolean isAllowed(Token token, List<BigDecimal> exact, List<BigDecimal> cells) {
        for (BigDecimal value : exact) {
            if (value.compareTo(token.value()) == 0) {
                return true;
            }
        }
        for (BigDecimal cell : cells) {
            if (matchesRounded(token, cell) || (token.percent() && matchesRounded(token, cell.multiply(HUNDRED)))) {
                return true;
            }
        }
        return false;
    }

    /** 셀 값을 토큰의 소수 자릿수에 맞춰 반올림하거나 버린 값과 같으면 같은 숫자다. 올림과 어림은 허용하지 않는다. */
    private static boolean matchesRounded(Token token, BigDecimal cell) {
        int scale = token.value().scale();
        return cell.setScale(scale, RoundingMode.HALF_UP).compareTo(token.value()) == 0
                || cell.setScale(scale, RoundingMode.DOWN).compareTo(token.value()) == 0;
    }

    /** 그대로 적혀 있어야 하는 숫자: 질문과 문자열 셀에 나온 숫자, 도구 인자, 날짜의 연·월·일, 행 수와 순위. */
    private static List<BigDecimal> exactValues(VerificationContext context) {
        List<BigDecimal> values = new ArrayList<>();
        tokensOf(context.question()).forEach(token -> values.add(token.value()));
        addDateParts(values, context.asOfDate());
        if (context.toolCall() != null) {
            if (context.toolCall().days() != null) {
                values.add(BigDecimal.valueOf(context.toolCall().days()));
            }
            if (context.toolCall().topN() != null) {
                values.add(BigDecimal.valueOf(context.toolCall().topN()));
            }
            addDateParts(values, context.toolCall().startDate());
            addDateParts(values, context.toolCall().endDate());
        }
        for (List<Object> row : context.result().rows()) {
            for (Object cell : row) {
                if (cell instanceof String text) {
                    tokensOf(text).forEach(token -> values.add(token.value()));
                } else if (cell instanceof LocalDate date) {
                    addDateParts(values, date);
                }
            }
        }
        for (int rank = 1; rank <= context.result().rows().size(); rank++) {
            values.add(BigDecimal.valueOf(rank));
        }
        return values;
    }

    private static void addDateParts(List<BigDecimal> values, LocalDate date) {
        if (date != null) {
            values.add(BigDecimal.valueOf(date.getYear()));
            values.add(BigDecimal.valueOf(date.getMonthValue()));
            values.add(BigDecimal.valueOf(date.getDayOfMonth()));
        }
    }

    /** 부호는 보지 않으므로 절댓값으로 둔다. */
    private static List<BigDecimal> numericCells(VerificationContext context) {
        List<BigDecimal> cells = new ArrayList<>();
        for (List<Object> row : context.result().rows()) {
            for (Object cell : row) {
                if (cell instanceof Number number && isFinite(number)) {
                    cells.add(new BigDecimal(number.toString()).abs());
                }
            }
        }
        return cells;
    }

    private static boolean isFinite(Number number) {
        return !(number instanceof Double || number instanceof Float) || Double.isFinite(number.doubleValue());
    }

    private static List<Token> tokensOf(String text) {
        List<Token> tokens = new ArrayList<>();
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            BigDecimal value = new BigDecimal(matcher.group(1).replace(",", ""));
            tokens.add(new Token(matcher.group().strip(), value, matcher.group(2) != null));
        }
        return tokens;
    }

    private record Token(String text, BigDecimal value, boolean percent) {
    }
}
