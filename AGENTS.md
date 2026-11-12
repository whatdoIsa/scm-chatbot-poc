# AGENTS.md: SCM 챗봇 PoC

Claude Code는 이 파일을 먼저 읽는다. `CLAUDE.md`에는 `@AGENTS.md` 한 줄만 둔다.

## 1. 프로젝트

한국어 SCM 질문을 LLM이 SQL·되묻기·거부로 바꾸고, 서버가 검증·읽기 전용 실행하는 챗봇 PoC. 산출물의 핵심은 30문항 골든셋 정확도 측정과 v0 → v2 비교다.

문서 우선순위: `docs/PRD.md`, `docs/API_SPEC.md`, 이 파일 > `SCM_챗봇_PoC_기획서.md`. 명세에 없는 것은 만들지 말고 질문한다.

## 2. 스택과 고정 결정

| 영역 | 결정 |
| --- | --- |
| 백엔드 | Java 21, Spring Boot 3.5.x(4.x 사용 금지), Gradle(wrapper), 패키지 `com.scmchat` |
| 의존성 | web, validation, data-jpa, jdbc, postgresql, `mybatis-spring-boot-starter` 3.0.x(Boot 3 계열), `jsqlparser`, test. Lombok 사용 금지 |
| DB | PostgreSQL 16 (Docker Compose), 스키마 `scm`(SCM 데이터)·`app`(로그·평가) |
| LLM | 로컬 Ollama, 모델 `qwen2.5-coder:7b`, temperature 0, seed 42 |
| 프런트 | React + TypeScript + Vite, 추가 UI 라이브러리 금지, 개발 프록시로 `/api`를 8080에 연결(CORS 설정 없음) |
| 포트 | Postgres 5432, 백엔드 8080, Vite 5173, Ollama 11434 |

의존성 추가가 필요하면 먼저 사용자에게 묻는다.

## 3. 저장소 구조

```
scm-chatbot-poc/
  AGENTS.md  CLAUDE.md  README.md  docker-compose.yml  .env.example  .gitignore
  db/            01_roles.sh  02_schema.sql  03_seed.sql  04_grants.sql  verify/verify.sql
  scripts/       db_reset.sh  db_checksum.sh  verify_db.sh  final_check.sh
  backend/       build.gradle  src/main/java/com/scmchat/{controller,service,llm,prompt,validator,executor,mapper,entity,repository,eval,config,dto}
  frontend/      Vite 프로젝트
  prompts/       v0.txt  v1.txt  v2.txt  metrics.yml  fewshot.json  schema_rules.yml
  eval/          golden_set.json  golden_set.sha256  crosscheck.md  reports/
  docs/          PRD.md  API_SPEC.md  ai-mistakes.md
```

## 4. 명령

```bash
set -a; . ./.env; set +a
bash scripts/db_reset.sh
bash scripts/verify_db.sh
cd backend && ./gradlew test
cd backend && ./gradlew integrationTest
cd backend && ./gradlew bootRun
cd backend && ./gradlew bootRun --args='--spring.profiles.active=eval --chat.llm.provider=ollama --eval.versions=v0 --eval.repeat=1'
cd frontend && npm ci && npm run build
bash scripts/final_check.sh
```

- `test` 태스크는 `integration` 태그를 제외하고, `integrationTest` 태스크는 해당 태그만 실행한다(DB·Ollama 필요).
- 평가 프로파일(`eval`)은 실행 후 `SpringApplication.exit`로 종료한다. 웹 서버를 띄우지 않는다(`spring.main.web-application-type=none`).

## 5. 코딩 규칙

- 계층: Controller → Service → Repository/Mapper/Executor. 컨트롤러에 비즈니스 로직 금지.
- 생성자 주입만 사용한다(필드 주입 금지). 엔티티를 API 응답으로 반환하지 않는다(DTO 사용).
- DataSource 2개: `app`은 `@Primary`(JPA, MyBatis, JdbcTemplate 기본), `scm-ro`는 `@Qualifier("scmRoJdbcTemplate")`로만 `ReadOnlySqlExecutor`가 사용한다. LLM이 만든 SQL은 이 경로 외로 실행하지 않는다.
- 트랜잭션: 서비스 계층에서만 선언. 읽기 전용 실행은 `Connection.setReadOnly(true)`, `setQueryTimeout(5)`, `setMaxRows(1000)`.
- 비밀 값은 `.env` 환경변수로만 주입한다. 코드·로그·테스트 픽스처·README에 실제 값 금지.
- 새 클래스는 API_SPEC 6장의 이름·시그니처를 따른다. 파일 간 연결(import, 빈 이름, 설정 키)이 명세와 다르면 명세를 고치지 말고 코드를 맞춘다.
- 주석은 의도가 코드로 드러나지 않을 때만 쓴다. 이모지 금지.
- 오류가 나면 증상을 숨기는 try/catch를 만들지 않는다.

