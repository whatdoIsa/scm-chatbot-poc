# API 명세와 내부 계약

버전 1.2 · PRD.md와 함께 읽는다. 이 문서는 외부 API, LLM 계약, 파일 포맷, DB 스키마, 자바 인터페이스, 설정 키의 단일 기준이다.

1.2(2026-11-13): v3 계약을 8장에 추가했다. 1~7장은 v0~v2 기준이며, v3에서 달라지는 부분은 8장이 우선한다.

## 1. 공통

- Base URL `http://localhost:8080`, JSON UTF-8, 인증 없음
- 날짜 `yyyy-MM-dd`, 시각 ISO-8601, 숫자는 JSON number
- 요청 본문 검증 실패와 서버 오류는 `ErrorResponse`, 챗봇 처리 결과는 상태와 무관하게 `ChatResponse`

```json
{"code": "INVALID_REQUEST", "message": "question must be 1..500 characters"}
```

## 2. 엔드포인트

### 2.1 POST /api/chat

요청

| 필드 | 타입 | 규칙 |
| --- | --- | --- |
| question | string | 공백 제외 1~500자. 위반 시 400 `INVALID_REQUEST` |

응답 `ChatResponse`

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| action | string | `sql`, `clarify`, `refuse`. LLM 호출 실패·형식 오류면 `null` |
| status | string | `OK`, `SQL_REJECTED`, `SQL_EXECUTION_ERROR`, `FORMAT_ERROR`, `LLM_UNAVAILABLE` |
| message | string/null | clarify·refuse 문장 또는 실패 사유 |
| sql | string/null | 실제 실행한 SQL(v2는 LIMIT 부여 후). 실행 전 거부된 SQL은 원문 |
| columns | string[]/null | 결과 컬럼명 |
| rows | array[]/null | 행 배열(각 행은 값 배열) |
| rowCount | number/null | 반환 행 수 |
| truncated | boolean | 최대 행 수(1000)에서 잘렸는지 |
| asOfDate | string | 기준일 |
| promptVersion | string | `v0`, `v1`, `v2` |
| model | string | LLM 모델명 |
| attempts | number | LLM 호출 횟수(1 또는 2) |
| elapsedMs | number | 서버 처리 전체 시간 |
| chatLogId | number | `app.chat_log.id` |

상태와 HTTP 코드

| status | HTTP | 조건 |
| --- | --- | --- |
| OK | 200 | sql 실행 성공, 또는 clarify·refuse |
| SQL_REJECTED | 422 | v2 검증 실패 (message에 사유) |
| SQL_EXECUTION_ERROR | 422 | 실행 오류(재시도 후에도 실패 포함) |
| FORMAT_ERROR | 422 | LLM 출력 파싱 실패, action=sql인데 sql이 비어 있음, action이 clarify·refuse인데 message가 비어 있음 |
| LLM_UNAVAILABLE | 502 | Ollama 연결 실패·타임아웃 |
| (ErrorResponse) | 400 / 500 | 요청 검증 실패 / 예기치 않은 오류 |

예시 1: 조회

```bash
curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' \
  -d '{"question":"SKU-007의 현재 재고는?"}'
```

```json
{
  "action": "sql", "status": "OK", "message": null,
  "sql": "SELECT on_hand FROM inventory_daily WHERE sku = 'SKU-007' AND snap_date = DATE '2026-11-08'",
  "columns": ["on_hand"], "rows": [[120]], "rowCount": 5, "truncated": false,
  "asOfDate": "2026-11-08", "promptVersion": "v1", "model": "qwen2.5-coder:7b",
  "attempts": 1, "elapsedMs": 6120, "chatLogId": 41
}
```

(예시의 rowCount는 형식 설명용이며 실제로는 rows 길이와 같다.)

예시 2: 되묻기

```json
{
  "action": "clarify", "status": "OK", "message": "어느 품목·거점·기간의 재고를 확인할까요?",
  "sql": null, "columns": null, "rows": null, "rowCount": null, "truncated": false,
  "asOfDate": "2026-11-08", "promptVersion": "v1", "model": "qwen2.5-coder:7b",
  "attempts": 1, "elapsedMs": 2310, "chatLogId": 42
}
```

예시 3: 거부는 action=`refuse`, message에 사유, 나머지 구조는 예시 2와 같다.

값 직렬화: `numeric` → number, `date` → `yyyy-MM-dd` 문자열, `NULL` → null.

### 2.2 GET /api/meta

```json
{"asOfDate": "2026-11-08", "promptVersion": "v1", "model": "qwen2.5-coder:7b", "llmProvider": "ollama"}
```

### 2.3 GET /api/eval/summary

쿼리 `model`(선택). 최신 실행부터 반환한다.

```json
{
  "runs": [{
    "runId": 3, "promptVersion": "v0", "model": "qwen2.5-coder:7b",
    "temperature": 0.0, "seed": 42, "repeatNo": 1,
    "total": 30, "correct": 12, "accuracy": 0.4,
    "sqlExecSuccessRate": 0.8, "formatOkRate": 1.0, "avgElapsedMs": 8200,
    "byType": [{"type": "SIMPLE_LOOKUP", "total": 6, "correct": 4, "accuracy": 0.6667}],
    "errorTypes": [{"errorType": "DATE_RANGE", "count": 3}, {"errorType": "UNCLASSIFIED", "count": 2}]
  }]
}
```

- `sqlExecSuccessRate`: action=sql인 응답 중 실행 오류 없이 끝난 비율(분모 0이면 null)
- `formatOkRate`: FORMAT_ERROR가 아닌 응답 비율
- `UNCLASSIFIED`: `correct=false`이고 `error_type IS NULL`인 건수

## 3. LLM 계약

### 3.1 Ollama 호출

`POST {base-url}/api/chat`

```json
{
  "model": "qwen2.5-coder:7b",
  "messages": [{"role": "system", "content": "..."}, {"role": "user", "content": "질문"}],
  "stream": false,
  "keep_alive": "30m",
  "format": {
    "type": "object",
    "properties": {
      "action": {"type": "string", "enum": ["sql", "clarify", "refuse"]},
      "sql": {"type": "string"},
      "message": {"type": "string"}
    },
    "required": ["action", "sql", "message"]
  },
  "options": {"temperature": 0, "seed": 42, "num_ctx": 8192}
}
```

