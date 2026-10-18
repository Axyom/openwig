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
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: PatchMain input.class output.class access.class undo.class");
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
        Files.write(Path.of(args[3]), undoAccess());
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
        method.visitVarInsn(Opcodes.ASTORE, 2);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitJumpInsn(Opcodes.IFNULL, absent);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "F", "getValue",
                "()Ljava/lang/Object;", true);
        method.visitTypeInsn(Opcodes.CHECKCAST, "java/util/List");
        method.visitVarInsn(Opcodes.ASTORE, 1);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitJumpInsn(Opcodes.IFNULL, absent);
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

        MethodVisitor createMain = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "createMainTrack", "(Ljava/lang/String;Ljava/lang/String;)I", null, null);
        createMain.visitCode();
        createMain.visitVarInsn(Opcodes.ALOAD, 0);
        createMain.visitVarInsn(Opcodes.ALOAD, 1);
        createMain.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "createMainTrack",
                "(Ljava/lang/String;Ljava/lang/String;)I", false);
        createMain.visitInsn(Opcodes.IRETURN);
        createMain.visitMaxs(0, 0);
        createMain.visitEnd();

        MethodVisitor deleteTrack = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "deleteTrack", "(I)I", null, null);
        deleteTrack.visitCode();
        deleteTrack.visitVarInsn(Opcodes.ILOAD, 0);
        deleteTrack.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "deleteTrack",
                "(I)I", false);
        deleteTrack.visitInsn(Opcodes.IRETURN);
        deleteTrack.visitMaxs(0, 0);
        deleteTrack.visitEnd();

        MethodVisitor clearMain = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "clearMainTracks", "()I", null, null);
        clearMain.visitCode();
        clearMain.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "clearMainTracks",
                "()I", false);
        clearMain.visitInsn(Opcodes.IRETURN);
        clearMain.visitMaxs(0, 0);
        clearMain.visitEnd();

        MethodVisitor effectCount = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "effectTrackCount", "()I", null, null);
        effectCount.visitCode();
        effectCount.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "effectTrackCount",
                "()I", false);
        effectCount.visitInsn(Opcodes.IRETURN);
        effectCount.visitMaxs(0, 0);
        effectCount.visitEnd();

        MethodVisitor createEffect = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "createEffectTrack", "(Ljava/lang/String;)I", null, null);
        createEffect.visitCode();
        createEffect.visitVarInsn(Opcodes.ALOAD, 0);
        createEffect.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "createEffectTrack",
                "(Ljava/lang/String;)I", false);
        createEffect.visitInsn(Opcodes.IRETURN);
        createEffect.visitMaxs(0, 0);
        createEffect.visitEnd();

        MethodVisitor deleteEffect = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "deleteEffectTrack", "(I)I", null, null);
        deleteEffect.visitCode();
        deleteEffect.visitVarInsn(Opcodes.ILOAD, 0);
        deleteEffect.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "deleteEffectTrack",
                "(I)I", false);
        deleteEffect.visitInsn(Opcodes.IRETURN);
        deleteEffect.visitMaxs(0, 0);
        deleteEffect.visitEnd();

        MethodVisitor clearEffect = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "clearEffectTracks", "()I", null, null);
        clearEffect.visitCode();
        clearEffect.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "clearEffectTracks",
                "()I", false);
        clearEffect.visitInsn(Opcodes.IRETURN);
        clearEffect.visitMaxs(0, 0);
        clearEffect.visitEnd();

        MethodVisitor removeEffect = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "removeEffectTrack", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        removeEffect.visitCode();
        removeEffect.visitVarInsn(Opcodes.ALOAD, 0);
        removeEffect.visitTypeInsn(Opcodes.CHECKCAST,
                "com/bitwig/flt/document/core/master/Yko");
        removeEffect.visitVarInsn(Opcodes.ALOAD, 1);
        removeEffect.visitTypeInsn(Opcodes.CHECKCAST, "roo");
        removeEffect.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/core/master/Yko", "Gcr", "(Lroo;)V", false);
        removeEffect.visitInsn(Opcodes.RETURN);
        removeEffect.visitMaxs(0, 0);
        removeEffect.visitEnd();

        MethodVisitor masterCount = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "masterDeviceCount", "()I", null, null);
        masterCount.visitCode();
        masterCount.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "masterDeviceCount",
                "()I", false);
        masterCount.visitInsn(Opcodes.IRETURN);
        masterCount.visitMaxs(0, 0);
        masterCount.visitEnd();

        MethodVisitor insertMaster = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "insertMasterFactoryDevice", "(Ljava/lang/String;)I", null, null);
        insertMaster.visitCode();
        insertMaster.visitVarInsn(Opcodes.ALOAD, 0);
        insertMaster.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "insertMasterFactoryDevice",
                "(Ljava/lang/String;)I", false);
        insertMaster.visitInsn(Opcodes.IRETURN);
        insertMaster.visitMaxs(0, 0);
        insertMaster.visitEnd();

        MethodVisitor clearMaster = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "clearMasterDevices", "()I", null, null);
        clearMaster.visitCode();
        clearMaster.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "clearMasterDevices",
                "()I", false);
        clearMaster.visitInsn(Opcodes.IRETURN);
        clearMaster.visitMaxs(0, 0);
        clearMaster.visitEnd();

        MethodVisitor clearProject = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "clearProject", "()Lorg/json/JSONObject;", null, null);
        clearProject.visitCode();
        clearProject.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "clearProject",
                "()Lorg/json/JSONObject;", false);
        clearProject.visitInsn(Opcodes.ARETURN);
        clearProject.visitMaxs(0, 0);
        clearProject.visitEnd();

        MethodVisitor removeDevice = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "removeDevice", "(Ljava/lang/Object;Ljava/lang/Object;)V", null, null);
        removeDevice.visitCode();
        removeDevice.visitVarInsn(Opcodes.ALOAD, 0);
        removeDevice.visitTypeInsn(Opcodes.CHECKCAST,
                "com/bitwig/flt/document/api/document/ApiDeviceChain");
        removeDevice.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiDeviceChain", "getTarget",
                "()Ljava/lang/Object;", false);
        removeDevice.visitTypeInsn(Opcodes.CHECKCAST,
                "com/bitwig/flt/document/core/master/device/Wju");
        removeDevice.visitVarInsn(Opcodes.ALOAD, 1);
        removeDevice.visitTypeInsn(Opcodes.CHECKCAST,
                "com/bitwig/flt/document/api/document/ApiDevice");
        removeDevice.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiDevice", "getTarget",
                "()Ljava/lang/Object;", false);
        removeDevice.visitTypeInsn(Opcodes.CHECKCAST,
                "com/bitwig/flt/document/core/master/device/Wcl");
        removeDevice.visitInsn(Opcodes.ICONST_1);
        removeDevice.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/core/master/device/Wju", "IYM",
                "(Lcom/bitwig/flt/document/core/master/device/Wcl;Z)V", false);
        removeDevice.visitInsn(Opcodes.RETURN);
        removeDevice.visitMaxs(0, 0);
        removeDevice.visitEnd();

        MethodVisitor setParams = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "setDeviceParameters", "(IILorg/json/JSONObject;Ljava/lang/String;)Lorg/json/JSONObject;", null, null);
        setParams.visitCode();
        setParams.visitVarInsn(Opcodes.ILOAD, 0);
        setParams.visitVarInsn(Opcodes.ILOAD, 1);
        setParams.visitVarInsn(Opcodes.ALOAD, 2);
        setParams.visitVarInsn(Opcodes.ALOAD, 3);
        setParams.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "setDeviceParameters",
                "(IILorg/json/JSONObject;Ljava/lang/String;)Lorg/json/JSONObject;", false);
        setParams.visitInsn(Opcodes.ARETURN);
        setParams.visitMaxs(0, 0);
        setParams.visitEnd();

        MethodVisitor getParams = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "getDeviceParameters", "(IILorg/json/JSONArray;Ljava/lang/String;)Lorg/json/JSONObject;", null, null);
        getParams.visitCode();
        getParams.visitVarInsn(Opcodes.ILOAD, 0);
        getParams.visitVarInsn(Opcodes.ILOAD, 1);
        getParams.visitVarInsn(Opcodes.ALOAD, 2);
        getParams.visitVarInsn(Opcodes.ALOAD, 3);
        getParams.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "getDeviceParameters",
                "(IILorg/json/JSONArray;Ljava/lang/String;)Lorg/json/JSONObject;", false);
        getParams.visitInsn(Opcodes.ARETURN);
        getParams.visitMaxs(0, 0);
        getParams.visitEnd();

        MethodVisitor renameDev = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "renameDevice", "(IILjava/lang/String;)Lorg/json/JSONObject;", null, null);
        renameDev.visitCode();
        renameDev.visitVarInsn(Opcodes.ILOAD, 0);
        renameDev.visitVarInsn(Opcodes.ILOAD, 1);
        renameDev.visitVarInsn(Opcodes.ALOAD, 2);
        renameDev.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "renameDevice",
                "(IILjava/lang/String;)Lorg/json/JSONObject;", false);
        renameDev.visitInsn(Opcodes.ARETURN);
        renameDev.visitMaxs(0, 0);
        renameDev.visitEnd();

        MethodVisitor expandDev = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "setDeviceExpanded", "(IIZ)Lorg/json/JSONObject;", null, null);
        expandDev.visitCode();
        expandDev.visitVarInsn(Opcodes.ILOAD, 0);
        expandDev.visitVarInsn(Opcodes.ILOAD, 1);
        expandDev.visitVarInsn(Opcodes.ILOAD, 2);
        expandDev.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "setDeviceExpanded",
                "(IIZ)Lorg/json/JSONObject;", false);
        expandDev.visitInsn(Opcodes.ARETURN);
        expandDev.visitMaxs(0, 0);
        expandDev.visitEnd();

        MethodVisitor deleteDev = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "deleteTrackDevice", "(II)I", null, null);
        deleteDev.visitCode();
        deleteDev.visitVarInsn(Opcodes.ILOAD, 0);
        deleteDev.visitVarInsn(Opcodes.ILOAD, 1);
        deleteDev.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "deleteTrackDevice",
                "(II)I", false);
        deleteDev.visitInsn(Opcodes.IRETURN);
        deleteDev.visitMaxs(0, 0);
        deleteDev.visitEnd();

        MethodVisitor listParams = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "listDeviceParameters", "(II)Lorg/json/JSONArray;", null, null);
        listParams.visitCode();
        listParams.visitVarInsn(Opcodes.ILOAD, 0);
        listParams.visitVarInsn(Opcodes.ILOAD, 1);
        listParams.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "listDeviceParameters",
                "(II)Lorg/json/JSONArray;", false);
        listParams.visitInsn(Opcodes.ARETURN);
        listParams.visitMaxs(0, 0);
        listParams.visitEnd();

        MethodVisitor trackSnapshot = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "trackSnapshot", "(I)Lorg/json/JSONObject;", null, null);
        trackSnapshot.visitCode();
        trackSnapshot.visitVarInsn(Opcodes.ILOAD, 0);
        trackSnapshot.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "trackSnapshot",
                "(I)Lorg/json/JSONObject;", false);
        trackSnapshot.visitInsn(Opcodes.ARETURN);
        trackSnapshot.visitMaxs(0, 0);
        trackSnapshot.visitEnd();

        MethodVisitor readObject = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "readObject", "(Ljava/lang/String;Ljava/lang/String;I)Lorg/json/JSONObject;", null, null);
        readObject.visitCode();
        readObject.visitVarInsn(Opcodes.ALOAD, 0);
        readObject.visitVarInsn(Opcodes.ALOAD, 1);
        readObject.visitVarInsn(Opcodes.ILOAD, 2);
        readObject.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "readObject",
                "(Ljava/lang/String;Ljava/lang/String;I)Lorg/json/JSONObject;", false);
        readObject.visitInsn(Opcodes.ARETURN);
        readObject.visitMaxs(0, 0);
        readObject.visitEnd();

        MethodVisitor enumeratePath = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "automationEnumeratePath", "(Ljava/lang/String;)Lorg/json/JSONArray;", null, null);
        enumeratePath.visitCode();
        enumeratePath.visitVarInsn(Opcodes.ALOAD, 0);
        enumeratePath.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "automationEnumerate",
                "(Ljava/lang/String;)Lorg/json/JSONArray;", false);
        enumeratePath.visitInsn(Opcodes.ARETURN);
        enumeratePath.visitMaxs(0, 0);
        enumeratePath.visitEnd();

        MethodVisitor dumpProject = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "dumpProject", "()Lorg/json/JSONObject;", null, null);
        dumpProject.visitCode();
        dumpProject.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "dumpProject",
                "()Lorg/json/JSONObject;", false);
        dumpProject.visitInsn(Opcodes.ARETURN);
        dumpProject.visitMaxs(0, 0);
        dumpProject.visitEnd();

        MethodVisitor exportDaw = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "exportDawProject", "(Ljava/lang/String;Z)Lorg/json/JSONObject;", null, null);
        exportDaw.visitCode();
        exportDaw.visitVarInsn(Opcodes.ALOAD, 0);
        exportDaw.visitVarInsn(Opcodes.ILOAD, 1);
        exportDaw.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "exportDawProject",
                "(Ljava/lang/String;Z)Lorg/json/JSONObject;", false);
        exportDaw.visitInsn(Opcodes.ARETURN);
        exportDaw.visitMaxs(0, 0);
        exportDaw.visitEnd();

        MethodVisitor automationEnumerate = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "automationEnumerate", "(I)Lorg/json/JSONArray;", null, null);
        automationEnumerate.visitCode();
        automationEnumerate.visitVarInsn(Opcodes.ILOAD, 0);
        automationEnumerate.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument",
                "automationEnumerate", "(I)Lorg/json/JSONArray;", false);
        automationEnumerate.visitInsn(Opcodes.ARETURN);
        automationEnumerate.visitMaxs(0, 0);
        automationEnumerate.visitEnd();

        MethodVisitor updateTrack = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "updateTrack", "(ILjava/lang/String;Lorg/json/JSONObject;)Lorg/json/JSONObject;",
                null, null);
        updateTrack.visitCode();
        updateTrack.visitVarInsn(Opcodes.ILOAD, 0);
        updateTrack.visitVarInsn(Opcodes.ALOAD, 1);
        updateTrack.visitVarInsn(Opcodes.ALOAD, 2);
        updateTrack.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "updateTrack",
                "(ILjava/lang/String;Lorg/json/JSONObject;)Lorg/json/JSONObject;", false);
        updateTrack.visitInsn(Opcodes.ARETURN);
        updateTrack.visitMaxs(0, 0);
        updateTrack.visitEnd();

        MethodVisitor active = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "activeDocumentObject", "()Ljava/lang/Object;", null, null);
        active.visitCode();
        active.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "currentDocument",
                "()Ljava/lang/Object;", false);
        active.visitInsn(Opcodes.ARETURN);
        active.visitMaxs(0, 0);
        active.visitEnd();

        MethodVisitor clipCount = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "clipCount", "(I)I", null, null);
        clipCount.visitCode();
        clipCount.visitVarInsn(Opcodes.ILOAD, 0);
        clipCount.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "clipCount",
                "(I)I", false);
        clipCount.visitInsn(Opcodes.IRETURN);
        clipCount.visitMaxs(0, 0);
        clipCount.visitEnd();

        MethodVisitor noteCount = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "noteCount", "(II)I", null, null);
        noteCount.visitCode();
        noteCount.visitVarInsn(Opcodes.ILOAD, 0);
        noteCount.visitVarInsn(Opcodes.ILOAD, 1);
        noteCount.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "noteCount",
                "(II)I", false);
        noteCount.visitInsn(Opcodes.IRETURN);
        noteCount.visitMaxs(0, 0);
        noteCount.visitEnd();

        MethodVisitor deviceId = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "factoryDeviceId", "(Ljava/lang/String;)Ljava/util/UUID;", null, null);
        deviceId.visitCode();
        deviceId.visitVarInsn(Opcodes.ALOAD, 0);
        deviceId.visitMethodInsn(Opcodes.INVOKESTATIC,
                "com/bitwig/flt/packaging/core/MD", "IYM",
                "(Ljava/lang/String;)Ljava/util/UUID;", false);
        deviceId.visitInsn(Opcodes.ARETURN);
        deviceId.visitMaxs(0, 0);
        deviceId.visitEnd();

        MethodVisitor deviceCount = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "deviceCount", "(I)I", null, null);
        deviceCount.visitCode();
        deviceCount.visitVarInsn(Opcodes.ILOAD, 0);
        deviceCount.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "deviceCount",
                "(I)I", false);
        deviceCount.visitInsn(Opcodes.IRETURN);
        deviceCount.visitMaxs(0, 0);
        deviceCount.visitEnd();

        MethodVisitor deviceSnapshot = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "deviceSnapshot", "(I)Lorg/json/JSONArray;", null, null);
        deviceSnapshot.visitCode();
        deviceSnapshot.visitVarInsn(Opcodes.ILOAD, 0);
        deviceSnapshot.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "deviceSnapshot",
                "(I)Lorg/json/JSONArray;", false);
        deviceSnapshot.visitInsn(Opcodes.ARETURN);
        deviceSnapshot.visitMaxs(0, 0);
        deviceSnapshot.visitEnd();

        MethodVisitor deviceEnabled = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "setDeviceEnabled", "(IIZ)Lorg/json/JSONObject;", null, null);
        deviceEnabled.visitCode();
        deviceEnabled.visitVarInsn(Opcodes.ILOAD, 0);
        deviceEnabled.visitVarInsn(Opcodes.ILOAD, 1);
        deviceEnabled.visitVarInsn(Opcodes.ILOAD, 2);
        deviceEnabled.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "setDeviceEnabled",
                "(IIZ)Lorg/json/JSONObject;", false);
        deviceEnabled.visitInsn(Opcodes.ARETURN);
        deviceEnabled.visitMaxs(0, 0);
        deviceEnabled.visitEnd();

        MethodVisitor insertDevice = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "insertFactoryDevice", "(ILjava/lang/String;)I", null, null);
        insertDevice.visitCode();
        insertDevice.visitVarInsn(Opcodes.ILOAD, 0);
        insertDevice.visitVarInsn(Opcodes.ALOAD, 1);
        insertDevice.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "insertFactoryDevice",
                "(ILjava/lang/String;)I", false);
        insertDevice.visitInsn(Opcodes.IRETURN);
        insertDevice.visitMaxs(0, 0);
        insertDevice.visitEnd();

        MethodVisitor undo = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "runInUndo", "(Ljava/lang/Object;Ljava/lang/Runnable;)V", null, null);
        undo.visitCode();
        undo.visitVarInsn(Opcodes.ALOAD, 0);
        undo.visitVarInsn(Opcodes.ALOAD, 1);
        undo.visitMethodInsn(Opcodes.INVOKESTATIC, "com/bitwig/ramona/core/OpenwigUndo",
                "run", "(Ljava/lang/Object;Ljava/lang/Runnable;)V", false);
        undo.visitInsn(Opcodes.RETURN);
        undo.visitMaxs(0, 0);
        undo.visitEnd();

        MethodVisitor addClip = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "addNoteClip", "(IDDLorg/json/JSONArray;)I", null, null);
        addClip.visitCode();
        addClip.visitVarInsn(Opcodes.ILOAD, 0);
        addClip.visitVarInsn(Opcodes.DLOAD, 1);
        addClip.visitVarInsn(Opcodes.DLOAD, 3);
        addClip.visitVarInsn(Opcodes.ALOAD, 5);
        addClip.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "addNoteClip",
                "(IDDLorg/json/JSONArray;)I", false);
        addClip.visitInsn(Opcodes.IRETURN);
        addClip.visitMaxs(0, 0);
        addClip.visitEnd();
        MethodVisitor automation = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "automation", "(Lorg/json/JSONObject;Z)Lorg/json/JSONObject;", null, null);
        automation.visitCode();
        automation.visitVarInsn(Opcodes.ALOAD, 0);
        automation.visitVarInsn(Opcodes.ILOAD, 1);
        automation.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "automation",
                "(Lorg/json/JSONObject;Z)Lorg/json/JSONObject;", false);
        automation.visitInsn(Opcodes.ARETURN);
        automation.visitMaxs(0, 0);
        automation.visitEnd();

        MethodVisitor transportObject = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "activeTransportObject", "()Ljava/lang/Object;", null, null);
        transportObject.visitCode();
        transportObject.visitMethodInsn(Opcodes.INVOKESTATIC, "com/openwig/bridge/DirectAccess",
                "activeDocumentObject", "()Ljava/lang/Object;", false);
        transportObject.visitTypeInsn(Opcodes.CHECKCAST, "com/bitwig/flt/document/core/iface/IPw");
        transportObject.visitMethodInsn(Opcodes.INVOKEINTERFACE,
                "com/bitwig/flt/document/core/iface/IPw", "ONV", "()LSsT;", true);
        transportObject.visitInsn(Opcodes.ARETURN);
        transportObject.visitMaxs(0, 0);
        transportObject.visitEnd();

        MethodVisitor transport = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "transport", "(Ljava/lang/String;Lorg/json/JSONObject;)Lorg/json/JSONObject;", null, null);
        transport.visitCode();
        transport.visitVarInsn(Opcodes.ALOAD, 0);
        transport.visitVarInsn(Opcodes.ALOAD, 1);
        transport.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigTransport", "execute",
                "(Ljava/lang/String;Lorg/json/JSONObject;)Lorg/json/JSONObject;", false);
        transport.visitInsn(Opcodes.ARETURN);
        transport.visitMaxs(0, 0);
        transport.visitEnd();

        MethodVisitor execute = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "execute", "(Ljava/lang/String;Lorg/json/JSONObject;)Lorg/json/JSONObject;", null, null);
        execute.visitCode();
        execute.visitVarInsn(Opcodes.ALOAD, 0);
        execute.visitVarInsn(Opcodes.ALOAD, 1);
        execute.visitMethodInsn(Opcodes.INVOKESTATIC, "OpenwigDocument", "execute",
                "(Ljava/lang/String;Lorg/json/JSONObject;)Lorg/json/JSONObject;", false);
        execute.visitInsn(Opcodes.ARETURN);
        execute.visitMaxs(0, 0);
        execute.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] undoAccess() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_5, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                "com/bitwig/ramona/core/OpenwigUndo", null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "run", "(Ljava/lang/Object;Ljava/lang/Runnable;)V", null, null);
        method.visitCode();
        method.visitVarInsn(Opcodes.ALOAD, 0);
        method.visitTypeInsn(Opcodes.CHECKCAST,
                "com/bitwig/flt/document/api/document/ApiObject");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                "com/bitwig/flt/document/api/document/ApiObject", "getTarget",
                "()Ljava/lang/Object;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/bitwig/ramona/core/cq");
        method.visitVarInsn(Opcodes.ASTORE, 2);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitLdcInsn("Insert Clip");
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitTypeInsn(Opcodes.INSTANCEOF, "AzF");
        Label noLock = new Label();
        Label callUndo = new Label();
        method.visitJumpInsn(Opcodes.IFEQ, noLock);
        method.visitInsn(Opcodes.ICONST_1);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "com/bitwig/ramona/core/TWT");
        method.visitInsn(Opcodes.DUP);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitTypeInsn(Opcodes.CHECKCAST, "AzF");
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "AzF", "Ihe",
                "()Lcom/bitwig/ramona/core/TWT;", false);
        method.visitInsn(Opcodes.AASTORE);
        method.visitJumpInsn(Opcodes.GOTO, callUndo);
        method.visitLabel(noLock);
        method.visitInsn(Opcodes.ICONST_0);
        method.visitTypeInsn(Opcodes.ANEWARRAY, "com/bitwig/ramona/core/TWT");
        method.visitLabel(callUndo);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/ramona/core/cq",
                "IYM", "(Ljava/lang/String;[Lcom/bitwig/ramona/core/TWT;)Z", false);
        Label started = new Label();
        method.visitJumpInsn(Opcodes.IFNE, started);
        method.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        method.visitInsn(Opcodes.DUP);
        method.visitLdcInsn("Bitwig refused clip undo transaction");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException",
                "<init>", "(Ljava/lang/String;)V", false);
        method.visitInsn(Opcodes.ATHROW);
        method.visitLabel(started);
        Label tryStart = new Label();
        Label tryEnd = new Label();
        Label catchAll = new Label();
        method.visitTryCatchBlock(tryStart, tryEnd, catchAll, null);
        method.visitLabel(tryStart);
        method.visitVarInsn(Opcodes.ALOAD, 1);
        method.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
        method.visitLabel(tryEnd);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/ramona/core/cq",
                "LTl", "()Z", false);
        method.visitInsn(Opcodes.POP);
        method.visitInsn(Opcodes.RETURN);
        method.visitLabel(catchAll);
        method.visitVarInsn(Opcodes.ASTORE, 3);
        method.visitVarInsn(Opcodes.ALOAD, 2);
        method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/bitwig/ramona/core/cq",
                "LTl", "()Z", false);
        method.visitInsn(Opcodes.POP);
        method.visitVarInsn(Opcodes.ALOAD, 3);
        method.visitInsn(Opcodes.ATHROW);
        method.visitMaxs(0, 0);
        method.visitEnd();


        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void emitNewProject(MethodVisitor method) {
        method.visitTypeInsn(Opcodes.NEW, "com/bitwig/flt/document/api/document/ApiProject");
        method.visitInsn(Opcodes.DUP);
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/openwig/bridge/DirectAccess", "activeDocumentObject",
                "()Ljava/lang/Object;", false);
        method.visitTypeInsn(Opcodes.CHECKCAST, "com/bitwig/flt/document/core/master/oZU");
        method.visitMethodInsn(Opcodes.INVOKESPECIAL,
                "com/bitwig/flt/document/api/document/ApiProject", "<init>",
                "(Lcom/bitwig/flt/document/core/master/oZU;)V", false);
    }
}
