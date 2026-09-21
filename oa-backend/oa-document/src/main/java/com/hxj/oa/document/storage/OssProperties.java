package com.hxj.oa.document.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 阿里云 OSS 配置（{@code oa.oss.*}）。
 *
 * <p>密钥<b>没有默认值</b>，必须由环境变量注入。仓库是 public，
 * 任何写进代码或 yml 的默认密钥都等于公开泄露 —— 这类"图方便留个默认值"的做法，
 * 在本项目的前一版代码里已经真实发生过一次（内置 JWT 默认密钥），
 * 后果是每个未配置该变量的部署都能被伪造管理员令牌。这里不再重复。
 */
@Data
@Component
@ConfigurationProperties(prefix = "oa.oss")
public class OssProperties {

    /** 形如 {@code https://oss-cn-shenzhen.aliyuncs.com}；不写协议头时 SDK 会按 http 处理 */
    private String endpoint;

    private String accessKeyId;

    private String accessKeySecret;

    /** 私有桶：业务附件走这里。下载一律经后端鉴权代理，不把 URL 直接给前端 */
    private String bucket;

    /**
     * 公开桶（可匿名访问）。
     *
     * <p><b>当前没有调用点</b>：本项目的资源要么在数据库里（表单模板 JSON），
     * 要么就在单个 {@code oa.html} 内联，没有"需要匿名直链"的文件。
     * 保留该配置是为了不改配置结构就能接上这类需求；
     * 一旦有（比如登录页 Logo、模板配图），在此按 {@code savePublic} 之类的入口补一个实现即可。
     */
    private String publicBucket;

    /** 让 SDK 依扩展名推断 Content-Type。关掉会导致浏览器把 PDF/图片当二进制一律"另存为" */
    private boolean autoContentType = true;
}
