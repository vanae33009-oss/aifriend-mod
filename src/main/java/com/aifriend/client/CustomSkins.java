package com.aifriend.client;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.aifriend.AiFriendMod;
import com.aifriend.SkinCatalog;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * Загружает свои скины бота из config/aifriend/skins/*.png (64x64, формат скина игрока) как динамические текстуры.
 * Если файл поменяли, текстура перезагружается. Вызывается только из потока отрисовки.
 */
final class CustomSkins {
    private record Entry(ResourceLocation location, long modified) {
    }

    private static final Map<String, Entry> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();

    private CustomSkins() {
    }

    @Nullable
    static ResourceLocation get(String id) {
        Path file = SkinCatalog.customFile(id);
        long modified;
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return null;
        }
        Entry cached = CACHE.get(id);
        if (cached != null && cached.modified == modified) {
            return cached.location;
        }
        String failKey = id + "@" + modified;
        if (FAILED.contains(failKey)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            NativeImage image = NativeImage.read(in);
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                AiFriendMod.LOGGER.warn("Skin '{}' is {}x{}, expected 64x64 (a modern skin). Using default.", id, image.getWidth(), image.getHeight());
                image.close();
                FAILED.add(failKey);
                return null;
            }
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(AiFriendMod.MODID,
                    "skins/" + safe(id) + "_" + Long.toHexString(modified));
            Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(image));
            if (cached != null) {
                Minecraft.getInstance().getTextureManager().release(cached.location);
            }
            CACHE.put(id, new Entry(rl, modified));
            return rl;
        } catch (Exception e) {
            AiFriendMod.LOGGER.warn("Could not load skin '{}': {}", id, e.getMessage());
            FAILED.add(failKey);
            return null;
        }
    }

    private static String safe(String id) {
        StringBuilder sb = new StringBuilder();
        for (char c : id.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-' ? c : '_');
        }
        return sb.toString() + "_" + Integer.toHexString(id.hashCode());
    }
}
