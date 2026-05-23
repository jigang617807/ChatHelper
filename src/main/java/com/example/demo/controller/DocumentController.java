package com.example.demo.controller;

import com.example.demo.entity.Document;
import com.example.demo.service.DocumentService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.UUID;

@Controller
@RequestMapping("/doc")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService docService;

    @Value("${upload.dir}")
    private String uploadDir;

    @Value("${upload.docs}")
    private String docsDir;

    @GetMapping("/upload")
    public String uploadPage() {
        return "redirect:/doc/list";
    }

    @PostMapping("/upload")
    public String uploadPdf(@RequestParam(value = "files", required = false) MultipartFile[] files,
                            @RequestParam(value = "file", required = false) MultipartFile legacyFile,
                            HttpSession session) throws Exception {
        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return "redirect:/auth/login";
        }

        boolean uploaded = false;
        if (files != null) {
            for (MultipartFile file : files) {
                if (saveUploadedPdf(uid, file)) {
                    uploaded = true;
                }
            }
        }
        if (!uploaded && legacyFile != null) {
            saveUploadedPdf(uid, legacyFile);
        }
        return "redirect:/doc/list";
    }

    private boolean saveUploadedPdf(Long uid, MultipartFile file) throws Exception {
        if (file == null || file.isEmpty()) {
            return false;
        }

        String originalName = safeFileName(file.getOriginalFilename());
        String filename = "u" + uid + "_" + UUID.randomUUID() + "_" + originalName;
        String absolutePath = new File(uploadDir).getAbsolutePath();
        String path = absolutePath + "/" + docsDir + filename;
        File dest = new File(path);
        dest.getParentFile().mkdirs();
        file.transferTo(dest);

        docService.saveDocument(uid, originalName, path);
        return true;
    }

    private String safeFileName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            return "document.pdf";
        }
        return originalName.replace("\\", "_").replace("/", "_");
    }

    @GetMapping("/list")
    public String listDocs(HttpSession session, Model model) {
        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return "redirect:/auth/login";
        }

        List<Document> docs = docService.listDocs(uid);
        model.addAttribute("docs", docs);
        return "doc_list";
    }

    @PostMapping("/delete")
    public ResponseEntity<String> deleteDocumentAndRelatedData(@RequestParam("docId") Long docId,
                                                               HttpSession session) {
        if (docId == null) {
            return ResponseEntity.badRequest().body("文档 ID 不能为空。");
        }

        Long uid = (Long) session.getAttribute("uid");
        if (uid == null) {
            return ResponseEntity.status(401).body("请先登录。");
        }

        try {
            docService.deleteDocumentWithRelatedData(docId, uid);
            return ResponseEntity.ok("文档删除成功。");
        } catch (RuntimeException e) {
            return ResponseEntity.internalServerError().body("删除失败：" + e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body("系统异常，删除失败。");
        }
    }
}
