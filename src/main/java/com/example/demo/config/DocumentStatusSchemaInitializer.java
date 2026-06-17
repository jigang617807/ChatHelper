package com.example.demo.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentStatusSchemaInitializer {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void ensureDocumentStatusConstraint() {
        try {
            jdbcTemplate.execute("""
                    DO $$
                    BEGIN
                        IF EXISTS (
                            SELECT 1
                            FROM pg_constraint c
                            JOIN pg_class t ON c.conrelid = t.oid
                            WHERE t.relname = 'document'
                              AND c.conname = 'document_status_check'
                        ) THEN
                            ALTER TABLE document DROP CONSTRAINT document_status_check;
                        END IF;

                        ALTER TABLE document
                        ADD CONSTRAINT document_status_check
                        CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED_RETRYABLE', 'FAILED'));
                    END $$;
                    """);
        } catch (Exception ex) {
            log.warn("Failed to ensure document status check constraint: {}", ex.getMessage());
        }
    }
}
