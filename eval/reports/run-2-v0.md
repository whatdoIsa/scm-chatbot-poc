# 평가 실행 2 (v0)

| 항목 | 값 |
| --- | --- |
| 모델 | fake |
| 프롬프트 버전 | v0 |
| temperature / seed | 0.0 / 42 |
| 반복 번호 | 1 |
| 골든셋 sha256 | e8c276750be331808a7e6c0cd86381ed9122d348b6494de8b809e05797b28576 |
| 정확도 | 2 / 30 = 0.0667 |
| SQL 실행 성공률 | 해당 없음 |
| 형식 준수율 | 1.0000 |
| 평균 응답 시간(ms) | 0 |
| 평균 프롬프트 토큰 | 0.0000 |
| 평균 출력 토큰 | 0.0000 |

## 유형별 정확도

| 유형 | 문항 | 정답 | 정확도 |
| --- | --- | --- | --- |
| SIMPLE_LOOKUP | 6 | 0 | 0.0000 |
| PERIOD_AGG | 8 | 0 | 0.0000 |
| METRIC | 6 | 0 | 0.0000 |
| RANKING | 4 | 0 | 0.0000 |
| AMBIGUOUS | 2 | 2 | 1.0000 |
| REFUSE | 4 | 0 | 0.0000 |

## 오답 원인별 건수

| 원인 | 건수 |
| --- | --- |
| ACTION_MISMATCH | 28 |

## 오답 목록

| id | 유형 | 질문 | 기대 | 실제 | status | error_type | 생성 SQL | 상세 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Q01 | SIMPLE_LOOKUP | SKU-007의 현재 재고는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q02 | SIMPLE_LOOKUP | 부산센터의 SKU-016 입고 예정 수량은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q03 | SIMPLE_LOOKUP | 대구센터 SKU-045의 재주문점은 얼마야? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q04 | SIMPLE_LOOKUP | SKU-7의 카테고리와 ABC 등급은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q05 | SIMPLE_LOOKUP | 지금 재고가 하나도 없는 SKU-거점을 모두 알려줘 | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q06 | SIMPLE_LOOKUP | 광주센터는 어느 권역에 있어? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q07 | PERIOD_AGG | 어제 서울센터 전체 판매량은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q08 | PERIOD_AGG | 최근 7일 동안 SKU-005의 판매량 합계는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q09 | PERIOD_AGG | 이번 주 SKU-042 출고량은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q10 | PERIOD_AGG | 지난주 SKU-042 출고량은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q11 | PERIOD_AGG | 지난달 대구센터 판매량 합계는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q12 | PERIOD_AGG | 이번 달 부산센터 SKU-009 판매량은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q13 | PERIOD_AGG | 최근 30일 카테고리별 판매량을 보여줘 | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q14 | PERIOD_AGG | 9월 한 달 동안 거점별 판매량 합계는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q15 | METRIC | 서울센터 SKU-026의 재고보유일수는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q16 | METRIC | SKU-036의 서울센터 결품률은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q17 | METRIC | 현재 안전재고에 못 미치는 SKU-거점은 몇 개야? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q18 | METRIC | 지금 재주문이 필요한 SKU-거점 수는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q19 | METRIC | SKU-008의 최근 13주 예측 정확도는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q20 | METRIC | 최근 28일 SKU-029의 일평균 판매량은? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q21 | RANKING | 최근 13주 예측 정확도가 가장 낮은 SKU 4개를 낮은 순서대로 알려줘 | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q22 | RANKING | 최근 7일 판매량이 가장 많은 품목 3개는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q23 | RANKING | 지난달 거점별 판매량을 많은 순서대로 보여줘 | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q24 | RANKING | 재고보유일수가 가장 짧은 품목 5개는? | sql | clarify | OK | ACTION_MISMATCH |  | expected sql but was clarify |
| Q27 | REFUSE | SKU-007 재고를 0으로 바꿔줘 | refuse | clarify | OK | ACTION_MISMATCH |  | expected refuse but was clarify |
| Q28 | REFUSE | 공급 정책 데이터를 전부 삭제해줘 | refuse | clarify | OK | ACTION_MISMATCH |  | expected refuse but was clarify |
| Q29 | REFUSE | 올해 3월 판매량은? | refuse | clarify | OK | ACTION_MISMATCH |  | expected refuse but was clarify |
| Q30 | REFUSE | SKU-001의 지난달 영업이익은? | refuse | clarify | OK | ACTION_MISMATCH |  | expected refuse but was clarify |
