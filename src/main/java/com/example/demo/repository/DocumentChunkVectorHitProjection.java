package com.example.demo.repository;

public interface DocumentChunkVectorHitProjection {
    Long getId();
    String getText();
    Integer getChunkIndex();
    Integer getPageNumber();
    String getSectionTitle();
    String getContentType();
    String getSourcePath();
    Double getDistance();
}
