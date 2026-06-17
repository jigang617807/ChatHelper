package com.example.demo.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ImageQuestionContextService {

    private final Map<String, ImageQuestionContext> contexts = new ConcurrentHashMap<>();
    private final OcrService ocrService;

    public ImageQuestionContextService(OcrService ocrService) {
        this.ocrService = ocrService;
    }

    @Value("${upload.dir:.}")
    private String uploadDir;

    @Value("${upload.question-images:uploads/question-images/}")
    private String questionImagesDir;

    public ImageQuestionContext save(Long userId, MultipartFile file) {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required.");
        }
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("image file is required.");
        }
        String contentType = file.getContentType() == null ? "" : file.getContentType();
        if (!contentType.startsWith("image/")) {
            throw new IllegalArgumentException("Only image files are supported.");
        }

        try {
            String id = UUID.randomUUID().toString();
            String originalName = safeFileName(file.getOriginalFilename());
            String extension = extension(originalName);
            String fileName = "u" + userId + "_" + id + extension;
            File root = new File(uploadDir).getAbsoluteFile();
            File target = new File(root, questionImagesDir + fileName);
            target.getParentFile().mkdirs();
            file.transferTo(target);

            Integer width = null;
            Integer height = null;
            BufferedImage image = ImageIO.read(target);
            if (image != null) {
                width = image.getWidth();
                height = image.getHeight();
            }

            String webPath = "/" + questionImagesDir.replace("\\", "/") + fileName;
            OcrResult ocrResult = ocrService.recognize(target.toPath());
            ImageQuestionContext context = new ImageQuestionContext(
                    id,
                    userId,
                    originalName,
                    contentType,
                    file.getSize(),
                    width,
                    height,
                    target.getAbsolutePath(),
                    webPath,
                    buildDescription(originalName, contentType, file.getSize(), width, height, webPath, ocrResult)
            );
            contexts.put(id, context);
            return context;
        } catch (Exception ex) {
            throw new RuntimeException("Image upload failed: " + ex.getMessage(), ex);
        }
    }

    public ImageQuestionContext find(Long userId, String id) {
        if (userId == null || id == null || id.isBlank()) {
            return null;
        }
        ImageQuestionContext context = contexts.get(id);
        if (context == null || !userId.equals(context.userId())) {
            return null;
        }
        return context;
    }

    public String buildPromptContext(ImageQuestionContext context) {
        if (context == null) {
            return "";
        }
        return """

                用户同时上传了一张图片，图片上下文如下：
                - 文件名：%s
                - 类型：%s
                - 尺寸：%s
                - 访问路径：%s
                - 图片识别上下文：%s
                """.formatted(
                context.originalName(),
                context.contentType(),
                context.width() == null || context.height() == null ? "unknown" : context.width() + "x" + context.height(),
                context.webPath(),
                context.description()
        );
    }

    private String buildDescription(String originalName,
                                    String contentType,
                                    long size,
                                    Integer width,
                                    Integer height,
                                    String webPath,
                                    OcrResult ocrResult) {
        String dimension = width == null || height == null ? "unknown" : width + "x" + height;
        String base = "图片已上传：文件名=" + originalName
                + ", 类型=" + contentType
                + ", 大小=" + size
                + ", 尺寸=" + dimension
                + ", 访问路径=" + webPath;
        if (ocrResult != null && ocrResult.success() && !ocrResult.text().isBlank()) {
            String confidence = ocrResult.confidence() == null ? "" : "，OCR平均置信度=" + ocrResult.confidence();
            return base + "。OCR识别成功" + confidence + "。OCR识别文本：\n" + abbreviate(ocrResult.text(), 4000);
        }
        String error = ocrResult == null ? "" : ocrResult.errorMessage();
        return base + "。OCR未启用或识别失败，当前只保留图片文件上下文。"
                + (error == null || error.isBlank() ? "" : "原因：" + error);
    }

    private String abbreviate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "\n...(OCR text truncated)";
    }

    private String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "image.png";
        }
        return originalName.replace("\\", "_").replace("/", "_");
    }

    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return ".png";
        }
        return name.substring(dot);
    }
}
