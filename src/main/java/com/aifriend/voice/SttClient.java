package com.aifriend.voice;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Распознавание речи (STT). Форматы: OpenAI /audio/transcriptions (OpenAI, Groq, локальные Whisper-серверы)
 * и ElevenLabs Scribe /v1/speech-to-text.
 */
public final class SttClient {
    private SttClient() {
    }

    public static CompletableFuture<String> transcribe(VoiceSettings.SttResolved r, byte[] wav) {
        HttpRequest request;
        try {
            request = build(r, wav);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new VoiceHttp.VoiceException("Bad STT request/URL: " + e.getMessage()));
        }
        return VoiceHttp.send(request).thenApply(bytes -> {
            try {
                JsonObject o = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                return o.has("text") ? o.get("text").getAsString().trim() : "";
            } catch (Exception e) {
                throw new VoiceHttp.VoiceException("unexpected recognition response: "
                        + VoiceHttp.shorten(new String(bytes, StandardCharsets.UTF_8), 120));
            }
        });
    }

    private static HttpRequest build(VoiceSettings.SttResolved r, byte[] wav) {
        String base = VoiceHttp.trimSlash(r.baseUrl());
        Multipart form = new Multipart();
        HttpRequest.Builder rb;
        if (r.style() == VoiceSettings.ApiStyle.ELEVENLABS) {
            String url = base.endsWith("/speech-to-text") ? base : base.contains("/v1") ? base + "/speech-to-text" : base + "/v1/speech-to-text";
            form.field("model_id", r.model());
            if (!r.language().isBlank()) {
                form.field("language_code", r.language());
            }
            form.field("tag_audio_events", "false");
            form.file("file", "speech.wav", "audio/wav", wav);
            rb = HttpRequest.newBuilder(URI.create(url));
            if (!r.apiKey().isBlank()) {
                rb.header("xi-api-key", r.apiKey());
            }
        } else {
            String url = base.endsWith("/audio/transcriptions") ? base : base + "/audio/transcriptions";
            form.field("model", r.model());
            if (!r.language().isBlank()) {
                form.field("language", r.language());
            }
            form.field("response_format", "json");
            form.file("file", "speech.wav", "audio/wav", wav);
            rb = HttpRequest.newBuilder(URI.create(url));
            if (!r.apiKey().isBlank()) {
                rb.header("Authorization", "Bearer " + r.apiKey());
            }
        }
        return rb.timeout(Duration.ofSeconds(40))
                .header("Content-Type", form.contentType())
                .POST(HttpRequest.BodyPublishers.ofByteArray(form.build()))
                .build();
    }
}
