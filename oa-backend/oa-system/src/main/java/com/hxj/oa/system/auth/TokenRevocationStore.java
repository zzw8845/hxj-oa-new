package com.hxj.oa.system.auth;

/**
 * 登出吊销存储：记住"哪些 token 已经被作废"。
 *
 * <h3>为什么必须存在这个组件</h3>
 * 本项目的鉴权是**无状态 JWT**：权限点、角色、数据范围全部编进 token，
 * 请求期校验零查库（见 {@code AuthInterceptor} / {@code PermInterceptor}）。
 * 这带来一个直接后果：登出时服务端什么都不做的话，那张 token 在到期前
 * （当前 720 分钟）**一直有效** —— 界面上"已经退出"了，别人拿到过却还能用。
 *
 * <h3>为什么是「按 token 拉黑」而不是「按用户拉黑」</h3>
 * 按 userId 拉黑会连登出后**重新登录拿到的新 token 一起废掉**，
 * 表现为"登出之后再也登不进来"，这是比原问题更糟的 bug。
 * 所以键是 token 自己的 jti：只作废当前这一张，重新登录立刻可用。
 *
 * <h3>一致性取舍（必须知道）</h3>
 * 吊销要求**每次受保护请求多做一次查询**，这与"零查库"的模型是冲突的。
 * 权衡后取：只在这一处引入查询，其余（权限点、角色、数据范围）仍走 token，
 * 不做整体改成"token 只带 userId + Redis 存快照" —— 那是另一个量级的重构。
 * 另外 Redis 不可用时**失败开放**（当作没被吊销），理由见
 * {@link RedisTokenRevocationStore}：不能因为 Redis 故障让整个系统 401。
 */
public interface TokenRevocationStore {

    /**
     * 作废一个 token。
     *
     * @param tokenId   token 的 jti
     * @param ttlMillis 需要记住多久。传 token 的**剩余寿命**即可：
     *                  过期之后它自然失效，再记着只是白占内存 ——
     *                  而这台 Redis 是 {@code noeviction} 且与别的项目共用，
     *                  漏掉 TTL 的后果是把同实例上的其他项目写挂。
     */
    void revoke(String tokenId, long ttlMillis);

    /** 是否已被作废。Redis 故障时返回 false（失败开放，见接口注释）。 */
    boolean isRevoked(String tokenId);
}
