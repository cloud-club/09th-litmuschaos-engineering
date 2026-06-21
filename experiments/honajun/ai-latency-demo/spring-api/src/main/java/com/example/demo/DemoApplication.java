package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

@SpringBootApplication
@RestController
public class DemoApplication {
    private final String aiServerUrl = System.getenv().getOrDefault(
            "AI_SERVER_URL",
            "http://ai-server-svc:8000/infer"
    );
    private final int timeoutMillis = Integer.parseInt(
            System.getenv().getOrDefault("AI_TIMEOUT_MS", "1500")
    );
    private final int maxRetries = Integer.parseInt(
            System.getenv().getOrDefault("AI_RETRIES", "2")
    );
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMillis))
            .build();

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "service", "spring-api",
                "aiServerUrl", aiServerUrl,
                "timeoutMillis", timeoutMillis,
                "maxRetries", maxRetries
        );
    }

    @GetMapping("/ask")
    public Map<String, Object> ask(@RequestParam(defaultValue = "hello") String prompt) {
        Instant start = Instant.now();
        int attempt = 0;
        Exception lastError = null;

        while (attempt <= maxRetries) {
            attempt++;
            try {
                String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
                String targetUrl = aiServerUrl + "?prompt=" + encodedPrompt;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(targetUrl))
                        .timeout(Duration.ofMillis(timeoutMillis))
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );
                long elapsedMs = Duration.between(start, Instant.now()).toMillis();
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return Map.of(
                            "status", "success",
                            "attempt", attempt,
                            "elapsedMs", elapsedMs,
                            "aiResponse", response.body()
                    );
                }
                throw new RuntimeException("AI server returned HTTP " + response.statusCode());
            } catch (Exception e) {
                lastError = e;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        long elapsedMs = Duration.between(start, Instant.now()).toMillis();
        return Map.of(
                "status", "fallback",
                "message", "AI server is slow or unavailable. Returning fallback response.",
                "attempts", attempt,
                "elapsedMs", elapsedMs,
                "error", lastError != null ? lastError.getClass().getSimpleName() : "unknown"
        );
    }
}
