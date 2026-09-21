package com.hxj.oa.document.storage;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.hxj.oa.common.exception.BizException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;

/**
 * 阿里云 OSS 存储实现（{@code oa.storage.type=oss}）。
 *
 * <p>只写<b>私有桶</b>：附件下载一律经 {@code AttachmentController} 鉴权后由后端代理取流，
 * 不向前端下发任何直链。这样"能不能看这张单据的附件"仍然只有一个判定入口
 * （见 {@code DocumentService#assertVisible}），不会因为多了一条 OSS 直链而出现绕过。
 *
 * <p><b>键的生成规则与 {@link LocalStorageService} 共用</b>（{@link StorageKeys}）：
 * {@code file_key} 是库里的存量数据，两套实现各写一份生成逻辑，
 * 迟早会出现"本地存的键 OSS 读不出来"，而那种问题只会在切换存储的那一刻爆发。
 *
 * <p>关于 {@code oa.oss.public-bucket}：配置已保留但<b>当前无调用点</b>，
 * 详见 {@link OssProperties#getPublicBucket()} 的说明。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "oa.storage.type", havingValue = "oss")
public class OssStorageService implements StorageService {

    /** 无扩展名或推断失败时的兜底类型 */
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    /** OSS 在对象不存在时返回的错误码 */
    private static final String NO_SUCH_KEY = "NoSuchKey";

    private final OSS client;
    private final OssProperties props;

    public OssStorageService(OssProperties props) {
        this.props = props;
        // fail-fast：漏配就启动失败并说清缺哪一项。
        // 反例是"带着空 AK 起服务、等第一次上传时抛一个看不懂的 403" ——
        // 那种错误在容器日志里往往被淹掉，排查成本远高于启动时直接拒绝。
        require(props.getEndpoint(), "oa.oss.endpoint 或环境变量 OSS_ENDPOINT");
        require(props.getAccessKeyId(), "oa.oss.access-key-id 或环境变量 OSS_ACCESS_KEY_ID");
        require(props.getAccessKeySecret(), "oa.oss.access-key-secret 或环境变量 OSS_ACCESS_KEY_SECRET");
        require(props.getBucket(), "oa.oss.bucket 或环境变量 OSS_BUCKET");

        this.client = new OSSClientBuilder()
                .build(withScheme(props.getEndpoint().trim()),
                        props.getAccessKeyId().trim(),
                        props.getAccessKeySecret().trim());
        // 只打桶名与端点：AccessKey 绝不进日志（日志会被转发、归档、被人翻看）
        log.info("附件 OSS 存储已启用 endpoint={} bucket={}（公开桶={}，当前无调用点）",
                props.getEndpoint(), props.getBucket(), blankToDash(props.getPublicBucket()));
    }

    @Override
    public String save(InputStream in, String originalName, String mimeType, long size) {
        if (in == null) {
            throw new BizException("附件内容为空");
        }
        String key = StorageKeys.dailyKey(originalName);
        ObjectMetadata meta = new ObjectMetadata();
        // 必须先声明长度：不声明时 SDK 走分块传输，部分网络中间设备会把它拦掉；
        // 声明错了则会被截断，所以只在拿到正数时才设置。
        if (size > 0) {
            meta.setContentLength(size);
        }
        meta.setContentType(resolveContentType(originalName, mimeType));
        try {
            client.putObject(props.getBucket(), key, in, meta);
        } catch (OSSException e) {
            // 只 log 细节，不把它拼进返回给前端的消息（含 bucket、requestId 等内部信息）。
            // requestId 一定要记：找阿里云工单时它是唯一的定位凭据。
            log.error("附件上传 OSS 失败 key={} size={} errorCode={} requestId={}",
                    key, size, e.getErrorCode(), e.getRequestId(), e);
            throw new BizException("附件保存失败，请稍后重试");
        } catch (ClientException e) {
            log.error("附件上传 OSS 网络异常 key={} size={}", key, size, e);
            throw new BizException("附件保存失败，请稍后重试");
        }
        log.info("附件已上传 OSS key={} size={} mime={}", key, size, mimeType);
        return key;
    }

    @Override
    public InputStream open(String fileKey) {
        requireKey(fileKey);
        try {
            OSSObject object = client.getObject(props.getBucket(), fileKey);
            // 必须把 OSSObject 一起返回给调用方持有，见 HeldObjectStream 的注释
            return new HeldObjectStream(object);
        } catch (OSSException e) {
            if (NO_SUCH_KEY.equals(e.getErrorCode())) {
                throw BizException.notFound("附件文件不存在或已被清理");
            }
            log.error("附件读取 OSS 失败 fileKey={} errorCode={} requestId={}",
                    fileKey, e.getErrorCode(), e.getRequestId(), e);
            throw new BizException("附件读取失败，请联系管理员");
        } catch (ClientException e) {
            log.error("附件读取 OSS 网络异常 fileKey={}", fileKey, e);
            throw new BizException("附件读取失败，请联系管理员");
        }
    }

    @Override
    public void delete(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return;
        }
        try {
            client.deleteObject(props.getBucket(), fileKey);
        } catch (OSSException | ClientException e) {
            // 与本地实现对齐：删除失败不影响业务（记录已逻辑删除，孤儿对象由生命周期规则清理）
            log.warn("附件对象删除失败，key={} 原因={}", fileKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return false;
        }
        try {
            return client.doesObjectExist(props.getBucket(), fileKey);
        } catch (OSSException | ClientException e) {
            log.warn("附件存在性探测失败，key={} 原因={}", fileKey, e.getMessage());
            return false;
        }
    }

    @PreDestroy
    void shutdown() {
        try {
            client.shutdown();
        } catch (RuntimeException e) {
            log.debug("OSS 客户端关闭时抛异常，忽略：{}", e.toString());
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 持有 {@link OSSObject} 引用的包装流。
     *
     * <p><b>不是画蛇添足</b>：{@code getObject} 返回的内容流由 {@code OSSObject} 持有，
     * 后者的 {@code finalize()} 会在被 GC 时关闭连接。若只把 {@code getObjectContent()}
     * 交出去，{@code OSSObject} 会在方法返回后立刻变成不可达对象，
     * 于是"偶发地在读取中途被切断" —— 大附件下载失败、小附件却正常，
     * 而且随 GC 时机漂移，属于最难复现的一类问题。多持一个引用即可根除。
     */
    private static final class HeldObjectStream extends FilterInputStream {

        @SuppressWarnings("unused")
        private final OSSObject held;

        HeldObjectStream(OSSObject object) {
            super(object.getObjectContent());
            this.held = object;
        }

        @Override
        public void close() throws IOException {
            // OSSObjectContent 的 close 会归还底层连接；先关流，再让 held 随对象一起被回收
            super.close();
        }
    }

    private String resolveContentType(String originalName, String mimeType) {
        if (mimeType != null && !mimeType.isBlank() && !DEFAULT_CONTENT_TYPE.equals(mimeType)) {
            return mimeType;
        }
        if (props.isAutoContentType() && originalName != null) {
            String guessed = URLConnection.guessContentTypeFromName(originalName);
            if (guessed != null && !guessed.isBlank()) {
                return guessed;
            }
        }
        return DEFAULT_CONTENT_TYPE;
    }

    /** 端点没写协议头时补 https：SDK 默认按 http 走，明文传输 AK 签名不是我们想要的默认行为 */
    private String withScheme(String endpoint) {
        return (endpoint.startsWith("http://") || endpoint.startsWith("https://"))
                ? endpoint
                : "https://" + endpoint;
    }

    private void requireKey(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new BizException("附件存储键为空");
        }
    }

    private void require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "附件 OSS 存储配置不完整，缺少：" + what + "（见 oa-backend/docker-test/.env.example）");
        }
    }

    private String blankToDash(String s) {
        return (s == null || s.isBlank()) ? "(未配置)" : s;
    }
}
