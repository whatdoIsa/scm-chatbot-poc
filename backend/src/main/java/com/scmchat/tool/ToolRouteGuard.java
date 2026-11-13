package com.scmchat.tool;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * v5의 도구 경로 점검(PRD 12장). 라우터가 고른 도구 호출이 질문과 맞는지 질문의 낱말로 확인한다.
 * 모델에게 "이 도구는 이런 질문에 쓰지 말라"고 적어 주는 것(v4의 not_for)만으로는 막히지 않던 오답을 서버가 규칙으로 막는다.
 *
 * <ul>
 *   <li>도구는 자기 지표를 계산한다. 질문에 그 지표를 가리키는 낱말이 없으면(예: "출고량 합계"에 일평균 도구)
 *       도구를 쓰지 않고 SQL 단계로 보낸다.</li>
 *   <li>거점별·카테고리별로 묶어 보는 질문은 도구가 표현하지 못하므로 SQL 단계로 보낸다.</li>
 *   <li>지표 값에 건 조건(3일 미만)이나 도구 인자에 없는 컬럼의 조건(리드타임이 7일인)이 있으면 SQL 단계로 보낸다.</li>
 *   <li>ranking은 품목 순위다. 질문이 거점·센터·권역·카테고리의 순위를 물으면 SQL 단계로 보낸다.</li>
 *   <li>ranking의 정렬 방향은 질문에 한쪽 방향의 낱말만 있으면 그 방향으로 고친다.</li>
 * </ul>
 * 판단은 공백을 지운 질문에서 한다("재고 보유일수"와 "재고보유일수"를 같게 본다).
 */
@Component
public class ToolRouteGuard {

    /** 점검 결과. call은 그대로 실행할(정렬 방향을 고쳤을 수 있는) 호출이고, rerouteReason이 있으면 SQL 단계로 보낸다. */
    public record Decision(ToolCall call, String rerouteReason, boolean orderCorrected) {

        public boolean reroute() {
            return rerouteReason != null;
        }
    }

    /** 도구마다 질문에 있어야 하는 지표 낱말. 공백을 지운 소문자 질문에 대고 찾는다. */
    private static final Map<ToolName, Pattern> METRIC_WORDS = Map.of(
            ToolName.AVG_DAILY_SALES, Pattern.compile("평균"),
            ToolName.DAYS_OF_SUPPLY, Pattern.compile("재고보유일수|재고일수|보유일수|dos|며칠치|며칠버틸|며칠분"),
            ToolName.STOCKOUT_RATE, Pattern.compile("결품률|결품율|결품비율|품절률|품절율"),
            ToolName.FORECAST_ACCURACY, Pattern.compile("정확도|정확했|잘맞|wmape|mape|오차율|예측.{0,6}(오차|안맞|빗나|틀)"),
            ToolName.BELOW_SAFETY_STOCK, Pattern.compile("안전재고.{0,6}(미달|밑돌|밑도|밑으|못미치|미치지|부족|아래|미만|모자|적은|낮은|작은)"
                    + "|(미달|밑돌|밑도|못미치|부족|모자).{0,6}안전재고"),
            ToolName.REORDER_NEEDED, Pattern.compile("(재주문|발주|재발주).{0,8}(필요|해야|대상|시점이된|시점인)"));

    /** 목록·개수 도구가 표현하지 못하는 다른 컬럼의 조건(예: 리드타임이 7일인, 입고 예정이 없는). */
    private static final Map<ToolName, Pattern> EXTRA_CONDITION = Map.of(
            ToolName.BELOW_SAFETY_STOCK, Pattern.compile("리드타임|재주문점|발주점|입고예정|입고"),
            ToolName.REORDER_NEEDED, Pattern.compile("리드타임|안전재고"));

    /** 지표 값에 건 조건(예: 3일 미만, 리드타임보다 짧은). 값·순위 도구는 이런 조건을 걸 수 없다. */
    private static final Pattern VALUE_CONDITION = Pattern.compile(
            "(미만|초과|이상|이하)(인|이|의|으로)|보다(짧|길|낮|높|적|많|작|크)|넘는|넘은|넘게|안되는|밑도는");

    private static final String ENTITY = "(거점|센터|창고|권역|지역|카테고리)";
    private static final Pattern NON_ITEM_RANKING = Pattern.compile(
            "어느" + ENTITY
                    + "|" + ENTITY + "(은|는|이|가|을|를)?(어디|어느|순으로|순서|별로|별순)"
                    + "|(가장|제일|최고로|최저로)[^0-9]{0,14}" + ENTITY + "(은|는|이|가|을|를)?(\\?|$|어디|알려|보여|순)");

    private static final Pattern DESCENDING = Pattern.compile(
            "(가장|제일).{0,4}?(높|많|길|긴|크|큰|잘)|상위|높은순|많은순|긴순|큰순|높은품목|많은품목|긴품목|큰품목|베스트|탑");
    private static final Pattern ASCENDING = Pattern.compile(
            "(가장|제일).{0,4}?(낮|적|짧|작|못|안맞)|하위|낮은순|적은순|짧은순|작은순|낮은품목|적은품목|짧은품목|작은품목|워스트");
    /** 거점별·카테고리별처럼 묶어서 보는 질문. 도구는 묶음별 값을 내지 않는다. */
    private static final Pattern GROUPED = Pattern.compile(ENTITY + "별");
    /** 예측 정확도 도구에서 "오차가 큰"은 정확도가 낮은 쪽이다. */
    private static final Pattern ERROR_WORDS = Pattern.compile("오차|틀린|빗나");

    public Decision check(String question, ToolCall call) {
        String text = question.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);

        if (!METRIC_WORDS.get(call.tool()).matcher(text).find()) {
            return new Decision(call, "question does not name the metric of " + call.tool().value(), false);
        }
        Pattern extra = EXTRA_CONDITION.get(call.tool());
        if (extra != null && extra.matcher(text).find()) {
            return new Decision(call, "question has a condition " + call.tool().value() + " cannot express", false);
        }
        if (extra == null && VALUE_CONDITION.matcher(text).find()) {
            return new Decision(call, "question puts a condition on the metric value", false);
        }
        if (GROUPED.matcher(text).find()) {
            return new Decision(call, "question groups by location, region or category", false);
        }
        if (call.output() != ToolOutput.RANKING) {
            return new Decision(call, null, false);
        }
        if (NON_ITEM_RANKING.matcher(text).find()) {
            return new Decision(call, "question ranks locations, regions or categories, not items", false);
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

    /** 질문에 한쪽 방향의 낱말만 있을 때 그 방향. 둘 다 있거나 없으면 null(모델의 값을 둔다). */
    private static Boolean askedAscending(String text, ToolName tool) {
        boolean desc = DESCENDING.matcher(text).find();
        boolean asc = ASCENDING.matcher(text).find();
        if (desc == asc) {
            return null;
        }
        boolean ascending = asc;
        if (tool == ToolName.FORECAST_ACCURACY && ERROR_WORDS.matcher(text).find()) {
            ascending = !ascending;
        }
        return ascending;
    }
}
