# SCM 자연어 질의 챗봇 PoC

한국어 SCM 질문을 로컬 LLM이 SQL·되묻기·거부 중 하나로 바꾸고, 서버가 검증한 뒤 읽기 전용 계정으로 실행해 결과를 돌려준다.
이 저장소의 핵심 산출물은 챗봇 화면이 아니라 **30문항 골든셋으로 잰 정확도와, 프롬프트 v0 → v1 → v2의 비교**다.

- 모델: 로컬 Ollama `qwen2.5-coder:7b`, temperature 0, seed 42
- 스택: Java 21, Spring Boot 3.5, PostgreSQL 16, MyBatis, JSqlParser, React + TypeScript + Vite
- 문서: [docs/PRD.md](docs/PRD.md), [docs/API_SPEC.md](docs/API_SPEC.md), [AGENTS.md](AGENTS.md)

## 결과 요약

| 세트 | v0 | v1 | v2 |
| --- | --- | --- | --- |
| 기본 30문항 (동결 후 측정) | 7 / 30 = 0.2333 | 22 / 30 = 0.7333 | 19 / 30 = 0.6333 |
| 심화 20문항 (결과를 본 뒤 작성) | 5 / 20 = 0.2500 | 11 / 20 = 0.5500 | 9 / 20 = 0.4500 |

- v0는 테이블 구조와 출력 형식만 준 기준선이다. v1은 기준일, 기간 규칙, 지표 사전, 예시 5개를 더했다. v2는 v1에 스키마 축소, SQL 검증, 실행 오류 재시도를 더했다.
- 두 세트 모두 v1이 가장 높고 v2는 v1보다 낮다. 문항 수가 적고 1회 측정이라 v1과 v2의 차이(3문항, 2문항)는 우열로 단정하지 않는다.
- v0 → v1의 상승은 기간 집계에서 가장 크다(0 / 8 → 7 / 8). 지표 계산은 v1에서도 2 / 6에 머문다.

## 실행 방법

필요한 것: Java 21, Docker(Compose), Ollama와 `qwen2.5-coder:7b`, Node 22.

```bash
cp .env.example .env            # change-me 값을 바꾼다
set -a; . ./.env; set +a
bash scripts/db_reset.sh        # Postgres 컨테이너 + 스키마 + 시드
bash scripts/verify_db.sh       # 시드 검증 23개 항목
bash scripts/eval_restore.sh    # (선택) 저장된 측정 결과를 DB에 넣는다
```

백엔드와 화면

```bash
cd backend && ./gradlew bootRun --args='--chat.llm.provider=ollama'   # http://localhost:8080
cd frontend && npm ci && npm run dev                                   # http://localhost:5173
```

`--chat.llm.provider`를 주지 않으면 모델 없이 동작하는 `fake`가 쓰인다. 프롬프트 버전은 `--chat.prompt-version=v0|v1|v2`(기본 v1).

테스트와 평가

```bash
cd backend && ./gradlew test                    # 단위 테스트 (DB, 모델 불필요)
cd backend && ./gradlew integrationTest         # 통합 테스트 (DB, Ollama 필요)
cd backend && ./gradlew bootRun --args='--spring.profiles.active=eval --chat.llm.provider=ollama --eval.versions=v0,v1,v2'
bash scripts/final_check.sh                     # 동결 전 전체 점검
```

평가 실행은 `eval/golden_set.sha256`과 골든셋의 해시가 다르면 시작하지 않는다. 심화 세트는 `--eval.golden-path=../eval/golden_set_deep.json --eval.golden-hash-path=../eval/golden_set_deep.sha256`를 붙인다.

## 설계 요약

```
질문 ─▶ PromptBuilder(v0|v1|v2) ─▶ Ollama(JSON 스키마 강제) ─▶ LlmOutputParser
                                                                 │ action
                         clarify / refuse ◀──────────────────────┤
                                                                 ▼ sql
        (v2) SqlValidator ─▶ ReadOnlySqlExecutor(scm_ro) ─▶ 결과 + chat_log
                 │ 실행 오류 (v2, 1회)
                 └──▶ PostgreSQL 오류를 붙여 다시 질문
```

