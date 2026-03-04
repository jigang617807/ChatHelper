package com.example.demo.repository;

import com.example.demo.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {
    List<DocumentChunk> findByDocumentId(Long docId);

    @Modifying
    @Query("DELETE FROM DocumentChunk c WHERE c.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);

    // 使用 pgvector 的 L2 距离 (<->) 或 余弦距离 (<=>) 运算符
    // 注意：ORDER BY embedding <=> :queryVector
    // 将 List<Double> 改为 String 以避免 Hibernate 将 List 展开为多个参数
    // 修改为只查询 text 字段，避免 vector 类型映射错误，并使用 Interface Projection 返回
    @Query(value = "SELECT text FROM document_chunk WHERE document_id = :docId ORDER BY embedding <=> cast(cast(:queryVector as text) as vector) LIMIT :limit", nativeQuery = true)
    List<DocumentChunkProjection> searchSimilar(@Param("docId") Long docId, @Param("queryVector") String queryVector, @Param("limit") int limit);
}