## 6. 작업 방식

1. 작업은 T0부터 순서대로 하나씩 한다. 한 번에 한 T만 진행한다.
2. 각 T의 "검증" 명령이 모두 통과해야 완료다. 통과 결과(명령과 핵심 출력)를 사용자에게 보고하고 다음 T로 넘어가기 전에 확인을 받는다.
3. 검증이 실패하면 원인을 고치고 재실행한다. 같은 원인으로 3번 실패하면 멈추고 보고한다.
4. 자신이 낸 실수를 발견하면 `docs/ai-mistakes.md`에 한 줄(날짜, T번호, 실수, 원인)을 추가한다.
5. T6의 `golden_set.json`은 사람이 작성한다. 임의로 문항을 만들거나 수정하지 않는다.
6. 골든셋 해시 동결 이후 `eval/golden_set.json`, v0 프롬프트, 모델 설정을 변경하지 않는다.

## 7. 금지

- 범위 밖 기능(조회 도구 호출, 숫자 후검증, RAG, RLS, QueryDSL, 결과 문장 생성, 로그인)
- 유료·외부 LLM API 호출
- LLM SQL을 `scm_ro` 외 계정으로 실행
- 평가 결과를 보고 골든셋 정답·문항을 바꾸는 것
- 검증 명령 없이 "완료"라고 보고하는 것

## 8. 작업 목록

의존 관계: T0 → T1 → T2 → T3 → T4 → T5 → T6 → T7 → T8 → T9 → T10. T11(React)은 T5 이후 아무 때나 시작할 수 있고, 모델 측정 대기 시간에 진행한다. T11의 README·동결 부분은 마지막에 한다.

### T0. 환경 점검과 저장소 골격

목표: 도구 버전 확인, 골격 생성.

작업
- `docs/`, `db/`, `scripts/`, `prompts/`, `eval/reports/` 디렉터리, `.gitignore`(`.env`, `build/`, `node_modules/`, `eval/reports/*.md` 제외 안 함), `.env.example`(키만), `CLAUDE.md`(`@AGENTS.md`), `docs/ai-mistakes.md`(헤더만)
- `git init`

검증
```bash
java -version 2>&1 | grep -q '"21' && echo JAVA_OK
docker compose version && docker info >/dev/null && echo DOCKER_OK
ollama --version && ollama pull qwen2.5-coder:7b && ollama list | grep -q 'qwen2.5-coder:7b' && echo MODEL_OK
curl -s localhost:11434/api/chat -d '{"model":"qwen2.5-coder:7b","stream":false,"messages":[{"role":"user","content":"1+1은?"}]}' | grep -q '"done":true' && echo OLLAMA_CHAT_OK
git ls-files .env | wc -l
```
완료: 위 네 개의 OK 출력, 마지막 줄 `0`.

### T1. DB 컨테이너·역할·스키마

참조: API_SPEC 5장

작업
- `docker-compose.yml`: `postgres:16`, `./db`를 `/docker-entrypoint-initdb.d`에 읽기 전용 마운트, named volume, 환경변수(`POSTGRES_*`, `SCM_RO_PASSWORD`), healthcheck는 `pg_isready -h 127.0.0.1 -U scm_app -d scm`(초기화 중 임시 서버는 소켓만 열기 때문)
- `db/01_roles.sh`: psql 변수(`-v ro_pw=...`)와 heredoc으로 `scm_ro` 생성, `search_path`, `statement_timeout='5s'`, `default_transaction_read_only=on`
- `db/02_schema.sql`: `scm`, `app` 스키마와 API_SPEC 5.2, 5.3의 DDL 그대로
- `db/04_grants.sql`: scm_ro에 `scm` USAGE·SELECT, `app`은 부여하지 않음 (`03_seed.sql`은 T2에서 생성, 이 시점엔 빈 파일)
- `scripts/db_reset.sh`: `docker compose down -v && docker compose up -d --wait db`

