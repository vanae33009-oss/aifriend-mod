package com.aifriend.voice;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

/**
 * Озвучка текста (TTS). Поддерживает три формата API:
 * ElevenLabs (и совместимые), Fish Audio (и совместимые), OpenAI /audio/speech (и совместимые: Kokoro, openedai-speech и т.д.).
 * Всегда просим несжатый звук (PCM/WAV), потому что MP3 стандартная Java декодировать не умеет.
 */
public final class TtsClient {
    private static final int ELEVEN_PCM_RATE = 24_000;

    private TtsClient() {
    }

    public static CompletableFuture<WavDecoder.Pcm> synth(VoiceSettings.TtsResolved r, String text) {
        HttpRequest request;
        try {
            request = build(r, text);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new VoiceHttp.VoiceException("Bad TTS request/URL: " + e.getMessage()));
        }
        return VoiceHttp.send(request).thenApply(bytes -> decode(r.style(), bytes));
    }

    private static HttpRequest build(VoiceSettings.TtsResolved r, String text) {
        String base = VoiceHttp.trimSlash(r.baseUrl());
        JsonObject body = new JsonObject();
        HttpRequest.Builder rb;
        switch (r.style()) {
            case ELEVENLABS -> {
                String url;
                if (base.contains("/text-to-speech")) {
                    url = base + (base.contains("?") ? "&" : "?") + "output_format=pcm_" + ELEVEN_PCM_RATE;
                } else {
                    String path = base.contains("/v1") ? "/text-to-speech/" : "/v1/text-to-speech/";
                    url = base + path + URLEncoder.encode(r.voice(), StandardCharsets.UTF_8) + "?output_format=pcm_" + ELEVEN_PCM_RATE;
                }
                body.addProperty("text", text);
                if (!r.model().isBlank()) {
                    body.addProperty("model_id", r.model());
                }
                rb = HttpRequest.newBuilder(URI.create(url));
                if (!r.apiKey().isBlank()) {
                    rb.header("xi-api-key", r.apiKey());
                }
            }
            case FISH -> {
                String url = base.endsWith("/tts") ? base : base + "/v1/tts";
                body.addProperty("text", text);
                body.addProperty("format", "wav");
                body.addProperty("sample_rate", ELEVEN_PCM_RATE);
                body.addProperty("normalize", true);
                if (!r.voice().isBlank()) {
                    body.addProperty("reference_id", r.voice());
                }
                rb = HttpRequest.newBuilder(URI.create(url));
                if (!r.apiKey().isBlank()) {
                    rb.header("Authorization", "Bearer " + r.apiKey());
                }
                if (!r.model().isBlank()) {
                    rb.header("model", r.model());
                }
            }
            default -> { // OPENAI
                String url = base.endsWith("/audio/speech") ? base : base + "/audio/speech";
                body.addProperty("model", r.model());
                body.addProperty("input", text);
                body.addProperty("voice", r.voice());
                body.addProperty("response_format", "wav");
                rb = HttpRequest.newBuilder(URI.create(url));
                if (!r.apiKey().isBlank()) {
                    rb.header("Authorization", "Bearer " + r.apiKey());
                }
            }
        }
        return rb.timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();
    }

    private static WavDecoder.Pcm decode(VoiceSettings.ApiStyle style, byte[] bytes) {
        try {
            if (bytes.length == 0) {
                throw new VoiceHttp.VoiceException("empty audio response");
            }
            if (WavDecoder.isWav(bytes)) {
                return WavDecoder.decode(bytes);
            }
            if (WavDecoder.looksLikeMp3(bytes)) {
                throw new VoiceHttp.VoiceException("the service returned MP3; WAV/PCM is required");
            }
            if (style == VoiceSettings.ApiStyle.ELEVENLABS) {
                return WavDecoder.fromRaw16(bytes, ELEVEN_PCM_RATE);
            }
            throw new VoiceHttp.VoiceException("the service did not return WAV audio: "
                    + VoiceHttp.shorten(new String(bytes, 0, Math.min(bytes.length, 160), StandardCharsets.UTF_8), 120));
        } catch (VoiceHttp.VoiceException e) {
            throw e;
        } catch (Exception e) {
            throw new VoiceHttp.VoiceException("could not read audio: " + e.getMessage());
        }
    }
}
