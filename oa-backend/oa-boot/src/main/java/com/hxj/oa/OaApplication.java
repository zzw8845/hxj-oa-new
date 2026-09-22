package com.hxj.oa;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
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
// 超时升级的定时盘点需要它；@Scheduled 方法自身按 oa.flow.escalation.enabled 决定是否真的执行，
// 所以开启它不会在"需求未确认"时产生任何副作用。
@EnableScheduling
public class OaApplication {

    public static void main(String[] args) {
        SpringApplication.run(OaApplication.class, args);
    }
}