검증
```bash
set -a; . ./.env; set +a
bash scripts/db_reset.sh
docker compose exec -T db psql -U scm_app -d scm -Atc "select count(*) from information_schema.tables where table_schema in ('scm','app')"
docker compose exec -T -e PGPASSWORD=$SCM_RO_PASSWORD db psql -h 127.0.0.1 -U scm_ro -d scm -c "set default_transaction_read_only = off" -c "create table scm.x(a int)" 2>&1 | grep -q 'permission denied' && echo RO_DDL_DENIED
docker compose exec -T -e PGPASSWORD=$SCM_RO_PASSWORD db psql -h 127.0.0.1 -U scm_ro -d scm -c "select * from app.chat_log" 2>&1 | grep -q 'permission denied' && echo RO_APP_DENIED
docker compose exec -T -e PGPASSWORD=$SCM_RO_PASSWORD db psql -h 127.0.0.1 -U scm_ro -d scm -c "select pg_sleep(10)" 2>&1 | grep -q 'statement timeout' && echo RO_TIMEOUT_OK
```
완료: 테이블 수 `9`(scm 6 + app 3), 출력에 `RO_DDL_DENIED`, `RO_APP_DENIED`, `RO_TIMEOUT_OK`.

### T2. 시드 데이터

참조: PRD 5.5, API_SPEC 5.2

작업
- `db/03_seed.sql`: PL/pgSQL 함수와 `generate_series`로 생성. 난수 대신 `hashtextextended(문자열, 시드)` 기반 값을 사용. 기간 2026-08-10 ~ 2026-11-08, 예측 주 시작일 2026-08-10 ~ 2026-11-02(13주)
- 재고는 전일 재고 − 판매 + 입고로 일별 순차 계산(음수 방지), 입고는 reorder_point 규칙으로 결정적 생성, in_transit은 입고 예정 수량
- 패턴: 요일 효과, 완만한 추세, 프로모션 급증 SKU 3개 이상, 결품 SKU-거점 3쌍 이상, 안전재고 미달 3~30쌍, 재주문 필요 3쌍 이상, WMAPE 0.4 초과 SKU 3개 이상
- `db/verify/verify.sql`: `name|ok` 형식으로 출력. 항목: 6개 테이블 행 수, on_hand 음수 없음, 기간 최소·최대일, 위 패턴 하한, FK 고아 행 0
- `scripts/verify_db.sh`: verify.sql 실행 결과에 `|f`가 있으면 exit 1
- `scripts/db_checksum.sh`: 테이블별 `md5(string_agg(row::text, '' order by pk))` 출력

검증
```bash
set -a; . ./.env; set +a
bash scripts/db_reset.sh && bash scripts/verify_db.sh && echo VERIFY_OK
bash scripts/db_checksum.sh > /tmp/c1
bash scripts/db_reset.sh && bash scripts/db_checksum.sh > /tmp/c2
diff /tmp/c1 /tmp/c2 && echo DETERMINISTIC
cp /tmp/c1 db/verify/checksum.expected
```
완료: `VERIFY_OK`, `DETERMINISTIC`, 행 수 50/5/22,750/22,750/3,250/250.

### T3. 백엔드 스캐폴드, DataSource, 엔티티

참조: API_SPEC 5.3, 6, 7장

작업
- Gradle 프로젝트, `application.yml`(API_SPEC 7장), `integration` 태그 분리
- 설정: `DataSourceConfig`(app `@Primary`, scm-ro JdbcTemplate), `ChatProperties`(`@ConfigurationProperties`)
- 엔티티·리포지토리: `ChatLog`, `EvalRun`, `EvalResult` (`ddl-auto=validate`)
- `GET /api/meta`
- 통합 테스트 `ReadOnlyRoleIT`: scm_ro의 INSERT·UPDATE·DELETE·DDL 거부, `app.chat_log` 조회 거부, `pg_sleep(10)` 타임아웃, scm 테이블 SELECT 성공

검증
```bash
set -a; . ./.env; set +a
bash scripts/db_reset.sh
cd backend && ./gradlew clean test integrationTest
./gradlew bootRun > /tmp/boot.log 2>&1 &
sleep 20; curl -s localhost:8080/api/meta; kill %1; lsof -ti tcp:8080 | xargs kill
```
완료: 테스트 전부 통과, 앱이 `validate`로 기동, `/api/meta`가 API_SPEC 2.2 형식(`llmProvider":"fake"`).

