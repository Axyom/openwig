package com.openwig.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Adds the bridge bootstrap to BitwigStudioMain.main without changing its control flow. */
public final class PatchMain {
    private PatchMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: PatchMain input.class output.class");
        }
        byte[] original = Files.readAllBytes(Path.of(args[0]));
        int major = ((original[6] & 0xff) << 8) | (original[7] & 0xff);
        if (major != 69) {
            throw new IllegalArgumentException("expected Java 25 class, found major " + major);
        }
        // Bitwig's bundled ASM rejects the Java 25 version header even though this
        // particular entry class uses bytecode it understands. Lower only the in-memory
        // header for parsing, then restore it after writing the modified class.
        original[6] = 0;
        original[7] = 65;
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] patched = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                MethodVisitor downstream = super.visitMethod(access, name, descriptor,
                        signature, exceptions);
                if (!name.equals("main") || !descriptor.equals("([Ljava/lang/String;)V")) {
                    return downstream;
                }
                patched[0]++;
                return new MethodVisitor(Opcodes.ASM9, downstream) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/openwig/bridge/Bootstrap", "start", "()V", false);
                    }
                };
            }
        }, 0);
        if (patched[0] != 1) {
            throw new IllegalStateException("expected one BitwigStudioMain.main, found " + patched[0]);
        }
        byte[] modified = writer.toByteArray();
        modified[6] = 0;
        modified[7] = 69;
        Files.write(Path.of(args[1]), modified);
    }
}
