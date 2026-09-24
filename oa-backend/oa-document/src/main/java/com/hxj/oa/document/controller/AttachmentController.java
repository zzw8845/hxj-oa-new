package com.hxj.oa.document.controller;

import com.hxj.oa.common.annotation.Audit;
import com.hxj.oa.common.api.R;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.UserContext;
import com.hxj.oa.document.dto.AttachmentVO;
import com.hxj.oa.document.entity.Attachment;
import com.hxj.oa.document.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 附件：上传、列表、下载、预览、删除。
 *
 * <p>权限不在本层用注解声明，而是由 {@link AttachmentService} 依据<b>单据可见性</b>判定 ——
 * 附件权限本质是「这张单据你能不能看」，是数据行级的，不是一个静态权限点能表达的。
 * 登录态仍由 AuthInterceptor 统一保证。
 */
@RestController
@RequestMapping("/api/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /**
     * 上传附件。
     *
     * @param file       要上传的文件（form-data 字段名固定为 {@code file}）
     * @param documentId 所属单据；草稿已创建的情况下由前端传入
     * @param nodeKey    所属流程节点（审批凭证需要，申请资料可空）
     * @param bizType    apply 申请 / approve 审批凭证 / seal 用印 / receipt 付款回单
     */
    @PostMapping
    @Audit(module = "document", action = "uploadAttachment")
    public R<AttachmentVO> upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam(value = "documentId", required = false) Long documentId,
                                  @RequestParam(value = "nodeKey", required = false) String nodeKey,
                                  @RequestParam(value = "bizType", required = false) String bizType) {
        return R.ok(attachmentService.upload(file, documentId, nodeKey, bizType, UserContext.require()), "上传成功");
    }

    /** 某单据的附件列表 */
    @GetMapping
    public R<List<AttachmentVO>> list(@RequestParam("documentId") Long documentId) {
        return R.ok(attachmentService.listByDocument(documentId, UserContext.require()));
    }

    /** 下载（强制落盘保存） */
    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        AttachmentService.AttachmentContent content = attachmentService.load(id, UserContext.require());
        return stream(content, contentTypeOf(content.attachment()), false);
    }

    /** 在线预览（仅图片与 PDF；其余类型一律拒绝，避免把不可信内容当 HTML/SVG 内联渲染出来） */
    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable Long id) {
        AttachmentService.AttachmentContent content = attachmentService.load(id, UserContext.require());
        MediaType inline = inlineTypeOf(content.attachment().getFileName());
        if (inline == null) {
            throw new BizException("该文件类型不支持在线预览，请下载后查看");
        }
        return stream(content, inline, true);
    }

    /**
     * 删除附件。
     *
     * <p>只能删本人上传的（管理员例外）；单据已提交后附件属于审批留痕，不允许再删。
     *
     * @param id 附件 ID
     */
    @DeleteMapping("/{id}")
    @Audit(module = "document", action = "deleteAttachment")
    public R<Void> delete(@PathVariable Long id) {
        attachmentService.delete(id, UserContext.require());
        return R.ok(null, "已删除");
    }

    // ------------------------------------------------------------------ 内部

    private ResponseEntity<Resource> stream(AttachmentService.AttachmentContent content,
                                            MediaType mediaType, boolean inline) {
        Attachment att = content.attachment();
        // 中文文件名必须走 RFC 5987 的 filename*，直接塞 filename= 会变乱码或被截断
        String encoded = URLEncoder.encode(att.getFileName(), StandardCharsets.UTF_8).replace("+", "%20");
        String disposition = (inline ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                // 附件内容不可信，禁止浏览器嗅探类型
                .header("X-Content-Type-Options", "nosniff")
                .contentType(mediaType)
                .contentLength(att.getFileSize() == null ? -1 : att.getFileSize())
                .body(new InputStreamResource(content.stream()));
    }

    /**
     * 下载时统一按二进制流下发。
     * 不采信数据库里的 mime_type —— 它来自客户端上传时自报的 Content-Type，拿它当响应类型等于让上传者决定浏览器怎么解析。
     */
    private MediaType contentTypeOf(Attachment att) {
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /** 内联类型只从文件扩展名推断，且仅限浏览器内安全的图片与 PDF */
    private MediaType inlineTypeOf(String fileName) {
        return switch (AttachmentVO.extensionOf(fileName)) {
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "bmp" -> MediaType.parseMediaType("image/bmp");
            case "webp" -> MediaType.parseMediaType("image/webp");
            case "pdf" -> MediaType.APPLICATION_PDF;
            default -> null;
        };
    }
}