응답에서 사용하는 필드: `message.content`(JSON 문자열), `prompt_eval_count`, `eval_count`. 값이 없으면 0으로 저장한다.

### 3.2 출력 파싱 규칙 (`LlmOutputParser`)

- 앞뒤 공백과 ```json 코드펜스는 제거한 뒤 JSON으로 파싱한다. 그 외 부가 텍스트가 있으면 FORMAT_ERROR.
- 빈 문자열은 null로 취급한다.
- `action=sql`이면 sql 필수, `clarify`·`refuse`면 message 필수. 위반은 FORMAT_ERROR.
- 알 수 없는 action은 FORMAT_ERROR.
- sql 끝의 `;`는 제거한다.

### 3.3 재시도 메시지 (v2, 실행 오류에 한해 1회)

기존 messages에 assistant(이전 raw 출력)와 user 메시지를 추가한다.

```
The previous SQL failed with this PostgreSQL error:
{error}
Return the corrected JSON in the same format.
```

## 4. 프롬프트·설정 파일 계약

### 4.1 프롬프트 파일 `prompts/v{0,1,2}.txt`

system 메시지 템플릿이다. user 메시지는 질문 원문이다.

| 플레이스홀더 | v0 | v1 | v2 |
| --- | --- | --- | --- |
| `{{SCHEMA}}` | 전체 DDL(6개 테이블) | 전체 DDL | 선택된 테이블 DDL만 |
| `{{AS_OF_DATE}}` / 기간 규칙 | 없음 | 있음 | 있음 |
| `{{METRICS}}` | 없음 | 전체 지표 사전 | 질문 관련 항목만 |
| `{{FEWSHOT}}` | 없음 | 5개 | 5개 |
| 출력 형식 설명(3 action) | 있음 | 있음 | 있음 |

- 지시문은 영어, 질문·예시 질문은 한국어, 테이블·컬럼명은 영어를 그대로 쓴다.
- v0에는 기준일을 넣지 않는다(기준선 정의). SQL은 PostgreSQL 방언, 읽기 전용, 스키마 접두어 없이 테이블명만 쓰도록 지시한다.
- 렌더링: 플레이스홀더가 남아 있으면 `PromptBuilder`가 예외를 던진다.

### 4.2 `prompts/metrics.yml`

```yaml
asOfDate: 2026-11-08
dateRules:
  - expr: 지난주
    range: "2026-10-26 ~ 2026-11-01"
terms:
  - words: [재고, 현재고]
    maps: inventory_daily.on_hand (snap_date = as_of_date)
metrics:
  - name: 재고보유일수
    keywords: [재고보유일수, 보유일수]
    definition: "기준일 on_hand / (최근 28일 qty 합 / 28), 분모 0이면 제외"
    tables: [inventory_daily, sales_daily]
```

`MetricsDictionary.select(question, pruned)`: pruned=false면 전체, true면 `keywords`가 질문에 포함된 항목과 `dateRules`만.

### 4.3 `prompts/fewshot.json` (5개, 골든셋과 겹치지 않음)

```json
[{"question": "SKU-012의 어제 판매량은?", "output": {"action": "sql", "sql": "SELECT ...", "message": ""}}]
```

### 4.4 `prompts/schema_rules.yml` (v2 스키마 축소)

```yaml
rules:
  - keywords: [판매, 출고, qty]
    tables: [sales_daily]
  - keywords: [재고, on_hand, 재고보유, 결품, 입고예정]
    tables: [inventory_daily]
  - keywords: [예측, 정확도, WMAPE]
    tables: [forecast_weekly, sales_daily]
  - keywords: [안전재고, 리드타임, 재주문]
    tables: [supply_policy, inventory_daily]
  - keywords: [품목, SKU, 카테고리, ABC]
    tables: [product]
  - keywords: [거점, 센터, 창고, 지역]
    tables: [location]
