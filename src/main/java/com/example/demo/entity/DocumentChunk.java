package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;

import java.util.List;

@Entity
@Table(uniqueConstraints = {
        @UniqueConstraint(name = "uk_document_chunk_doc_index", columnNames = {"document_id", "chunk_index"})
})
@Data
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "document_id")
    private Long documentId;

    @Column(name = "chunk_index")
    private int chunkIndex;

    private Integer pageNumber;

    private String sectionTitle;

    private String contentType = ChunkContentType.TEXT.name();

    private String sourcePath;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Type(ListArrayType.class)
    @Column(columnDefinition = "vector")
    private List<Double> embedding;

    public List<Double> getEmbeddingVector() {
        return embedding;
    }

    public void setEmbeddingVector(List<Double> vector) {
        this.embedding = vector;
    }
}
