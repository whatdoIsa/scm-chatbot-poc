package com.scmchat.eval;

import com.scmchat.executor.QueryResult;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 정답 SQL 결과와 생성 SQL 결과를 값으로 비교한다(PRD 5.4).
 * 컬럼명과 컬럼 순서는 보지 않는다. 정답의 각 컬럼이 생성 결과의 서로 다른 컬럼 하나와 모든 행에서 일치하면 같다고 본다.
 * 생성 결과에 컬럼이 더 있어도 되지만 행 수는 같아야 한다.
 */
@Component
public class ResultComparator {

    private static final BigDecimal RELATIVE_TOLERANCE = new BigDecimal("0.001");
    private static final BigDecimal ZERO_TOLERANCE = new BigDecimal("1e-9");

    public record Comparison(boolean match, String reason) {
    }

    public Comparison compare(QueryResult expected, QueryResult actual, boolean orderSensitive) {
        if (expected.rows().size() != actual.rows().size()) {
            return new Comparison(false,
                    "row count differs: expected " + expected.rows().size() + " but was " + actual.rows().size());
        }
        int expectedColumns = expected.columns().size();
        int actualColumns = actual.columns().size();
        if (actualColumns < expectedColumns) {
            return new Comparison(false,
                    "column count is too small: expected at least " + expectedColumns + " but was " + actualColumns);
        }
        List<List<Object>> expectedRows = normalize(expected.rows());
        List<List<Object>> actualRows = normalize(actual.rows());

        List<List<Integer>> candidates = new ArrayList<>();
        for (int expectedColumn = 0; expectedColumn < expectedColumns; expectedColumn++) {
            List<Integer> matching = new ArrayList<>();
            for (int actualColumn = 0; actualColumn < actualColumns; actualColumn++) {
                if (columnMatches(expectedRows, expectedColumn, actualRows, actualColumn, orderSensitive)) {
                    matching.add(actualColumn);
                }
            }
            if (matching.isEmpty()) {
                return new Comparison(false,
                        "no column matches expected column " + expected.columns().get(expectedColumn));
            }
            candidates.add(matching);
        }
        int[] mapping = new int[expectedColumns];
        if (assign(0, mapping, new boolean[actualColumns], candidates, expectedRows, actualRows, orderSensitive)) {
            return new Comparison(true, null);
        }
        return new Comparison(false, "columns match one by one but rows do not match as a whole");
    }

    /** 정답 컬럼마다 서로 다른 생성 컬럼을 하나씩 배정해 본다. 정답 컬럼 수가 작아 전수 탐색으로 충분하다. */
    private boolean assign(int expectedColumn, int[] mapping, boolean[] used, List<List<Integer>> candidates,
            List<List<Object>> expectedRows, List<List<Object>> actualRows, boolean orderSensitive) {
        if (expectedColumn == mapping.length) {
            // 순서를 보는 비교는 컬럼별 행 단위 일치가 곧 전체 일치다.
            return orderSensitive || sameRowsIgnoringOrder(expectedRows, project(actualRows, mapping));
        }
        for (int actualColumn : candidates.get(expectedColumn)) {
            if (!used[actualColumn]) {
                used[actualColumn] = true;
                mapping[expectedColumn] = actualColumn;
                if (assign(expectedColumn + 1, mapping, used, candidates, expectedRows, actualRows, orderSensitive)) {
                    return true;
                }
                used[actualColumn] = false;
            }
        }
        return false;
    }