### T4. LlmClient, 파서, 프롬프트 v0

참조: API_SPEC 3, 4.1, 6장

작업
- `LlmClient`, `LlmMessage`, `LlmReply`, `OllamaLlmClient`(`RestClient`, 타임아웃, `format` 스키마, `keep_alive`), `FakeLlmClient`, `LlmOutputParser`, `ParsedOutput`, `FormatException`
- `PromptBuilder`(v0만 구현, v1·v2는 `UnsupportedOperationException`), `prompts/v0.txt`(전체 DDL 포함)
- 단위 테스트: 파서 10개 이상(정상 3 action, 코드펜스, 빈 sql, 빈 message, 알 수 없는 action, 부가 텍스트, 세미콜론 제거), 빌더(플레이스홀더 잔존 시 예외, v0에 기준일이 없음), Fake 큐

검증
```bash
cd backend && ./gradlew test --tests '*LlmOutputParserTest' --tests '*PromptBuilderTest' --tests '*FakeLlmClientTest'
set -a; . ./.env; set +a
./gradlew integrationTest --tests '*OllamaLlmClientIT'
```
완료: 단위 테스트 통과, `OllamaLlmClientIT`가 실제 호출에서 파싱 가능한 JSON과 토큰 수(0 초과)를 받음.

### T5. 읽기 전용 실행기, ChatService(v0), /api/chat

참조: API_SPEC 2.1, 3.3, 6장

작업
- `ReadOnlySqlExecutor`, `QueryResult`, `SqlExecutionException`
- `ChatService.answer`: 프롬프트 조립 → LLM → 파싱 → action 분기 → (sql이면) 실행 → `chat_log` 저장. v0·v1은 검증 없이 실행, 재시도 없음. 실패는 status로 변환(예외를 밖으로 던지지 않음)
- `ChatController`, `ChatRequest`, `ChatResponse`, `ErrorResponse`, 전역 예외 처리(`@RestControllerAdvice`)
- 테스트: Fake 큐로 3 action, FORMAT_ERROR, SQL_EXECUTION_ERROR, LLM_UNAVAILABLE, chat_log 1행 저장, MockMvc로 HTTP 코드 매트릭스, 요청 검증(빈 질문, 501자)

검증
```bash
set -a; . ./.env; set +a
bash scripts/db_reset.sh
cd backend && ./gradlew test integrationTest
./gradlew bootRun --args='--chat.llm.provider=ollama --chat.prompt-version=v0' > /tmp/boot.log 2>&1 &
sleep 25
for q in 'SKU-007의 현재 재고는?' '재고 어때?' 'SKU-007 재고를 0으로 바꿔줘'; do
  curl -s -X POST localhost:8080/api/chat -H 'Content-Type: application/json' -d "{\"question\":\"$q\"}"; echo
done
docker compose exec -T db psql -U scm_app -d scm -Atc "select count(*) from app.chat_log"
kill %1; lsof -ti tcp:8080 | xargs kill
```
완료: 테스트 통과, 3개 curl 모두 API_SPEC 형식의 JSON, `chat_log` 3행. (모델 응답의 정확성은 T8에서 평가하므로 action이 기대와 달라도 통과)

### T6. 골든셋 로더와 동결 게이트

참조: PRD 5.4, API_SPEC 4.5

작업
- `GoldenItem`, `GoldenSetLoader`(형식 검증), 해시 계산·검증 유틸
- 사람이 `eval/golden_set.json` 30문항과 `eval/crosscheck.md`(교차 검증 5문항의 손계산·스프레드시트 결과) 작성. Claude Code는 형식 검증과 테스트만 만든다
- 통합 테스트 `GoldenSetIT`: 유형별 6/8/6/4/2/4, id Q01~Q30 고유, sql 문항만 goldenSql 보유, 모든 goldenSql이 scm_ro로 실행 가능·1행 이상·200행 이하, 비교·순위는 `orderSensitive=true`이고 ORDER BY 포함, `fewshot.json` 질문과 골든셋 질문이 겹치지 않음(fewshot이 있을 때)
- 사람이 검토를 마치면 `sha256sum eval/golden_set.json > eval/golden_set.sha256`로 동결