fallbackAllTables: true
alwaysAddDimensions: true
```

`alwaysAddDimensions`: 팩트 테이블이 하나라도 선택되면 product, location을 함께 포함한다. 어떤 규칙에도 맞지 않으면 전체 6개.

### 4.5 `eval/golden_set.json`

```json
[{
  "id": "Q01",
  "type": "SIMPLE_LOOKUP",
  "question": "SKU-007의 현재 재고는?",
  "expectedAction": "sql",
  "goldenSql": "SELECT SUM(on_hand) FROM scm.inventory_daily WHERE sku = 'SKU-007' AND snap_date = DATE '2026-11-08'",
  "orderSensitive": false,
  "note": ""
}]
```

- `type`: SIMPLE_LOOKUP(6), PERIOD_AGG(8), METRIC(6), RANKING(4), AMBIGUOUS(2), REFUSE(4)
- `id`: Q01~Q30 고유. `goldenSql`은 expectedAction이 sql일 때만 존재.
- `goldenSql`은 `scm.` 접두어를 써도 되고 scm_ro로 실행된다.
- RANKING은 `orderSensitive=true`이며 ORDER BY에 동점 해소 컬럼(sku 등)을 포함한다.

## 5. DB 계약

### 5.1 역할

| 역할 | 용도 | 권한 |
| --- | --- | --- |
| scm_app | JPA, MyBatis, 시드 | DB 소유자(컨테이너 기본 사용자) |
| scm_ro | LLM SQL 실행 | `scm` 스키마 USAGE·SELECT만. `app` 스키마 권한 없음 |

scm_ro 설정: `search_path = scm`, `statement_timeout = '5s'`, `default_transaction_read_only = on`.

### 5.2 scm 스키마

```sql
CREATE TABLE scm.product (
  sku varchar(8) PRIMARY KEY,
  name varchar(60) NOT NULL,
  category varchar(30) NOT NULL,
  unit_cost numeric(10,2) NOT NULL,
  abc_class char(1) NOT NULL CHECK (abc_class IN ('A','B','C'))
);
CREATE TABLE scm.location (
  location_id varchar(8) PRIMARY KEY,
  name varchar(30) NOT NULL,
  region varchar(20) NOT NULL
);
CREATE TABLE scm.sales_daily (
  sale_date date NOT NULL,
  sku varchar(8) NOT NULL REFERENCES scm.product(sku),
  location_id varchar(8) NOT NULL REFERENCES scm.location(location_id),
  qty integer NOT NULL CHECK (qty >= 0),
  PRIMARY KEY (sale_date, sku, location_id)
);
CREATE TABLE scm.inventory_daily (
  snap_date date NOT NULL,
  sku varchar(8) NOT NULL REFERENCES scm.product(sku),
  location_id varchar(8) NOT NULL REFERENCES scm.location(location_id),
  on_hand integer NOT NULL CHECK (on_hand >= 0),
  in_transit integer NOT NULL CHECK (in_transit >= 0),
  PRIMARY KEY (snap_date, sku, location_id)
);
CREATE TABLE scm.forecast_weekly (
  week_start date NOT NULL,
  sku varchar(8) NOT NULL REFERENCES scm.product(sku),
  location_id varchar(8) NOT NULL REFERENCES scm.location(location_id),
  forecast_qty integer NOT NULL CHECK (forecast_qty >= 0),
  PRIMARY KEY (week_start, sku, location_id)
);
CREATE TABLE scm.supply_policy (
  sku varchar(8) NOT NULL REFERENCES scm.product(sku),
  location_id varchar(8) NOT NULL REFERENCES scm.location(location_id),
  lead_time_days smallint NOT NULL,
  safety_stock integer NOT NULL,
  reorder_point integer NOT NULL,
  PRIMARY KEY (sku, location_id)
);
```

행 수: product 50, location 5, sales_daily 22,750, inventory_daily 22,750, forecast_weekly 3,250, supply_policy 250.

### 5.3 app 스키마

```sql
CREATE TABLE app.chat_log (
  id bigserial PRIMARY KEY,
  question text NOT NULL,
  prompt_version varchar(8) NOT NULL,
  model varchar(64) NOT NULL,
  action varchar(16),
  status varchar(32) NOT NULL,
  generated_sql text,
  row_count integer,
  attempts smallint NOT NULL,
  prompt_tokens integer NOT NULL DEFAULT 0,
  completion_tokens integer NOT NULL DEFAULT 0,
  elapsed_ms integer NOT NULL,
  error_message text,
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app.eval_run (
  id bigserial PRIMARY KEY,
  prompt_version varchar(8) NOT NULL,
  model varchar(64) NOT NULL,
  temperature numeric(4,2) NOT NULL,
  seed integer NOT NULL,
  repeat_no smallint NOT NULL,
  golden_sha256 char(64) NOT NULL,
  started_at timestamptz NOT NULL,
  finished_at timestamptz,
  total integer,
  correct integer,
  accuracy numeric(5,4),
  sql_exec_success_rate numeric(5,4),
  format_ok_rate numeric(5,4),
  avg_elapsed_ms integer,
  note text
);
CREATE TABLE app.eval_result (
  id bigserial PRIMARY KEY,
  run_id bigint NOT NULL REFERENCES app.eval_run(id),
  question_id varchar(8) NOT NULL,
  qtype varchar(16) NOT NULL,
  expected_action varchar(16) NOT NULL,
  actual_action varchar(16),
  generated_sql text,
  correct boolean NOT NULL,
  error_type varchar(32),
  status varchar(32) NOT NULL,
  attempts smallint NOT NULL,
  prompt_tokens integer NOT NULL DEFAULT 0,
  completion_tokens integer NOT NULL DEFAULT 0,
  elapsed_ms integer NOT NULL,
  detail text,
  UNIQUE (run_id, question_id)
);
```

JPA는 `ddl-auto=validate`, 기본 스키마 `app`.

## 6. 자바 내부 인터페이스 (패키지 `com.scmchat`)

| 패키지 | 타입 | 시그니처 |
| --- | --- | --- |
| llm | `LlmClient` | `LlmReply generate(List<LlmMessage> messages)`, `String modelName()` |
| llm | `LlmMessage` (record) | `String role, String content` |
| llm | `LlmReply` (record) | `String rawContent, int promptTokens, int completionTokens` |
| llm | `LlmOutputParser` | `ParsedOutput parse(String raw)` (실패 시 `FormatException`) |
| llm | `ParsedOutput` (record) | `Action action, String sql, String message` |
| llm | `OllamaLlmClient`, `FakeLlmClient`, `OracleLlmClient` | `chat.llm.provider` = `ollama` / `fake` / `oracle` |
| prompt | `PromptBuilder` | `List<LlmMessage> build(PromptVersion v, String question)`, `List<LlmMessage> buildRetry(List<LlmMessage> prior, String rawReply, String error)` |
| prompt | `MetricsDictionary`, `SchemaSelector`, `FewShotLoader` | 4.2~4.4 |
| validator | `SqlValidator` | `ValidationResult validate(String sql)` |
| validator | `ValidationResult` (record) | `boolean ok, String sql, String reason, boolean limitApplied` |
| executor | `ReadOnlySqlExecutor` | `QueryResult execute(String sql)` (실패 시 `SqlExecutionException`) |
| executor | `QueryResult` (record) | `List<String> columns, List<List<Object>> rows, boolean truncated` |
| service | `ChatService` | `ChatResult answer(String question, PromptVersion version)` |
| eval | `GoldenSetLoader`, `ResultComparator`, `Grader`, `EvalRunner`, `EvalReportWriter` | `Grader.grade(GoldenItem item, ChatResult actual, QueryResult expected)` → `GradeResult(boolean correct, ErrorType errorType, String detail)` |
| mapper | `EvalSummaryMapper` (MyBatis) | `List<RunSummaryRow> runSummaries(String model)`, `List<TypeSummaryRow> typeSummaries(long runId)`, `List<ErrorTypeCountRow> errorTypeCounts(long runId)` |
| repository | `ChatLogRepository`, `EvalRunRepository`, `EvalResultRepository` | Spring Data JPA |
| controller | `ChatController`, `MetaController`, `EvalController` | 2장 |
| dto | `ChatRequest`, `ChatResponse`, `ErrorResponse`, `EvalSummaryResponse` | 엔티티 직접 반환 금지 |

- `ChatService.answer`는 컨트롤러와 `EvalRunner`가 공통으로 호출한다. 평가는 HTTP를 거치지 않는다.
- `FakeLlmClient` 기본 응답: `{"action":"clarify","sql":"","message":"fake"}`. 테스트에서 응답 큐를 주입할 수 있다.
- `OracleLlmClient`: `eval/golden_set.json`에서 질문 문자열이 같은 항목을 찾아 goldenSql(`sql`), 되묻기(`clarify`), 거부(`refuse`) 응답을 반환한다. 채점기 검증 전용이며 운영 설정에서는 사용하지 않는다.

### SqlValidator 규칙 (v2)

1. JSqlParser로 파싱 실패 → 거부
2. 문장 1개만 허용
3. 최상위가 SELECT(CTE 포함)이어야 하고, `INTO`, `FOR UPDATE/SHARE` 금지
4. 참조 테이블(CTE 이름 제외)은 6개 허용 테이블이며 접두어는 없거나 `scm.`만 허용
5. 함수명이 `pg_` 접두어이거나 `set_config`, `current_setting`, `dblink`, `lo_import`, `lo_export`, `copy`에 해당하면 거부 (`pg_sleep` 포함)
6. 최상위 LIMIT이 없거나 200 초과면 200으로 설정
7. 달러 인용(`$$`), 역슬래시, `E''`·`U&` 접두 문자열은 거부 (정규식과 PostgreSQL이 문자열 경계를 다르게 읽을 수 있음)
8. 함수는 허용 목록(집계, 윈도, 수치, 날짜, 문자열)에 있는 것만 허용. 주석(`--`, `/* */`)은 거부
9. 검증기가 LIMIT을 붙였거나 낮췄고 결과가 상한을 채우면 응답의 `truncated`를 true로 둔다

## 7. 설정 키 (`backend/src/main/resources/application.yml`)

```yaml
chat:
  prompt-version: v1
  as-of-date: 2026-11-08
  max-rows: 1000
  validator-limit: 200
  prompts-dir: ../prompts
  llm:
    provider: fake
    base-url: http://localhost:11434
    model: qwen2.5-coder:7b
    temperature: 0
    seed: 42
    num-ctx: 8192
    timeout-seconds: 120
eval:
  golden-path: ../eval/golden_set.json
  golden-hash-path: ../eval/golden_set.sha256
  report-dir: ../eval/reports
  versions: v0
  repeat: 1
app:
  datasource:
    url: ${APP_DB_URL}
    username: ${APP_DB_USER}
    password: ${APP_DB_PASSWORD}
scm-ro:
  datasource:
    url: ${SCM_RO_DB_URL}
    username: ${SCM_RO_DB_USER}
    password: ${SCM_RO_DB_PASSWORD}
```

`.env` 키: `POSTGRES_DB=scm`, `POSTGRES_USER=scm_app`, `POSTGRES_PASSWORD`, `SCM_RO_PASSWORD`, 그리고 위 `APP_DB_*`, `SCM_RO_DB_*`. 실제 값은 `.env`에만 두고 `.env.example`에는 자리표시자만 둔다. 기본 provider는 `fake`이며 실제 측정은 `--chat.llm.provider=ollama`로 명시한다.

## 8. v3 계약 (PRD 10장)

v0~v2의 요청·응답·LLM 호출 본문은 바뀌지 않는다. 아래는 `promptVersion=v3`일 때 더해지는 것이다.

### 8.1 POST /api/chat 추가 사항

`ChatResponse` 추가 필드 (v0~v2에서는 모두 null)

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| route | string/null | 라우터가 고른 경로 `tool`, `sql`, `clarify`, `refuse`. 라우터 호출 실패·형식 오류면 null |
| tool | object/null | route가 `tool`일 때 `{"name": "days_of_supply", "args": {...}}`. args는 검증·정규화를 거친 값이고, 검증에 실패했으면 모델이 낸 원래 값(빈 문자열과 0은 뺀다). 검증에 실패한 호출의 name은 모델이 낸 이름을 32자까지 남긴다 |
| answer | string/null | 숫자 후검증을 통과한 답변 문장. 통과하지 못했거나 만들지 않았으면 null |
| answerStatus | string/null | `VERIFIED`, `REJECTED`, `SKIPPED` |

기존 필드의 v3 값

| 필드 | v3 |
| --- | --- |
| action | `tool`이 더해진다. 도구로 조회했으면 `tool`, SQL로 조회했으면 `sql` |
| status | `TOOL_REJECTED`(HTTP 422)가 더해진다. 도구 인자 검증 실패이며 message에 사유. 품목·거점을 확인하는 조회나 도구 쿼리가 DB 오류로 실패하면 `SQL_EXECUTION_ERROR` |
| sql | 도구 경로에서는 null |
| attempts | LLM 호출 횟수 1~4 (라우터 1, SQL 생성 1, 재시도 1, 답변 문장 1) |
| promptVersion | `v3` |

`answerStatus`

| 값 | 조건 |
| --- | --- |
| VERIFIED | 답변 문장의 숫자가 모두 허용 집합에 있다(PRD 10.5) |
| REJECTED | 허용되지 않은 숫자가 있다. `answer`는 null이고 원문은 `chat_log.answer_text`에만 남긴다 |
| SKIPPED | 결과가 0행이거나 `chat.answer-max-rows`를 넘거나 잘렸다. 또는 답변 호출이 실패했거나 형식이 틀렸거나 문장이 비어 있다 |

예시: 도구 경로

```json
{
  "action": "tool", "status": "OK", "message": null, "sql": null,
  "columns": ["days_of_supply"], "rows": [[12.4907]],
  "rowCount": 1, "truncated": false,
  "route": "tool",
  "tool": {"name": "days_of_supply", "args": {"sku": "SKU-026", "location": "서울센터", "output": "value"}},
  "answer": "서울센터 SKU-026의 재고보유일수는 약 12.5일입니다.", "answerStatus": "VERIFIED",
  "asOfDate": "2026-11-08", "promptVersion": "v3", "model": "qwen2.5-coder:7b",
  "attempts": 2, "elapsedMs": 5200, "chatLogId": 77
}
```

(숫자는 형식 설명용이다.)

### 8.2 LLM 출력 형식

`LlmClient.generate(messages, format)`의 `format`이 Ollama 요청의 `format` 스키마를 정한다. `ACTION`은 3.1의 스키마 그대로다.

`ROUTE` (라우터)

```json
{
  "type": "object",
  "properties": {
    "route": {"type": "string", "enum": ["tool", "sql", "clarify", "refuse"]},
    "tool": {"type": "string", "enum": ["", "avg_daily_sales", "days_of_supply", "stockout_rate", "forecast_accuracy", "below_safety_stock", "reorder_needed"]},
    "sku": {"type": "string"},
    "location": {"type": "string"},
    "days": {"type": "integer"},
    "start_date": {"type": "string"},
    "end_date": {"type": "string"},
    "output": {"type": "string", "enum": ["", "value", "ranking", "list", "count"]},
    "top_n": {"type": "integer"},
    "order": {"type": "string", "enum": ["", "asc", "desc"]},
    "message": {"type": "string"}
  },
  "required": ["route", "tool", "sku", "location", "days", "start_date", "end_date", "output", "top_n", "order", "message"]
}
```

쓰지 않는 문자열은 `""`, 쓰지 않는 정수는 `0`으로 낸다.

`ANSWER` (답변 문장)

```json
{"type": "object", "properties": {"answer": {"type": "string"}}, "required": ["answer"]}
```

파싱 규칙 (`LlmOutputParser`)

- 공통: 3.2와 같이 앞뒤 공백과 코드펜스를 떼고 JSON 객체 하나만 허용한다. 빈 문자열은 null로 본다.
- `parseRoute`: 알 수 없는 route, 문자열이 아닌 문자열 필드, 정수가 아닌 정수 필드는 FORMAT_ERROR. route가 `clarify`·`refuse`인데 message가 비어 있으면 FORMAT_ERROR. route가 `tool`일 때 도구 이름과 인자의 값 검증은 파서가 아니라 `ToolArgsValidator`가 한다.
- `parseAnswer`: answer가 문자열이 아니면 FORMAT_ERROR. 비어 있으면 null을 돌려준다.
- `parse`(ACTION)는 `tool`을 알 수 없는 action으로 본다.

### 8.3 프롬프트 파일

| 파일 | 용도 | 플레이스홀더 |
| --- | --- | --- |
| `prompts/v3_router.txt` | 라우터 system 메시지. 질문이 달라져도 내용이 같다 | `{{AS_OF_DATE}}`, `{{DATE_RULES}}`, `{{TOOLS}}`, `{{ROUTER_FEWSHOT}}` |
| `prompts/v3_answer.txt` | 답변 문장 system 메시지 | `{{AS_OF_DATE}}` |
| `prompts/v1.txt` | v3의 SQL 생성 단계. 파일과 렌더링 결과 모두 v1과 같다 | 4.1 |

- 라우터의 user 메시지는 질문 원문이다. 라우터 프롬프트에는 DDL을 넣지 않고, 테이블이 담는 데이터의 요약(거점 이름 5개 포함, PRD 5.5), 기간 규칙, 도구 목록과 인자 설명, 경로 선택 규칙을 넣는다. 도구가 표현하지 못하는 질문의 목록은 PRD 10.4의 문장을 옮긴 것이다.
- 답변 단계의 user 메시지는 질문, 결과 컬럼명, 결과 행을 담는다.

```
Question: {질문}
Columns: {컬럼명을 쉼표로}
Rows:
{행마다 한 줄, 값은 " | "로 구분, NULL은 null}
```

- 지시문은 영어, 답변 문장은 한국어 1~2문장으로 쓰게 한다. 결과에 있는 값만 쓰고 새로 계산하지 말라고 지시한다. 값만 내지 않고 완전한 문장으로 쓰도록 예시 하나를 둔다(질문은 `fewshot.json`의 것이며 세 골든셋과 겹치지 않는다).
- 숫자 셀은 지수 표기 없이 적는다(`BigDecimal.toPlainString`).

`prompts/tools.yml` (`{{TOOLS}}`로 렌더링)

```yaml
tools:
  - name: days_of_supply
    metric: 재고보유일수
    description: "기준일 on_hand / (최근 28일 qty 합 / 28)"
    args: [sku, location]
    outputs: [value, ranking]
```

`prompts/router_fewshot.json` (세 골든셋과 겹치지 않음, 네 경로를 모두 포함)

```json
[{"question": "...", "output": {"route": "tool", "tool": "stockout_rate", "sku": "", "location": "대전센터", "days": 0, "start_date": "", "end_date": "", "output": "value", "top_n": 0, "order": "", "message": ""}}]
```

### 8.4 조회 도구

인자 검증 (`ToolArgsValidator`). 하나라도 어기면 거부하고 사유를 돌려준다.

1. 도구 이름이 6개 중 하나다.
2. `sku`: 비어 있거나 `SKU-` 뒤에 숫자 1~3자리(대소문자 무시). `SKU-007` 형식으로 정규화하고 product에 있어야 한다.
3. `location`: 비어 있거나 location.name과 정확히 같아야 한다.
4. `output`: 도구가 허용하는 값이어야 한다(아래 표).
5. `ranking`: `top_n` 1~50, `order`가 asc·desc, `sku`는 비어 있어야 한다.
6. `avg_daily_sales`: `days` 1~91.
7. `stockout_rate`: `start_date`, `end_date`는 둘 다 비어 있거나 둘 다 `yyyy-MM-dd`이고, 시작 ≤ 끝, `chat.data-start-date` ~ `chat.as-of-date` 안이어야 한다.
8. 그 도구가 쓰지 않는 인자(다른 도구의 `days`·날짜, ranking이 아닐 때의 `top_n`·`order`)는 무시한다.

결과 컬럼 (`QueryResult`)

| 도구 | output | 컬럼 |
| --- | --- | --- |
| avg_daily_sales | value | `avg_daily_qty` |
| avg_daily_sales | ranking | `sku`, `avg_daily_qty` |
| days_of_supply | value | `days_of_supply` |
| days_of_supply | ranking | `sku`, `days_of_supply` |
| stockout_rate | value | `stockout_rate` |
| stockout_rate | ranking | `sku`, `stockout_rate` |
| forecast_accuracy | value | `accuracy` |
| forecast_accuracy | ranking | `sku`, `accuracy` |
| below_safety_stock | list | `sku`, `location_name`, `on_hand`, `safety_stock` |
| below_safety_stock | count | `cnt` |
| reorder_needed | list | `sku`, `location_name`, `on_hand`, `in_transit`, `reorder_point` |
| reorder_needed | count | `cnt` |

- value와 count는 항상 1행이다. 분모가 0이면 지표 값은 NULL이다(MyBatis가 NULL뿐인 행을 버리지 않도록 `returnInstanceForEmptyRow`와 `callSettersOnNulls`를 켠다).
- 도구는 자기 지표 값(과 순위·목록의 라벨)만 돌려준다. 재고나 일평균 같은 구성 요소를 함께 내면, 도구를 잘못 골라도 추가 컬럼 허용 규칙(PRD 5.4) 때문에 정답으로 채점될 수 있다.
- ranking은 지표 값이 NULL인 품목을 빼고 `order` 방향으로 정렬하며 동점은 sku 오름차순이다.
- list는 sku, location_name 오름차순이고 `chat.validator-limit`(200)에서 자른다. 상한을 채우면 `truncated=true`.
- 쿼리는 `mapper/scmro/MetricToolMapper.xml`에 고정돼 있고, 인자는 `#{}` 바인딩으로만 들어간다. 정렬 방향처럼 바인딩할 수 없는 부분은 검증된 enum 값으로 XML의 `<choose>`가 고른다(`${}` 치환을 쓰지 않는다).
- 실행은 scm_ro DataSource로 만든 별도 `SqlSessionFactory`가 맡는다. 문장 타임아웃 5초.
- 날짜 범위(최근 N일, 28일, 전체 기간)와 list의 상한은 `ToolExecutor`가 설정값으로 계산한다. 모델이 정하는 것은 `ToolCall`의 값뿐이다.

### 8.5 DB 추가 컬럼 (`db/02_schema.sql`, 모두 NULL 허용)

```sql
-- app.chat_log, app.eval_result 공통
route varchar(16),
tool_name varchar(32),
tool_args text,
answer_text text,
answer_status varchar(16),

-- app.eval_run
answer_verified_rate numeric(5,4)
```

- `tool_args`는 JSON 문자열이다. `answer_text`는 검증 결과와 무관하게 모델이 쓴 문장 원문이다.
- `eval_run.answer_verified_rate`: `VERIFIED` ÷ (`VERIFIED` + `REJECTED`), 분모 0이면 NULL.
- `eval_result.error_type`에 `TOOL_SELECTION`, `TOOL_ARGUMENT`가 더해진다.
- 컬럼을 더한 뒤에는 `eval_dump.sh` → `db_reset.sh` → `eval_restore.sh` 순서로 기존 측정 결과를 옮긴다. `eval_data.sql`은 컬럼명을 적은 INSERT라 새 컬럼이 있어도 복원된다.

### 8.6 자바 인터페이스 추가·변경

| 패키지 | 타입 | 시그니처 |
| --- | --- | --- |
| llm | `OutputFormat` (enum) | `ACTION`, `ROUTE`, `ANSWER`. `Map<String, Object> schema()` |
| llm | `LlmClient` | `LlmReply generate(List<LlmMessage> messages, OutputFormat format)` (기존 `generate(messages)`를 대체) |
| llm | `LlmOutputParser` | `ParsedRoute parseRoute(String raw)`, `String parseAnswer(String raw)` 추가 |
| llm | `ParsedRoute` (record) | `Route route, String tool, RawToolArgs args, String message` |
| llm | `Route` (enum) | `TOOL`, `SQL`, `CLARIFY`, `REFUSE` |
| llm | `Action` | `TOOL("tool")` 추가 |
| tool | `ToolName` (enum), `ToolOutput` (enum) | 8.4의 도구 6개, `VALUE`·`RANKING`·`LIST`·`COUNT` |
| tool | `RawToolArgs` (record) | `String sku, String location, int days, String startDate, String endDate, String output, int topN, String order` |
| tool | `ToolCall` (record) | `ToolName tool, ToolOutput output, String sku, String location, Integer days, LocalDate startDate, LocalDate endDate, Integer topN, Boolean ascending` |
| tool | `ToolArgsValidator` | `ToolValidation validate(String toolName, RawToolArgs raw)` → `ToolValidation(boolean ok, ToolCall call, String reason)` |
| tool | `Dimensions`, `MapperDimensions` | `boolean skuExists(String sku)`, `List<String> locationNames()`. 검증기가 품목·거점 존재를 확인하는 창구이고 구현은 `MetricToolMapper`를 부른다 |
| tool | `ToolExecutor` | `QueryResult execute(ToolCall call)`. DB 오류는 `SqlExecutionException`(`SqlExecutionException.from(DataAccessException)`). `MapperDimensions`도 같은 예외로 바꾼다 |
| tool | `ToolCatalog` | `tools.yml`을 읽어 `String render()` |
| mapper | `MetricToolMapper` (MyBatis, scm_ro) | 도구·출력 방식별 조회 메서드 12개(`ToolQuery`를 받아 `List<Map<String, Object>>` 반환), `List<String> locationNames()`, `boolean skuExists(String sku)` |
| mapper | `ToolQuery` (record) | `String sku, String location, LocalDate asOfDate, LocalDate fromDate, LocalDate toDate, Integer days, Integer limit, boolean ascending` |
| mapper | `ScmRoMapper` (annotation) | scm_ro `SqlSessionFactory`에 붙일 매퍼 표시. `@Mapper`가 붙은 매퍼는 app DataSource |
| config | `MyBatisConfig` | `sqlSessionFactory`(app, `@Primary`, `classpath:mapper/*.xml`)와 `scmRoSqlSessionFactory`(scm_ro, `classpath:mapper/scmro/*.xml`). `SqlSessionFactory` 빈을 직접 등록하면 자동 구성이 물러나므로 둘 다 여기서 만든다. 기존 `EvalSummaryMapper`는 app DataSource를 그대로 쓴다 |
| answer | `NumberVerifier` | `Verification verify(String answer, VerificationContext context)` → `Verification(boolean ok, List<String> unverified)`. `unverified`는 허용되지 않은 숫자 토큰(문장에 적힌 그대로) |
| entity | `ChatLog`, `EvalResult`, `EvalRun` | `recordAnswer(String answerText, String answerStatus)`, `EvalRun.recordAnswerVerifiedRate(BigDecimal)`와 getter |
| answer | `VerificationContext` (record) | `String question, QueryResult result, ToolCall toolCall, LocalDate asOfDate` |
| answer | `AnswerStatus` (enum) | `VERIFIED`, `REJECTED`, `SKIPPED` |
| prompt | `PromptVersion` | `V3("v3")` 추가 |
| prompt | `PromptBuilder` | `List<LlmMessage> buildRouter(String question)`, `List<LlmMessage> buildAnswer(String question, QueryResult result)` 추가. `build(V3, …)`은 지원하지 않는다(SQL 단계는 `build(V1, …)`) |
| service | `ChatStatus` | `TOOL_REJECTED` 추가 |
| service | `ChatResult` | `Route route, String toolName, Map<String, Object> toolArgs`(T14), `String answerText, AnswerStatus answerStatus`(T15) 추가. `answerText`는 모델이 쓴 문장 원문이며 `REJECTED`여도 담긴다. 응답의 `answer`에는 `VERIFIED`일 때만 싣는다. v0~v2용 12개 인자 생성자는 그대로 둔다 |
| tool | `ToolCall.toArgsMap()`, `RawToolArgs.toArgsMap()`, `ToolArgsJson.write(Map)` | 응답의 `tool.args`와 `tool_args` 컬럼에 쓸 인자. 키는 라우터 출력과 같고 쓰지 않는 인자는 뺀다 |
| dto | `ChatResponse` | `Route route, ToolInfo tool` 추가. `ToolInfo(String name, Map<String, Object> args)` |
| entity | `ChatLog`, `EvalResult` | `recordRoute(String route, String toolName, String toolArgs)`와 getter. 생성자는 그대로 둔다 |
| prompt | `FewShotLoader` | `static FewShotLoader forRouter(Path file)` 추가. 예시의 output을 `parseRoute`로 검사한다 |
| eval | `ErrorType` | `TOOL_SELECTION`, `TOOL_ARGUMENT` 추가 |
| eval | `Grader` | 정답이 sql인 문항은 action이 `sql`·`tool` 모두 결과 비교로 채점하고 `TOOL_REJECTED`는 `TOOL_ARGUMENT`. 정답이 clarify·refuse인 문항에 도구로 답했으면 status와 무관하게 `ACTION_MISMATCH` |
| mapper | `EvalSummaryMapper` | `List<RouteSummaryRow> routeSummaries(long runId)` 추가 |

- `OllamaLlmClient`는 `format.schema()`를 요청의 `format`에 넣는다. `ACTION`의 요청 본문은 v0~v2 측정 때와 바이트 단위로 같아야 하며 테스트로 고정한다.
- `FakeLlmClient`는 format과 무관하게 큐의 응답을 순서대로 돌려준다. 큐가 비면 format에 맞는 기본 응답을 준다(`ACTION`은 기존 기본 응답, `ROUTE`는 `{"route":"clarify","message":"fake"}`, `ANSWER`는 빈 문장).
- `OracleLlmClient`: `ROUTE`에는 정답 action에 맞춘 route(`sql`·`clarify`·`refuse`)를, `ACTION`에는 기존과 같은 응답을, `ANSWER`에는 빈 문장을 돌려준다. 그래서 oracle의 v3 평가는 SQL 경로만 지나가고 답변은 `SKIPPED`다. 도구의 정확성은 `MetricToolIT`가 확인한다.

### 8.7 설정 키 추가

```yaml
chat:
  data-start-date: 2026-08-10
  answer-max-rows: 20
```

`mybatis.mapper-locations`는 없앤다(매퍼 XML 위치는 `MyBatisConfig`가 정한다).

`chat.prompt-version`과 `eval.versions`가 `v3`을 받는다. 기본 `chat.prompt-version`은 v1 그대로다.

### 8.8 GET /api/eval/summary 추가 필드

실행마다 아래 두 필드가 더해진다. v0~v2 실행은 `answerVerifiedRate`가 null이고 `routes`는 빈 배열이다.

```json
{
  "answerVerifiedRate": 0.9,
  "routes": [{"route": "tool", "total": 9, "correct": 8}, {"route": "sql", "total": 15, "correct": 10}]
}
```

`routes`의 route는 `eval_result.route` 값이고, NULL(라우터 실패)은 `none`으로 묶는다. 경로가 하나도 기록되지 않은 실행은 빈 배열이다.

리포트(`eval/reports/run-{id}-v3.md`)에는 경로별 정확도, 답변 문장 후검증 건수, 거부된 문장 목록, 그리고 `VERIFIED` 문장 가운데 조회 결과가 오답인 건수가 더해진다. 후검증은 숫자의 출처만 보므로 통과율만으로는 문장이 맞는지 알 수 없다.

### 8.9 `eval/golden_set_v3.json`

4.5와 같은 형식이다. id는 Q51~Q70, 유형별 문항 수는 SIMPLE_LOOKUP 2, PERIOD_AGG 3, METRIC 7, RANKING 4, AMBIGUOUS 2, REFUSE 2. `eval/golden_set_v3.sha256`으로 동결한다.

## 9. v4 계약 (PRD 11장)

v3과 다른 점만 적는다. 응답 형식(8.1), 답변 단계, 숫자 후검증, DB 컬럼은 v3과 같다. `promptVersion`은 `v4`.

### 9.1 라우터 출력 형식 `ROUTE_V4`

8.2의 `ROUTE` 스키마에 문자열 속성 세 개를 더한다. 순서는 `location` 다음에 `region`, `category`, `abc_class`다. `abc_class`는 enum `["", "A", "B", "C"]`. 모두 required이며 쓰지 않으면 `""`.

`ROUTE`(v3) 스키마는 바꾸지 않는다. `parseRoute`는 세 필드가 없으면 null로 읽는다.

### 9.2 파일

| 파일 | 용도 |
| --- | --- |
| `prompts/v4_router.txt` | v4 라우터 system 메시지. 플레이스홀더는 v3과 같다 |
| `prompts/tools_v4.yml` | 도구 목록. 항목마다 `not_for`(이 도구가 답하지 않는 질문)를 둔다 |
| `prompts/router_fewshot_v4.json` | v4 라우터 예시. 네 골든셋과 겹치지 않는다 |
| `eval/golden_set_v4.json`, `.sha256` | 4.5 형식, id Q71~Q110(`GoldenSetLoader`의 id는 `Q` + 숫자 2~3자리) |
| `eval/golden_set_v5.json`, `.sha256` | 4.5 형식, id Q111~Q150. v5 규칙을 보지 않고 쓴 v4·v5 비교용 세트(PRD 12.4) |
| `backend/src/test/resources/router_dev_v4.json` | 라우터 개발 세트 41문항(질문, 기대 경로, 도구 경로면 도구와 검증 뒤 인자). v4 라우터 프롬프트를 다듬을 때 평가 세트 대신 본다. 네 골든셋·라우터 예시와 겹치지 않고 평가 세트를 보지 않고 썼다. `RouterDevSetIT`(`ollama` 태그)가 v4와 v3 라우터로 재서 `build/router-dev/`에 문항별 결과를 쓴다 |

`prompts/v3_router.txt`, `tools.yml`, `router_fewshot.json`은 v3 전용으로 남고 바꾸지 않는다.

### 9.3 조회 도구의 조건 인자

`RawToolArgs`, `ToolCall`, `ToolQuery`에 `region`, `category`, `abcClass`를 더한다. `ToolCall.toArgsMap()`의 키는 `region`, `category`, `abc_class`.

검증 규칙 추가(8.4에 이어서)

9. `category`: 비어 있거나 product.category에 있는 값.
10. `abc_class`: 비어 있거나 A, B, C.
11. `region`: 비어 있거나 location.region에 있는 값. `location`과 함께 주면 거부.
12. `sku`와 `category` 또는 `abc_class`를 함께 주면 거부.

`Dimensions`에 `List<String> categories()`, `List<String> regions()`를 더한다. v3 라우터는 이 인자를 내지 않으므로 v3의 도구 호출 결과는 바뀌지 않는다.

결과 컬럼은 8.4와 같다. 조건은 매퍼 XML에서 product·location에 대한 `EXISTS` 서브쿼리와 `#{}` 바인딩으로 건다.

### 9.4 자바 인터페이스 변경

| 패키지 | 타입 | 변경 |
| --- | --- | --- |
| llm | `OutputFormat` | `ROUTE_V4` 추가 |
| prompt | `PromptVersion` | `V4("v4")` 추가 |
| prompt | `PromptBuilder` | `buildRouter(PromptVersion version, String question)`. V3은 기존 세 파일, V4는 9.2의 파일을 쓴다 |
| tool | `ToolCatalog` | `not_for`가 있으면 도구 줄 다음에 `  Not for: a; b.` 한 줄로 렌더링한다. `tools.yml`의 렌더링 결과는 바뀌지 않는다(v3-freeze의 해시로 테스트에 고정) |
| service | `ChatService` | V4는 V3과 같은 흐름이며 라우터 메시지와 출력 형식만 다르다 |
| validator | `SqlValidator` | 접두 문자열 검사가 닫는 따옴표 앞의 한 글자(`'B'`)에 걸리지 않게 한다 |
| llm | `OracleLlmClient` | `ROUTE_V4`에도 정답 action에 맞춘 route를 돌려준다 |

`SqlValidator` 수정은 v2와 v3의 SQL 경로에도 적용된다. 지금까지의 실제 모델 측정에는 `SQL_REJECTED`가 한 건도 없어 저장된 결과는 영향을 받지 않는다.

## 10. v5 계약 (PRD 12장, 채택하지 않음)

v4와 다른 점만 적는다. 라우터의 메시지와 출력 형식은 v4와 같다(`buildRouter(V5, …)`는 v4의 세 파일을 쓰고, 출력 형식은 `ROUTE_V4`). `promptVersion`은 `v5`.

| 패키지 | 타입 | 변경 |
| --- | --- | --- |
| prompt | `PromptVersion` | `V5("v5")`. `routed()`가 true |
| tool | `ToolRouteGuard` (`@Component`) | `Decision check(String question, ToolCall call)` → `Decision(ToolCall call, String rerouteReason, boolean orderCorrected)`. `reroute()`는 `rerouteReason != null` |
| service | `ChatService` | V5의 도구 경로: 인자 검증 실패 또는 `reroute()`면 SQL 단계(v1 프롬프트, 검증, 재시도). `orderCorrected`면 고친 호출을 실행 |

- SQL 단계로 넘긴 응답: `route = "sql"`, `tool = null`, `action`은 SQL 단계의 결과. `chat_log`·`eval_result`의 `tool_name`은 라우터가 낸 도구 이름, `tool_args`는 그 인자에 `"guard": "<이유>"`를 더한 JSON. 인자 검증 실패는 이유가 `rejected: <검증기 사유>`다.
- 정렬 방향을 고친 도구 호출: `tool.args`의 `order`는 고친 값이고 `"guard": "order corrected"`가 붙는다.
- `attempts`는 1~4 그대로다(라우터 1, SQL 생성 1, 재시도 1, 답변 문장 1).
- `OracleLlmClient`는 v4와 같이 `ROUTE_V4`에 정답 action의 route를 준다. oracle의 v5는 SQL 경로만 지나가므로 점검을 거치지 않는다. 점검은 `ToolRouteGuardTest`와 `ChatServiceTest`가 본다.
