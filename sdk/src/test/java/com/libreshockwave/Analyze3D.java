package com.libreshockwave;

import com.libreshockwave.chunks.*;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.lingo.Opcode;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Analysis tool for reverse-engineering 3D content in DCR files.
 */
public class Analyze3D {

    public static void main(String[] args) throws IOException {
        String[] files = args.length > 0 ? args : new String[]{
            "C:/SourceControl/3d/UFO_Pool_Party.dcr"
        };

        for (String filePath : files) {
            System.out.println("\n========================================");
            System.out.println("FILE: " + filePath);
            System.out.println("========================================\n");
            analyzeFile(filePath);
        }
    }

    private static void analyzeFile(String filePath) throws IOException {
        DirectorFile file = DirectorFile.load(Path.of(filePath));

        // 1. Find and deeply analyze 3D members
        System.out.println("=== 3D / Shockwave3D Members ===\n");
        for (CastMemberChunk member : file.getCastMembers()) {
            boolean is3D = member.memberType() == MemberType.SHOCKWAVE_3D;
            if (member.memberType() == MemberType.XTRA && member.specificData().length >= 15) {
                String sd = asciiDump(member.specificData(), 50);
                if (sd.contains("shockwave3d")) is3D = true;
            }
            if (!is3D) continue;

            System.out.printf("  Member: name='%s' id=%s type=%s%n", member.name(), member.id(), member.memberType());
            System.out.println("  specificData (" + member.specificData().length + " bytes):");
            System.out.println("  " + hexDumpFull(member.specificData()));

            // Parse the 3DPR structure
            parse3DPR(member.specificData());

            // Associated chunks
            KeyTableChunk keyTable = file.getKeyTable();
            if (keyTable != null) {
                List<KeyTableChunk.KeyTableEntry> entries = keyTable.getEntriesForOwner(member.id());
                System.out.println("\n  Associated chunks: " + entries.size());
                for (KeyTableChunk.KeyTableEntry entry : entries) {
                    DirectorFile.ChunkInfo chunkInfo = file.getChunkInfo(entry.sectionId());
                    int size = chunkInfo != null ? chunkInfo.length() : -1;
                    System.out.printf("    %s -> %s (size=%d)%n",
                        entry.fourccString(), entry.sectionId(), size);
                }
            }
            System.out.println();
        }

        // 2. Decompile 3D-related scripts
        System.out.println("\n=== Script Decompilation (3D-related) ===\n");
        Set<String> target3DScripts = Set.of(
            "create_world", "modelImport", "ufo_control", "movieScript_resetWorld"
        );

        for (ScriptChunk script : file.getScripts()) {
            String scriptName = findScriptName(file, script);
            if (!target3DScripts.contains(scriptName)) continue;

            System.out.println("--- Script: '" + scriptName + "' ---");

            // Show literals (string constants reveal the 3D API calls)
            if (!script.literals().isEmpty()) {
                System.out.println("  Literals:");
                for (int i = 0; i < script.literals().size(); i++) {
                    ScriptChunk.LiteralEntry lit = script.literals().get(i);
                    System.out.printf("    [%d] type=%d value=%s%n", i, lit.type(), lit.value());
                }
            }

            // Show globals
            if (!script.globals().isEmpty()) {
                System.out.println("  Globals:");
                for (ScriptChunk.GlobalEntry g : script.globals()) {
                    System.out.printf("    nameId=%d%n", g.nameId());
                }
            }

            // Disassemble each handler
            for (ScriptChunk.Handler handler : script.handlers()) {
                ScriptNamesChunk names = file.getScriptNames();
                String hName = getHandlerName(names, handler);
                System.out.println("\n  handler " + hName + " (args=" + handler.argCount()
                    + " locals=" + handler.localCount() + "):");

                // Show arg/local names
                if (!handler.argNameIds().isEmpty()) {
                    System.out.print("    args: ");
                    for (int id : handler.argNameIds()) {
                        System.out.print(getNameById(names, id) + " ");
                    }
                    System.out.println();
                }
                if (!handler.localNameIds().isEmpty()) {
                    System.out.print("    locals: ");
                    for (int id : handler.localNameIds()) {
                        System.out.print(getNameById(names, id) + " ");
                    }
                    System.out.println();
                }

                // Disassemble instructions
                for (ScriptChunk.Handler.Instruction instr : handler.instructions()) {
                    String annotation = annotateInstruction(instr, names, script.literals());
                    System.out.printf("    %4d: %-20s %s%n", instr.offset(),
                        instr.toString().replaceFirst("^\\[\\d+\\] ", ""), annotation);
                }
            }
            System.out.println();
        }

        // 3. Unknown chunks (might be 3D-specific)
        System.out.println("\n=== Unknown/Unrecognized Chunks ===\n");
        for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
            if (info.type().getFourCCString().equals("????")) {
                String fourCC = fourCCFromInt(info.fourcc());
                Chunk chunk = file.getChunk(info.id());
                int dataLen = chunk instanceof RawChunk rc ? rc.data().length : 0;
                System.out.printf("  id=%s fourcc='%s' (0x%08X) size=%d dataLen=%d%n",
                    info.id(), fourCC, info.fourcc(), info.length(), dataLen);
                if (chunk instanceof RawChunk rc && rc.data().length > 0 && rc.data().length <= 200) {
                    System.out.println("    Hex: " + hexDump(rc.data(), 200));
                    System.out.println("    ASCII: " + asciiDump(rc.data(), 200));
                } else if (chunk instanceof RawChunk rc && rc.data().length > 200) {
                    System.out.println("    First 200: " + hexDump(rc.data(), 200));
                    System.out.println("    ASCII: " + asciiDump(rc.data(), 200));
                }
            }
        }