- **세 가지 action**: 모델은 `sql`, `clarify`, `refuse` 중 하나를 JSON으로 낸다. 형식은 Ollama의 structured output으로 모든 버전에서 똑같이 강제한다.
- **쓰기 차단은 여러 겹이다.**
  1. DB 계정 `scm_ro`는 `scm` 스키마 6개 테이블의 SELECT 권한만 가진다. 로그·평가 테이블이 있는 `app` 스키마에는 권한이 없다.
  2. 실행기는 읽기 전용 트랜잭션에서 실행하고 항상 롤백한다. SQL이 세션 설정을 바꿔도 풀의 커넥션에 남지 않는다.
  3. 드라이버 쿼리 타임아웃 5초. SQL이 `statement_timeout`을 풀어도 취소된다.
  4. (v2) `SqlValidator`가 단일 SELECT, 허용 테이블, 허용 함수 목록, LIMIT 200을 확인한다.
- **기준일 고정**: 데이터의 마지막 날 2026-11-08을 "오늘"로 본다. v1부터 프롬프트에 기준일과 기간 규칙을 넣는다.
- **채점**: 생성 SQL의 실행 결과를 정답 SQL의 실행 결과와 값으로 비교한다. 컬럼명과 컬럼 순서는 보지 않고, 생성 결과에 라벨 컬럼이 더 있어도 된다. 숫자는 상대 오차 0.1%까지 같은 값이다.
- **채점기 자체 검증**: 정답을 그대로 돌려주는 `oracle` provider로 평가하면 30 / 30이 나와야 한다. v2 경로(검증기와 LIMIT 적용)로도 30 / 30, 심화 세트 20 / 20을 확인했다.
- **골든셋 동결**: 측정 전에 SHA-256을 기록하고, 평가 실행기는 해시가 다르면 실행을 거부한다.
- **결정적 시드**: 난수 대신 `hashtextextended`로 값을 만들어 다시 생성해도 테이블 체크섬이 같다.

## 측정 결과

세 버전 모두 모델 `qwen2.5-coder:7b`, temperature 0.00, seed 42, 골든셋 sha256 `e8c27675…8576`로 1회씩 측정했다.

### 기본 30문항

| 유형 | 문항 | v0 (실행 12) | v1 (실행 22) | v2 (실행 50) |
| --- | --- | --- | --- | --- |
| SIMPLE_LOOKUP | 6 | 2 | 5 | 4 |
| PERIOD_AGG | 8 | 0 | 7 | 7 |
| METRIC | 6 | 0 | 2 | 3 |
| RANKING | 4 | 2 | 3 | 2 |
| AMBIGUOUS | 2 | 1 | 2 | 1 |
| REFUSE | 4 | 2 | 3 | 2 |
| **합계** | **30** | **7 (0.2333)** | **22 (0.7333)** | **19 (0.6333)** |

| 지표 | v0 | v1 | v2 |
| --- | --- | --- | --- |
| SQL 실행 성공률 | 0.9259 | 0.9600 | 0.9630 |
| 형식 준수율 | 1.0000 | 1.0000 | 1.0000 |
| 평균 응답 시간 | 3,579ms | 4,494ms | 7,375ms |
| 평균 프롬프트 토큰 | 621 | 2,025 | 1,496 |
| 평균 출력 토큰 | 62 | 69 | 74 |

### 오답 원인 (기본 30문항)

| 원인 | v0 | v1 | v2 |
| --- | --- | --- | --- |
| DATE_RANGE | 9 | 1 | 1 |
| METRIC_DEFINITION | 6 | 3 | 3 |
| SCHEMA_MISUNDERSTANDING | 3 | 0 | 0 |
| JOIN_AGGREGATION | 0 | 2 | 3 |
| ACTION_MISMATCH | 3 | 1 | 3 |
| SQL_EXECUTION_ERROR | 2 | 1 | 1 |
| 오답 합계 | 23 | 8 | 11 |

