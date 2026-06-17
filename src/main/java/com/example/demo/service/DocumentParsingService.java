package com.example.demo.service;

import com.example.demo.entity.ChunkContentType;
import com.example.demo.utils.TextSplitter;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class DocumentParsingService {

    private static final Logger log = LoggerFactory.getLogger(DocumentParsingService.class);
    private static final Pattern HEADING_PATTERN = Pattern.compile(
            "^(第[一二三四五六七八九十0-9]+[章节部分]|[0-9]+(\\.[0-9]+){0,3}\\s+|#{1,6}\\s+).{2,80}$"
    );
    private static final Pattern TABLE_SEPARATOR_PATTERN = Pattern.compile(".*(\\|.*\\||\\t|\\s{2,}).*");
    private static final int MAX_IMAGE_RESOURCE_DEPTH = 4;

    @Value("${document.multimodal.render-ocr-pages:true}")
    private boolean renderOcrPages;

    @Value("${document.parsing.strategy:structured}")
    private String parsingStrategy;

    @Value("${document.multimodal.page-image-dpi:120}")
    private float pageImageDpi;

    @Value("${document.multimodal.extract-inline-images:true}")
    private boolean extractInlineImages;

    @Value("${document.multimodal.min-image-width:80}")
    private int minImageWidth;

    @Value("${document.multimodal.min-image-height:80}")
    private int minImageHeight;

    @Value("${document.multimodal.max-images-per-page:20}")
    private int maxImagesPerPage;

    private final OcrService ocrService;

    public DocumentParsingService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    public ParsedDocument parsePdf(File file, Path mediaDir, String mediaWebPrefix) {
        try (PDDocument pdf = PDDocument.load(file)) {
            if (isSimpleParsing()) {
                return parseSimplePdf(pdf);
            }

            Files.createDirectories(mediaDir);
            PDFTextStripper stripper = new PDFTextStripper();
            PDFRenderer renderer = new PDFRenderer(pdf);
            List<ParsedChunk> chunks = new ArrayList<>();
            StringBuilder fullText = new StringBuilder();
            String currentSection = "";

            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = safeText(stripper.getText(pdf));
                boolean hasPageText = !pageText.isBlank();
                if (hasPageText) {
                    fullText.append(pageText).append("\n\n");
                    PageParseResult result = parsePageText(pageText, page, currentSection);
                    chunks.addAll(result.chunks());
                    currentSection = result.lastSection();
                }

                boolean hasInlineImages = extractInlineImages && extractAndOcrInlineImages(
                        pdf.getPage(page - 1),
                        page,
                        mediaDir,
                        mediaWebPrefix,
                        currentSection,
                        chunks,
                        fullText
                );

                if (!hasPageText && !hasInlineImages && renderOcrPages) {
                    Path imagePath = renderPageImage(renderer, page, mediaDir);
                    String sourcePath = mediaWebPrefix + imagePath.getFileName();
                    OcrResult ocrResult = ocrService.recognize(imagePath);
                    if (ocrResult.success() && !ocrResult.text().isBlank()) {
                        fullText.append(ocrResult.text()).append("\n\n");
                        addOcrChunks(chunks, page, currentSection, ocrResult.text(), sourcePath, ocrResult, ",\"source\":\"rendered-page\"");
                    } else {
                        chunks.add(new ParsedChunk(
                                ChunkContentType.OCR,
                                page,
                                currentSection,
                                "第 " + page + " 页未提取到文本，可能是扫描页或图片页。已保留页面图片；OCR 未启用或识别失败。",
                                sourcePath,
                                "{\"status\":\"OCR_PENDING\",\"provider\":\"" + escapeJson(ocrResult.provider()) + "\",\"error\":\"" + escapeJson(ocrResult.errorMessage()) + "\"}"
                        ));
                    }
                }
            }

            if (chunks.isEmpty()) {
                throw new NonRetryableDocumentProcessingException("PDF text is empty and no OCR candidate page was generated.");
            }
            return new ParsedDocument(fullText.toString(), chunks);
        } catch (DocumentProcessingException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new NonRetryableDocumentProcessingException("PDF parse failed: " + ex.getMessage(), ex);
        }
    }

    private boolean isSimpleParsing() {
        return "simple".equalsIgnoreCase(parsingStrategy);
    }

    private ParsedDocument parseSimplePdf(PDDocument pdf) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        String text = safeText(stripper.getText(pdf));
        if (text.isBlank()) {
            throw new NonRetryableDocumentProcessingException("PDF text is empty. Simple parsing cannot extract text.");
        }

        List<ParsedChunk> chunks = new ArrayList<>();
        for (String part : TextSplitter.splitText(text, 800, 200)) {
            chunks.add(new ParsedChunk(ChunkContentType.TEXT, null, null, part, null, "{\"parser\":\"simple\"}"));
        }
        return new ParsedDocument(text, chunks);
    }

    private PageParseResult parsePageText(String pageText, int pageNumber, String initialSection) {
        List<ParsedChunk> chunks = new ArrayList<>();
        List<String> textBuffer = new ArrayList<>();
        List<String> tableBuffer = new ArrayList<>();
        String currentSection = initialSection == null ? "" : initialSection;

        for (String rawLine : pageText.replace("\r", "").split("\n")) {
            String line = rawLine.strip();
            if (line.isBlank()) {
                flushTable(chunks, tableBuffer, pageNumber, currentSection);
                flushText(chunks, textBuffer, pageNumber, currentSection);
                continue;
            }

            if (isHeading(line)) {
                flushTable(chunks, tableBuffer, pageNumber, currentSection);
                flushText(chunks, textBuffer, pageNumber, currentSection);
                currentSection = line;
                textBuffer.add(line);
                continue;
            }

            if (isTableLine(line)) {
                flushText(chunks, textBuffer, pageNumber, currentSection);
                tableBuffer.add(line);
            } else {
                flushTable(chunks, tableBuffer, pageNumber, currentSection);
                textBuffer.add(line);
            }
        }
        flushTable(chunks, tableBuffer, pageNumber, currentSection);
        flushText(chunks, textBuffer, pageNumber, currentSection);
        return new PageParseResult(chunks, currentSection);
    }

    private void flushText(List<ParsedChunk> chunks, List<String> textBuffer, int pageNumber, String sectionTitle) {
        if (textBuffer.isEmpty()) {
            return;
        }
        String text = String.join("\n", textBuffer).strip();
        textBuffer.clear();
        if (text.isBlank()) {
            return;
        }
        for (String part : TextSplitter.splitText(text, 800, 160)) {
            chunks.add(new ParsedChunk(ChunkContentType.TEXT, pageNumber, sectionTitle, part, null, null));
        }
    }

    private void flushTable(List<ParsedChunk> chunks, List<String> tableBuffer, int pageNumber, String sectionTitle) {
        if (tableBuffer.isEmpty()) {
            return;
        }
        String tableText = normalizeTable(tableBuffer);
        tableBuffer.clear();
        if (!tableText.isBlank()) {
            chunks.add(new ParsedChunk(
                    ChunkContentType.TABLE,
                    pageNumber,
                    sectionTitle,
                    tableText,
                    null,
                    "{\"parser\":\"heuristic-line-table\"}"
            ));
        }
    }

    private void addOcrChunks(List<ParsedChunk> chunks,
                              int pageNumber,
                              String sectionTitle,
                              String text,
                              String sourcePath,
                              OcrResult ocrResult) {
        addOcrChunks(chunks, pageNumber, sectionTitle, text, sourcePath, ocrResult, "");
    }

    private void addOcrChunks(List<ParsedChunk> chunks,
                              int pageNumber,
                              String sectionTitle,
                              String text,
                              String sourcePath,
                              OcrResult ocrResult,
                              String extraMetadataFields) {
        String confidence = ocrResult.confidence() == null ? "" : ",\"confidence\":" + ocrResult.confidence();
        String metadata = "{\"status\":\"OCR_DONE\",\"provider\":\"" + escapeJson(ocrResult.provider()) + "\""
                + confidence
                + (extraMetadataFields == null ? "" : extraMetadataFields)
                + "}";
        for (String part : TextSplitter.splitText(text, 800, 160)) {
            chunks.add(new ParsedChunk(ChunkContentType.OCR, pageNumber, sectionTitle, part, sourcePath, metadata));
        }
    }

    private boolean extractAndOcrInlineImages(PDPage page,
                                              int pageNumber,
                                              Path mediaDir,
                                              String mediaWebPrefix,
                                              String sectionTitle,
                                              List<ParsedChunk> chunks,
                                              StringBuilder fullText) {
        if (page == null || page.getResources() == null) {
            return false;
        }
        try {
            List<PdfImageCandidate> images = new ArrayList<>();
            collectImages(page.getResources(), images, "", 0);
            if (images.isEmpty()) {
                return false;
            }

            int imageIndex = 0;
            for (PdfImageCandidate candidate : images) {
                if (imageIndex >= Math.max(1, maxImagesPerPage)) {
                    break;
                }
                imageIndex++;
                Path imagePath = saveInlineImage(candidate.image(), pageNumber, imageIndex, mediaDir);
                String sourcePath = mediaWebPrefix + imagePath.getFileName();
                OcrResult ocrResult = ocrService.recognize(imagePath);
                String extraMetadata = ",\"source\":\"inline-image\",\"imageIndex\":" + imageIndex
                        + ",\"width\":" + candidate.width()
                        + ",\"height\":" + candidate.height()
                        + ",\"resource\":\"" + escapeJson(candidate.resourceName()) + "\"";

                if (ocrResult.success() && !ocrResult.text().isBlank()) {
                    fullText.append(ocrResult.text()).append("\n\n");
                    addOcrChunks(chunks, pageNumber, sectionTitle, ocrResult.text(), sourcePath, ocrResult, extraMetadata);
                } else {
                    chunks.add(new ParsedChunk(
                            ChunkContentType.IMAGE,
                            pageNumber,
                            sectionTitle,
                            "第 " + pageNumber + " 页图片 " + imageIndex + " 已抽取。OCR 未启用或识别失败。",
                            sourcePath,
                            "{\"status\":\"IMAGE_EXTRACTED\",\"ocrStatus\":\"OCR_PENDING\",\"provider\":\""
                                    + escapeJson(ocrResult.provider())
                                    + "\",\"error\":\"" + escapeJson(ocrResult.errorMessage()) + "\""
                                    + extraMetadata
                                    + "}"
                    ));
                }
            }
            return imageIndex > 0;
        } catch (Exception ex) {
            log.warn("Extract inline PDF images failed. page={}, reason={}", pageNumber, ex.getMessage());
            return false;
        }
    }

    private void collectImages(PDResources resources,
                               List<PdfImageCandidate> images,
                               String resourcePrefix,
                               int depth) throws Exception {
        if (resources == null || depth > MAX_IMAGE_RESOURCE_DEPTH || images.size() >= Math.max(1, maxImagesPerPage)) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            if (images.size() >= Math.max(1, maxImagesPerPage)) {
                return;
            }
            PDXObject xObject = resources.getXObject(name);
            String resourceName = resourcePrefix + name.getName();
            if (xObject instanceof PDImageXObject imageObject) {
                BufferedImage image = imageObject.getImage();
                if (shouldKeepImage(image)) {
                    images.add(new PdfImageCandidate(resourceName, image, image.getWidth(), image.getHeight()));
                }
            } else if (xObject instanceof PDFormXObject formObject) {
                collectImages(formObject.getResources(), images, resourceName + "/", depth + 1);
            }
        }
    }

    private boolean shouldKeepImage(BufferedImage image) {
        if (image == null) {
            return false;
        }
        return image.getWidth() >= Math.max(1, minImageWidth)
                && image.getHeight() >= Math.max(1, minImageHeight);
    }

    private Path saveInlineImage(BufferedImage image, int pageNumber, int imageIndex, Path mediaDir) throws Exception {
        Path target = mediaDir.resolve("page-" + pageNumber + "-image-" + imageIndex + ".png");
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    private boolean isHeading(String line) {
        return HEADING_PATTERN.matcher(line).matches();
    }

    private boolean isTableLine(String line) {
        if (line.length() < 8) {
            return false;
        }
        if (!TABLE_SEPARATOR_PATTERN.matcher(line).matches()) {
            return false;
        }
        String[] columns = line.split("\\||\\t|\\s{2,}");
        int nonBlank = 0;
        for (String column : columns) {
            if (!column.isBlank()) {
                nonBlank++;
            }
        }
        return nonBlank >= 3;
    }

    private String normalizeTable(List<String> rows) {
        StringBuilder builder = new StringBuilder("表格内容：\n");
        for (String row : rows) {
            String normalized = row.strip().replaceAll("\\s{2,}", " | ");
            builder.append("| ").append(normalized).append(" |\n");
        }
        return builder.toString();
    }

    private Path renderPageImage(PDFRenderer renderer, int pageNumber, Path mediaDir) throws Exception {
        BufferedImage image = renderer.renderImageWithDPI(pageNumber - 1, pageImageDpi, ImageType.RGB);
        Path target = mediaDir.resolve("page-" + pageNumber + ".png");
        ImageIO.write(image, "png", target.toFile());
        return target;
    }

    private String safeText(String text) {
        return text == null ? "" : text.strip();
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record PageParseResult(List<ParsedChunk> chunks, String lastSection) {
    }

    private record PdfImageCandidate(String resourceName, BufferedImage image, int width, int height) {
    }
}
