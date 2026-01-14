package dev.sixik.asm;

import org.objectweb.asm.*;

import java.util.function.Consumer;

import static org.objectweb.asm.Opcodes.*;

public class AsmCtx {
    protected final MethodVisitor mv;
    protected final String ownerInternalName;

    // управление локалами
    protected int nextLocal;
    public final LocalsCache locals = new LocalsCache();
    public final FieldsCache fields = new FieldsCache();


    // где лежит текущий FunctionContext
    protected int currentContextVar;

    public AsmCtx(MethodVisitor mv, String ownerInternalName, int firstFreeLocal, int currentContextVar) {
        this.mv = mv;
        this.ownerInternalName = ownerInternalName;
        this.nextLocal = firstFreeLocal;
        this.currentContextVar = currentContextVar;
    }

    public MethodVisitor mv() {
        return mv;
    }

    // ---------- locals allocation ----------
    public int newLocalRef() {
        return nextLocal++;
    }

    public int newLocalInt() {
        return nextLocal++;
    }

    public int newLocalDouble() {
        int idx = nextLocal;
        nextLocal += 2;
        return idx;
    }

    // ---------- context var ----------
    public void setCurrentContextVar(int var) {
        this.currentContextVar = var;
    }

    public int getCurrentContextVar() {
        return currentContextVar;
    }

    public void loadContext() {
        mv.visitVarInsn(ALOAD, currentContextVar);
    }

    public void loadThis() {
        mv.visitVarInsn(ALOAD, 0);
    }

    // ---------- tiny emit helpers ----------
    public void aload(int var) {
        mv.visitVarInsn(ALOAD, var);
    }

    public void daload() {
        mv.visitInsn(DALOAD);
    }

    public void iload(int var) {
        mv.visitVarInsn(ILOAD, var);
    }

    public void dload(int var) {
        mv.visitVarInsn(DLOAD, var);
    }

    public void astore(int var) {
        mv.visitVarInsn(ASTORE, var);
    }

    public void istore(int var) {
        mv.visitVarInsn(ISTORE, var);
    }

    public void dstore(int var) {
        mv.visitVarInsn(DSTORE, var);
    }

    public void iconst(int v) {
        if (v >= -1 && v <= 5) mv.visitInsn(ICONST_0 + v);
        else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) mv.visitIntInsn(BIPUSH, v);
        else mv.visitIntInsn(SIPUSH, v);
    }

    public void insn(int opcode) {
        mv.visitInsn(opcode);
    }

    public void ldc(double v) {
        mv.visitLdcInsn(v);
    }

    public void ldc(Object v) {
        mv.visitLdcInsn(v);
    }

    public void getField(String name, String desc) {
        mv.visitFieldInsn(GETFIELD, ownerInternalName, name, desc);
    }

    public void putField(String name, String desc) {
        mv.visitFieldInsn(PUTFIELD, ownerInternalName, name, desc);
    }

    public void invokeInterface(String owner, String name, String desc) {
        mv.visitMethodInsn(INVOKEINTERFACE, owner, name, desc, true);
    }

    public void invokeVirtual(String owner, String name, String desc) {
        mv.visitMethodInsn(INVOKEVIRTUAL, owner, name, desc, false);
    }

    public void invokeStatic(String owner, String name, String desc) {
        mv.visitMethodInsn(INVOKESTATIC, owner, name, desc, false);
    }

    // ---------- loop helper ----------
    public void forIntRange(int iVar, Runnable loadLenOnStack, Consumer<Integer> body) {
        // int i = 0
        iconst(0);
        istore(iVar);

        Label start = new Label();
        Label end = new Label();
        mv.visitLabel(start);

        // if (i >= len) break
        iload(iVar);
        loadLenOnStack.run(); // must push len int
        mv.visitJumpInsn(IF_ICMPGE, end);

        body.accept(iVar);

        mv.visitIincInsn(iVar, 1);
        mv.visitJumpInsn(GOTO, start);
        mv.visitLabel(end);
    }
}