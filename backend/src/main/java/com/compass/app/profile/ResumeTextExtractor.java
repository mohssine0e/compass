package com.compass.app.profile;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * Pulls raw text out of an uploaded resume/CV locally — PDF via Apache PDFBox, DOCX via Apache
 * POI. No external API is involved in extraction itself (CLAUDE.md §3). The raw file is never
 * stored; only the text is handed on for the AI extraction pass, then dropped.
 */
@Component
class ResumeTextExtractor {

    /** Resumes are short; cap extracted text so a runaway file can't blow the AI token budget. */
    private static final int MAX_CHARS = 20_000;

    /** Extract text from a PDF or DOCX upload. Throws {@link IllegalArgumentException} otherwise. */
    String extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("No file uploaded.");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            String text;
            if (name.endsWith(".pdf")) {
                text = fromPdf(file);
            } else if (name.endsWith(".docx")) {
                text = fromDocx(file);
            } else {
                throw new IllegalArgumentException("Upload a PDF or DOCX resume.");
            }
            String cleaned = text == null ? "" : text.strip();
            if (cleaned.isEmpty()) {
                throw new IllegalArgumentException("Couldn't read any text from that file.");
            }
            return cleaned.length() > MAX_CHARS ? cleaned.substring(0, MAX_CHARS) : cleaned;
        } catch (IOException ex) {
            throw new IllegalArgumentException("Couldn't read that file — is it a valid PDF or DOCX?");
        }
    }

    private static String fromPdf(MultipartFile file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String fromDocx(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream();
             XWPFDocument doc = new XWPFDocument(in);
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }
}
