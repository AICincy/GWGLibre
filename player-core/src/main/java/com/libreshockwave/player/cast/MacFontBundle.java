package com.libreshockwave.player.cast;

import com.libreshockwave.font.BitmapFont;
import com.libreshockwave.font.TtfBitmapRasterizer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Embedded Mac system fonts for Director movie compatibility.
 * Director movies authored on Mac reference system fonts (Geneva, Chicago, Monaco, etc.)
 * that aren't available on Windows or in WASM. This bundle provides bitmap font TTFs
 * converted from classic Mac OS bitmap fonts, rasterized via TtfBitmapRasterizer
 * (pure Java, TeaVM-compatible — no AWT dependency).
 *
 * Bold variants are handled the same way as WindowsFontBundle: e.g., EspySansBold
 * is the bold variant of EspySans, not a separate font family.
 *
 * Source: https://github.com/jcs/classic-mac-fonts
 */
public class MacFontBundle {

    private static final String RESOURCE_BASE = "/fonts/mac/";

    /**
     * Font family definition: regular sizes, bold file prefix (null if no bold variant),
     * and bold sizes.
     */
    private static class FontFamily {
        final String filePrefix;       // e.g., "Geneva"
        final int[] sizes;             // available regular sizes
        final String boldFilePrefix;   // e.g., "EspySansBold", null if no bold variant
        final int[] boldSizes;         // available bold sizes (null if no bold)

        FontFamily(String filePrefix, int[] sizes, String boldFilePrefix, int[] boldSizes) {
            this.filePrefix = filePrefix;
            this.sizes = sizes;
            this.boldFilePrefix = boldFilePrefix;
            this.boldSizes = boldSizes;
        }

        FontFamily(String filePrefix, int[] sizes) {
            this(filePrefix, sizes, null, null);
        }
    }

    /** Available Mac font families keyed by lowercase font name */
    private static final Map<String, FontFamily> FONT_FAMILIES = new HashMap<>();
    static {
        FONT_FAMILIES.put("geneva",    new FontFamily("Geneva",    new int[]{9, 10, 12, 14, 18, 20}));
        FONT_FAMILIES.put("chicago",   new FontFamily("Chicago",   new int[]{12}));
        FONT_FAMILIES.put("monaco",    new FontFamily("Monaco",    new int[]{9, 12}));
        FONT_FAMILIES.put("helvetica", new FontFamily("Helvetica", new int[]{9, 10, 12, 14, 18, 24}));
        FONT_FAMILIES.put("courier",   new FontFamily("Courier",   new int[]{9, 10, 12, 14, 18, 24}));
        FONT_FAMILIES.put("times",     new FontFamily("Times",     new int[]{9, 10, 12, 14, 18, 24}));
        FONT_FAMILIES.put("palatino",  new FontFamily("Palatino",  new int[]{10, 12, 14, 18, 24}));
        FONT_FAMILIES.put("espysans",  new FontFamily("EspySans",  new int[]{9, 10, 12, 14, 16},
                                                       "EspySansBold", new int[]{9, 10, 12, 14, 16}));
        FONT_FAMILIES.put("espyserif", new FontFamily("EspySerif", new int[]{10, 12, 14, 16},
                                                       "EspySerifBold", new int[]{10, 12, 14, 16}));
    }

    /**
     * Windows→Mac font name mappings.
     * Director movies may reference Windows font names (from XMED section 0008)
     * that need to be mapped to their Mac equivalents.
     */
    private static final Map<String, String> FONT_ALIASES = Map.of(
            "verdana", "geneva",
            "arial", "helvetica",
            "courier new", "courier",
            "times new roman", "times"
    );

    private static volatile boolean initialized = false;

    /** Cache: "fontkey:size:bold" -> rasterized BitmapFont */
    private static final Map<String, BitmapFont> cache = new HashMap<>();

    /** Cache: resource path -> raw TTF bytes */
    private static final Map<String, byte[]> ttfBytesCache = new HashMap<>();