미분류(UNCLASSIFIED)는 0건이다. 자동 분류는 ACTION_MISMATCH, SQL_EXECUTION_ERROR, FORMAT_ERROR뿐이고, 나머지는 생성 SQL을 읽고 붙였다. 원인이 여러 개인 문항은 가장 먼저 걸리는 것 하나로 분류했다. 분류 내용은 `eval/reports/run-*-error-types.sql`에 있다.

### 심화 20문항

기본 세트의 결과를 본 뒤에 쓴 문항이다. 3개 테이블 조인, 두 기간 비교, 권역 집계, 이름 반환, 지시 무시 유도 등을 담았다(`eval/golden_set_deep.json`, sha256 `8ac43b1c…`).

| 유형 | 문항 | v0 (실행 36) | v1 (실행 37) | v2 (실행 51) |
| --- | --- | --- | --- | --- |
| SIMPLE_LOOKUP | 3 | 0 | 3 | 2 |
| PERIOD_AGG | 3 | 0 | 1 | 1 |
| METRIC | 4 | 0 | 2 | 1 |
| RANKING | 5 | 1 | 1 | 1 |
| AMBIGUOUS | 2 | 2 | 1 | 1 |
| REFUSE | 3 | 2 | 3 | 3 |
| **합계** | **20** | **5 (0.2500)** | **11 (0.5500)** | **9 (0.4500)** |

심화 세트에서는 SQL 실행 성공률이 0.73 ~ 0.75로 떨어진다. 조인과 집계가 여러 단계인 질문에서 문법 오류(집계 중첩, GROUP BY 누락, 없는 컬럼 참조)가 늘어난다.

## 오답 사례와 변경

### 1. 기준일이 없어 날짜를 추측한다 (DATE_RANGE)

- 질문: Q07 "어제 서울센터 전체 판매량은?"
- v0 생성 SQL: `... WHERE sale_date = CURRENT_DATE - INTERVAL '1 day' ...`
- 원인: 프롬프트에 "오늘"이 없어 실제 시계를 썼다. 데이터의 마지막 날은 2026-11-08이라 값이 다르다.
- 변경(v1): 기준일과 기간 규칙(어제, 최근 N일, 이번 주, 지난주, 이번 달, 지난달)을 넣고 `CURRENT_DATE`를 쓰지 말라고 지시했다.
- 결과: DATE_RANGE 오답이 9건에서 1건으로 줄었다. 기간 집계 유형은 0 / 8에서 7 / 8이 됐다. 남은 1건(Q13)은 "최근 30일"의 시작일을 하루 이틀 틀린 것이다.

### 2. 거점 이름과 SKU 표기를 값 그대로 넣는다 (SCHEMA_MISUNDERSTANDING)

- 질문: Q04 "SKU-7의 카테고리와 ABC 등급은?", Q12 "이번 달 부산센터 SKU-009 판매량은?"
- v0 생성 SQL: `WHERE sku = 'SKU-7'`, `WHERE location_id = '부산센터'`
- 원인: SKU 표기 규칙과, 거점은 이름으로 찾아 ID로 조인한다는 점을 스키마만으로는 알 수 없다.
- 변경(v1): 단위 규칙("SKU-7은 SKU-007로 정규화")과 용어 사전("거점 이름은 location.name으로 찾는다")을 넣었다.
- 결과: SCHEMA_MISUNDERSTANDING 3건이 0건이 됐다.

### 3. 지표를 자기 방식으로 계산한다 (METRIC_DEFINITION)

- 질문: Q20 "최근 28일 SKU-029의 일평균 판매량은?"
- 생성 SQL(v0, v1, v2 모두): `SELECT AVG(qty) ...`
- 원인: 정의는 "기간 내 판매 합 ÷ 일수"인데 `AVG(qty)`는 거점-일 행의 평균이라 5분의 1 수준이 나온다.
- 변경(v1): 지표 사전에 정의를 넣고 "AVG(qty)를 쓰지 않는다"고 적었다.
- 결과: **고쳐지지 않았다.** 세 버전 모두 `AVG(qty)`를 썼다. 지표 정의 오답은 6건에서 3건으로 줄었지만 결품률(Q16), 일평균(Q20), 예측 정확도 순위(Q21)는 남았다. 정의를 문장으로 주는 것만으로는 부족하고, 지표별 SQL 템플릿이나 조회 도구가 필요해 보인다.

