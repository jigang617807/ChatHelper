package com.example.demo.search;

import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

import java.util.List;

public interface ChunkSearchRepository extends ElasticsearchRepository<ChunkSearchDoc, String> {
    List<ChunkSearchDoc> findByDocumentId(Long documentId);

    void deleteByDocumentId(Long documentId);
}
