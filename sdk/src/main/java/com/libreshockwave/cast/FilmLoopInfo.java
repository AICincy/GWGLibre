package com.libreshockwave.cast;

import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;

/**
 * Film loop-specific cast member data.
 *
 * The specificData stores a bounding rect in stage coordinates (top, left, bottom, right)
 * as signed 16-bit values. The film loop's actual pixel dimensions are derived from
 * the rect: width = right - left, height = bottom - top.
 *
 * Sub-sprites within the film loop use absolute stage coordinates; to map them to
 * bitmap pixel positions, subtract rect.left and rect.top.
 */
public record FilmLoopInfo(
    int rectTop,
    int rectLeft,
    int rectBottom,
    int rectRight,
    boolean center,
    boolean crop,
    boolean sound,
    boolean loops
) implements Dimensioned {

    @Override
    public int width() {
        return rectRight - rectLeft;
    }

    @Override
    public int height() {
        return rectBottom - rectTop;
    }

    @Override
    public int regX() {
        return -rectLeft;
    }

    @Override
    public int regY() {
        return -rectTop;
    }

    public static FilmLoopInfo parse(byte[] data) {
        if (data == null || data.length < 11) {
            return new FilmLoopInfo(0, 0, 0, 0, false, true, false, true);
        }

        BinaryReader reader = new BinaryReader(data, ByteOrder.BIG_ENDIAN);

        int rectTop = reader.readI16();
        int rectLeft = reader.readI16();
        int rectBottom = reader.readI16();
        int rectRight = reader.readI16();
        reader.skip(3); // unknown (3 bytes)
        int flags = reader.readU8();

        boolean center = (flags & 0b1) != 0;
        boolean crop = (flags & 0b10) == 0;
        boolean sound = (flags & 0b1000) != 0;
        boolean loops = (flags & 0b100000) == 0;

        return new FilmLoopInfo(rectTop, rectLeft, rectBottom, rectRight, center, crop, sound, loops);
    }
}
