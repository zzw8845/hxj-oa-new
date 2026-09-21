package com.hxj.oa.document.dto;

import com.hxj.oa.document.entity.Attachment;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * 附件视图对象。
 *
 * <p>刻意<b>不包含 {@code fileKey}</b> —— 存储键是实现细节，暴露出去等于把本地路径/对象存储桶名告诉前端，
 * 前端一律通过附件 id 走下载接口。
 */
@Data
public class AttachmentVO {

    private static final Set<String> PREVIEWABLE = Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "pdf");

    private Long id;
    private Long documentId;
    private String nodeKey;
    private String bizType;
    /** 业务类型中文名，前端直接展示，避免再维护一份映射 */
    private String bizTypeName;
    private String fileName;
    private Long fileSize;
    /** 人类可读大小，如 1.2 MB */
    private String sizeText;
    private String mimeType;
    private Long uploaderId;
    private String uploaderName;
    private LocalDateTime createdAt;
    /** 能否在浏览器内直接预览（图片 / PDF），否则前端只给下载入口 */
    private Boolean previewable;

    public static AttachmentVO of(Attachment a) {
        if (a == null) {
            return null;
        }
        AttachmentVO vo = new AttachmentVO();
        vo.setId(a.getId());
        vo.setDocumentId(a.getDocumentId());
        vo.setNodeKey(a.getNodeKey());
        vo.setBizType(a.getBizType());
        vo.setBizTypeName(bizTypeName(a.getBizType()));
        vo.setFileName(a.getFileName());
        vo.setFileSize(a.getFileSize());
        vo.setSizeText(humanSize(a.getFileSize()));
        vo.setMimeType(a.getMimeType());
        vo.setUploaderId(a.getUploaderId());
        vo.setUploaderName(a.getUploaderName());
        vo.setCreatedAt(a.getCreatedAt());
        vo.setPreviewable(PREVIEWABLE.contains(extensionOf(a.getFileName())));
        return vo;
    }

    public static String bizTypeName(String bizType) {
        if (bizType == null) {
            return null;
        }
        return switch (bizType) {
            case "apply" -> "申请资料";
            case "approve" -> "审批凭证";
            case "seal" -> "用印文件";
            case "receipt" -> "付款回单";
            default -> bizType;
        };
    }

    public static String humanSize(Long bytes) {
        if (bytes == null || bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB"};
        double v = bytes;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return i == 0 ? bytes + " B" : String.format(Locale.ROOT, "%.1f %s", v, units[i]);
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
