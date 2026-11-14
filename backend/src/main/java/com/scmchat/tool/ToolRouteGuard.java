package com.scmchat.tool;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * v5의 도구 경로 점검(PRD 12장). 라우터가 고른 도구 호출이 질문과 맞는지 질문의 낱말로 확인한다.
 * 모델에게 "이 도구는 이런 질문에 쓰지 말라"고 적어 주는 것(v4의 not_for)만으로는 막히지 않던 오답을 서버가 규칙으로 막는다.
 *
 * <ul>
 *   <li>도구는 자기 지표를 계산한다. 질문에 그 지표를 가리키는 낱말이 없으면(예: "출고량 합계"에 일평균 도구)
 *       도구를 쓰지 않고 SQL 단계로 보낸다.</li>
 *   <li>지표 값에 건 조건(3일 미만)이나 도구 인자에 없는 컬럼의 조건(리드타임이 7일인)이 있으면 SQL 단계로 보낸다.</li>
 *   <li>거점별·카테고리별로 묶어 보거나 거점·권역·카테고리 가운데 하나를 고르는 질문은 SQL 단계로 보낸다.
 *       도구의 값은 범위 전체 하나이고 순위는 품목 순위다.</li>
 *   <li>순위의 정렬 방향은 질문에 한쪽 방향의 낱말만 있으면 그 방향으로 고친다.</li>
 * </ul>
 * 판단은 공백을 지운 소문자 질문에서 한다("재고 보유일수"와 "재고보유일수"를 같게 본다).
 * 거점 이름("서울센터")은 판단 전에 지운다. 이름 속의 "센터"를 거점 순위로 읽지 않게 한다.
 */
@Component
public class ToolRouteGuard {

    /** SQL 단계로 보내는 이유. 로그와 tool_args의 guard에 message를 남긴다. */
    public enum Reason {
        METRIC_NOT_NAMED("question does not name the metric of the tool"),
        UNSUPPORTED_CONDITION("question has a condition the tool cannot express"),
        VALUE_CONDITION("question puts a condition on the metric value"),
        GROUPED("question groups by location, region or category"),
        NON_ITEM_RANKING("question picks a location, region or category, not an item");

        private final String message;

