package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.Compiler;
import dev.omega.arcane.compiler.CompilerContext;
import dev.omega.arcane.random.MolangRandomSource;
import dev.omega.arcane.reference.FloatAccessor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Emits a random float read without per-evaluation caching.
 */
public record RandomSourceIR(FloatAccessor<?> accessor, Object target, int accessorIndex) implements IR {
    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        if (accessorIndex < 0) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, Compiler.RANDOM_METHOD_OWNER, "nextRandomFloat", "()F", false);
            return;
        }

        var info = ctx.accessorInfos.get(accessorIndex);
        if (info != null && info.isSpecialized) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "accessor$" + accessorIndex,
                    "L" + Compiler.FLOAT_ACCESSOR_INTERNAL + ";");

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "target$" + accessorIndex,
                    "Ljava/lang/Object;");

            if (info.targetClass != null && !info.targetClass.equals("java/lang/Object")) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, info.targetClass);
            }

            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Compiler.FLOAT_ACCESSOR_INTERNAL, "apply",
                    "(Ljava/lang/Object;)F", true);
            return;
        }

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "accessors", "[L" + Compiler.FLOAT_ACCESSOR_INTERNAL + ";");
        IR.pushInt(mv, accessorIndex);
        mv.visitInsn(Opcodes.AALOAD);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "targets", "[Ljava/lang/Object;");
        IR.pushInt(mv, accessorIndex);
        mv.visitInsn(Opcodes.AALOAD);

        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Compiler.FLOAT_ACCESSOR_INTERNAL, "apply",
                "(Ljava/lang/Object;)F", true);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        if (accessor != null && target instanceof MolangRandomSource) {
            ctx.registerAccessor(accessor, target);
        }
    }
}