검증
```bash
set -a; . ./.env; set +a
cd backend && ./gradlew integrationTest --tests '*GoldenSetIT'
cd .. && sha256sum -c eval/golden_set.sha256
```
완료: 테스트 통과, `golden_set.json: OK`, `eval/crosscheck.md`에 5문항 일치 기록. 이 게이트 전에는 T7 이후를 진행하지 않는다.

### T7. 평가 실행기, 채점기, 집계

참조: PRD 5.4, API_SPEC 2.3, 6장

작업
- `ResultComparator`(값 기준, 컬럼명 무시, 0.1% 허용, 정렬 옵션, 숫자·날짜 정규화), `Grader`(오류 자동 분류), `OracleLlmClient`
- `EvalRunner`(`ApplicationRunner`, `eval` 프로파일): 골든셋 해시 검증 → 버전·반복별 `eval_run` 생성 → 문항별 `ChatService.answer` 호출, 정답 SQL 실행, 채점, `eval_result` 저장 → `eval_run` 요약 갱신 → `EvalReportWriter`가 콘솔과 `eval/reports/run-{id}-{version}.md` 출력(오답 표에 질문, 생성 SQL, 미분류 error_type)
- `EvalSummaryMapper`(MyBatis XML), `EvalController`(`GET /api/eval/summary`)
- 단위 테스트: ResultComparator 12개 이상(컬럼명 무시, 순서 무시·민감, 0.1% 경계, BigDecimal·Double, null, 행 수 불일치, 날짜), Grader(clarify·refuse·실행 오류·action 불일치)

검증
```bash
set -a; . ./.env; set +a
bash scripts/db_reset.sh
cd backend && ./gradlew test
./gradlew bootRun --args='--spring.profiles.active=eval --chat.llm.provider=oracle --eval.versions=v0 --eval.repeat=1'
./gradlew bootRun --args='--spring.profiles.active=eval --chat.llm.provider=fake --eval.versions=v0 --eval.repeat=1'
docker compose exec -T db psql -U scm_app -d scm -Atc "select id, prompt_version, correct, total, accuracy from app.eval_run order by id"
```
완료: oracle 실행의 정확도 `1.0000`(30/30, FR-14), fake 실행의 정확도 `0.0667`(2/30, AMBIGUOUS 2문항만 정답), 각 실행의 `eval_result` 30행, 리포트 파일 생성. oracle이 30/30이 아니면 채점기 버그이므로 T8로 가지 않는다.

### T8. v0 측정과 오답 분류

작업
- 실제 모델로 v0 측정 1회
- 리포트의 오답에 사람이 `error_type`을 지정(미분류 0건 목표). Claude Code는 갱신 SQL 템플릿과 요약 확인만 돕는다

검증
```bash
set -a; . ./.env; set +a
cd backend && ./gradlew bootRun --args='--spring.profiles.active=eval --chat.llm.provider=ollama --eval.versions=v0 --eval.repeat=1'
docker compose exec -T db psql -U scm_app -d scm -c "update app.eval_result set error_type='DATE_RANGE' where run_id=<RUN_ID> and question_id='<Q>'"
docker compose exec -T db psql -U scm_app -d scm -Atc "select count(*) from app.eval_result where run_id=<RUN_ID> and not correct and error_type is null"
```
완료: 마지막 쿼리 결과 `0`, `eval_run`에 model·temperature·seed·golden_sha256 저장, 평균 응답 시간과 토큰 수 확인. 이 실행 시간으로 v1·v2 측정 소요를 추정해 보고한다.

### T9. v1 (지표 사전, 기간 규칙, few-shot)

참조: PRD 5장, API_SPEC 4.1~4.3

작업
- `prompts/metrics.yml`(PRD 5.1~5.3 그대로), `prompts/fewshot.json`(골든셋과 겹치지 않는 5개), `prompts/v1.txt`, `MetricsDictionary`, `FewShotLoader`, `PromptBuilder` v1
- 단위 테스트: v1에 기준일·전체 지표·few-shot 5개가 들어감, few-shot 질문이 골든셋과 겹치지 않음(`GoldenSetIT` 재실행)
- v1 측정 1회 후 v0과 비교

