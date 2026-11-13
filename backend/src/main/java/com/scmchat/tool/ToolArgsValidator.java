package com.scmchat.tool;

import com.scmchat.config.ChatProperties;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 라우터가 낸 도구 호출을 API_SPEC 8.4의 규칙으로 검증하고 정규화한다.
 * 하나라도 어기면 거부한다. 그 도구가 쓰지 않는 인자는 값이 있어도 무시한다.
 */
@Component
public class ToolArgsValidator {

    static final int TOP_N_MAX = 50;
    private static final Pattern SKU = Pattern.compile("(?i)SKU-(\\d{1,3})");

    private final Dimensions dimensions;
    private final LocalDate dataStartDate;
    private final LocalDate asOfDate;
    private final int maxDays;

    public ToolArgsValidator(Dimensions dimensions, ChatProperties chatProperties) {
        this.dimensions = dimensions;
        this.dataStartDate = chatProperties.dataStartDate();
        this.asOfDate = chatProperties.asOfDate();
        this.maxDays = (int) ChronoUnit.DAYS.between(dataStartDate, asOfDate) + 1;
    }

    public ToolValidation validate(String toolName, RawToolArgs raw) {
        try {
            return ToolValidation.accepted(toCall(toolName, raw));
        } catch (Rejection rejection) {
            return ToolValidation.rejected(rejection.getMessage());
        }
    }

    private ToolCall toCall(String toolName, RawToolArgs raw) {
        ToolName tool = ToolName.fromValue(blankToNull(toolName))
                .orElseThrow(() -> new Rejection("unknown tool: " + toolName));
        ToolOutput output = output(tool, raw.output());
        String sku = sku(raw.sku());
        String location = location(raw.location());

        Integer topN = null;
        Boolean ascending = null;
        if (output == ToolOutput.RANKING) {
            if (sku != null) {
                throw new Rejection("ranking does not take a sku: " + sku);
            }
            if (raw.topN() < 1 || raw.topN() > TOP_N_MAX) {
                throw new Rejection("top_n must be 1.." + TOP_N_MAX + " for ranking: " + raw.topN());
            }
            topN = raw.topN();
            ascending = ascending(raw.order());
        }

        Integer days = null;
        if (tool == ToolName.AVG_DAILY_SALES) {
            if (raw.days() < 1 || raw.days() > maxDays) {
                throw new Rejection("days must be 1.." + maxDays + ": " + raw.days());
            }
            days = raw.days();
        }

        LocalDate startDate = null;
        LocalDate endDate = null;
        if (tool == ToolName.STOCKOUT_RATE) {
            String start = blankToNull(raw.startDate());
            String end = blankToNull(raw.endDate());
            if ((start == null) != (end == null)) {
                throw new Rejection("start_date and end_date must be given together");
            }
            if (start != null) {
                startDate = date("start_date", start);
                endDate = date("end_date", end);
                if (startDate.isAfter(endDate)) {
                    throw new Rejection("start_date is after end_date: " + start + " > " + end);
                }
                if (startDate.isBefore(dataStartDate) || endDate.isAfter(asOfDate)) {
                    throw new Rejection("period must be within " + dataStartDate + " ~ " + asOfDate + ": "
                            + start + " ~ " + end);
                }
            }
        }
        return new ToolCall(tool, output, sku, location, days, startDate, endDate, topN, ascending);
    }

    private static ToolOutput output(ToolName tool, String raw) {
        return ToolOutput.fromValue(blankToNull(raw))
                .filter(tool.outputs()::contains)
                .orElseThrow(() -> new Rejection("output must be one of " + allowedOutputs(tool) + " for "
                        + tool.value() + ": " + raw));
    }

    private static String allowedOutputs(ToolName tool) {
        return tool.outputs().stream().map(ToolOutput::value).sorted().collect(Collectors.joining(", ", "[", "]"));
    }

    /** SKU-7, sku-07 같은 표기를 SKU-007로 맞춘다. */
    private String sku(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        Matcher matcher = SKU.matcher(value);
        if (!matcher.matches()) {
            throw new Rejection("sku must look like SKU-007: " + value);
        }
        String normalized = String.format("SKU-%03d", Integer.parseInt(matcher.group(1)));
        if (!dimensions.skuExists(normalized)) {
            throw new Rejection("unknown sku: " + normalized);
        }
        return normalized;
    }

    private String location(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        List<String> names = dimensions.locationNames();
        if (!names.contains(value)) {
            throw new Rejection("unknown location: " + value + " (known: " + String.join(", ", names) + ")");
        }
        return value;
    }

    private static boolean ascending(String raw) {
        String order = blankToNull(raw);
        if ("asc".equals(order)) {
            return true;
        }
        if ("desc".equals(order)) {
            return false;
        }
        throw new Rejection("order must be asc or desc for ranking: " + raw);
    }

    private static LocalDate date(String field, String value) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new Rejection(field + " must be yyyy-MM-dd: " + value);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static final class Rejection extends RuntimeException {

        Rejection(String message) {
            super(message, null, false, false);
        }
    }
}
