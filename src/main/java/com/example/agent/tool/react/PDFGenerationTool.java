package com.example.agent.tool.react;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PDFGenerationTool implements ReactTool {

    private final AgentWorkspaceService workspaceService;
    private final String fontPath;

    public PDFGenerationTool(AgentWorkspaceService workspaceService,
                             @Value("${agent.react.pdf.font-path:}") String fontPath) {
        this.workspaceService = workspaceService;
        this.fontPath = fontPath;
    }

    @Override
    public String name() {
        return "pdf_generation";
    }

    @Override
    public String description() {
        return "Generate a simple PDF artifact from plain text or Markdown-like content in the current agent workspace.";
    }

    @Override
    public String parameters() {
        return """
                {
                  "title": "string, optional",
                  "content": "string, required",
                  "fileName": "string, required, should end with .pdf"
                }
                """;
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        String title = ToolArguments.string(arguments, "title");
        String content = ToolArguments.string(arguments, "content");
        String fileName = ToolArguments.string(arguments, "fileName");
        if (content == null || content.isBlank()) {
            return ToolExecutionResult.failure("content is required.");
        }
        if (fileName == null || fileName.isBlank()) {
            fileName = "agent-report.pdf";
        }
        if (!fileName.toLowerCase().endsWith(".pdf")) {
            fileName = fileName + ".pdf";
        }

        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            Path target = workspaceService.resolveInside(context.workspaceRoot(), fileName);
            Files.createDirectories(target.getParent());
            writePages(document, font, title, content);
            document.save(target.toFile());
            return ToolExecutionResult.success("PDF generated: " + target, target.toString());
        } catch (Exception ex) {
            return ToolExecutionResult.failure("PDF generation failed: " + ex.getMessage());
        }
    }

    private PDFont loadFont(PDDocument document) {
        try {
            if (fontPath != null && !fontPath.isBlank()) {
                File file = Path.of(fontPath).toFile();
                if (file.exists() && file.isFile()) {
                    return PDType0Font.load(document, file);
                }
            }
        } catch (Exception ignored) {
            // Fall back to a built-in font. Non-Latin characters may be replaced below.
        }
        return PDType1Font.HELVETICA;
    }

    private void writePages(PDDocument document, PDFont font, String title, String content) throws Exception {
        float margin = 54;
        float fontSize = 11;
        float leading = 16;
        PDRectangle pageSize = PDRectangle.LETTER;
        float width = pageSize.getWidth() - margin * 2;
        List<String> lines = wrap(safeForFont(font, buildContent(title, content)), font, fontSize, width);

        PDPage page = new PDPage(pageSize);
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.newLineAtOffset(margin, pageSize.getHeight() - margin);

        float y = pageSize.getHeight() - margin;
        for (String line : lines) {
            if (y <= margin) {
                stream.endText();
                stream.close();
                page = new PDPage(pageSize);
                document.addPage(page);
                stream = new PDPageContentStream(document, page);
                stream.beginText();
                stream.setFont(font, fontSize);
                stream.newLineAtOffset(margin, pageSize.getHeight() - margin);
                y = pageSize.getHeight() - margin;
            }
            stream.showText(line);
            stream.newLineAtOffset(0, -leading);
            y -= leading;
        }
        stream.endText();
        stream.close();
    }

    private String buildContent(String title, String content) {
        if (title == null || title.isBlank()) {
            return content;
        }
        return title + "\n\n" + content;
    }

    private List<String> wrap(String text, PDFont font, float fontSize, float width) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.replace("\r", "").split("\n")) {
            if (paragraph.isBlank()) {
                lines.add("");
                continue;
            }
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (font.getStringWidth(candidate) / 1000 * fontSize > width && !line.isEmpty()) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private String safeForFont(PDFont font, String content) {
        if (!(font instanceof PDType1Font)) {
            return content;
        }
        return content.chars()
                .mapToObj(ch -> ch <= 0x7F ? String.valueOf((char) ch) : "?")
                .reduce("", String::concat);
    }
}
