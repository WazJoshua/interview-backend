package com.josh.interviewj.llm.template;

import com.josh.interviewj.config.LlmProperties;
import com.josh.interviewj.llm.core.LlmException;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TemplateRequestExecutor {

    private final ObjectMapper objectMapper;
    private final ResourceLoader resourceLoader;
    private final TemplateRequestRenderer renderer;
    private final Map<String, TemplateRequestDefinition> requestCache = new ConcurrentHashMap<>();

    @Autowired
    public TemplateRequestExecutor(ResourceLoader resourceLoader, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.resourceLoader = resourceLoader;
        this.renderer = new TemplateRequestRenderer(objectMapper);
    }

    public TemplateRequestExecutor(ObjectMapper objectMapper) {
        this(new DefaultResourceLoader(), objectMapper);
    }

    public TemplateHttpResponse execute(
            LlmProperties.ProviderProperties providerConfig,
            TemplateDescriptor templateDescriptor,
            TemplateVariables variables
    ) {
        TemplateRequestDefinition definition = requestCache.computeIfAbsent(
                templateDescriptor.requestResourcePath(),
                this::loadRequestDefinition
        );
        return execute(providerConfig, renderer.render(definition, variables));
    }

    public TemplateHttpResponse execute(
            LlmProperties.ProviderProperties providerConfig,
            RenderedTemplateRequest request
    ) {
        try {
            URI uri = buildUri(providerConfig.getBaseUrl(), request.path(), request.query());
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(providerConfig.getTimeoutMs()))
                    .method(
                            request.method(),
                            HttpRequest.BodyPublishers.ofString(request.body() == null ? "" : request.body(), StandardCharsets.UTF_8)
                    );

            Map<String, String> requestHeaders = new LinkedHashMap<>(request.headers());
            requestHeaders.putIfAbsent("Content-Type", MediaType.APPLICATION_JSON_VALUE);
            requestHeaders.forEach(requestBuilder::header);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(providerConfig.getTimeoutMs()))
                    .build();
            HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String contentType = response.headers().firstValue("Content-Type").orElse(null);
            return new TemplateHttpResponse(
                    response.statusCode(),
                    response.headers().map(),
                    contentType,
                    response.body()
            );
        } catch (LlmException exception) {
            throw exception;
        } catch (HttpTimeoutException | InterruptedIOException exception) {
            throw new LlmException("Template request timed out", "TIMEOUT", true, exception);
        } catch (java.io.IOException exception) {
            throw mapIoError(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LlmException("Template request timed out", "TIMEOUT", true, exception);
        } catch (Exception exception) {
            throw new LlmException("Template request failed", "IO", true, exception);
        }
    }

    private TemplateRequestDefinition loadRequestDefinition(String resourcePath) {
        try (var inputStream = resourceLoader.getResource(resourcePath).getInputStream()) {
            TemplateRequestFile file = objectMapper.readValue(inputStream, TemplateRequestFile.class);
            if (file.request() == null) {
                throw new LlmException("Template request is missing request block", "IO", false);
            }
            return new TemplateRequestDefinition(
                    file.request().method(),
                    file.request().path(),
                    file.request().headers() == null ? Map.of() : Map.copyOf(file.request().headers()),
                    file.request().query() == null ? Map.of() : Map.copyOf(file.request().query()),
                    file.request().body()
            );
        } catch (LlmException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new LlmException("Template request definition is invalid", "IO", false, exception);
        }
    }

    private URI buildUri(String baseUrl, String requestPath, Map<String, String> query) {
        try {
            URI baseUri = new URI(baseUrl);
            UriComponentsBuilder builder = UriComponentsBuilder.newInstance()
                    .scheme(baseUri.getScheme())
                    .host(baseUri.getHost());
            if (baseUri.getPort() >= 0) {
                builder.port(baseUri.getPort());
            }
            builder.path(combinePaths(baseUri.getPath(), requestPath));
            for (Map.Entry<String, String> entry : query.entrySet()) {
                builder.queryParam(entry.getKey(), entry.getValue());
            }
            return builder.build(true).toUri();
        } catch (URISyntaxException exception) {
            throw new LlmException("Template request URI is invalid", "IO", false, exception);
        }
    }

    private String combinePaths(String basePath, String requestPath) {
        String normalizedBase = normalizePath(basePath);
        String normalizedRequest = normalizePath(requestPath);
        if (normalizedBase.isEmpty()) {
            return normalizedRequest;
        }
        if (normalizedRequest.equals(normalizedBase) || normalizedRequest.startsWith(normalizedBase + "/")) {
            return normalizedRequest;
        }

        String[] baseSegments = normalizedBase.substring(1).split("/");
        String[] requestSegments = normalizedRequest.substring(1).split("/");
        int maxOverlap = Math.min(baseSegments.length, requestSegments.length);
        for (int overlap = maxOverlap; overlap > 0; overlap--) {
            boolean matches = true;
            for (int i = 0; i < overlap; i++) {
                if (!baseSegments[baseSegments.length - overlap + i].equals(requestSegments[i])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                StringBuilder builder = new StringBuilder(normalizedBase);
                for (int i = overlap; i < requestSegments.length; i++) {
                    builder.append("/").append(requestSegments[i]);
                }
                return builder.toString();
            }
        }
        return normalizedBase + normalizedRequest;
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private LlmException mapIoError(Exception exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof InterruptedIOException) {
                return new LlmException("Template request timed out", "TIMEOUT", true, exception);
            }
            cause = cause.getCause();
        }

        String message = exception.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("timeout") || lower.contains("timed out")) {
                return new LlmException("Template request timed out", "TIMEOUT", true, exception);
            }
        }
        return new LlmException("Template request I/O failed", "IO", true, exception);
    }

    private record TemplateRequestFile(TemplateRequestPayload request) {
    }

    private record TemplateRequestPayload(
            String method,
            String path,
            Map<String, String> headers,
            Map<String, String> query,
            tools.jackson.databind.JsonNode body
    ) {
    }
}
