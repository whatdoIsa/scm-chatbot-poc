-- scm_ro는 scm 스키마 조회만 가능하다. app 스키마에는 어떤 권한도 주지 않는다.
GRANT USAGE ON SCHEMA scm TO scm_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA scm TO scm_ro;
