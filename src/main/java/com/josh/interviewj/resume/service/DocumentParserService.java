package com.josh.interviewj.resume.service;

import com.josh.interviewj.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.sax.BodyContentHandler;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

@Service
@Slf4j
public class DocumentParserService {

    private static final int SCAN_DETECT_MIN_TEXT_LENGTH = 80;

    /**
     * Extract plain text content from a resume file.
     *
     * <p>Primary engine is Apache Tika. If it fails, a best-effort fallback is used based on
     * file type (PDFBox/POI or direct text read). This method does not persist anything.</p>
     *
     * @param filePath absolute file path
     * @param fileType MIME type (may be null)
     * @return extracted and normalized text
     */
    public String extractText(Path filePath, String fileType) {
        if (filePath == null) {
            throw new BusinessException("RESUME_003", "Resume parse failed: missing file path");
        }

        try {
            // 1) Prefer Tika for auto detection and parsing.
            String text = extractWithTika(filePath);
            return postProcessText(text, filePath, fileType, "tika");
        } catch (Exception e) {
            log.warn("Tika parse failed, trying fallback: fileName={}, fileType={}, error={}",
                    filePath.getFileName(), fileType, e.getMessage());
            try {
                // 2) Fallback engines for common types.
                String text = extractWithFallback(filePath, fileType);
                return postProcessText(text, filePath, fileType, "fallback");
            } catch (Exception fallbackEx) {
                throw new BusinessException("RESUME_003", "Resume parse failed", fallbackEx);
            }
        }
    }

    /**
     * Extract text using Apache Tika's auto-detect parser.
     *
     * @param filePath file path
     * @return extracted text
     */
    private String extractWithTika(Path filePath) throws IOException, TikaException, SAXException {
        AutoDetectParser parser = new AutoDetectParser();
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        ContentHandler handler = new BodyContentHandler(-1);

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            parser.parse(inputStream, handler, metadata, context);
            return handler.toString();
        }
    }

    /**
     * Extract text with fallback engines based on file type and extension.
     *
     * @param filePath file path
     * @param fileType MIME type
     * @return extracted text
     */
    private String extractWithFallback(Path filePath, String fileType) throws IOException {
        String normalizedType = fileType == null ? "" : fileType.trim().toLowerCase(Locale.ROOT);
        String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);

        if (normalizedType.contains("pdf") || fileName.endsWith(".pdf")) {
            return extractPdfText(filePath);
        }
        if (normalizedType.contains("officedocument") || fileName.endsWith(".docx")) {
            return extractDocxText(filePath);
        }
        if (normalizedType.contains("msword") || fileName.endsWith(".doc")) {
            return extractDocText(filePath);
        }

        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /**
     * Extract text from a PDF file using PDFBox.
     *
     * @param filePath pdf path
     * @return extracted text
     */
    private String extractPdfText(Path filePath) throws IOException {
        try (PDDocument document = PDDocument.load(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    /**
     * Extract text from a DOCX file using POI.
     *
     * @param filePath docx path
     * @return extracted text
     */
    private String extractDocxText(Path filePath) throws IOException {
        try (InputStream inputStream = Files.newInputStream(filePath);
             XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * Extract text from a DOC file using POI.
     *
     * @param filePath doc path
     * @return extracted text
     */
    private String extractDocText(Path filePath) throws IOException {
        try (InputStream inputStream = Files.newInputStream(filePath);
             HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    /**
     * Normalize extracted text and apply lightweight heuristics.
     *
     * @param text extracted raw text
     * @param filePath file path
     * @param fileType MIME type
     * @param engine engine name
     * @return normalized text
     */
    private String postProcessText(String text, Path filePath, String fileType, String engine) {
        String normalized = text == null ? "" : text.replace("\u0000", "").trim();

        // Heuristic: very short extracted text might indicate scanned PDF with no embedded text.
        if (normalized.length() < SCAN_DETECT_MIN_TEXT_LENGTH) {
            log.warn("Extracted text too short, maybe scanned document: fileName={}, fileType={}, engine={}, length={}",
                    filePath.getFileName(), fileType, engine, normalized.length());
        }

        return normalized;
    }
}
