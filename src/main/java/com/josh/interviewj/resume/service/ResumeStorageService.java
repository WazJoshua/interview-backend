package com.josh.interviewj.resume.service;

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

@Service
public class ResumeStorageService {

    private final Path baseDir;

    /**
     * Create a storage service rooted at the given base directory.
     *
     * @param storagePath base storage directory (relative or absolute)
     */
    public ResumeStorageService(@Value("${app.resume.storage-path:uploads/resumes}") String storagePath) {
        this.baseDir = Path.of(storagePath).toAbsolutePath().normalize();
    }

    /**
     * Store the uploaded resume file under a generated UUID filename.
     *
     * @param file uploaded file
     * @return relative URL/path stored in DB
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
     * Resolve an absolute file path from a stored relative URL.
     *
     * <p>Includes path traversal protection by ensuring the resolved path stays within baseDir.</p>
     *
     * @param relativeUrl stored relative url
     * @return absolute path
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
     * Delete a stored file by relative URL.
     *
     * @param relativeUrl stored relative url
     */
    public void delete(String relativeUrl) {
        if (relativeUrl == null || relativeUrl.isBlank()) {
            return;
        }
        try {
            Path targetPath = getFilePath(relativeUrl);
            Files.deleteIfExists(targetPath);
        } catch (IOException ex) {
            throw new BusinessException("FILE_001", "File upload failed", ex);
        }
    }

    /**
     * Extract a safe file extension from the original file name.
     *
     * @param fileName original file name
     * @return safe extension (lowercase) or "bin"
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
