package com.example.agent.tool.react;

import com.example.demo.entity.Document;
import com.example.demo.repository.DocumentRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DocumentListReactTool implements ReactTool {

    private final DocumentRepository documentRepository;

    public DocumentListReactTool(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public String name() {
        return "document_list";
    }

    @Override
    public String description() {
        return "List the current user's uploaded documents, including document id, title and status.";
    }

    @Override
    public String parameters() {
        return "{}";
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionContext context, Map<String, Object> arguments) {
        List<Document> documents = documentRepository.findByUserId(context.userId());
        if (documents.isEmpty()) {
            return ToolExecutionResult.success("The user has not uploaded any documents.");
        }
        String result = documents.stream()
                .map(document -> "id=" + document.getId()
                        + ", title=" + document.getTitle()
                        + ", status=" + document.getStatus())
                .collect(Collectors.joining("\n"));
        return ToolExecutionResult.success(result);
    }
}
