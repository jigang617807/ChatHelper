package com.example.demo.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Type;
import io.hypersistence.utils.hibernate.type.array.ListArrayType;

import java.util.List;

@Entity
@Data
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long documentId;
    private int chunkIndex;

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