### 4. SQL 실행 오류는 재시도로 일부만 줄어든다 (SQL_EXECUTION_ERROR)

- 질문: Q19 "SKU-008의 최근 13주 예측 정확도는?"
- v1 생성 SQL: `sales_daily`에서 없는 컬럼 `week_start`를 참조해 실행 오류.
- 변경(v2): PostgreSQL 오류 문구를 붙여 한 번 더 묻는다.
- 결과: v2도 같은 오류를 냈고, 재시도 답은 SQL이 아니었다. 이 모델은 "일별 판매를 주 단위로 묶어 예측과 맞춘다"는 단계를 오류 문구만으로는 만들지 못했다.

### 5. v2가 v1보다 낮아진 문항

- Q05, Q24, Q25, Q30은 v1에서 맞고 v2에서 틀렸다. Q15는 반대로 v2에서만 맞았다.
- v2는 질문마다 프롬프트의 스키마·지표 부분이 달라진다. Q24는 재고보유일수 순위에 `abc_class = 'A'` 조건을 임의로 넣었고, Q25("재고 어때?")와 Q30(영업이익)은 되묻거나 거부하지 않고 SQL을 냈다.
- 원인은 확정하지 못했다. 문맥이 줄면서 "되묻기·거부" 판단의 근거가 약해졌을 가능성이 있지만, 1회 측정의 차이라 우연일 수도 있다.

## 레드팀 점검에서 고친 것

- **검증기 우회**: `E'\''`와 `$$` 표기로 문자열 경계를 속이면 시스템 카탈로그 조회, `pg_sleep`, 주석이 검증을 통과했다. 테스트로 재현한 뒤 해당 표기를 거부하고, 함수는 허용 목록 방식으로 바꿨다. `repeat`, `generate_series`, `version`, `current_user`, `nextval` 등이 함께 막힌다.
- **타임아웃 해제**: `scm_ro`의 `statement_timeout`은 세션에서 바꿀 수 있는 기본값이다. DB에서는 막을 수 없어 드라이버 쿼리 타임아웃으로 취소하고, 항상 롤백해 설정이 남지 않게 했다.
- **임시 테이블**: PUBLIC 기본 권한으로 `scm_ro`가 임시 테이블을 만들 수 있었다. 권한을 회수했다.
- **조용한 잘림**: v2의 LIMIT 200으로 결과가 잘려도 `truncated`가 false였다. 검증기가 LIMIT을 적용했고 결과가 상한을 채우면 true로 둔다.
- **모델 장애 시 평가**: Ollama가 꺼진 채 평가하면 정확도 0인 실행이 정상 측정처럼 저장됐다. 중단하고 그 실행을 지운다.
- **시드의 의도하지 않은 결품**: 프로모션 사전 물량이 정기 발주를 밀어 결품이 생겼다. 재고 위치 계산에서 분리했다.

## 한계

