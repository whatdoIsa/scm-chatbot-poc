package com.scmchat.mapper;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 읽기 전용 계정(scm_ro)의 SqlSessionFactory에 붙는 매퍼 표시. @Mapper가 붙은 매퍼는 app DataSource를 쓴다. */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ScmRoMapper {
}
