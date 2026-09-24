package com.hxj.oa.document.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hxj.oa.common.exception.BizException;
import com.hxj.oa.common.security.LoginUser;
import com.hxj.oa.document.dto.AttachmentVO;
import com.hxj.oa.document.entity.Attachment;
import com.hxj.oa.document.entity.Document;
import com.hxj.oa.document.mapper.AttachmentMapper;
import com.hxj.oa.document.mapper.DocumentMapper;
import com.hxj.oa.document.storage.StorageService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 附件服务：上传、查询、下载取流、删除。
 *
 * <p><b>权限模型</b>：附件永远依附于单据，因此可见性完全复用 {@link DocumentService#assertVisible} ——
 * 能看这张单据，才能看/传它的附件。删除额外收紧为「本人上传 + 单据未提交」，
 * 因为单据一旦进入流程，附件就是审批留痕的一部分，不允许事后抹除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    /** 与 attachment.biz_type 注释保持一致 */
    private static final Set<String> BIZ_TYPES = Set.of("apply", "approve", "seal", "receipt");

    private final AttachmentMapper attachmentMapper;
    private final DocumentMapper documentMapper;
    private final DocumentService documentService;
    private final StorageService storageService;

    @Value("${oa.storage.max-file-size:20971520}")
    private long maxFileSize;

    @Value("${oa.storage.allowed-ext:jpg,jpeg,png,gif,bmp,webp,pdf,doc,docx,xls,xlsx,ppt,pptx,txt,csv,zip,rar,7z}")
    private String allowedExtRaw;

    private Set<String> allowedExt;

    @PostConstruct
    void init() {
        allowedExt = Arrays.stream(allowedExtRaw.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        log.info("附件上传白名单（{} 种）：{}", allowedExt.size(), allowedExt);
    }

    // ============================================================ 上传

    @Transactional(rollbackFor = Exception.class)
    public AttachmentVO upload(MultipartFile file, Long documentId, String nodeKey, String bizType, LoginUser user) {
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        if (file.getSize() > maxFileSize) {
            throw BizException.of("附件不能超过 %s，当前 %s",
                    AttachmentVO.humanSize(maxFileSize), AttachmentVO.humanSize(file.getSize()));
        }
        String ext = AttachmentVO.extensionOf(file.getOriginalFilename());
        if (ext.isEmpty() || !allowedExt.contains(ext)) {
            throw BizException.of("不支持的文件类型「%s」，允许：%s",
                    ext.isEmpty() ? "(无扩展名)" : ext, String.join(" / ", allowedExt));
        }
        String type = (bizType == null || bizType.isBlank()) ? "apply" : bizType.trim();
        if (!BIZ_TYPES.contains(type)) {
            throw BizException.of("未知的附件业务类型：%s", bizType);
        }

        Attachment att = new Attachment();
        att.setCompanyId(user.getCompanyId());
        att.setBizType(type);
        att.setNodeKey(trimToNull(nodeKey));
        att.setFileName(safeFileName(file.getOriginalFilename()));
        att.setFileSize(file.getSize());
        att.setMimeType(trimToNull(file.getContentType()));
        att.setUploaderId(user.getUserId());
        att.setUploaderName(user.getRealName());
        att.setCreatedBy(user.getUserId());
        att.setUpdatedBy(user.getUserId());

        if (documentId != null) {
            Document doc = requireDocument(documentId);
            documentService.assertVisible(doc, user);
            if (doc.getStatus() != null && isFinishedStatus(doc.getStatus())) {
                throw new BizException("单据已办结，不能再追加附件");
            }
            att.setDocumentId(documentId);
            att.setCompanyId(doc.getCompanyId());
        }

        try (InputStream in = file.getInputStream()) {
            att.setFileKey(storageService.save(in, att.getFileName(), att.getMimeType(), att.getFileSize()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("附件读取失败：" + e.getMessage());
        }

        try {
            attachmentMapper.insert(att);
        } catch (RuntimeException e) {
            // 落库失败必须回收已写盘的二进制，否则根目录会攒下一堆永远没人引用的孤儿文件
            storageService.delete(att.getFileKey());
            throw e;
        }
        log.info("附件上传 id={} doc={} biz={} node={} name={} by={}",
                att.getId(), documentId, type, att.getNodeKey(), att.getFileName(), user.getRealName());
        return AttachmentVO.of(att);
    }

    // ============================================================ 查询

    /** 某单据下的全部附件（按上传时间正序，审批留痕按时间读更自然） */
    public List<AttachmentVO> listByDocument(Long documentId, LoginUser user) {
        Document doc = requireDocument(documentId);
        documentService.assertVisible(doc, user);
        return attachmentMapper.selectList(Wrappers.<Attachment>lambdaQuery()
                        .eq(Attachment::getDocumentId, documentId)
                        .orderByAsc(Attachment::getId))
                .stream().map(AttachmentVO::of).collect(Collectors.toList());
    }

    /** 取附件元数据 + 内容流。调用方负责关闭流 */
    public AttachmentContent load(Long id, LoginUser user) {
        Attachment att = requireAttachment(id);
        if (att.getDocumentId() != null) {
            documentService.assertVisible(requireDocument(att.getDocumentId()), user);
        } else if (!Objects.equals(att.getUploaderId(), user.getUserId())) {
            // 尚未挂到单据上的草稿附件：只有上传者本人可见。
            // 这里刻意没有 ADMIN 旁路：草稿附件是「还没提交给别人看」的材料，
            // 管理员身份不构成查看它的业务理由；而"附件挂在单据上"的那条路径
            // 已经由 assertVisible 裁决过了 —— 两处都放行才是重复实现。
            throw BizException.forbidden("无权下载该附件");
        }
        if (!storageService.exists(att.getFileKey())) {
            throw BizException.notFound("附件文件已丢失，请联系管理员");
        }
        return new AttachmentContent(att, storageService.open(att.getFileKey()));
    }

    /** 某单据某节点下指定业务类型的附件数量（凭证必填校验用） */
    public long count(Long documentId, String nodeKey, String bizType) {
        return attachmentMapper.selectCount(Wrappers.<Attachment>lambdaQuery()
                .eq(Attachment::getDocumentId, documentId)
                .eq(nodeKey != null, Attachment::getNodeKey, nodeKey)
                .eq(bizType != null, Attachment::getBizType, bizType));
    }

    // ============================================================ 删除

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id, LoginUser user) {
        Attachment att = requireAttachment(id);
        // 删除是「写」动作，同样不给 ADMIN 旁路：附件一旦删掉，文件系统的二进制也一并回收，
        // 不可逆。要清理别人的附件应走"停用单据 / 流程终止"这类可追溯的业务动作，
        // 而不是让管理员拥有一个无痕的删除后门。
        if (!Objects.equals(att.getUploaderId(), user.getUserId())) {
            throw BizException.forbidden("只能删除本人上传的附件");
        }
        if (att.getDocumentId() != null) {
            Document doc = documentMapper.selectById(att.getDocumentId());
            if (doc != null && doc.getStatus() != null && !isDeletableStatus(doc.getStatus())) {
                throw new BizException("单据已提交，附件属于审批留痕，不能删除");
            }
        }
        attachmentMapper.deleteById(id);
        storageService.delete(att.getFileKey());
        log.info("附件删除 id={} name={} by={}", id, att.getFileName(), user.getRealName());
    }

    // ============================================================ 内部

    private Attachment requireAttachment(Long id) {
        Attachment att = id == null ? null : attachmentMapper.selectById(id);
        if (att == null) {
            throw BizException.notFound("附件不存在");
        }
        return att;
    }

    private Document requireDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            throw BizException.notFound("单据不存在: " + documentId);
        }
        return doc;
    }

    /**
     * 未进入流程的状态：草稿 / 已驳回 / 已撤回。
     *
     * <p>只用于<b>删除</b>判定 —— 单据一旦提交，附件就是审批留痕的一部分，不能再抹除。
     * 注意别把它用到上传上：审批人恰恰是在单据「审批中」时才需要上传办理凭证。
     */
    private boolean isDeletableStatus(int status) {
        return status == DocumentService.STATUS_DRAFT
                || status == DocumentService.STATUS_REJECTED
                || status == DocumentService.STATUS_WITHDRAWN;
    }

    /** 已办结（审批通过 / 归档）：此时才真正不再接受追加附件 */
    private boolean isFinishedStatus(int status) {
        return status == DocumentService.STATUS_APPROVED
                || status == DocumentService.STATUS_ARCHIVED;
    }

    private String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > 255 ? t.substring(0, 255) : t;
    }

    /**
     * 原始文件名只用于展示，但仍要挡掉换行等控制字符：
     * 它会被塞进 Content-Disposition 响应头，带 CR/LF 就是响应头注入。
     */
    private String safeFileName(String original) {
        String name = (original == null || original.isBlank()) ? "未命名文件" : original.trim();
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && sb.length() < 200; i++) {
            char c = name.charAt(i);
            if (c >= 32 && c != 127 && c != '\\' && c != '/') {
                sb.append(c);
            }
        }
        return sb.length() == 0 ? "未命名文件" : sb.toString();
    }

    /** 附件元数据 + 内容流 */
    public record AttachmentContent(Attachment attachment, InputStream stream) {
    }
}
