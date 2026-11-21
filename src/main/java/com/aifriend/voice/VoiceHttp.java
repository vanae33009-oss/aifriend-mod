package com.aifriend.voice;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Общий HTTP-клиент для голосовых сервисов. */
final class VoiceHttp {
    static final class VoiceException extends RuntimeException {
        VoiceException(String message) {
            super(message);
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private VoiceHttp() {
    }

    static CompletableFuture<byte[]> send(HttpRequest request) {
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenCompose(resp -> {
            int code = resp.statusCode();
            if (code >= 200 && code < 300) {
                return CompletableFuture.completedFuture(resp.body());
            }
            String body = new String(resp.body() == null ? new byte[0] : resp.body(), StandardCharsets.UTF_8);
            return CompletableFuture.failedFuture(new VoiceException("HTTP " + code + ": " + shorten(extractError(body), 200)));
        });
    }

    static String trimSlash(String s) {
        String r = s.trim();
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    static String shorten(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.replace('\n', ' ').trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String extractError(String body) {
        try {
            JsonElement root = JsonParser.parseString(body);
            if (root.isJsonObject()) {
                JsonObject o = root.getAsJsonObject();
                for (String key : new String[] { "error", "detail", "message" }) {
                    if (!o.has(key)) {
                        continue;
                    }
                    JsonElement e = o.get(key);
                    if (e.isJsonPrimitive()) {
                        return e.getAsString();
                    }
                    if (e.isJsonObject() && e.getAsJsonObject().has("message")) {
                        return e.getAsJsonObject().get("message").getAsString();
                    }
                    return e.toString();
                }
            }
        } catch (Exception ignored) {
            // не JSON
        }
        return body;
    }
}
