package com.example.demo.repository;

public interface DocumentChunkVectorHitProjection {
    Long getId();
    String getText();
    Integer getChunkIndex();
    Double getDistance();
}
