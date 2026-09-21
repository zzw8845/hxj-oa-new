package com.hxj.oa.system.auth;

import com.hxj.oa.common.security.DataScopeType;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Set;

/**
 * 登录认证快照缓存：把 {@code AuthService#buildLoginUser} 里那 4 次权限查询的结果按 userId 缓存。
 *
 * <p><b>它不改变"权限是登录时刻快照"这个既有语义。</b>
 * 权限点只在下发 JWT 时被读取一次，之后请求期校验走 token 里的 permCodes、零查库
 * （见 {@code PermInterceptor}）。缓存做的只是让<em>重复登录</em>不必重放那 4 条 JOIN 查询，
 * 因此"改角色要重新登录"这条规则原样保留。
 *
 * <p><b>失效用全局版本号，而不是逐用户删除</b>：角色改了会影响哪些用户，
 * 需要额外查一次 user_role；而这类操作是人工配置、每天个位数。
 * 换代的代价只是"下次登录多查 4 次库"，换来的是<b>不可能出现脏缓存</b>，
 * 同时完全避开 SCAN/KEYS —— 那两个命令在大 key 空间上会阻塞整个 Redis，
 * 而生产上这台 Redis 是与其他项目共用的。
 */
public interface AuthSnapshotCache {

    /**
     * 用户级权限快照，字段与 {@code AuthService#buildLoginUser} 的四次查询一一对应。
     *
     * <p>刻意<b>不含</b>部门名称/部门路径：那两项目前只需 1 次主键查询，
     * 且"部门改名"比"改角色权限"频繁得多，缓存它们只会换来更多陈旧数据的投诉。
     */
    record AuthSnapshot(
            Set<String> roleCodes,
            Set<String> permCodes,
            DataScopeType dataScope,
            Set<Long> scopeDeptIds) {
    }

    /** 未命中或已失效返回 null */
    AuthSnapshot get(Long userId);

    void put(Long userId, AuthSnapshot snapshot);

    /** 让所有已缓存快照立即失效 */
    void invalidateAll();

    /**
     * 在当前事务<b>提交之后</b>失效缓存。
     *
     * <p>权限/角色/成员变更都发生在 {@code @Transactional} 方法里，必须在提交后失效，
     * 否则会踩两个方向的坑：
     * <ul>
     *   <li>在方法体内直接失效，而事务随后回滚（例如提交了一个库里不存在的权限编码），
     *       权限其实没变，却已经白白清了一遍缓存；</li>
     *   <li>更危险的是反向假设 —— 若在提交前失效、提交时却失败了，
     *       就成了"缓存说没事、库也没变"，看似无害，但这种"先失效后提交"的写法
     *       一旦被后来者复制到"先提交后失效"的语义里，就会出现<em>缓存比数据库新</em>
     *       这种最难排查的不一致。</li>
     * </ul>
     * 挂在 afterCommit 上，语义就只有一种：事务成功 ⇒ 缓存必被清。
     */
    default void invalidateAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            invalidateAll();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                invalidateAll();
            }
        });
    }
}