        // 4. XTRl (full dump)
        System.out.println("\n=== XTRl ===\n");
        for (DirectorFile.ChunkInfo info : file.getAllChunkInfo()) {
            String fourCC = fourCCFromInt(info.fourcc());
            if (fourCC.equals("XTRl")) {
                Chunk chunk = file.getChunk(info.id());
                if (chunk instanceof RawChunk rc) {
                    System.out.println("  XTRl (" + rc.data().length + " bytes):");
                    System.out.println("  " + hexDumpFull(rc.data()));
                    System.out.println("  ASCII: " + asciiDump(rc.data(), rc.data().length));
                }
            }
        }
    }

    private static void parse3DPR(byte[] data) {
        System.out.println("\n  === 3DPR Parse ===");
        BinaryReader r = new BinaryReader(data);
        r.setOrder(ByteOrder.BIG_ENDIAN);

        int nameLen = r.readI32();
        String xtraName = r.readStringMacRoman(nameLen);
        System.out.println("  xtraName: '" + xtraName + "'");

        int totalDataSize = r.readI32();
        System.out.println("  totalDataSize: " + totalDataSize);

        String fourCC = r.readStringMacRoman(4);
        System.out.println("  fourCC: '" + fourCC + "'");

        int blockSize = r.readI32();
        System.out.println("  blockSize: " + blockSize);

        // Parse 3DPR fields
        int f1 = r.readI32(); // version/flags?
        int f2 = r.readI32();
        int f3 = r.readI32();
        int f4 = r.readI32();
        int f5 = r.readI32();
        System.out.printf("  header: [%d, %d, %d, %d, %d]%n", f1, f2, f3, f4, f5);

        // Now parse tagged elements
        while (r.bytesLeft() >= 4) {
            int pos = r.getPosition();
            int tag = r.readI32();

            if (tag == 0x03) {
                // String section
                if (r.bytesLeft() < 4) break;
                int sLen = r.readI32();
                if (sLen > 0 && sLen < 256 && r.bytesLeft() >= sLen) {
                    String s = r.readStringMacRoman(sLen);
                    System.out.printf("  [%3d] STRING '%s'%n", pos, s);
                } else {
                    System.out.printf("  [%3d] TAG=3, sLen=%d (invalid?)%n", pos, sLen);
                }
            } else if (tag == 0x16) {
                // Vector/Transform: 4 floats
                if (r.bytesLeft() >= 16) {
                    float x = Float.intBitsToFloat(r.readI32());
                    float y = Float.intBitsToFloat(r.readI32());
                    float z = Float.intBitsToFloat(r.readI32());
                    float w = Float.intBitsToFloat(r.readI32());
                    System.out.printf("  [%3d] TRANSFORM (%.4f, %.4f, %.4f, %.4f)%n", pos, x, y, z, w);
                }
            } else if (tag == 0x12) {
                // Color: 3 u32 values (R, G, B)
                if (r.bytesLeft() >= 12) {
                    int cr = r.readI32();
                    int cg = r.readI32();
                    int cb = r.readI32();
                    System.out.printf("  [%3d] COLOR rgb(%d, %d, %d)%n", pos, cr, cg, cb);
                }
            } else {
                // Try interpreting as float
                float fVal = Float.intBitsToFloat(tag);
                boolean isReasonableFloat = !Float.isNaN(fVal) && !Float.isInfinite(fVal) &&
                    Math.abs(fVal) > 0.001f && Math.abs(fVal) < 100000.0f;
                if (isReasonableFloat) {
                    System.out.printf("  [%3d] FLOAT %.4f (0x%08X)%n", pos, fVal, tag);
                } else {
                    System.out.printf("  [%3d] INT %d (0x%08X)%n", pos, tag, tag);
                }
            }
        }
    }

    private static String annotateInstruction(ScriptChunk.Handler.Instruction instr,
                                               ScriptNamesChunk names,
                                               List<ScriptChunk.LiteralEntry> literals) {
        Opcode op = instr.opcode();
        int arg = instr.argument();

        // Name-based opcodes
        if (op == Opcode.PUSH_SYMB || op == Opcode.EXT_CALL || op == Opcode.OBJ_CALL ||
            op == Opcode.OBJ_CALL_V4 || op == Opcode.GET_PROP || op == Opcode.SET_PROP ||
            op == Opcode.GET_GLOBAL || op == Opcode.SET_GLOBAL || op == Opcode.GET_GLOBAL2 ||
            op == Opcode.SET_GLOBAL2 || op == Opcode.GET_OBJ_PROP || op == Opcode.SET_OBJ_PROP ||
            op == Opcode.GET_MOVIE_PROP || op == Opcode.SET_MOVIE_PROP ||
            op == Opcode.GET_TOP_LEVEL_PROP || op == Opcode.LOCAL_CALL ||
            op == Opcode.PUSH_VAR_REF || op == Opcode.THE_BUILTIN ||
            op == Opcode.GET_CHAINED_PROP || op == Opcode.NEW_OBJ) {
            return "// " + getNameById(names, arg);
        }

        // Literal-based opcodes
        if (op == Opcode.PUSH_CONS && literals != null && arg >= 0 && arg < literals.size()) {
            return "// " + literals.get(arg).value();
        }

        return "";
    }

    private static String findScriptName(DirectorFile file, ScriptChunk script) {
        for (CastMemberChunk m : file.getCastMembers()) {
            if (m.isScript()) {
                ScriptChunk linked = file.getScriptByContextId(m.scriptId());
                if (linked != null && linked.id().equals(script.id())) {
                    return m.name();
                }
            }
        }
        return "";
    }

    private static String getHandlerName(ScriptNamesChunk names, ScriptChunk.Handler handler) {
        return getNameById(names, handler.nameId());
    }

    private static String getNameById(ScriptNamesChunk names, int id) {
        if (names != null && id >= 0 && id < names.names().size()) {
            return names.names().get(id);
        }
        return "?id=" + id;
    }

    private static String fourCCFromInt(int val) {
        char[] c = new char[4];
        c[0] = (char) ((val >> 24) & 0xFF);
        c[1] = (char) ((val >> 16) & 0xFF);
        c[2] = (char) ((val >> 8) & 0xFF);
        c[3] = (char) (val & 0xFF);
        for (int i = 0; i < 4; i++) {
            if (c[i] < 0x20 || c[i] > 0x7E) c[i] = '.';
        }
        return new String(c);
    }

    private static String hexDump(byte[] data, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(data.length, maxBytes);
        for (int i = 0; i < len; i++) {
            if (i > 0 && i % 32 == 0) sb.append("\n                    ");
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        if (len < data.length) sb.append("... (" + data.length + " total)");
        return sb.toString();
    }

    private static String hexDumpFull(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i++) {
            if (i > 0 && i % 32 == 0) sb.append("\n    ");
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String asciiDump(byte[] data, int maxBytes) {
        StringBuilder sb = new StringBuilder();
        int len = Math.min(data.length, maxBytes);
        for (int i = 0; i < len; i++) {
            int b = data[i] & 0xFF;
            if (b >= 0x20 && b < 0x7F) {
                sb.append((char) b);
            } else {
                sb.append('.');
            }
        }
        if (len < data.length) sb.append("... (" + data.length + " total)");
        return sb.toString();
    }
}
