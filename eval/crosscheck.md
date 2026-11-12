# 골든셋 교차 검증

- 작성일: 2026-11-13
- 데이터: `bash scripts/db_checksum.sh | diff - db/verify/checksum.expected` 결과가 같음(SAME_DATA)을 확인한 DB
- 골든셋: `eval/golden_set.sha256`으로 동결된 30문항 (sha256 `e8c27675...8576`)
- 방법: 원본 행을 CSV로 추출(`SELECT *`, 집계 없음)한 뒤 SQL을 쓰지 않고 다시 계산했다. 합계·조인·필터는 파이썬 표준 라이브러리(csv, 딕셔너리, 분수 연산)로 했고 정답 SQL은 계산에 쓰지 않았다
- 한계: 스프레드시트 수작업이 아니라 스크립트 계산이다. 또한 정답 SQL 결과를 이미 본 상태에서 계산했으므로, 값을 모르는 상태에서 하는 교차 검증보다 독립성이 약하다. 계산 과정은 기계적이라 결과를 맞추는 조정은 없었다
- 추출한 CSV와 계산 스크립트는 `local-notes/`에 두며 저장소에는 올리지 않는다. 추출 명령은 문항마다 적었다

## 요약

| 문항 | 유형 | 다른 방법 결과 | 정답 SQL 결과 | 일치 |
| --- | --- | --- | --- | --- |
| Q03 | 단순 조회 | 57 | 57 | 일치 |
| Q10 | 기간 집계 | 565 | 565 | 일치 |
| Q09 | 기간 집계(보조) | 564 | 564 | 일치 |
| Q18 | 지표 | 29 | 29 | 일치 |
| Q22 | 순위 | SKU-018, SKU-005, SKU-009 | SKU-018, SKU-005, SKU-009 | 일치 |
| Q19 | 예측 정확도 | 0.277183 | 0.277183 | 일치 |

5문항(보조 1문항 포함 6개 값) 모두 일치한다.

## Q03 대구센터 SKU-045 재주문점

- 원본: `SELECT * FROM scm.supply_policy WHERE sku = 'SKU-045' ORDER BY location_id` (5행)
- 방법: `location_id = 'LOC-05'`인 행의 `reorder_point`를 읽음
- 결과: 57

## Q10 / Q09 SKU-042 지난주·이번 주 출고량

- 원본: `SELECT * FROM scm.sales_daily WHERE sku = 'SKU-042' ORDER BY sale_date, location_id` (455행)
- 방법: `sale_date`가 범위 안인 행의 `qty` 합. 지난주 2026-10-26 ~ 2026-11-01, 이번 주 2026-11-02 ~ 2026-11-08 (양 끝 포함)
- 검산: 두 범위 모두 35행(7일 x 5거점)
- 결과: 지난주 = 565, 이번 주 = 564
- 참고: 두 값의 차이는 1(0.18%)이다. 채점 허용 오차 0.1%보다 크므로 두 기간을 바꿔 답하면 오답으로 채점된다

## Q18 재주문 필요 SKU-거점 수

- 원본: `SELECT * FROM scm.inventory_daily WHERE snap_date = DATE '2026-11-08' ORDER BY sku, location_id` (250행), `SELECT * FROM scm.supply_policy ORDER BY sku, location_id` (250행)
- 방법: `(sku, location_id)`를 키로 두 파일을 붙이고(행 순서에 기대지 않음) `on_hand + in_transit <= reorder_point`인 쌍을 셈
- 검산: 두 파일의 키 집합이 같고 각각 250개
- 결과: `<=` 기준 = 29, `<` 기준 = 26
- 경계 쌍(`on_hand + in_transit = reorder_point`): SKU-017·LOC-05(대구), SKU-022·LOC-05(대구), SKU-046·LOC-02(부산) 3쌍. 이 3쌍이 29와 26의 차이를 만든다

## Q22 최근 7일 판매 상위 3개 SKU

- 원본: `SELECT * FROM scm.sales_daily WHERE sale_date BETWEEN DATE '2026-11-02' AND DATE '2026-11-08' ORDER BY sale_date, sku, location_id` (1,750행)
- 방법: `sku`별 `qty` 합을 내림차순 정렬(동점이면 sku 오름차순)
- 검산: 1,750행 = 7일 x 50 SKU x 5거점, 날짜 최소 2026-11-02, 최대 2026-11-08, SKU 50개
- 결과: 1위 SKU-018 (2,497), 2위 SKU-005 (2,460), 3위 SKU-009 (2,160). 4위는 SKU-039 (1,791)이고 3·4위 차이는 17.08%

## Q19 SKU-008 최근 13주 예측 정확도

- 원본: `SELECT * FROM scm.sales_daily WHERE sku = 'SKU-008' ORDER BY sale_date, location_id` (455행), `SELECT * FROM scm.forecast_weekly WHERE sku = 'SKU-008' ORDER BY week_start, location_id` (65행 = 13주 x 5거점)
- 방법: 판매일을 그 주의 월요일로 바꿔 (주, 거점)별 실적을 구하고, 예측 행마다 |실적 - 예측|을 더해 실적 합으로 나눔
- 검산: 주에 매칭된 실적 합 22,054 = 판매 원본 합 22,054 (모든 판매가 13주 안에 들어감). 예측 합 37,995
- 결과: 절대오차 합 15,941, WMAPE = 15,941 / 22,054 = 0.722817, 정확도 = 0.277183
- 계산 단위: 거점을 합산한 뒤 (주) 단위로 오차를 구해도 WMAPE는 0.722817로 같다. 이 SKU는 모든 주·거점에서 예측이 실적보다 커서 두 계산이 같아진다

## 불일치와 조치

없음.
