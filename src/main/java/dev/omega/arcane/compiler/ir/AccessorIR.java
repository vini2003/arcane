package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.Compiler;
import dev.omega.arcane.compiler.CompilerContext;
import dev.omega.arcane.reference.FloatAccessor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node that reads a bound {@link dev.omega.arcane.reference.FloatAccessor} from its target object.
 *
 * <p><b>Purpose</b>: models {@code accessor.apply(target)} as part of the expression graph and emits bytecode
 * that retrieves the floating-point value efficiently, with two key optimizations:</p>
 * <ol>
 *   <li><b>Per-evaluation value caching</b>: the first time an accessor value is computed during a single
 *       {@code evaluate()} call, the result is stored into a method-local slot and remembered in
 *       {@link CompilerContext#accessorValueLocals}. Any subsequent uses of the same accessor
 *       index are compiled as a simple {@code FLOAD}, avoiding repeated interface dispatch.</li>
 *   <li><b>Field specialization</b>: for each distinct accessor/target pair registered via
 *       {@link CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)},
 *       the compiler records an {@code AccessorInfo}. When the accessor is specialized, the generated class owns
 *       private final fields {@code accessor$<index>} and {@code target$<index>}. Emission then loads from these
 *       fields instead of indexing shared arrays, reducing array traffic and enabling a precise {@code CHECKCAST}
 *       to the concrete target class if known.</li>
 * </ol>
 *
 * <p><b>Emission strategy</b> (<em>first-use</em> vs <em>repeat-use</em>):</p>
 * <ul>
 *   <li><b>Repeat-use</b>: if {@link CompilerContext#accessorValueLocals} already contains a local for
 *       {@code accessorIndex}, the node emits {@code FLOAD local} and returns.</li>
 *   <li><b>First-use (specialized)</b>: load {@code this.accessor$<index>} and {@code this.target$<index>};
 *       if the recorded target class is more specific than {@code java/lang/Object}, emit {@code CHECKCAST}
 *       to that class; then invoke {@code FloatAccessor.apply(Object)} to get the {@code float}.</li>
 *   <li><b>First-use (generic)</b>: load {@code this.accessors[<index>]} and {@code this.targets[<index>]}
 *       from the backing arrays; invoke {@code FloatAccessor.apply(Object)}.</li>
 *   <li>In both first-use paths, allocate a method-local slot via
 *       {@link CompilerContext#allocateLocal()}, {@code DUP} the computed value,
 *       {@code FSTORE} it, and record the mapping in {@code accessorValueLocals} so later nodes can {@code FLOAD} it.</li>
 * </ul>
 *
 * <p><b>Registration & layout</b>:</p>
 * <ul>
 *   <li>{@link #collectAccessors(CompilerContext)} calls
 *       {@link CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)}, which:
 *       <ul>
 *         <li>Assigns a stable {@code accessorIndex} for de-duplication.</li>
 *         <li>Appends entries to the per-class arrays/fields; constructor emission wires
 *             {@code accessors}, {@code targets}, and (if specialized) {@code accessor$<i>}/{@code target$<i>}.</li>
 *       </ul>
 *   </li>
 * </ul>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Common subexpression reuse</b>: because each {@code AccessorIR} shares the same {@code accessorIndex}
 *       for identical accessors, the first-use cache ensures all subsequent uses in the same {@code evaluate()}
 *       are cheap loads.</li>
 *   <li><b>Trigonometric caching</b>: upstream, {@link CompilerContext#buildCachedTrigIR(IR, String)}
 *       detects {@code AccessorIR} inputs and assigns a stable trig cache slot per {@code accessorIndex}, allowing
 *       {@link CachedTrigIR} to reuse both input and output across multiple trig calls of the same accessor.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>:</p>
 * <ul>
 *   <li>The generated evaluator instance stores only immutable fields (captured arrays or specialized fields).
 *       All per-evaluation caches live in method locals, so concurrent calls on distinct instances are safe.</li>
 * </ul>
 *
 * <p><b>Bytecode summary</b> (first-use specialized path):</p>
 * <pre>
 *   ALOAD 0
 *   GETFIELD this.accessor$&lt;i&gt; : Ldev/omega/arcane/reference/FloatAccessor;
 *   ALOAD 0
 *   GETFIELD this.target$&lt;i&gt;   : Ljava/lang/Object;
 *   (optional) CHECKCAST &lt;concrete target class&gt;
 *   INVOKEINTERFACE FloatAccessor.apply (Ljava/lang/Object;)F
 *   DUP
 *   FSTORE &lt;local&gt;
 *   // leaves the float value on stack
 * </pre>
 *
 * @implNote This node does not perform null checks; it assumes the constructor populated accessors/targets or
 * specialized fields correctly. If an accessor throws, the exception propagates to the caller.
 * @see CompilerContext#accessorValueLocals
 * @see CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)
 * @see CachedTrigIR
 */

public final class AccessorIR extends IR {
    public final FloatAccessor<?> accessor;
    public final Object target;
    public final int accessorIndex;

    public AccessorIR(FloatAccessor<?> accessor, Object target, int accessorIndex) {
        this.accessor = accessor;
        this.target = target;
        this.accessorIndex = accessorIndex;
    }

    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        Integer cachedLocal = ctx.accessorValueLocals.get(accessorIndex);
        if (cachedLocal != null) {
            mv.visitVarInsn(Opcodes.FLOAD, cachedLocal);
            return;
        }

        AccessorInfo info = ctx.accessorInfos.get(accessorIndex);
        if (info != null && info.isSpecialized) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "accessor$" + accessorIndex,
                    "L" + dev.omega.arcane.compiler.Compiler.FLOAT_ACCESSOR_INTERNAL + ";");

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "target$" + accessorIndex,
                    "Ljava/lang/Object;");

            if (info.targetClass != null && !info.targetClass.equals("java/lang/Object")) {
                mv.visitTypeInsn(Opcodes.CHECKCAST, info.targetClass);
            }

            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, dev.omega.arcane.compiler.Compiler.FLOAT_ACCESSOR_INTERNAL, "apply",
                    "(Ljava/lang/Object;)F", true);
        } else {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "accessors", "[L" + dev.omega.arcane.compiler.Compiler.FLOAT_ACCESSOR_INTERNAL + ";");
            IR.pushInt(mv, accessorIndex);
            mv.visitInsn(Opcodes.AALOAD);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "targets", "[Ljava/lang/Object;");
            IR.pushInt(mv, accessorIndex);
            mv.visitInsn(Opcodes.AALOAD);

            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Compiler.FLOAT_ACCESSOR_INTERNAL, "apply", "(Ljava/lang/Object;)F", true);
        }

        int cacheLocal = ctx.allocateLocal();
        ctx.accessorValueLocals.put(accessorIndex, cacheLocal);
        mv.visitInsn(Opcodes.DUP);
        mv.visitVarInsn(Opcodes.FSTORE, cacheLocal);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        ctx.registerAccessor(accessor, target);
    }
}
