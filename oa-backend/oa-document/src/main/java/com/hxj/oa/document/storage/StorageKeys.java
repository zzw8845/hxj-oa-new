package com.hxj.oa.document.storage;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * 附件存储键的生成规则（本地磁盘与对象存储<b>共用</b>）。
 *
 * <p>抽出来的原因很实际：{@code file_key} 是落在数据库里的数据，
 * 两套存储实现若各写一份生成逻辑，迟早会出现"本地存的键 OSS 读不出来"这种
 * 只在切换存储时才暴露的问题。同一个包内不留孪生实现。
 *
 * <p>键形态：{@code yyyy/MM/dd/<32位随机hex>[.<ext>]}。
 * 按日期分目录是为了在控制台按天定位、也方便按生命周期规则清理，
 * 随机名则是为了不泄露原始文件名（原始名另存在 {@code attachment.file_name}）。
 */
final class StorageKeys {

    private StorageKeys() {
    }

    private static final DateTimeFormatter DAY_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /**
     * 扩展名只保留字母数字、最长 12 位 —— 挡住 {@code .php/.jsp} 之类的可执行后缀与超长垃圾。
     * 上传白名单（{@code oa.storage.allowed-ext}）已经拦了一道，这里是第二道：
     * 存储层的自我防护不应该依赖调用方是否记得校验。
     */
    private static final int MAX_EXT_LEN = 12;

    /** 生成当日目录下的随机键 */
    static String dailyKey(String originalName) {
        String ext = extensionOf(originalName);
        return LocalDate.now().format(DAY_DIR) + "/"
                + UUID.randomUUID().toString().replace("-", "")
                + (ext.isEmpty() ? "" : "." + ext);
    }

    /** 从原始文件名里取一个安全的扩展名；取不到就返回空串（不猜、不抛异常） */
    static String extensionOf(String originalName) {
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
