package com.aifriend.ai;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Минимальный HTTP-клиент для OpenAI-, Anthropic- и Gemini-совместимых API. Вызывается из фонового потока. */
public final class AiClient {
    /** role: "user" или "assistant". */
    public record Message(String role, String content) {
    }

    public static final class AiException extends RuntimeException {
        public AiException(String message) {
            super(message);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private AiClient() {
    }

    public static CompletableFuture<String> chat(AiSettings.Resolved api, String system, List<Message> messages,
            double temperature, int maxTokens) {
        return send(api, system, messages, temperature, maxTokens, true);
    }

    private static CompletableFuture<String> send(AiSettings.Resolved api, String system, List<Message> messages,
            double temperature, int maxTokens, boolean withTemperature) {
        HttpRequest request;
        try {
            request = buildRequest(api, system, messages, temperature, maxTokens, withTemperature);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new AiException("Bad request/URL: " + e.getMessage()));
        }
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).thenCompose(resp -> {
            int code = resp.statusCode();
            String body = resp.body() == null ? "" : resp.body();
            if (code == 400 && withTemperature && body.toLowerCase(Locale.ROOT).contains("temperature")) {
                // Некоторые модели не принимают temperature - повторяем без него.
                return send(api, system, messages, temperature, maxTokens, false);
            }
            if (code < 200 || code >= 300) {
                return CompletableFuture.failedFuture(new AiException("HTTP " + code + ": " + shorten(extractError(body), 220)));
            }
            try {
                return CompletableFuture.completedFuture(parseResponse(api.format(), body));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(new AiException("Unexpected response: " + shorten(body, 160)));
            }
        });
    }

    // ---------------------------------------------------------------- запросы

    private static HttpRequest buildRequest(AiSettings.Resolved api, String system, List<Message> messages,
            double temperature, int maxTokens, boolean withTemperature) {
        String base = trimSlash(api.baseUrl());
        JsonObject body = new JsonObject();
        String url;
        HttpRequest.Builder rb;

        switch (api.format()) {
            case ANTHROPIC -> {
                url = base.endsWith("/v1/messages") ? base : base.endsWith("/v1") ? base + "/messages" : base + "/v1/messages";
                body.addProperty("model", api.model());
                body.addProperty("max_tokens", maxTokens);
                body.addProperty("system", system);
                if (withTemperature) {
                    body.addProperty("temperature", temperature);
                }
                JsonArray arr = new JsonArray();
                for (Message m : messages) {
                    arr.add(message(m.role(), "content", m.content()));
                }
                body.add("messages", arr);
                rb = HttpRequest.newBuilder(URI.create(url))
                        .header("x-api-key", api.apiKey())
                        .header("anthropic-version", "2023-06-01");
            }
            case GEMINI -> {
                String model = api.model().startsWith("models/") ? api.model().substring(7) : api.model();
                url = base.contains(":generateContent") ? base : base + "/v1beta/models/" + model + ":generateContent";
                JsonObject sys = new JsonObject();
                sys.add("parts", textParts(system));
                body.add("systemInstruction", sys);
                JsonArray contents = new JsonArray();
                for (Message m : messages) {
                    JsonObject c = new JsonObject();
                    c.addProperty("role", m.role().equals("assistant") ? "model" : "user");
                    c.add("parts", textParts(m.content()));
                    contents.add(c);
                }
                body.add("contents", contents);
                JsonObject gen = new JsonObject();
                gen.addProperty("maxOutputTokens", Math.max(maxTokens, 600));
                if (withTemperature) {
                    gen.addProperty("temperature", temperature);
                }
                body.add("generationConfig", gen);
                rb = HttpRequest.newBuilder(URI.create(url)).header("x-goog-api-key", api.apiKey());
            }
            default -> { // OPENAI
                url = base.endsWith("/chat/completions") ? base : base + "/chat/completions";
                body.addProperty("model", api.model());
                JsonArray arr = new JsonArray();
                arr.add(message("system", "content", system));
                for (Message m : messages) {
                    arr.add(message(m.role(), "content", m.content()));
                }
                body.add("messages", arr);
                boolean official = base.contains("api.openai.com");
                body.addProperty(official ? "max_completion_tokens" : "max_tokens", maxTokens);
                if (withTemperature && !official) {
                    body.addProperty("temperature", temperature);
                }
                rb = HttpRequest.newBuilder(URI.create(url));
                if (!api.apiKey().isBlank()) {
                    rb.header("Authorization", "Bearer " + api.apiKey());
                }
            }
        }
        return rb.timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static JsonObject message(String role, String key, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("role", role);
        o.addProperty(key, text);
        return o;
    }

    private static JsonArray textParts(String text) {
        JsonArray parts = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("text", text);
        parts.add(p);
        return parts;
    }

    // ---------------------------------------------------------------- ответы

    private static String parseResponse(AiSettings.ApiFormat format, String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        StringBuilder out = new StringBuilder();
        switch (format) {
            case ANTHROPIC -> {
                for (JsonElement e : root.getAsJsonArray("content")) {
                    JsonObject block = e.getAsJsonObject();
                    if (block.has("type") && "text".equals(block.get("type").getAsString()) && block.has("text")) {
                        out.append(block.get("text").getAsString());
                    }
                }
            }
            case GEMINI -> {
                JsonObject content = root.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content");
                if (content != null && content.has("parts")) {
                    for (JsonElement e : content.getAsJsonArray("parts")) {
                        JsonObject part = e.getAsJsonObject();
                        boolean thought = part.has("thought") && part.get("thought").getAsBoolean();
                        if (!thought && part.has("text")) {
                            out.append(part.get("text").getAsString());
                        }
                    }
                }
            }
            default -> {
                JsonObject msg = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
                JsonElement c = msg.get("content");
                if (c != null && c.isJsonPrimitive()) {
                    out.append(c.getAsString());
                }
            }
        }
        return out.toString().trim();
    }

    private static String extractError(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject() && root.getAsJsonObject().has("error")) {
                JsonElement err = root.getAsJsonObject().get("error");
                if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                    return err.getAsJsonObject().get("message").getAsString();
                }
                return err.toString();
            }
        } catch (Exception ignored) {
            // не JSON - вернём как есть
        }
        return body;
    }

    private static String trimSlash(String s) {
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    public static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
}
