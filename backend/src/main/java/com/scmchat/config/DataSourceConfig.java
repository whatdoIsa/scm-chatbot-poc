package com.scmchat.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * app(scm_app)은 JPA, 평가 집계 매퍼, 기본 JdbcTemplate이 쓰는 기본 DataSource다.
 * scm-ro(scm_ro)는 scm 스키마를 읽기만 하는 경로 전용이다. LLM이 만든 SQL은 scmRoJdbcTemplate으로,
 * 조회 도구의 고정 쿼리는 MyBatisConfig의 scmRoSqlSessionFactory로 실행한다.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("app.datasource")
    public DataSourceProperties appDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @Primary
    public HikariDataSource dataSource(DataSourceProperties appDataSourceProperties) {
        HikariDataSource dataSource = appDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("app");
        return dataSource;
    }

    // scmRoJdbcTemplate 빈이 있으면 자동 구성의 JdbcTemplate이 만들어지지 않으므로 기본 JdbcTemplate을 직접 등록한다.
    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConfigurationProperties("scm-ro.datasource")
    public DataSourceProperties scmRoDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    public HikariDataSource scmRoDataSource(
            @Qualifier("scmRoDataSourceProperties") DataSourceProperties scmRoDataSourceProperties) {
        HikariDataSource dataSource = scmRoDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        dataSource.setPoolName("scm-ro");
        dataSource.setMaximumPoolSize(4);
        return dataSource;
    }

    @Bean
    public JdbcTemplate scmRoJdbcTemplate(@Qualifier("scmRoDataSource") DataSource scmRoDataSource) {
        return new JdbcTemplate(scmRoDataSource);
    }
}
