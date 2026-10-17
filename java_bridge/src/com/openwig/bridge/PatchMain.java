package com.openwig.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Adds the bridge bootstrap to BitwigStudioMain.main without changing its control flow. */
public final class PatchMain {
    private PatchMain() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("usage: PatchMain input.class output.class access.class");
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
        Files.write(Path.of(args[2]), directAccess());
    }

    /**
     * Compile a tiny, exact-build adapter without loading Java 25 Bitwig classes
     * into the Java 21 compiler. Every call below is a direct JVM invocation;
     * no reflection or method-name lookup occurs at runtime.
     */
    private static byte[] directAccess() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "com/openwig/bridge/DirectAccess", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "hasActiveDocument", "()Z", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/bitwig/flt/app/eY1", "ncg",
                "()Lcom/bitwig/flt/app/eY1;", false);
        Label absent = new Label();
        method.visitVarInsn(Opcodes.ASTORE, 0);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitJumpInsn(Opcodes.IFNULL, absent);
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/flt/app/eY1", "Lg",
                "()LF;", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "F", "getValue",
                "()Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "isEmpty",
                "()Z", true);
        method.visitJumpInsn(Opcodes.IFNE, absent);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "aBx");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "aBx", "wtf",
                "()Lcom/bitwig/flt/document/core/iface/IPw;", false);
        method.visitJumpInsn(Opcodes.IFNULL, absent);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitInsn(Opcodes.IRETURN);
        method.visitLabel(absent);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();

        MethodVisitor tempo = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "readTempo", "()D", null, null);
        tempo.visitCode();
        tempo.visitTypeInsn(Opcodes.NEW, "com/bitwig/flt/document/api/document/ApiTransport");
        tempo.visitInsn(Opcodes.DUP);
        tempo.visitMethodInsn(Opcodes.INVOKESTATIC, "com/bitwig/flt/app/eY1", "ncg",
                "()Lcom/bitwig/flt/app/eY1;", false);
        tempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/flt/app/eY1", "Lg",
                "()LF;", false);
        tempo.visitMethodInsn(Opcodes.INVOKEINTERFACE, "F", "getValue",
                "()Ljava/lang/Object;", true);
        tempo.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
        tempo.visitInsn(Opcodes.ICONST_0);
        tempo.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        tempo.visitTypeInsn(Opcodes.CHECKCAST, "aBx");
        tempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "aBx", "wtf",
                "()Lcom/bitwig/flt/document/core/iface/IPw;", false);
        tempo.visitTypeInsn(Opcodes.CHECKCAST, "com/bitwig/flt/document/core/master/oZU");
        tempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/core/master/oZU", "THS", "()LAvn;", false);
        tempo.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/bitwig/flt/document/api/document/ApiTransport", "<init>", "(LAvn;)V", false);
        tempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiTransport", "getTempo",
                "()Lcom/bitwig/extension/api/project/parameter/DoubleParameter;", false);
        tempo.visitFieldInsn(Opcodes.GETSTATIC,
                "com/bitwig/extension/api/project/parameter/Unit", "BPM",
                "Lcom/bitwig/extension/api/project/parameter/Unit;");
        tempo.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/bitwig/extension/api/project/parameter/DoubleParameter", "getValue",
                "(Lcom/bitwig/extension/api/project/parameter/Unit;)D", true);
        tempo.visitInsn(Opcodes.DRETURN);
        tempo.visitMaxs(0, 0);
        tempo.visitEnd();

        MethodVisitor setTempo = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "writeTempo", "(D)V", null, null);
        setTempo.visitCode();
        setTempo.visitTypeInsn(Opcodes.NEW, "com/bitwig/flt/document/api/document/ApiTransport");
        setTempo.visitInsn(Opcodes.DUP);
        setTempo.visitMethodInsn(Opcodes.INVOKESTATIC, "com/bitwig/flt/app/eY1", "ncg",
                "()Lcom/bitwig/flt/app/eY1;", false);
        setTempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/flt/app/eY1", "Lg",
                "()LF;", false);
        setTempo.visitMethodInsn(Opcodes.INVOKEINTERFACE, "F", "getValue",
                "()Ljava/lang/Object;", true);
        setTempo.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
        setTempo.visitInsn(Opcodes.ICONST_0);
        setTempo.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        setTempo.visitTypeInsn(Opcodes.CHECKCAST, "aBx");
        setTempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "aBx", "wtf",
                "()Lcom/bitwig/flt/document/core/iface/IPw;", false);
        setTempo.visitTypeInsn(Opcodes.CHECKCAST, "com/bitwig/flt/document/core/master/oZU");
        setTempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/core/master/oZU", "THS", "()LAvn;", false);
        setTempo.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/bitwig/flt/document/api/document/ApiTransport", "<init>", "(LAvn;)V", false);
        setTempo.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiTransport", "getTempo",
                "()Lcom/bitwig/extension/api/project/parameter/DoubleParameter;", false);
        setTempo.visitVarInsn(Opcodes.DLOAD, 0);
        setTempo.visitFieldInsn(Opcodes.GETSTATIC,
                "com/bitwig/extension/api/project/parameter/Unit", "BPM",
                "Lcom/bitwig/extension/api/project/parameter/Unit;");
        setTempo.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/bitwig/extension/api/project/parameter/DoubleParameter", "setValue",
                "(DLcom/bitwig/extension/api/project/parameter/Unit;)V", true);
        setTempo.visitInsn(Opcodes.RETURN);
        setTempo.visitMaxs(0, 0);
        setTempo.visitEnd();

        MethodVisitor dispatch = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "dispatch", "(Ljava/lang/Runnable;)V", null, null);
        dispatch.visitCode();
        dispatch.visitMethodInsn(Opcodes.INVOKESTATIC, "com/bitwig/flt/app/eY1", "ncg",
                "()Lcom/bitwig/flt/app/eY1;", false);
        dispatch.visitVarInsn(Opcodes.ALOAD, 0);
        dispatch.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/flt/app/eY1",
                "asyncExec", "(Ljava/lang/Runnable;)V", false);
        dispatch.visitInsn(Opcodes.RETURN);
        dispatch.visitMaxs(0, 0);
        dispatch.visitEnd();

        MethodVisitor count = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "mainTrackCount", "()I", null, null);
        count.visitCode();
        emitNewProject(count);
        count.visitVarInsn(Opcodes.ASTORE, 0);
        count.visitVarInsn(Opcodes.ALOAD, 0);
        count.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiProject", "getTrackGroup",
                "()Lcom/bitwig/extension/api/project/track/TrackGroup;", false);
        count.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/bitwig/extension/api/project/track/TrackGroup", "mainTracks",
                "()Ljava/util/List;", true);
        count.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "size", "()I", true);
        count.visitVarInsn(Opcodes.ISTORE, 1);
        count.visitVarInsn(Opcodes.ALOAD, 0);
        count.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiProject", "dispose", "()V", false);
        count.visitVarInsn(Opcodes.ILOAD, 1);
        count.visitInsn(Opcodes.IRETURN);
        count.visitMaxs(0, 0);
        count.visitEnd();

        MethodVisitor create = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "createInstrumentTrack", "(Ljava/lang/String;)I", null, null);
        create.visitCode();
        emitNewProject(create);
        create.visitVarInsn(Opcodes.ASTORE, 1);
        create.visitVarInsn(Opcodes.ALOAD, 1);
        create.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiProject", "getFactory",
                "()Lcom/bitwig/extension/api/project/Factory;", false);
        create.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/bitwig/extension/api/project/Factory", "createInstrumentTrack",
                "()Lcom/bitwig/extension/api/project/track/Track;", true);
        create.visitVarInsn(Opcodes.ASTORE, 2);
        create.visitVarInsn(Opcodes.ALOAD, 2);
        create.visitVarInsn(Opcodes.ALOAD, 0);
        create.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/bitwig/extension/api/project/Titled", "setTitle",
                "(Ljava/lang/String;)V", true);
        create.visitVarInsn(Opcodes.ALOAD, 1);
        create.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiProject", "getTrackGroup",
                "()Lcom/bitwig/extension/api/project/track/TrackGroup;", false);
        create.visitVarInsn(Opcodes.ALOAD, 2);
        create.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/bitwig/extension/api/project/track/TrackGroup", "addMainTrack",
                "(Lcom/bitwig/extension/api/project/track/TrackOrTrackGroup;)V", true);
        create.visitVarInsn(Opcodes.ALOAD, 1);
        create.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiProject", "dispose", "()V", false);
        create.visitMethodInsn(Opcodes.INVOKESTATIC, "com/openwig/bridge/DirectAccess",
                "mainTrackCount", "()I", false);
        create.visitInsn(Opcodes.IRETURN);
        create.visitMaxs(0, 0);
        create.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitNewProject(MethodVisitor method) {
        method.visitTypeInsn(Opcodes.NEW, "com/bitwig/flt/document/api/document/ApiProject");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/bitwig/flt/app/eY1", "ncg",
                "()Lcom/bitwig/flt/app/eY1;", false);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/flt/app/eY1", "Lg",
                "()LF;", false);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "F", "getValue",
                "()Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
        method.visitInsn(Opcodes.ICONST_0);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/List", "get",
                "(I)Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "aBx");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "aBx", "wtf",
                "()Lcom/bitwig/flt/document/core/iface/IPw;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/bitwig/flt/document/core/master/oZU");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/bitwig/flt/document/api/document/ApiProject", "<init>",
                "(Lcom/bitwig/flt/document/core/master/oZU;)V", false);
    }
}
