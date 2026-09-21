package com.hxj.oa.document.storage;

import com.hxj.oa.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 本地磁盘存储实现：把附件按 {@code yyyy/MM/dd/<uuid>.<ext>} 落到可配置根目录下。
 *
 * <p>生产环境建议换成对象存储；本实现的存在意义是让联调与私有化部署零外部依赖即可跑通。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "oa.storage.type", havingValue = "local", matchIfMissing = true)
public class LocalStorageService implements StorageService {

    private final Path root;

    public LocalStorageService(@Value("${oa.storage.local.root:./data/attachments}") String rootDir) {
        this.root = Paths.get(rootDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new IllegalStateException("附件根目录无法创建：" + this.root, e);
        }
        log.info("附件本地存储已启用，根目录={}", this.root);
    }

    @Override
    public String save(InputStream in, String originalName, String mimeType, long size) {
        if (in == null) {
            throw new BizException("附件内容为空");
        }
        // 键的生成规则与 OSS 实现共用（见 StorageKeys），避免切换存储时键对不上
        String key = StorageKeys.dailyKey(originalName);
        Path target = resolveInside(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // 细节（含落盘绝对路径）只进日志：异常消息会被 GlobalExceptionHandler 原样返回给前端
            log.error("附件保存失败 key={}", key, e);
            throw new BizException("附件保存失败，请稍后重试");
        }
        log.info("附件落盘 key={} size={} mime={}", key, size, mimeType);
        return key;
    }

    @Override
    public InputStream open(String fileKey) {
        Path p = resolveInside(fileKey);
        try {
            return Files.newInputStream(p);
        } catch (NoSuchFileException e) {
            throw BizException.notFound("附件文件不存在或已被清理");
        } catch (IOException e) {
            // 同上：不把文件系统路径回给调用方
            log.error("附件读取失败 fileKey={}", fileKey, e);
            throw new BizException("附件读取失败，请联系管理员");
        }
    }

    @Override
    public void delete(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(resolveInside(fileKey));
        } catch (IOException e) {
            // 删除失败不影响业务（记录已被逻辑删除，孤儿文件由清理任务兜底）
            log.warn("附件文件删除失败，key={} 原因={}", fileKey, e.getMessage());
        }
    }

    @Override
    public boolean exists(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return false;
        }
        return Files.isRegularFile(resolveInside(fileKey));
    }

    // ------------------------------------------------------------------ 内部

    /** 把存储键解析成绝对路径，并确认它没有逃出根目录。
     *
     * <p>键虽然来自自己的数据库，但脏数据或人为写入的 {@code ../../etc/passwd} 一旦被拼接读取，
     * 就是任意文件读取漏洞 —— 这道校验是本地存储实现里最不能省的一步。
     * （对象存储实现不需要它：OSS 的 objectKey 是平坦命名空间，不存在"越界"这个维度。）
     */
    private Path resolveInside(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            throw new BizException("附件存储键为空");
        }
        Path p = root.resolve(fileKey).normalize();
        if (!p.startsWith(root)) {
            log.warn("拦截越界的附件存储键：{}", fileKey);
            throw BizException.forbidden("非法的附件存储键");
        }
        return p;
    }
}
