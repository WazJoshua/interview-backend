package com.josh.interviewj.knowledgebase.service;

import com.josh.interviewj.common.exception.BusinessException;
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
 * Stores and resolves files that belong to knowledge-base documents.
 */
@Service
public class KnowledgeBaseStorageService {

    private final Path baseDir;

    /**
     * Creates the storage service rooted at the configured base directory.
     *
     * @param storagePath configured storage path
     */
    public KnowledgeBaseStorageService(@Value("${app.kb.storage-path:uploads/kb}") String storagePath) {
        this.baseDir = Path.of(storagePath).toAbsolutePath().normalize();
    }

    /**
     * Persists an uploaded knowledge-base file and returns its relative storage key.
     *
     * @param file uploaded file
     * @return relative storage key
     */
    public String store(MultipartFile file) {
        try {
            Files.createDirectories(baseDir);
            String relativeUrl = UUID.randomUUID() + "." + resolveExtension(file.getOriginalFilename());
            Path targetPath = getFilePath(relativeUrl);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            return relativeUrl;
        } catch (IOException ex) {
            throw new BusinessException("FILE_001", "File upload failed", ex);
        }
    }

    /**
     * Resolves a relative storage key to a safe absolute path under the KB storage directory.
     *
     * @param relativeUrl relative storage key
     * @return normalized absolute path
     */
    public Path getFilePath(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isBlank()) {
            throw new BusinessException("FILE_001", "Invalid file path");
        }

        Path targetPath = baseDir.resolve(relativeUrl).normalize();
        if (!targetPath.startsWith(baseDir)) {
            throw new BusinessException("FILE_001", "Invalid file path");
        }
        return targetPath;
    }

    /**
     * Deletes a stored KB file when it exists.
     *
     * @param relativeUrl relative storage key
     */
    public void delete(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(getFilePath(relativeUrl));
        } catch (IOException ex) {
            throw new BusinessException("FILE_001", "File upload failed", ex);
        }
    }

    /**
     * Extracts a conservative file extension for generated storage names.
     *
     * @param fileName original filename
     * @return safe extension or {@code bin}
     */
    private String resolveExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "bin";
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return "bin";
        }

        String ext = fileName.substring(dotIndex + 1).trim().toLowerCase(Locale.ROOT);
        if (!ext.matches("[a-z0-9]{1,10}")) {
            return "bin";
        }
        return ext;
    }
}
