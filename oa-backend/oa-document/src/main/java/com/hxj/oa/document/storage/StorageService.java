package com.hxj.oa.document.storage;

import java.io.InputStream;

/**
 * 附件二进制存储抽象。
 *
 * <p>当前只有本地磁盘实现（{@link LocalStorageService}）。将来上对象存储（OSS / COS / MinIO）时，
 * 新增一个实现类并切换 {@code oa.storage.type} 即可，业务侧代码零改动。
 *
 * <p><b>约定</b>：{@code fileKey} 的形态由实现自行决定（本地实现是相对路径，对象存储是 objectKey），
 * 调用方只负责原样存取，<b>不得解析、拼接或对外暴露该字符串</b>。
 */
public interface StorageService {

    /**
     * 保存文件流。
     *
     * @param in           文件流，由调用方负责关闭
     * @param originalName 原始文件名，仅用于推断扩展名
     * @param mimeType     MIME 类型，可空
     * @param size         字节数，用于流式落盘时的容量保护
     * @return 可直接持久化到 {@code attachment.file_key} 的存储键
     */
    String save(InputStream in, String originalName, String mimeType, long size);

    /** 打开读取流，调用方负责关闭。键不存在时抛 {@code BizException} */
    InputStream open(String fileKey);

    /** 删除。键不存在时静默返回（幂等） */
    void delete(String fileKey);

    boolean exists(String fileKey);
}