    private static boolean columnMatches(List<List<Object>> expectedRows, int expectedColumn,
            List<List<Object>> actualRows, int actualColumn, boolean orderSensitive) {
        if (!orderSensitive) {
            return sameRowsIgnoringOrder(
                    project(expectedRows, new int[] {expectedColumn}), project(actualRows, new int[] {actualColumn}));
        }
        for (int row = 0; row < expectedRows.size(); row++) {
            if (!sameValue(expectedRows.get(row).get(expectedColumn), actualRows.get(row).get(actualColumn))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameRowsIgnoringOrder(List<List<Object>> expectedRows, List<List<Object>> actualRows) {
        // 정렬한 뒤 가까운 자리부터 짝을 찾는다. 허용 오차 때문에 정렬 순서가 어긋나도 이웃한 행과 짝지을 수 있다.
        List<List<Object>> expectedSorted = new ArrayList<>(expectedRows);
        List<List<Object>> actualSorted = new ArrayList<>(actualRows);
        expectedSorted.sort(ROW_ORDER);
        actualSorted.sort(ROW_ORDER);
        boolean[] used = new boolean[actualSorted.size()];
        for (int row = 0; row < expectedSorted.size(); row++) {
            int match = findMatch(expectedSorted.get(row), actualSorted, used, row);
            if (match < 0) {
                return false;
            }
            used[match] = true;
        }
        return true;
    }

    private static int findMatch(List<Object> expectedRow, List<List<Object>> actualRows, boolean[] used, int start) {
        for (int distance = 0; distance < actualRows.size(); distance++) {
            for (int candidate : new int[] {start - distance, start + distance}) {
                if (candidate >= 0 && candidate < actualRows.size() && !used[candidate]
                        && sameRow(expectedRow, actualRows.get(candidate))) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    private static boolean sameRow(List<Object> expectedRow, List<Object> actualRow) {
        for (int column = 0; column < expectedRow.size(); column++) {
            if (!sameValue(expectedRow.get(column), actualRow.get(column))) {
                return false;
            }
        }
        return true;
    }

    private static List<List<Object>> project(List<List<Object>> rows, int[] mapping) {
        List<List<Object>> projected = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> cells = new ArrayList<>(mapping.length);
            for (int column : mapping) {
                cells.add(row.get(column));
            }
            projected.add(cells);
        }
        return projected;
    }

    /** 숫자는 상대 오차 0.1% 이내(정답이 0이면 절대 오차 1e-9 이내)를 같은 값으로 본다. */
    private static boolean sameValue(Object expected, Object actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected instanceof BigDecimal expectedNumber && actual instanceof BigDecimal actualNumber) {
            BigDecimal difference = expectedNumber.subtract(actualNumber).abs();
            BigDecimal tolerance = expectedNumber.signum() == 0
                    ? ZERO_TOLERANCE
                    : expectedNumber.abs().multiply(RELATIVE_TOLERANCE);
            return difference.compareTo(tolerance) <= 0;
        }
        return expected.equals(actual);
    }

    /** 정수·실수·numeric은 BigDecimal로, 날짜를 포함한 나머지는 문자열로 맞춘다. 불리언과 null은 그대로 둔다. */
    private static List<List<Object>> normalize(List<List<Object>> rows) {
        List<List<Object>> normalized = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> cells = new ArrayList<>(row.size());
            for (Object value : row) {
                cells.add(normalizeCell(value));
            }
            normalized.add(cells);
        }
        return normalized;
    }

    private static Object normalizeCell(Object value) {
        if (value == null || value instanceof Boolean) {
            return value;
        }
        if (value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Number number) {
            if (number instanceof Double || number instanceof Float) {
                double doubleValue = number.doubleValue();
                return Double.isFinite(doubleValue) ? BigDecimal.valueOf(doubleValue) : number.toString();
            }
            return new BigDecimal(number.toString());
        }
        return value.toString();
    }

    private static int rank(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof BigDecimal) {
            return 1;
        }
        return value instanceof Boolean ? 2 : 3;
    }

    private static final Comparator<Object> CELL_ORDER = (left, right) -> {
        int byRank = Integer.compare(rank(left), rank(right));
        if (byRank != 0 || left == null) {
            return byRank;
        }
        if (left instanceof BigDecimal leftNumber) {
            return leftNumber.compareTo((BigDecimal) right);
        }
        if (left instanceof Boolean leftFlag) {
            return leftFlag.compareTo((Boolean) right);
        }
        return ((String) left).compareTo((String) right);
    };

    private static final Comparator<List<Object>> ROW_ORDER = (left, right) -> {
        for (int column = 0; column < left.size(); column++) {
            int byCell = CELL_ORDER.compare(left.get(column), right.get(column));
            if (byCell != 0) {
                return byCell;
            }
        }
        return 0;
    };
}
