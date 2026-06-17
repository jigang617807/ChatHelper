package com.example.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private static final long TOKEN_REFRESH_SAFETY_MS = 60_000L;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${ocr.enabled:false}")
    private boolean enabled;

    @Value("${ocr.provider:baidu}")
    private String provider;

    @Value("${ocr.baidu.api-key:}")
    private String baiduApiKey;

    @Value("${ocr.baidu.secret-key:}")
    private String baiduSecretKey;

    @Value("${ocr.baidu.token-url:https://aip.baidubce.com/oauth/2.0/token}")
    private String baiduTokenUrl;

    @Value("${ocr.baidu.endpoint:https://aip.baidubce.com/rest/2.0/ocr/v1/general_basic}")
    private String baiduEndpoint;

    @Value("${ocr.baidu.timeout-seconds:20}")
    private long timeoutSeconds;

    private volatile String cachedAccessToken;
    private volatile long tokenExpireAtMs;

    public OcrService(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    public boolean isEnabled() {
        return enabled && "baidu".equalsIgnoreCase(provider) && hasText(baiduApiKey) && hasText(baiduSecretKey);
    }

    public OcrResult recognize(Path imagePath) {
        if (!isEnabled()) {
            return OcrResult.failure(normalizedProvider(), "OCR is disabled or Baidu OCR credentials are missing.");
        }
        try {
            String accessToken = getAccessToken();
            String base64Image = Base64.getEncoder().encodeToString(Files.readAllBytes(imagePath));
            String response = webClient.post()
                    .uri(baiduEndpoint + "?access_token={accessToken}", accessToken)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(BodyInserters.fromFormData("image", base64Image)
                            .with("detect_direction", "true")
                            .with("probability", "true"))
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .block();

            return parseOcrResponse(response);
        } catch (Exception ex) {
            log.warn("OCR recognize failed for {}: {}", imagePath, ex.getMessage());
            return OcrResult.failure(normalizedProvider(), ex.getMessage());
        }
    }

    private synchronized String getAccessToken() throws Exception {
        long now = System.currentTimeMillis();
        if (hasText(cachedAccessToken) && tokenExpireAtMs - TOKEN_REFRESH_SAFETY_MS > now) {
            return cachedAccessToken;
        }

        String response = webClient.post()
                .uri(baiduTokenUrl + "?grant_type=client_credentials&client_id={clientId}&client_secret={secretKey}",
                        baiduApiKey, baiduSecretKey)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        JsonNode root = objectMapper.readTree(response);
        String token = root.path("access_token").asText("");
        if (!hasText(token)) {
            throw new IllegalStateException("Baidu OCR token response missing access_token: " + response);
        }
        long expiresInSeconds = root.path("expires_in").asLong(2_592_000L);
        cachedAccessToken = token;
        tokenExpireAtMs = now + expiresInSeconds * 1000L;
        return token;
    }

    private OcrResult parseOcrResponse(String response) throws Exception {
        JsonNode root = objectMapper.readTree(response);
        if (root.has("error_code")) {
            return OcrResult.failure(
                    normalizedProvider(),
                    root.path("error_msg").asText("Baidu OCR returned error_code=" + root.path("error_code").asText())
            );
        }

        StringBuilder text = new StringBuilder();
        double confidenceSum = 0.0;
        int confidenceCount = 0;
        JsonNode words = root.path("words_result");
        if (words.isArray()) {
            for (JsonNode item : words) {
                String line = item.path("words").asText("");
                if (hasText(line)) {
                    text.append(line.strip()).append('\n');
                }
                JsonNode probability = item.path("probability");
                if (probability.has("average")) {
                    confidenceSum += probability.path("average").asDouble();
                    confidenceCount++;
                }
            }
        }

        if (text.length() == 0) {
            return OcrResult.failure(normalizedProvider(), "Baidu OCR returned empty words_result.");
        }
        Double confidence = confidenceCount == 0 ? null : confidenceSum / confidenceCount;
        return OcrResult.success(text.toString(), confidence, normalizedProvider(), response);
    }

    private String normalizedProvider() {
        return hasText(provider) ? provider.strip().toLowerCase() : "baidu";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