검증
```bash
set -a; . ./.env; set +a
cd backend && ./gradlew test integrationTest --tests '*GoldenSetIT'
./gradlew bootRun --args='--spring.profiles.active=eval --chat.llm.provider=ollama --eval.versions=v1 --eval.repeat=1'
./gradlew bootRun > /tmp/boot.log 2>&1 &
sleep 20; curl -s localhost:8080/api/eval/summary; kill %1; lsof -ti tcp:8080 | xargs kill
```
완료: v0과 v1 `eval_run`의 model·temperature·seed·golden_sha256이 동일, 요약 API에서 두 버전의 유형별 정확도와 오답 원인별 건수 확인. 점수 차이가 작으면 개선이라 단정하지 않는다. 이 시점이 필수 완료 기준이다.

### T10. v2 (스키마 축소, SQL 검증, 재시도)

참조: API_SPEC 4.4, 6장 SqlValidator 규칙

작업
- `SchemaSelector`, `MetricsDictionary` pruned 모드, `SqlValidator`, `prompts/v2.txt`, `ChatService`의 v2 분기(검증 → 실행 → 오류 시 `buildRetry`로 1회 재시도, 거부는 재시도 없음)
- 단위 테스트: `SqlValidatorTest` 통과 5개 이상(단순 SELECT, JOIN, CTE, 윈도 함수, `scm.` 접두어)·거부 10개 이상(INSERT, UPDATE, DELETE, DROP, 다중 문장, CTE 안 DELETE, `pg_user`, `app.chat_log`, `pg_sleep`, `FOR UPDATE`, `SELECT INTO`, 빈 문자열, 주석에 숨긴 DDL, 미등록 테이블), LIMIT 부여·하향(없음→200, 500→200, 50 유지), `SchemaSelectorTest`(규칙별, 폴백, 차원 테이블 추가), 재시도 2종
- v2 측정 1회

검증
```bash
set -a; . ./.env; set +a
cd backend && ./gradlew test integrationTest
./gradlew bootRun --args='--spring.profiles.active=eval --chat.llm.provider=ollama --eval.versions=v2 --eval.repeat=1'
./gradlew bootRun > /tmp/boot.log 2>&1 &
sleep 20; curl -s localhost:8080/api/eval/summary; kill %1; lsof -ti tcp:8080 | xargs kill
```
완료: 테스트 통과, 세 버전의 설정(model, temperature, seed, golden_sha256)이 동일, 요약 API에 v0·v1·v2가 모두 나옴.

### T11. React 화면, README, 동결

참조: PRD FR-12, API_SPEC 2.1, 2.2

작업
- `frontend/`: 질문 입력, 제출, action별 표시(sql은 결과 표와 근거 SQL, clarify·refuse는 메시지), status 오류 표시, 하단에 기준일·모델·버전(`/api/meta`)
- `README.md`: 실행 방법(명령 순서), 설계 요약, 결과표(PRD·기획서 6장 형식), 오답 사례 3건 이상(질문, 원인, 변경, 결과), 한계(PRD 7장), 면접용 1분 설명의 자리표시(실측값은 사람이 채움)
- `scripts/final_check.sh`: DB 초기화·verify, 백엔드 `clean test integrationTest`, `npm run build`, `.env` 미추적 확인, 소스에 비밀번호 리터럴이 없는지 `grep`

검증
```bash
set -a; . ./.env; set +a
bash scripts/final_check.sh
cd frontend && npx tsc --noEmit && npm run build
git ls-files .env | wc -l
git tag poc-freeze
```
완료: `final_check.sh` exit 0, 빌드 성공, `.env` 미추적(`0`), 화면에서 3가지 시나리오 수동 확인, `poc-freeze` 태그 생성.

## 9. 일정과 T 매핑

| 날짜 | T | 그날의 완료 기준 |
| --- | --- | --- |
| 목 11/12 | T0, T1, T2 | `VERIFY_OK`, `DETERMINISTIC`, Ollama 호출 성공 |
| 금 11/13 | T3, T4, T5, T6 | curl로 3 action 응답, 골든셋 동결(사람 작업, 오전 중 권장) |
| 토 11/14 | T7, T8, T9 (+ T11 화면은 측정 대기 중) | oracle 30/30, v0·v1 점수표 |
| 일 11/15 | T10, T11 마무리 | v2 점수표, README, `poc-freeze` |

막히면 버리는 순서: T11 화면 → T10 → 골든셋 문항 수(30 → 20, 이 경우 PRD 3장 FR-08 수치와 GoldenSetIT 기대값을 함께 수정). T0~T9는 끝까지 남긴다.
