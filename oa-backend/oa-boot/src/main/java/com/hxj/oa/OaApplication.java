package com.hxj.oa;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 海峡金 OA 审批系统 —— 启动入口。
 *
 * 注意：MapperScan 只扫业务自己的 mapper 包，不要放大到 com.hxj 或 org.flowable，
 * 否则会把 Flowable 内部的 MyBatis mapper 一起注册，导致 SqlSessionFactory 冲突。
 */
@SpringBootApplication(scanBasePackages = "com.hxj.oa")
@MapperScan(basePackages = {
        "com.hxj.oa.system.mapper",
        "com.hxj.oa.flow.mapper",
        "com.hxj.oa.document.mapper"
})
@EnableTransactionManagement
public class OaApplication {

    public static void main(String[] args) {
        SpringApplication.run(OaApplication.class, args);
    }
}