        Reason(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    /** 점검 결과. call은 그대로 실행할(정렬 방향을 고쳤을 수 있는) 호출이고, reason이 있으면 SQL 단계로 보낸다. */
    public record Decision(ToolCall call, Reason reason, boolean orderCorrected) {

        public boolean reroute() {
            return reason != null;
        }

        public String rerouteReason() {
            return reason == null ? null : reason.message();
        }
    }

    /** 도구마다 질문에 있어야 하는 지표 낱말. */
    private static final Map<ToolName, Pattern> METRIC_WORDS = Map.of(
            ToolName.AVG_DAILY_SALES, Pattern.compile("일평균|하루평균|평균.{0,3}(판매|출고)"),
            ToolName.DAYS_OF_SUPPLY, Pattern.compile("재고보유일수|재고일수|보유일수|dos|며칠치|며칠버틸|며칠분"),
            ToolName.STOCKOUT_RATE, Pattern.compile("결품률|결품율|결품비율|품절률|품절율"),
            ToolName.FORECAST_ACCURACY, Pattern.compile("정확도|정확했|잘맞|예측.{0,6}(오차|안맞|빗나|틀)"),
            ToolName.BELOW_SAFETY_STOCK, Pattern.compile(
                    "안전재고.{0,6}(미달|밑돌|밑도|밑으|못미치|미치지|부족|아래|미만|모자|적은|낮은|작은)"
                            + "|(미달|밑돌|밑도|못미치|부족|모자).{0,6}안전재고"),
            ToolName.REORDER_NEEDED, Pattern.compile("(재주문|발주|재발주).{0,8}(필요|해야|대상|시점이된|시점인)"));

    /** 목록·개수 도구가 표현하지 못하는 다른 컬럼의 조건(예: 리드타임이 7일인, 입고 예정이 없는). */
    private static final Map<ToolName, Pattern> EXTRA_CONDITION = Map.of(
            ToolName.BELOW_SAFETY_STOCK, Pattern.compile("리드타임|재주문점|발주점|입고예정|입고"),
            ToolName.REORDER_NEEDED, Pattern.compile("리드타임|안전재고"));

    /** 지표 값에 건 조건(예: 3일 미만, 리드타임보다 짧은, 0.8을 넘는). 값·순위 도구는 이런 조건을 걸 수 없다. */
    private static final Pattern VALUE_CONDITION = Pattern.compile(
            "\\d(일|개|%|퍼센트)?(미만|초과|이상|이하)|(미만|초과|이상|이하)(인|이|의|으로)"
                    + "|보다(짧|길|낮|높|적|많|작|크)|넘는|넘은|넘게|안되는|밑도는");

    private static final String ENTITY = "(거점|센터|창고|권역|지역|카테고리)";
    private static final String RANK_WORD = "(가장|제일|최고로|최저로|상위|하위|top|탑)";
    /** 묶어서 보는 질문. */
    private static final Pattern GROUPED = Pattern.compile(ENTITY + "별");
    /** 거점·권역·카테고리 가운데 하나(또는 몇 개)를 고르는 질문. 거점 이름은 미리 지운다. */
    private static final Pattern NON_ITEM_PICK = Pattern.compile(
            "어느" + ENTITY
                    + "|" + ENTITY + "(은|는|이|가|을|를)?(어디|어느|순으로|순서)"
                    + "|" + ENTITY + "(중|가운데)"
                    + "|" + RANK_WORD + "[^품]{0,14}?" + ENTITY + "(은|는|이|가|을|를)?(\\?|$|어디|알려|보여|순|\\d|곳|top)"
                    + "|" + RANK_WORD + "\\d*(개|곳)?" + ENTITY);

    /** "잘"은 뒤에 부정이 오지 않을 때만 높은 쪽이다(가장 잘 안 팔리는 = 낮은 쪽). */
    private static final Pattern DESCENDING = Pattern.compile(
            "(가장|제일).{0,4}?(높|많|길|긴|크|큰|잘(?!안|못))|상위|높은순|많은순|긴순|큰순"
                    + "|높은품목|많은품목|긴품목|큰품목|베스트|top|탑\\d");
    private static final Pattern ASCENDING = Pattern.compile(
            "(가장|제일).{0,4}?(낮|적|짧|작|못|안맞|잘안|잘못)|하위|낮은순|적은순|짧은순|작은순"
                    + "|낮은품목|적은품목|짧은품목|작은품목|워스트");
    /** 예측 정확도 도구에서 오차가 큰 쪽은 정확도가 낮은 쪽이다. 방향 낱말이 오차를 꾸밀 때만 뒤집는다. */
    private static final Pattern ERROR_DIRECTION = Pattern.compile(
            "(오차|mape|틀린|틀리|빗나).{0,8}(크|큰|많|높|낮|적|작)");

    private final Dimensions dimensions;

    public ToolRouteGuard(Dimensions dimensions) {
        this.dimensions = dimensions;
    }

    public Decision check(String question, ToolCall call) {
        String text = normalize(question);

        if (!METRIC_WORDS.get(call.tool()).matcher(text).find()) {
            return reroute(call, Reason.METRIC_NOT_NAMED);
        }
        Pattern extra = EXTRA_CONDITION.get(call.tool());
        if (extra != null && extra.matcher(text).find()) {
            return reroute(call, Reason.UNSUPPORTED_CONDITION);
        }
        if (extra == null && VALUE_CONDITION.matcher(text).find()) {
            return reroute(call, Reason.VALUE_CONDITION);
        }
        if (GROUPED.matcher(text).find()) {
            return reroute(call, Reason.GROUPED);
        }
        if (NON_ITEM_PICK.matcher(text).find()) {
            return reroute(call, Reason.NON_ITEM_RANKING);
        }
        if (call.output() != ToolOutput.RANKING) {
            return new Decision(call, null, false);
        }
        Boolean ascending = askedAscending(text, call.tool());
        if (ascending == null || ascending.equals(call.ascending())) {
            return new Decision(call, null, false);
        }
        ToolCall corrected = new ToolCall(call.tool(), call.output(), call.sku(), call.location(), call.days(),
                call.startDate(), call.endDate(), call.topN(), ascending, call.region(), call.category(),
                call.abcClass());
        return new Decision(corrected, null, true);
    }

    /** 공백을 지우고 소문자로 바꾼 뒤 거점 이름을 지운다. */
    private String normalize(String question) {
        String text = question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
        for (String name : dimensions.locationNames()) {
            text = text.replace(name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT), "@");
        }
        return text;
    }

    private static Decision reroute(ToolCall call, Reason reason) {
        return new Decision(call, reason, false);
    }

    /** 질문에 한쪽 방향의 낱말만 있을 때 그 방향. 둘 다 있거나 없으면 null(모델의 값을 둔다). */
    private static Boolean askedAscending(String text, ToolName tool) {
        boolean desc = DESCENDING.matcher(text).find();
        boolean asc = ASCENDING.matcher(text).find();
        if (desc == asc) {
            return null;
        }
        boolean ascending = asc;
        if (tool == ToolName.FORECAST_ACCURACY) {
            Matcher error = ERROR_DIRECTION.matcher(text);
            if (error.find()) {
                ascending = !ascending;
            }
        }
        return ascending;
    }
}
