package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Base type for immutable Molang IR nodes.
 *
 * <p>Nodes now carry no per-compilation bookkeeping (reference counts, assigned locals,
 * or initialization state). That information lives entirely inside {@link CompilerContext},
 * which owns the compilation process and manages evaluation caching.</p>
 *
 * <p>Subclasses should expose only the structural data required to describe the computation and
 * rely on {@link CompilerContext} for traversal, optimisation, and code generation orchestration.</p>
 */
public interface IR {
    static void pushFloat(MethodVisitor mv, float value) {
        if (value == 0.0f) {
            mv.visitInsn(Opcodes.FCONST_0);
        } else if (value == 1.0f) {
            mv.visitInsn(Opcodes.FCONST_1);
        } else if (value == 2.0f) {
            mv.visitInsn(Opcodes.FCONST_2);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    static void pushInt(MethodVisitor mv, int value) {
        if (value == -1) {
            mv.visitInsn(Opcodes.ICONST_M1);
        } else if (value >= 0 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE && value >= Byte.MIN_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value <= Short.MAX_VALUE && value >= Short.MIN_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    void emit(MethodVisitor mv, CompilerContext ctx);

    void collectAccessors(CompilerContext ctx);
}
