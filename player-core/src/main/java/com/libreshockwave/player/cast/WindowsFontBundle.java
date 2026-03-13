package com.libreshockwave.player.cast;

import com.libreshockwave.font.BitmapFont;
import com.libreshockwave.font.TtfBitmapRasterizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Embedded Windows system fonts for Director movie compatibility.
 * Director movies authored on Windows reference system fonts (Verdana, Arial, etc.)
 * that may not be available in WASM. This bundle provides TTF font data that is
 * rasterized via the pure-Java TtfBitmapRasterizer (TeaVM-compatible).
 *
 * Bold, italic, and bold-italic variants are loaded when available.
 */
public class WindowsFontBundle {

    private static final String RESOURCE_BASE = "/fonts/windows/";

    /**
     * Available Windows font TTF resources.
     * Key: lowercase font name, Value: array of {regular, bold, italic, bolditalic} filenames (null if absent).
     */
    private static final Map<String, String[]> AVAILABLE_FONTS = new HashMap<>();
    static {
        AVAILABLE_FONTS.put("verdana", new String[]{"Verdana.ttf", "VerdanaBd.ttf", "VerdanaIt.ttf", "VerdanaBdIt.ttf"});
        AVAILABLE_FONTS.put("arial", new String[]{"Arial.ttf", null, null, null});
        AVAILABLE_FONTS.put("courier new", new String[]{"CourierNew.ttf", null, null, null});
        AVAILABLE_FONTS.put("times new roman", new String[]{"TimesNewRoman.ttf", "TimesNewRomanBd.ttf", "TimesNewRomanIt.ttf", "TimesNewRomanBdIt.ttf"});
    }

    /** Cache: "fontname:size:style" -> rasterized BitmapFont */
    private static final Map<String, BitmapFont> cache = new HashMap<>();

    /** Cache: filename -> raw TTF bytes */
    private static final Map<String, byte[]> ttfBytesCache = new HashMap<>();

    /**
     * Try to get a Windows font at the given size and style.
     * @param fontName font name (e.g., "Verdana")
     * @param fontSize target pixel size
     * @param bold true for bold variant
     * @param italic true for italic variant
     * @return BitmapFont, or null if not available
     */
    public static BitmapFont getFont(String fontName, int fontSize, boolean bold, boolean italic) {
        if (fontName == null) return null;
        String key = fontName.toLowerCase();

        String[] variants = AVAILABLE_FONTS.get(key);
        if (variants == null) return null;

        // Select variant: 0=regular, 1=bold, 2=italic, 3=bolditalic
        int variantIdx = (bold ? 1 : 0) + (italic ? 2 : 0);
        String ttfFile = variants[variantIdx];

        // Fall back to regular if specific variant not available
        if (ttfFile == null) {
            ttfFile = variants[0];
        }
        if (ttfFile == null) return null;

        String cacheKey = key + ":" + fontSize + ":" + variantIdx;
        BitmapFont cached = cache.get(cacheKey);
        if (cached != null) return cached;

        byte[] ttfBytes = loadTtfBytes(ttfFile);
        if (ttfBytes == null) return null;

        BitmapFont font = TtfBitmapRasterizer.rasterize(ttfBytes, fontSize, fontName);
        if (font != null) {
            cache.put(cacheKey, font);
        }
        return font;
    }

    /**
     * Check if a font name has a Windows TTF available.
     */
    public static boolean hasWindowsFont(String fontName) {
        if (fontName == null) return false;
        return AVAILABLE_FONTS.containsKey(fontName.toLowerCase());
    }

    /**
     * Check if a specific bold variant exists for the font.
     */
    public static boolean hasBoldVariant(String fontName) {
        if (fontName == null) return false;
        String[] variants = AVAILABLE_FONTS.get(fontName.toLowerCase());
        return variants != null && variants[1] != null;
    }

    private static byte[] loadTtfBytes(String filename) {
        byte[] cached = ttfBytesCache.get(filename);
        if (cached != null) return cached;

        String resource = RESOURCE_BASE + filename;
        try (InputStream is = WindowsFontBundle.class.getResourceAsStream(resource)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            ttfBytesCache.put(filename, bytes);
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }
}
