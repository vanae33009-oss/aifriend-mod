package com.aifriend.voice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Разбор WAV и «сырого» PCM в моно 16-bit. Свой разборщик, потому что потоковые WAV часто содержат неверную длину данных. */
public final class WavDecoder {
    public record Pcm(short[] samples, int sampleRate) {
        public double seconds() {
            return sampleRate <= 0 ? 0 : samples.length / (double) sampleRate;
        }
    }

    private WavDecoder() {
    }

    public static boolean isWav(byte[] b) {
        return b.length >= 12 && tag(b, 0, "RIFF") && tag(b, 8, "WAVE");
    }

    public static boolean looksLikeMp3(byte[] b) {
        if (b.length >= 3 && b[0] == 'I' && b[1] == 'D' && b[2] == '3') {
            return true;
        }
        return b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0;
    }

    /** Сырой PCM 16-bit little-endian, моно. */
    public static Pcm fromRaw16(byte[] b, int sampleRate) {
        int n = b.length / 2;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) {
            s[i] = (short) ((b[2 * i] & 0xFF) | (b[2 * i + 1] << 8));
        }
        return new Pcm(s, sampleRate);
    }

    public static Pcm decode(byte[] b) throws IOException {
        if (!isWav(b)) {
            throw new IOException("not a WAV file");
        }
        int pos = 12;
        int format = 1;
        int channels = 1;
        int rate = 0;
        int bits = 16;
        int dataStart = -1;
        int dataLen = 0;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
            long size = le32(b, pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if (id.equals("fmt ") && body + 16 <= b.length) {
                format = le16(b, body);
                channels = Math.max(1, le16(b, body + 2));
                rate = le32(b, body + 4);
                bits = le16(b, body + 14);
            } else if (id.equals("data")) {
                long avail = b.length - body;
                dataStart = body;
                dataLen = (int) ((size == 0 || size > avail) ? avail : size);
                break;
            }
            long next = (long) body + size + (size & 1);
            if (next >= b.length) {
                break;
            }
            pos = (int) next;
        }
        if (dataStart < 0 || rate <= 0) {
            throw new IOException("WAV has no audio data");
        }

        int bytesPerSample = bits / 8;
        if (bytesPerSample <= 0) {
            throw new IOException("unsupported WAV format");
        }
        boolean isFloat = format == 3 && bits == 32;
        if (!(format == 1 || format == 0xFFFE || isFloat) || !(bits == 16 || bits == 8 || bits == 24 || bits == 32)) {
            throw new IOException("unsupported WAV format (" + bits + "-bit, tag " + format + ")");
        }
        int frameSize = bytesPerSample * channels;
        int frames = dataLen / frameSize;
        short[] out = new short[frames];
        for (int f = 0; f < frames; f++) {
            long sum = 0;
            for (int c = 0; c < channels; c++) {
                int p = dataStart + f * frameSize + c * bytesPerSample;
                sum += sample16(b, p, bits, isFloat);
            }
            out[f] = (short) (sum / channels);
        }
        return new Pcm(out, rate);
    }

    private static int sample16(byte[] b, int p, int bits, boolean isFloat) {
        switch (bits) {
            case 8:
                return ((b[p] & 0xFF) - 128) << 8;
            case 16:
                return (short) ((b[p] & 0xFF) | (b[p + 1] << 8));
            case 24:
                return (short) ((b[p + 1] & 0xFF) | (b[p + 2] << 8));
            default: // 32
                if (isFloat) {
                    float v = Float.intBitsToFloat(le32(b, p));
                    return (int) Math.max(-32768, Math.min(32767, v * 32767f));
                }
                return (short) ((b[p + 2] & 0xFF) | (b[p + 3] << 8));
        }
    }

    private static boolean tag(byte[] b, int off, String t) {
        for (int i = 0; i < 4; i++) {
            if (b[off + i] != t.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int p) {
        return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24);
    }
}
