package com.scmchat.config;

import com.scmchat.mapper.ScmRoMapper;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * SqlSessionFactory를 DataSource마다 하나씩 둔다. SqlSessionFactory 빈을 직접 등록하면 자동 구성이 물러나므로
 * app 쪽도 여기서 만든다. @Mapper는 app(평가 집계), @ScmRoMapper는 scm_ro(조회 도구)에 붙는다.
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "com.scmchat.mapper", annotationClass = Mapper.class,
        sqlSessionFactoryRef = "sqlSessionFactory")
@MapperScan(basePackages = "com.scmchat.mapper", annotationClass = ScmRoMapper.class,
        sqlSessionFactoryRef = "scmRoSqlSessionFactory")
public class MyBatisConfig {

    private static final int SCM_RO_STATEMENT_TIMEOUT_SECONDS = 5;

    @Bean
    @Primary
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath:mapper/*.xml"));
        return factory.getObject();
    }

    @Bean
    public SqlSessionFactory scmRoSqlSessionFactory(@Qualifier("scmRoDataSource") DataSource scmRoDataSource)
            throws Exception {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setDefaultStatementTimeout(SCM_RO_STATEMENT_TIMEOUT_SECONDS);
        // 지표 값이 NULL이어도(분모 0, 대상 없음) 행과 컬럼이 남게 한다. 기본 설정은 NULL 컬럼을 맵에서 빼고,
        // 모든 컬럼이 NULL인 행은 행 자체를 null로 돌려준다.
        configuration.setCallSettersOnNulls(true);
        configuration.setReturnInstanceForEmptyRow(true);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(scmRoDataSource);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath:mapper/scmro/*.xml"));
        return factory.getObject();
    }
}
