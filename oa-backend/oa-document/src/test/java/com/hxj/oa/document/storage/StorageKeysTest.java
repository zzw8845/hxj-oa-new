package com.hxj.oa.document.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附件存储键生成：本地磁盘与 OSS <b>共用</b>这一份逻辑。
 *
 * <p>它值得单独测的理由：{@code file_key} 是入库的数据，
 * 一旦两套存储实现的键规则不一致，只会在"切换存储类型"时爆出来 ——
 * 那是上线当天最不该出现的问题。
 *
 * <p>扩展名过滤是安全边界（挡住 {@code .php/.jsp} 之类），也必须锁住。
 */
class StorageKeysTest {

    @Test
    @DisplayName("dailyKey 形如 yyyy/MM/dd/<32位hex>[.ext]，按当天日期分目录")
    void dailyKeyShape() {
        String key = StorageKeys.dailyKey("合同扫描件.PDF");
        String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"));

        assertTrue(key.startsWith(today + "/"), "键必须以当天目录开头，实际=" + key);
        String rest = key.substring(today.length() + 1);
        assertTrue(rest.matches("[0-9a-f]{32}\\.pdf"), "文件名应为 32 位随机 hex + 小写扩展名，实际=" + rest);
    }

    @Test
    @DisplayName("无扩展名时不留多余的点")
    void noExtensionNoDot() {
        String key = StorageKeys.dailyKey("README");
        assertTrue(key.matches(".*/[0-9a-f]{32}$"), "不该出现扩展名，实际=" + key);
    }

    @Test
    @DisplayName("原始文件名不进键名 —— 避免泄露与路径注入")
    void originalNameNeverLeaksIntoKey() {
        String key = StorageKeys.dailyKey("员工离职证明-张伟.pdf");
        assertTrue(key.indexOf("张伟") < 0, "键里不应出现原始文件名");
        assertTrue(key.indexOf("..") < 0, "键里不应出现上跳路径");
    }

    @Test
    @DisplayName("扩展名只保留字母数字，遇非法字符即截断")
    void extensionIsSanitized() {
        assertEquals("pdf", StorageKeys.extensionOf("a.PDF"), "大写统一转小写");
        assertEquals("j", StorageKeys.extensionOf("a.j p g"), "空格处截断");
        assertEquals("", StorageKeys.extensionOf("../../etc/passwd"), "路径分隔符必须被截掉");
        assertEquals("", StorageKeys.extensionOf("shell."), "以点结尾视为无扩展名");
        assertEquals("", StorageKeys.extensionOf("无扩展名"), "中文没有点则无扩展名");
        assertEquals("", StorageKeys.extensionOf(null), "null 安全返回空串");
    }

    @Test
    @DisplayName("超长扩展名被截到 12 位，防止键名异常膨胀")
    void extensionIsTruncated() {
        String ext = StorageKeys.extensionOf("a.abcdefghijklmnopqrstuvwxyz");
        assertEquals(12, ext.length());
        assertEquals("abcdefghijkl", ext);
    }

    @Test
    @DisplayName("每次调用都产生不同的键（同名文件不会互相覆盖）")
    void keysAreUnique() {
        String a = StorageKeys.dailyKey("same.txt");
        String b = StorageKeys.dailyKey("same.txt");
        assertTrue(!a.equals(b), "同名文件必须得到不同的存储键");
    }
}
