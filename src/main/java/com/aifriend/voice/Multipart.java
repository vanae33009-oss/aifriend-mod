package com.aifriend.voice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Минимальный сборщик multipart/form-data для загрузки аудио в API распознавания речи. */
final class Multipart {
    private final String boundary = "----AiFriend" + UUID.randomUUID().toString().replace("-", "");
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    Multipart field(String name, String value) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        write(value + "\r\n");
        return this;
    }

    Multipart file(String name, String filename, String contentType, byte[] bytes) {
        write("--" + boundary + "\r\n");
        write("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n");
        write("Content-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(bytes);
        write("\r\n");
        return this;
    }

    String contentType() {
        return "multipart/form-data; boundary=" + boundary;
    }

    byte[] build() {
        write("--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void write(String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }
}
