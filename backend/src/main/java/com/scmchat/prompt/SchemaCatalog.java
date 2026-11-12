package com.scmchat.prompt;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** scm 스키마 6개 테이블의 DDL(API_SPEC 5.2). 프롬프트에는 스키마 접두어 없이 넣는다. */
public final class SchemaCatalog {

    private static final Map<String, String> DDL = new LinkedHashMap<>();

    static {
        DDL.put("product", """
                CREATE TABLE product (
                  sku varchar(8) PRIMARY KEY,
                  name varchar(60) NOT NULL,
                  category varchar(30) NOT NULL,
                  unit_cost numeric(10,2) NOT NULL,
                  abc_class char(1) NOT NULL CHECK (abc_class IN ('A','B','C'))
                );""");
        DDL.put("location", """
                CREATE TABLE location (
                  location_id varchar(8) PRIMARY KEY,
                  name varchar(30) NOT NULL,
                  region varchar(20) NOT NULL
                );""");
        DDL.put("sales_daily", """
                CREATE TABLE sales_daily (
                  sale_date date NOT NULL,
                  sku varchar(8) NOT NULL REFERENCES product(sku),
                  location_id varchar(8) NOT NULL REFERENCES location(location_id),
                  qty integer NOT NULL CHECK (qty >= 0),
                  PRIMARY KEY (sale_date, sku, location_id)
                );""");
        DDL.put("inventory_daily", """
                CREATE TABLE inventory_daily (
                  snap_date date NOT NULL,
                  sku varchar(8) NOT NULL REFERENCES product(sku),
                  location_id varchar(8) NOT NULL REFERENCES location(location_id),
                  on_hand integer NOT NULL CHECK (on_hand >= 0),
                  in_transit integer NOT NULL CHECK (in_transit >= 0),
                  PRIMARY KEY (snap_date, sku, location_id)
                );""");
        DDL.put("forecast_weekly", """
                CREATE TABLE forecast_weekly (
                  week_start date NOT NULL,
                  sku varchar(8) NOT NULL REFERENCES product(sku),
                  location_id varchar(8) NOT NULL REFERENCES location(location_id),
                  forecast_qty integer NOT NULL CHECK (forecast_qty >= 0),
                  PRIMARY KEY (week_start, sku, location_id)
                );""");
        DDL.put("supply_policy", """
                CREATE TABLE supply_policy (
                  sku varchar(8) NOT NULL REFERENCES product(sku),
                  location_id varchar(8) NOT NULL REFERENCES location(location_id),
                  lead_time_days smallint NOT NULL,
                  safety_stock integer NOT NULL,
                  reorder_point integer NOT NULL,
                  PRIMARY KEY (sku, location_id)
                );""");
    }

    private SchemaCatalog() {
    }

    public static List<String> tables() {
        return List.copyOf(DDL.keySet());
    }

    public static boolean isFactTable(String table) {
        return DDL.containsKey(table) && !table.equals("product") && !table.equals("location");
    }

    /** 요청한 테이블의 DDL을 카탈로그 순서대로 이어 붙인다. */
    public static String ddlOf(Collection<String> tables) {
        for (String table : tables) {
            if (!DDL.containsKey(table)) {
                throw new IllegalArgumentException("unknown table: " + table);
            }
        }
        return DDL.entrySet().stream()
                .filter(entry -> tables.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.joining("\n"));
    }

    public static String allDdl() {
        return ddlOf(DDL.keySet());
    }
}
