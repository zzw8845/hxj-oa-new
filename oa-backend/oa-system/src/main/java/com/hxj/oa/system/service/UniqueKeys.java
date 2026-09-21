package com.hxj.oa.system.service;

import org.springframework.util.StringUtils;

/**
 * 逻辑删除时的「唯一键让位」工具。
 *
 * <p>背景：本项目的若干唯一键把 deleted 也纳入了键，例如
 * uk_user_account(account, deleted)、uk_user_company_jobno、uk_role_company_code。
 * 这带来一个不直观的后果——deleted=1 的记录只能存在一条。
 * 于是「同一账号/编码 创建 → 删除 → 再创建 → 再删除」会在第二次删除时撞唯一键，
 * 前端看到的是 500，而不是任何业务含义。
 *
 * <p>做法：逻辑删除前，把唯一键字段改写成「原名#D{id}」。
 * 这样既让位给将来的同值记录，又保留可追溯的审计痕迹（id 可回查原对象）。
 *
 * <p>放在同包的包级类里而不是各自实现一份，是为了避免两个 Service
 * 出现行为不一致的孪生拷贝。
 */
final class UniqueKeys {

    /** 拼接后长度不超过 maxLen，超长时截断原值前缀，后缀（#D + id）一定保留。 */
    static String release(String value, Long id, int maxLen) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String suffix = "#D" + id;
        int keep = Math.max(1, maxLen - suffix.length());
        String base = value.length() > keep ? value.substring(0, keep) : value;
        return base + suffix;
    }

    private UniqueKeys() {
    }
}
