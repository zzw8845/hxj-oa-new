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

    private static final DateTimeFormatter DAY_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    /** 扩展名只保留字母数字，最长 12 位 —— 挡住 {@code .php/ .jsp/} 之类的可执行后缀与超长垃圾 */
    private static final int MAX_EXT_LEN = 12;

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
        String ext = extensionOf(originalName);
        String key = LocalDate.now().format(DAY_DIR) + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + (ext.isEmpty() ? "" : "." + ext);
        Path target = resolveInside(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new BizException("附件保存失败：" + e.getMessage());
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
            throw new BizException("附件读取失败：" + e.getMessage());
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

    /**
     * 把存储键解析成绝对路径，并确认它没有逃出根目录。
     *
     * <p>键虽然来自自己的数据库，但脏数据或人为写入的 {@code ../../etc/passwd} 一旦被拼接读取，
     * 就是任意文件读取漏洞 —— 这道校验是本地存储实现里最不能省的一步。
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

    /** 从原始文件名里取一个安全的扩展名；取不到就返回空串（不猜、不例外） */
    private String extensionOf(String originalName) {
        if (originalName == null) {
            return "";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        String ext = originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ext.length() && sb.length() < MAX_EXT_LEN; i++) {
            char c = ext.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.toString();
    }
}
