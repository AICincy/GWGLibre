package com.libreshockwave;

import com.libreshockwave.io.BinaryReader;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Macromedia Shockwave 3D (.w3d) files.
 *
 * Format:
 *   Header: "IFX\0" (4) + version(u32 LE) + numEntries(u32 LE) + fileSize(u32 LE)
 *   Entries: sequential, each: u8 type | u24 parentRef | u32 dataLen | byte data[dataLen] (4-byte aligned)
 *
 * Chunk types:
 *   0x01 = Scene root
 *   0x02 = Version/settings
 *   0x72 = Node (camera, group, model pivot)
 *   0x74 = Shape (mesh geometry)
 */
public class W3DParser {

    public static void main(String[] args) throws IOException {
        String file = args.length > 0 ? args[0] : "C:/SourceControl/3d/levelDesign_030.w3d";
        parse(Path.of(file));
    }

    static void parse(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        BinaryReader r = new BinaryReader(data);
        r.setOrder(ByteOrder.LITTLE_ENDIAN);

        // Header
        String magic = r.readStringMacRoman(4);
        int version = r.readI32();
        int numEntries = r.readI32();
        int fileSize = r.readI32();

        System.out.printf("W3D File: %s%n", path.getFileName());
        System.out.printf("  Magic: '%s'  Version: %d  Entries: %d  FileSize: %d (actual: %d)%n",
            magic, version, numEntries, fileSize, data.length);
        System.out.println();

        int entryIndex = 0;
        List<String> nodeNames = new ArrayList<>();

        while (r.bytesLeft() >= 8 && entryIndex < numEntries * 20) {
            int entryOffset = r.getPosition();

            // Read entry header: u8 type + 3 bytes parentRef + u32 dataLen
            int typeByte = r.readU8();
            int parentB0 = r.readU8();
            int parentB1 = r.readU8();
            int parentB2 = r.readU8();
            int parentRef = parentB0 | (parentB1 << 8) | (parentB2 << 16);
            if (parentRef == 0xFFFFFF) parentRef = -1;

            int dataLen = r.readI32();

            if (dataLen < 0 || dataLen > r.bytesLeft()) {
                System.out.printf("  [%d] @0x%04X: INVALID dataLen=%d, stopping%n", entryIndex, entryOffset, dataLen);
                break;
            }

            int dataStart = r.getPosition();

            switch (typeByte) {
                case 0x01 -> {
                    System.out.printf("  [%d] @0x%04X: SCENE_ROOT (len=%d)%n", entryIndex, entryOffset, dataLen);
                    if (dataLen > 0) {
                        System.out.printf("    data: %s%n", hexDump(data, dataStart, Math.min(dataLen, 32)));
                    }
                }
                case 0x02 -> {
                    System.out.printf("  [%d] @0x%04X: VERSION (len=%d)%n", entryIndex, entryOffset, dataLen);
                    if (dataLen >= 3) {
                        int b0 = data[dataStart] & 0xFF;
                        int b1 = data[dataStart + 1] & 0xFF;
                        int b2 = data[dataStart + 2] & 0xFF;
                        System.out.printf("    version bytes: %d.%d.%d%n", b0, b1, b2);
                    }
                }
                case 0x72 -> {
                    // Node entry
                    r.setPosition(dataStart);
                    String name = readPString16(r);
                    String parent = readPString16(r);
                    nodeNames.add(name);

                    int flags = r.bytesLeft() >= 2 ? r.readI16() & 0xFFFF : 0;
                    int transformType = r.bytesLeft() >= 1 ? r.readU8() : 0;

                    System.out.printf("  [%d] @0x%04X: NODE '%s' parent='%s' flags=0x%04X tfType=0x%02X (len=%d)%n",
                        entryIndex, entryOffset, name, parent, flags, transformType, dataLen);

                    // Parse transform (4x4 matrix as 16 floats if transformType == 0x20)
                    if (transformType == 0x20 && r.bytesLeft() >= 64) {
                        r.setOrder(ByteOrder.LITTLE_ENDIAN);
                        float[] matrix = new float[16];
                        for (int i = 0; i < 16; i++) {
                            matrix[i] = Float.intBitsToFloat(r.readI32());
                        }
                        // Extract position from last column
                        System.out.printf("    position: (%.2f, %.2f, %.2f)%n", matrix[12], matrix[13], matrix[14]);
                        // Check if non-identity rotation
                        boolean isIdentity = Math.abs(matrix[0] - 1) < 0.001f &&
                            Math.abs(matrix[5] - 1) < 0.001f &&
                            Math.abs(matrix[10] - 1) < 0.001f;
                        if (!isIdentity) {
                            System.out.printf("    rotation: [%.4f %.4f %.4f] [%.4f %.4f %.4f] [%.4f %.4f %.4f]%n",
                                matrix[0], matrix[1], matrix[2],
                                matrix[4], matrix[5], matrix[6],
                                matrix[8], matrix[9], matrix[10]);
                        }
                    }

                    // Read remaining data (model resource name, shader name, etc.)
                    int remaining = dataLen - (r.getPosition() - dataStart);
                    if (remaining > 0) {
                        int savedPos = r.getPosition();
                        // Try to read model resource name
                        if (remaining >= 2) {
                            int resNameLen = r.readI16() & 0xFFFF;
                            if (resNameLen > 0 && resNameLen < 256 && resNameLen <= remaining - 2) {
                                String resName = r.readStringMacRoman(resNameLen);
                                System.out.printf("    modelResource: '%s'%n", resName);
                                // Try parent ref
                                if (r.getPosition() - dataStart < dataLen && r.bytesLeft() >= 2) {
                                    int refNameLen = r.readI16() & 0xFFFF;
                                    if (refNameLen > 0 && refNameLen < 256 && refNameLen + (r.getPosition() - dataStart) <= dataLen) {
                                        String refName = r.readStringMacRoman(refNameLen);
                                        System.out.printf("    modelRef: '%s'%n", refName);
                                    }
                                }
                                // Check for more fields
                                int leftover = dataLen - (r.getPosition() - dataStart);
                                if (leftover >= 2) {
                                    int numExtra = r.readI16() & 0xFFFF;
                                    if (numExtra > 0 && numExtra < 100) {
                                        // Read shader name
                                        if (r.getPosition() - dataStart < dataLen && r.bytesLeft() >= 2) {
                                            int shaderNameLen = r.readI16() & 0xFFFF;
                                            if (shaderNameLen > 0 && shaderNameLen < 256 && shaderNameLen + (r.getPosition() - dataStart) <= dataLen) {
                                                String shaderName = r.readStringMacRoman(shaderNameLen);
                                                System.out.printf("    shader: '%s'%n", shaderName);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                case 0x74 -> {
                    // Shape entry
                    r.setPosition(dataStart);
                    String name = readPString16(r);
                    String parent = readPString16(r);

                    int flags = r.bytesLeft() >= 2 ? r.readI16() & 0xFFFF : 0;
                    int shapeType = r.bytesLeft() >= 1 ? r.readU8() : 0;

                    System.out.printf("  [%d] @0x%04X: SHAPE '%s' parent='%s' flags=0x%04X type=0x%02X (len=%d)%n",
                        entryIndex, entryOffset, name, parent, flags, shapeType, dataLen);

                    // Parse shape data
                    if (shapeType == 0x20 && r.bytesLeft() >= 64) {
                        // Skip 4x4 transform matrix
                        r.skip(64);
                    }

                    // Try to read mesh header
                    int meshDataStart = r.getPosition();
                    int meshRemaining = dataLen - (meshDataStart - dataStart);
                    if (meshRemaining >= 8) {
                        int meshField1 = r.readI16() & 0xFFFF;
                        int meshField2 = r.readI16() & 0xFFFF;
                        System.out.printf("    meshFields: %d, %d%n", meshField1, meshField2);

                        // Read more mesh data to identify vertex/face counts
                        if (meshRemaining >= 16) {
                            // Try to identify float arrays (vertices)
                            int pos = r.getPosition();
                            float f1 = Float.intBitsToFloat(r.readI32());
                            float f2 = Float.intBitsToFloat(r.readI32());
                            float f3 = Float.intBitsToFloat(r.readI32());
                            System.out.printf("    meshData start: %.4f, %.4f, %.4f%n", f1, f2, f3);
                        }

                        // Dump a summary of the remaining bytes
                        int left = dataLen - (r.getPosition() - dataStart);
                        System.out.printf("    remaining mesh data: %d bytes%n", left);
                    }
                }
                default -> {
                    System.out.printf("  [%d] @0x%04X: TYPE=0x%02X parent=%d (len=%d)%n",
                        entryIndex, entryOffset, typeByte, parentRef, dataLen);
                    if (dataLen > 0 && dataLen <= 64) {
                        System.out.printf("    data: %s%n", hexDump(data, dataStart, dataLen));
                        System.out.printf("    ascii: %s%n", asciiDump(data, dataStart, dataLen));
                    }
                }
            }

            // Advance past data (data section is padded to 4-byte boundary)
            int paddedDataLen = (dataLen + 3) & ~3;
            int nextPos = dataStart + paddedDataLen;
            r.setPosition(nextPos);

            entryIndex++;

            // Safety check
            if (r.bytesLeft() < 8) break;
        }

        System.out.printf("%n  Total entries parsed: %d%n", entryIndex);
        System.out.printf("  Total node names: %d%n", nodeNames.size());
        System.out.println("  Nodes: " + String.join(", ", nodeNames));
    }

    private static String readPString16(BinaryReader r) {
        if (r.bytesLeft() < 2) return "";
        int len = r.readI16() & 0xFFFF;
        if (len == 0 || len > 256 || len > r.bytesLeft()) return "";
        return r.readStringMacRoman(len);
    }

    private static String hexDump(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            sb.append(String.format("%02X ", data[offset + i] & 0xFF));
        }
        return sb.toString().trim();
    }

    private static String asciiDump(byte[] data, int offset, int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len && offset + i < data.length; i++) {
            int b = data[offset + i] & 0xFF;
            sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        return sb.toString();
    }
}
