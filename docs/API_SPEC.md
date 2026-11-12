# API 명세와 내부 계약

버전 1.1 · PRD.md와 함께 읽는다. 이 문서는 외부 API, LLM 계약, 파일 포맷, DB 스키마, 자바 인터페이스, 설정 키의 단일 기준이다.

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
| validator | `ValidationResult` (record) | `boolean ok, String sql, String reason` |
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
