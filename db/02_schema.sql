CREATE SCHEMA scm;
CREATE SCHEMA app;

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