    /**
     * Load all bundled Mac fonts and register them in FontRegistry.
     * Safe to call multiple times — only loads once.
     */
    public static void initialize() {
        if (initialized) return;
        initialized = true;

        for (var entry : FONT_FAMILIES.entrySet()) {
            String fontKey = entry.getKey();
            FontFamily family = entry.getValue();
            for (int size : family.sizes) {
                BitmapFont font = loadTtf(family.filePrefix, size, fontKey);
                if (font != null) {
                    FontRegistry.registerBitmapFont(fontKey, size, font);
                }
            }
        }
    }

    /**
     * Try to load a specific Mac font at the given size and style.
     * Consistent with WindowsFontBundle.getFont() signature.
     *
     * @param fontName font name (e.g., "Geneva", "EspySans")
     * @param fontSize target pixel size
     * @param bold true for bold variant
     * @param italic true for italic variant (not yet supported for Mac fonts)
     * @return BitmapFont, or null if not available
     */
    public static BitmapFont getFont(String fontName, int fontSize, boolean bold, boolean italic) {
        if (fontName == null) return null;
        String key = fontName.toLowerCase();

        // Map Windows font names to Mac equivalents
        String mapped = FONT_ALIASES.get(key);
        if (mapped != null) key = mapped;

        FontFamily family = FONT_FAMILIES.get(key);
        if (family == null) return null;

        // Select variant and sizes
        String filePrefix;
        int[] sizes;
        if (bold && family.boldFilePrefix != null) {
            filePrefix = family.boldFilePrefix;
            sizes = family.boldSizes;
        } else {
            filePrefix = family.filePrefix;
            sizes = family.sizes;
        }

        // Find best matching size
        int bestSize = findBestSize(sizes, fontSize);
        if (bestSize <= 0) return null;

        // Check cache
        String cacheKey = key + ":" + bestSize + ":" + (bold ? "bold" : "regular");
        BitmapFont cached = cache.get(cacheKey);
        if (cached != null) return cached;

        // Load on demand
        BitmapFont font = loadTtf(filePrefix, bestSize, key);
        if (font != null) {
            cache.put(cacheKey, font);
        }
        return font;
    }

    /** Backward-compatible overload without bold/italic. */
    public static BitmapFont getFont(String fontName, int fontSize) {
        return getFont(fontName, fontSize, false, false);
    }

    /**
     * Check if a font name matches a bundled Mac font.
     */
    public static boolean hasMacFont(String fontName) {
        if (fontName == null) return false;
        String key = fontName.toLowerCase();
        String mapped = FONT_ALIASES.get(key);
        if (mapped != null) key = mapped;
        return FONT_FAMILIES.containsKey(key);
    }

    /**
     * Check if a specific bold variant exists for the font.
     */
    public static boolean hasBoldVariant(String fontName) {
        if (fontName == null) return false;
        String key = fontName.toLowerCase();
        String mapped = FONT_ALIASES.get(key);
        if (mapped != null) key = mapped;
        FontFamily family = FONT_FAMILIES.get(key);
        return family != null && family.boldFilePrefix != null;
    }

    private static BitmapFont loadTtf(String filePrefix, int size, String fontKey) {
        String resource = RESOURCE_BASE + filePrefix + "-" + size + ".ttf";
        byte[] ttfBytes = loadTtfBytes(resource);
        if (ttfBytes == null) return null;
        return TtfBitmapRasterizer.rasterize(ttfBytes, size, fontKey);
    }

    private static byte[] loadTtfBytes(String resource) {
        byte[] cached = ttfBytesCache.get(resource);
        if (cached != null) return cached;

        try (InputStream is = MacFontBundle.class.getResourceAsStream(resource)) {
            if (is == null) return null;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            byte[] bytes = bos.toByteArray();
            ttfBytesCache.put(resource, bytes);
            return bytes;
        } catch (Exception e) {
            return null;
        }
    }

    private static int findBestSize(int[] available, int requested) {
        // Prefer exact match
        for (int s : available) {
            if (s == requested) return s;
        }
        // Find closest size
        int best = -1;
        int bestDiff = Integer.MAX_VALUE;
        for (int s : available) {
            int diff = Math.abs(s - requested);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }
}
