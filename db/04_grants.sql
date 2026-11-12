-- scm_ro는 scm 스키마 조회만 가능하다. app 스키마에는 어떤 권한도 주지 않는다.
GRANT USAGE ON SCHEMA scm TO scm_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA scm TO scm_ro;

-- PUBLIC 기본 권한으로 scm_ro가 임시 테이블을 만들거나 public 스키마를 쓰지 못하게 회수한다.
REVOKE TEMPORARY ON DATABASE :"DBNAME" FROM PUBLIC;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
