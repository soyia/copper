package com.soyia.copperv2.service;

import com.soyia.copperv2.dto.OrderDtos;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Service
public class InboundAttachmentService {
    private static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final int MAX_FILES_PER_INBOUND = 12;
    private static final Set<String> ALLOWED_TYPES = Set.of(
            MediaType.IMAGE_JPEG_VALUE, MediaType.IMAGE_PNG_VALUE, "image/webp"
    );

    private final JdbcTemplate jdbcTemplate;
    private final LocalFileStorageService fileStorageService;
    private final StorageCapacityService storageCapacityService;

    public InboundAttachmentService(JdbcTemplate jdbcTemplate, LocalFileStorageService fileStorageService,
                                    StorageCapacityService storageCapacityService) {
        this.jdbcTemplate = jdbcTemplate;
        this.fileStorageService = fileStorageService;
        this.storageCapacityService = storageCapacityService;
    }

    public List<OrderDtos.InboundAttachment> list(Long inboundId) {
        requireInbound(inboundId);
        return jdbcTemplate.query("""
                select id,inbound_id,file_name,content_type,file_size,sha256,uploaded_at
                from inbound_attachment where inbound_id=? order by id
                """, (rs, rowNum) -> new OrderDtos.InboundAttachment(
                rs.getLong("id"), rs.getLong("inbound_id"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("file_size"), rs.getString("sha256"),
                rs.getTimestamp("uploaded_at").toLocalDateTime()
        ), inboundId);
    }

    @Transactional
    public OrderDtos.InboundAttachment upload(Long inboundId, MultipartFile file) {
        storageCapacityService.assertLargeWriteAllowed("附件上传");
        requireInbound(inboundId);
        if (file == null || file.isEmpty()) throw new BusinessException("请选择发货单图片");
        if (file.getSize() > MAX_FILE_SIZE) throw new BusinessException("单张发货单图片不能超过10MB");
        String contentType = StringUtils.hasText(file.getContentType())
                ? file.getContentType().toLowerCase() : "";
        if (!ALLOWED_TYPES.contains(contentType)) throw new BusinessException("仅支持 JPG、PNG、WebP 图片");
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from inbound_attachment where inbound_id=?", Integer.class, inboundId);
        if (count != null && count >= MAX_FILES_PER_INBOUND) {
            throw new BusinessException("每张入库单最多上传12张发货单图片");
        }
        try {
            byte[] content = file.getBytes();
            String sha256 = sha256(content);
            Integer duplicate = jdbcTemplate.queryForObject("""
                    select count(*) from inbound_attachment where inbound_id=? and sha256=?
                    """, Integer.class, inboundId, sha256);
            if (duplicate != null && duplicate > 0) throw new BusinessException("此图片已经上传，无需重复添加");
            String fileName = safeFileName(file.getOriginalFilename());
            String storagePath = fileStorageService.store(content, "inbounds/" + inboundId,
                    fileStorageService.extensionForContentType(contentType));
            jdbcTemplate.update("""
                    insert into inbound_attachment
                    (inbound_id,file_name,content_type,file_size,sha256,storage_path,file_content,uploaded_at)
                    values(?,?,?,?,?,?,null,now())
                    """, inboundId, fileName, contentType, content.length, sha256, storagePath);
            Long id = jdbcTemplate.queryForObject("select last_insert_id()", Long.class);
            return list(inboundId).stream().filter(item -> item.id().equals(id)).findFirst()
                    .orElseThrow(() -> new BusinessException("发货单图片保存后读取失败"));
        } catch (IOException ex) {
            throw new BusinessException("发货单图片读取失败");
        }
    }

    public AttachmentContent content(Long inboundId, Long attachmentId) {
        requireInbound(inboundId);
        List<StoredAttachment> rows = jdbcTemplate.query("""
                select file_name,content_type,storage_path,file_content from inbound_attachment
                where id=? and inbound_id=?
                """, (rs, rowNum) -> new StoredAttachment(
                rs.getString("file_name"), rs.getString("content_type"), rs.getString("storage_path"),
                rs.getBytes("file_content")
        ), attachmentId, inboundId);
        if (rows.isEmpty()) throw new BusinessException("发货单图片不存在");
        StoredAttachment stored = rows.get(0);
        byte[] bytes = StringUtils.hasText(stored.storagePath())
                ? fileStorageService.read(stored.storagePath()) : stored.legacyContent();
        if (bytes == null) throw new BusinessException("发货单图片文件不存在");
        return new AttachmentContent(stored.fileName(), stored.contentType(), bytes);
    }

    @Transactional
    public void delete(Long inboundId, Long attachmentId) {
        requireInbound(inboundId);
        String status = jdbcTemplate.queryForObject(
                "select status from inbound_order where id=?", String.class, inboundId);
        if (!Set.of("DRAFT", "REJECTED").contains(status)) {
            throw new BusinessException("只有草稿或已拒绝入库单可以删除发货单图片；待审核及已入库影像只能补充留痕");
        }
        List<String> storagePaths = jdbcTemplate.query("""
                select storage_path from inbound_attachment where id=? and inbound_id=?
                """, (rs, rowNum) -> rs.getString("storage_path"), attachmentId, inboundId);
        int changed = jdbcTemplate.update(
                "delete from inbound_attachment where id=? and inbound_id=?", attachmentId, inboundId);
        if (changed == 0) throw new BusinessException("发货单图片不存在");
        if (!storagePaths.isEmpty()) fileStorageService.deleteAfterTransactionCommit(storagePaths.get(0));
    }

    private void requireInbound(Long inboundId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from inbound_order where id=?", Integer.class, inboundId);
        if (count == null || count == 0) throw new BusinessException("入库单不存在");
    }

    private String safeFileName(String value) {
        String name = StringUtils.hasText(value) ? value.trim() : "delivery-note.jpg";
        name = name.replace('\\', '_').replace('/', '_');
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256不可用", ex);
        }
    }

    private record StoredAttachment(String fileName, String contentType, String storagePath, byte[] legacyContent) {
    }

    public record AttachmentContent(String fileName, String contentType, byte[] bytes) {
    }
}
