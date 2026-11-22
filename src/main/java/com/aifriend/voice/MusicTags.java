package com.aifriend.voice;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** Читает из MP3 теги ID3v2: название, исполнителя и встроенную обложку. Ошибок не бросает: нет тега - пустые поля. */
public final class MusicTags {
    public record Tags(String title, String artist, byte[] image) {
        static final Tags EMPTY = new Tags("", "", null);
    }

    private static final Charset CP1251 = Charset.forName("windows-1251");

    private MusicTags() {
    }

    public static Tags read(byte[] b) {
        try {
            return parse(b);
        } catch (RuntimeException e) {
            return Tags.EMPTY;
        }
    }

    private static Tags parse(byte[] b) {
        if (b.length < 10 || b[0] != 'I' || b[1] != 'D' || b[2] != '3') {
            return Tags.EMPTY;
        }
        int ver = b[3] & 0xFF;
        int flags = b[5] & 0xFF;
        int end = Math.min(b.length, 10 + syncsafe(b, 6));
        int pos = 10;
        if ((flags & 0x40) != 0 && ver >= 3) { // расширенный заголовок
            pos += ver == 4 ? syncsafe(b, pos) : int32(b, pos) + 4;
        }
        String title = "";
        String artist = "";
        byte[] image = null;
        int imageType = 255;
        int hdr = ver == 2 ? 6 : 10;
        while (pos + hdr <= end) {
            String id = ascii(b, pos, ver == 2 ? 3 : 4);
            int size;
            if (ver == 2) {
                size = (b[pos + 3] & 0xFF) << 16 | (b[pos + 4] & 0xFF) << 8 | (b[pos + 5] & 0xFF);
            } else {
                size = ver == 4 ? syncsafe(b, pos + 4) : int32(b, pos + 4);
            }
            if (id.isEmpty() || id.charAt(0) == 0 || size <= 0) {
                break;
            }
            int ds = pos + hdr;
            int de = Math.min(end, ds + size);
            if (ds >= de) {
                break;
            }
            switch (id) {
                case "TIT2", "TT2" -> title = text(b, ds, de);
                case "TPE1", "TP1" -> artist = text(b, ds, de);
                case "APIC", "PIC" -> {
                    int[] r = picture(b, ds, de, ver == 2);
                    if (r != null && r[1] < imageType) { // тип 3 - обложка спереди, предпочитаем её
                        imageType = r[1];
                        image = java.util.Arrays.copyOfRange(b, r[0], de);
                    }
                }
                default -> { }
            }
            pos = de;
        }
        return new Tags(title, artist, image);
    }

    /** Возвращает {начало картинки, тип картинки} либо null. */
    private static int[] picture(byte[] b, int ds, int de, boolean v22) {
        int enc = b[ds] & 0xFF;
        int p = ds + 1;
        if (v22) {
            p += 3; // формат "JPG" / "PNG"
        } else {
            while (p < de && b[p] != 0) { // mime
                p++;
            }
            p++;
        }
        if (p >= de) {
            return null;
        }
        int type = b[p] & 0xFF;
        p++;
        if (enc == 1 || enc == 2) { // описание в UTF-16: конец - два нуля на чётной границе
            while (p + 1 < de && !(b[p] == 0 && b[p + 1] == 0)) {
                p += 2;
            }
            p += 2;
        } else {
            while (p < de && b[p] != 0) {
                p++;
            }
            p++;
        }
        return p < de ? new int[] { p, type } : null;
    }

    private static String text(byte[] b, int ds, int de) {
        int enc = b[ds] & 0xFF;
        int s = ds + 1;
        int len = de - s;
        if (len <= 0) {
            return "";
        }
        String out;
        switch (enc) {
            case 1 -> out = new String(b, s, len, StandardCharsets.UTF_16);
            case 2 -> out = new String(b, s, len, StandardCharsets.UTF_16BE);
            case 3 -> out = new String(b, s, len, StandardCharsets.UTF_8);
            default -> {
                String latin = new String(b, s, len, StandardCharsets.ISO_8859_1);
                String cyr = new String(b, s, len, CP1251);
                // Русские теги часто записаны в cp1251 с пометкой "latin-1": если так читается лучше, берём её
                out = cyrillicShare(cyr) > 0.5 && cyrillicShare(latin) < 0.2 ? cyr : latin;
            }
        }
        int nul = out.indexOf('\0');
        return (nul >= 0 ? out.substring(0, nul) : out).trim();
    }

    private static double cyrillicShare(String s) {
        int letters = 0;
        int cyr = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c >= 0x0400 && c <= 0x04FF) {
                    cyr++;
                }
            }
        }
        return letters == 0 ? 0 : (double) cyr / letters;
    }

    private static int syncsafe(byte[] b, int p) {
        return (b[p] & 0x7F) << 21 | (b[p + 1] & 0x7F) << 14 | (b[p + 2] & 0x7F) << 7 | (b[p + 3] & 0x7F);
    }

    private static int int32(byte[] b, int p) {
        return (b[p] & 0xFF) << 24 | (b[p + 1] & 0xFF) << 16 | (b[p + 2] & 0xFF) << 8 | (b[p + 3] & 0xFF);
    }

    private static String ascii(byte[] b, int p, int n) {
        return new String(b, p, n, StandardCharsets.ISO_8859_1);
    }
}
