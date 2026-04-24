package com.example.demo.search;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.DateFormat;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

@Data
@Document(indexName = "document_chunk")
public class ChunkSearchDoc {

    @Id
    private String id;

    @Field(type = FieldType.Long)
    private Long chunkId;

    @Field(type = FieldType.Long)
    private Long documentId;

    @Field(type = FieldType.Integer)
    private Integer chunkIndex;

    @Field(type = FieldType.Text)
    private String text;

    @Field(type = FieldType.Date, format = DateFormat.date_time)
    private Instant updatedAt;
}