- **가짜 데이터, 적은 문항, 1회 측정**이다. 수치는 방향을 보는 용도이며 버전 간 2 ~ 3문항 차이는 우열로 읽지 않는다.
- **v0 점수는 측정일에 따라 달라진다.** v0는 기준일이 없어 15개 문항에서 `CURRENT_DATE`를 썼다. 측정일(2026-11-12 ~ 13)이 데이터 마지막 날과 가까워 Q22, Q23은 맞았지만 다른 날에는 틀릴 수 있다.
- **few-shot 예시가 골든셋과 같은 틀이다.** 질문 문자열은 겹치지 않지만, 안전재고 미달·재고보유일수·어제 판매량 예시는 골든셋 문항과 구조가 같다. v0 → v1 상승분의 일부는 예시가 정답의 틀을 알려 준 효과일 수 있고, 그 크기는 재지 않았다. 심화 세트로 방향만 따로 확인했다.
- **심화 세트는 결과를 본 뒤에 썼다.** 약점을 알고 쓴 문항이라 편향이 있을 수 있다.
- **오답 분류와 교차 검증의 독립성이 약하다.** v1·v2와 심화 세트의 오답 분류, `eval/crosscheck.md`의 재계산은 사람의 손계산이 아니라 생성 SQL과 원본 데이터를 근거로 한 판정이다.
- **추가 컬럼 허용 규칙의 부작용**: 정답이 값 하나인 문항은 생성 결과의 어느 컬럼이든 그 값과 같으면 통과한다.
- **`scm_app`은 컨테이너의 슈퍼유저다**(초기화 단순화). LLM SQL이 쓰는 계정은 `scm_ro`로 분리되어 있다.
- **v2의 스키마 축소는 키워드 규칙**이라 표현이 바뀌면 누락될 수 있다. 키워드가 없으면 전체 스키마로 돌아간다.
- **v2는 느리다.** 프롬프트 토큰은 v1보다 적은데 응답은 1.6배 걸렸다. 질문마다 프롬프트 앞부분이 달라져 모델의 프롬프트 캐시를 재사용하지 못하기 때문으로 추정한다(확인하지 않음).
- 조회 도구 호출, 숫자 후검증, RAG, 테넌트 격리, 결과 문장 생성, 로그인, 배포는 범위 밖이다.

## 테스트와 CI

- 단위 테스트는 DB와 모델 없이 돈다. 통합 테스트는 `integration` 태그, 그중 모델 호출은 `ollama` 태그로 나눴다.
- GitHub Actions(`.github/workflows/ci.yml`)는 PR과 main 푸시마다 다음을 실행한다.
  - 골든셋 해시 확인, DB 초기화와 시드 검증, 테이블 체크섬 비교
  - 단위 테스트, DB 통합 테스트(Ollama 제외)
  - `oracle` provider로 v0·v1·v2 평가를 돌려 30 / 30인지 확인(채점기와 검증기 회귀 방지)
  - 프런트 타입 검사, 린트, 빌드
- 실제 모델 측정은 로컬에서만 한다. CI 러너에는 모델이 없다.

## 저장소 구조

```
db/         역할, 스키마, 시드, 권한, 검증 SQL
scripts/    db_reset, verify_db, db_checksum, eval_dump, eval_restore, final_check
backend/    Spring Boot (controller, service, llm, prompt, validator, executor, mapper, eval)
frontend/   React + TypeScript + Vite
prompts/    v0.txt, v1.txt, v2.txt, metrics.yml, fewshot.json, schema_rules.yml
eval/       golden_set.json(동결), golden_set_deep.json(동결), crosscheck.md, reports/
docs/       PRD, API 명세, AI 실수 기록
```

## 면접용 1분 설명 (초안, 직접 다듬을 것)

> 한국어 SCM 질문을 SQL로 바꿔 답하는 챗봇을 만들면서, "잘 되는 것 같다"가 아니라 숫자로 말할 수 있게 평가 체계를 먼저 세웠습니다.
> 30문항 골든셋을 측정 전에 해시로 동결하고, 정답을 그대로 넣었을 때 30점이 나오는지로 채점기부터 검증했습니다.
> 스키마만 준 기준선은 30문항 중 7문항이었고, 기준일과 지표 사전을 넣자 22문항이 됐습니다. 오답을 원인별로 나눠 보니 날짜 해석 오류가 9건에서 1건으로 줄어든 것이 가장 컸습니다.
> 반면 SQL 검증과 재시도를 더한 v2는 19문항으로 오히려 낮았고, 지표 계산 오답은 끝까지 남았습니다. 이 결과를 숨기지 않고 한계로 적었습니다.
> 안전 쪽은 읽기 전용 계정, 읽기 전용 트랜잭션, 타임아웃, SQL 검증의 네 겹으로 막았고, 레드팀 점검에서 검증기 우회를 실제로 재현해 고쳤습니다.

(수치와 표현은 직접 확인하고 자신의 말로 바꿔 쓴다.)
