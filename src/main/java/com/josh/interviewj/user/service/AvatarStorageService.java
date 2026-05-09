package com.josh.interviewj.user.service;

import com.josh.interviewj.common.exception.BusinessException;
import com.josh.interviewj.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

/**
 * Local avatar file storage isolated from resume uploads.
 */
@Service
public class AvatarStorageService {

    private final Path baseDir;
    private final String storagePath;

    public AvatarStorageService(@Value("${app.user.avatar-storage-path:uploads/avatars}") String storagePath) {
        this.storagePath = storagePath.replace('\\', '/');
        this.baseDir = Path.of(storagePath).toAbsolutePath().normalize();
    }

    public String store(MultipartFile file) {
        try {
            Files.createDirectories(baseDir);
            String fileName = UUID.randomUUID() + "." + resolveExtension(file);
            Path targetPath = getFilePath(fileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            return buildStoredPath(fileName);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.FILE_001, "File upload failed", ex);
        }
    }

    public Path getFilePath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new BusinessException(ErrorCode.FILE_001, "Invalid file path");
        }

        String relativePath = stripStoragePrefix(storedPath);
        Path targetPath = baseDir.resolve(relativePath).normalize();
        if (!targetPath.startsWith(baseDir)) {
            throw new BusinessException(ErrorCode.FILE_001, "Invalid file path");
        }
        return targetPath;
    }

    public void delete(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(getFilePath(storedPath));
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.FILE_001, "File upload failed", ex);
        }
    }

    private String buildStoredPath(String fileName) {
        if (Path.of(storagePath).isAbsolute()) {
            return fileName;
        }
        return storagePath + "/" + fileName;
    }

    private String stripStoragePrefix(String storedPath) {
        String normalized = storedPath.replace('\\', '/');
        if (normalized.startsWith(storagePath + "/")) {
            return normalized.substring(storagePath.length() + 1);
        }
        return normalized;
    }

    private String resolveExtension(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null) {
            int dotIndex = originalFilename.lastIndexOf('.');
            if (dotIndex >= 0 && dotIndex < originalFilename.length() - 1) {
                String ext = originalFilename.substring(dotIndex + 1).trim().toLowerCase(Locale.ROOT);
                if (ext.matches("[a-z0-9]{1,10}")) {
                    return ext;
                }
            }
        }

        String contentType = file.getContentType();
        if ("image/jpeg".equalsIgnoreCase(contentType)) {
            return "jpg";
        }
        if ("image/png".equalsIgnoreCase(contentType)) {
            return "png";
        }
        return "bin";
    }
}
