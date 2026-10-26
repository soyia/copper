package com.soyia.copperv2.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class LocalFileStorageService {
    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final Path rootPath;

    public LocalFileStorageService(@Value("${copper.file-storage.root-path}") String configuredRootPath) {
        if (!StringUtils.hasText(configuredRootPath)) {
            throw new IllegalStateException("copper.file-storage.root-path 不能为空");
        }
        rootPath = Path.of(configuredRootPath).toAbsolutePath().normalize();
        try {
            Files.createDirectories(rootPath);
            if (!Files.isDirectory(rootPath) || !Files.isWritable(rootPath)) {
                throw new IllegalStateException("附件存储目录不可写: " + rootPath);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("附件存储目录初始化失败: " + rootPath, ex);
        }
    }

    public String store(byte[] content, String directory, String extension) {
        return storeNamed(content, directory, UUID.randomUUID() + safeExtension(extension));
    }

    public String storeNamed(byte[] content, String directory, String fileName) {
        Path targetDirectory = resolveDirectory(directory);
        if (!StringUtils.hasText(fileName) || !fileName.matches("[a-zA-Z0-9._-]{1,255}")) {
            throw new IllegalArgumentException("非法存储文件名");
        }
        Path target = targetDirectory.resolve(fileName);
        Path temporary = null;
        try {
            Files.createDirectories(targetDirectory);
            temporary = Files.createTempFile(targetDirectory, ".upload-", ".tmp");
            Files.write(temporary, content);
            moveAtomically(temporary, target);
            String relativePath = toRelativePath(target);
            deleteOnTransactionRollback(relativePath);
            return relativePath;
        } catch (IOException ex) {
            deleteQuietly(temporary);
            throw new BusinessException("附件写入本地存储失败");
        }
    }

    public String storeGeneratedFile(Path source, String directory, String fileName) {
        if (source == null || !Files.isRegularFile(source)) throw new BusinessException("待归档文件不存在");
        Path targetDirectory = resolveDirectory(directory);
        if (!StringUtils.hasText(fileName) || !fileName.matches("[a-zA-Z0-9._-]{1,255}")) {
            throw new IllegalArgumentException("非法存储文件名");
        }
        Path target = targetDirectory.resolve(fileName);
        Path temporary = null;
        try {
            Files.createDirectories(targetDirectory);
            temporary = Files.createTempFile(targetDirectory, ".archive-", ".tmp");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(temporary, target);
            String relativePath = toRelativePath(target);
            deleteOnTransactionRollback(relativePath);
            return relativePath;
        } catch (IOException ex) {
            deleteQuietly(temporary);
            throw new BusinessException("归档文件写入本地存储失败");
        }
    }

    public String copy(String sourceRelativePath, String directory, String extension) {
        Path source = resolveFile(sourceRelativePath);
        if (!Files.isRegularFile(source)) throw new BusinessException("附件文件不存在");
        Path targetDirectory = resolveDirectory(directory);
        Path target = targetDirectory.resolve(UUID.randomUUID() + safeExtension(extension));
        Path temporary = null;
        try {
            Files.createDirectories(targetDirectory);
            temporary = Files.createTempFile(targetDirectory, ".copy-", ".tmp");
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            moveAtomically(temporary, target);
            String relativePath = toRelativePath(target);
            deleteOnTransactionRollback(relativePath);
            return relativePath;
        } catch (IOException ex) {
            deleteQuietly(temporary);
            deleteQuietly(target);
            throw new BusinessException("审核快照影像复制失败");
        }
    }

    public byte[] read(String relativePath) {
        Path file = resolveFile(relativePath);
        try {
            return Files.readAllBytes(file);
        } catch (IOException ex) {
            throw new BusinessException("附件文件读取失败");
        }
    }

    public boolean exists(String relativePath) {
        if (!StringUtils.hasText(relativePath)) return false;
        return Files.isRegularFile(resolveFile(relativePath));
    }

    public Resource resource(String relativePath) {
        Path file = resolveFile(relativePath);
        if (!Files.isRegularFile(file)) throw new BusinessException("附件文件不存在");
        return new FileSystemResource(file);
    }

    public void deleteNow(String relativePath) {
        if (!StringUtils.hasText(relativePath)) return;
        deleteQuietly(resolveFile(relativePath));
    }

    public void deleteAfterTransactionCommit(String relativePath) {
        if (!StringUtils.hasText(relativePath)) return;
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteNow(relativePath);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteNow(relativePath);
            }
        });
    }

    public String extensionForContentType(String contentType) {
        if (contentType == null) return "";
        return switch (contentType.toLowerCase()) {
            case MediaType.IMAGE_JPEG_VALUE -> ".jpg";
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case "image/webp" -> ".webp";
            case MediaType.APPLICATION_PDF_VALUE -> ".pdf";
            case MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE -> ".xml";
            case "application/ofd" -> ".ofd";
            default -> "";
        };
    }

    private void deleteOnTransactionRollback(String relativePath) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) deleteNow(relativePath);
            }
        });
    }

    private Path resolveDirectory(String relativeDirectory) {
        if (!StringUtils.hasText(relativeDirectory)) throw new IllegalArgumentException("存储子目录不能为空");
        Path directory = rootPath.resolve(relativeDirectory).normalize();
        if (!directory.startsWith(rootPath)) throw new IllegalArgumentException("非法存储目录");
        return directory;
    }

    private Path resolveFile(String relativePath) {
        if (!StringUtils.hasText(relativePath)) throw new BusinessException("附件存储路径为空");
        Path candidate = Path.of(relativePath);
        if (candidate.isAbsolute()) throw new BusinessException("附件存储路径非法");
        Path file = rootPath.resolve(candidate).normalize();
        if (!file.startsWith(rootPath)) throw new BusinessException("附件存储路径非法");
        return file;
    }

    private String toRelativePath(Path file) {
        return rootPath.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private String safeExtension(String extension) {
        if (!StringUtils.hasText(extension)) return "";
        String value = extension.trim().toLowerCase();
        return value.matches("\\.[a-z0-9]{1,10}") ? value : "";
    }

    private void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("附件文件清理失败: {}", path, ex);
        }
    }
}
