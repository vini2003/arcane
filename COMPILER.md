# Arcane `ExpressionCompiler` â€” Deep Dive & Worked Examples

> Package: `dev.omega.arcane.compiler`

This document explains **how the JIT compiler for Molang expressions works endâ€‘toâ€‘end**: inputs, IR, optimizations, bytecode emission via ASM, runtime layout, and evaluation path. It also walks through two **fully worked examples**:

* `math.sin(45.0) + 3.0 * 5.0 - 2.0`
* `math.cos(query.value) + math.cos(query.value) + math.cos(query.value)`

If youâ€™re skimming, focus on: **Pipeline Overview**, **IR Nodes**, **Optimizations**, **Bytecode Structure**, and the **Worked Examples**.

---

## 1) Design Goals

* **Interpreter fallback**: never fail; compilation is opportunistic.
* **Hotâ€‘path latency**: minimize callâ€‘throughs and virtual dispatch during `evaluate()`.
* **Strength reduction & constant folding**: reduce arithmetic at compileâ€‘time.
* **Common subâ€‘expression reuse**: reuse values via locals by tracking `refCount` inside the compiler context.
* **Targetâ€‘specialized accessors**: sidestep array indirections when possible.
* **Trigonometric memoization**: avoid recomputing `sin/cos/...` when the accessor backing value doesnâ€™t change within an evaluation.
* **Bytecode simplicity**: floatâ€‘only datapath with minimal boxing; helper intrinsics (`pushFloat`, `pushInt`).

---

## 2) Highâ€‘Level Pipeline

```mermaid
flowchart LR
  A[User AST MolangExpression] --> B[compile(MolangExpression)]
  B -->|skip if already Compiled/Constant| Z[Return Early]
  B --> C[CompilerContext(root)]
  C --> D[buildIR(root)]
  D --> E[optimize(IR)]
  E --> E1[constantFold]
  E --> E2[algebraicSimplify]
  E --> F[collectAccessors(IR)]
  F --> G[assignLocals(IR by refCount)]
  G --> H[emitConstructor]
  G --> I[emitEvaluate]
  H --> J[ClassWriter -> byte[]]
  I --> J
  J --> K[defineClass + newInstance]
  K --> L[wrap in CompiledExpression]
```

**Key idea:** **IR** is a thin, purpose-built SSA-ish tree; the `CompilerContext` attaches per-node metadata (ref counts, local slots, initialization flags) to drive CSE-like reuse during codegen.

---

## 3) Key Runtime Types (Birdâ€™sâ€‘Eye)

* **`ExpressionCompiler`**: static entrypoint `compile(...)` and all codegen helpers.
* **`CompiledExpression`**: wraps original `MolangExpression` + `CompiledEvaluator` instance. On `bind(...)`, reâ€‘compiles if binding changed.
* **`CompiledEvaluator` (interface)**: generated class implements it with an `evaluate(): float` method.
* **`CompilerContext`**: *compilation session state*: IR cache, accessor registry, locals allocator, and code emission helpers.
* **IR subclasses**: purposeâ€‘built nodes that know how to **emit** themselves.

---

## 4) IR Node Set & Semantics

The `CompilerContext` tracks per-node metadata for every IR instance:

* `int local` — assigned when `refCount > 1` so repeated uses can load from a JVM local during `evaluate()`.
* `int refCount` — incremented whenever a parent references the node; informs local allocation decisions.
* `boolean localInitialized` — records whether the cached local has already been materialized during emission.

### 4.1 Constants & Accessors

* **`ConstantIR`**: pushes an immediate float via `FCONST_*`/`LDC`.
* **`AccessorIR`**:

    * Registers a `(FloatAccessor, target)` in `CompilerContext` via `registerAccessor(...)`, yielding an `accessorIndex`.
    * **Two emission modes**:

        1. **Specialized fields** per accessor (`accessor$N`, `target$N`) â†’ direct `GETFIELD` + `INVOKEINTERFACE`.
        2. Generic arrays `accessors[]`, `targets[]` â†’ `AALOAD` indirection.
    * **Perâ€‘evaluation cache**: `accessorValueLocals[accessorIndex]` stores the float value the first time itâ€™s loaded in an `evaluate()` call, avoiding duplicate loads of the same accessor.

### 4.2 Arithmetic & Boolean

* **`BinaryOpIR`**: emits `FADD`, `FSUB`, `FMUL`, `FDIV`, `FREM`.
* **`UnaryOpIR`**: emits `FNEG` / noâ€‘op for `+x`; `!x` is a tiny FSM producing 0/1 floats.
* **`ComparisonIR`**: compares two floats via `FCMPL` + jump sequence, returning 0.0/1.0.
* **`TernaryIR`**: standard ifâ€‘thenâ€‘else on float truthiness (`!= 0`).

### 4.3 Math Functions

* **`MathFuncIR`** (singleâ€‘arg): `abs/trunc/round` stay in float; others call into `java.lang.Math` as `double` and `D2F` back.
* **`ComplexMathIR`**: `min/max/pow/atan2/clamp/lerp/hermiteBlend/minAngle/random/randomInteger` with small structured sequences and locals for temporary values.
* **`CachedTrigIR`**: *the trig fastâ€‘path*.

    * Associates a **cache slot** per **accessor index** (`accessorTrigCacheMap`).
    * Maintains two locals per slot **inside `evaluate()`**:

        * `trigInputLocals[slot]` â€” last input (float degrees).
        * `trigOutputLocals[slot]` â€” last output (float of `Math.<fn>(rad)`).
    * On reuse: recompute the input, **compare vs cached**, and **reuse output** if identical; otherwise recompute and update.
    * For **nonâ€‘accessor inputs**, we fall back to `MathFuncIR` (no memoization), with degreesâ†’radians multiply inâ€‘line.

### 4.4 Fallback

* **`FallbackIR`**: for unsupported AST nodes, captures a reference into a side array `captured[]` and calls back to `.evaluate()` at runtime.

---

## 5) Mapping From AST â†’ IR

`buildIR(MolangExpression)` handles each known AST type and produces IR:

* `ConstantExpression` â†’ `ConstantIR`.
* `BoundFloatAccessorExpression` â†’ `AccessorIR` with accessor registry & potential specialization.
* Operators (`+ - * / %`) â†’ `BinaryOpIR` with opcode.
* Unary (`+ - !`) â†’ `UnaryOpIR`.
* Comparisons / logicals â†’ `ComparisonIR`, or `TernaryIR` for `&&` / `||` lowering.
* `ArithmeticExpression` hierarchy â†’ `MathFuncIR`, `ComplexMathIR`, or `CachedTrigIR` (for trig with **accessor inputs**).
* `ReferenceExpression` currently â†’ `ConstantIR(0.0f)` (stubbed default).
* Unknown â†’ `FallbackIR` + capture.

IR nodes are **memoized** in `irCache` (identity), so shared AST subtrees share IR and bump the context-maintained `refCount` metadata.

---

## 6) Optimizations Passes

### 6.1 Constant Folding

Recursively rewrites IR when **all inputs are constants**:

* Arithmetic: `+, -, *, /, %`.
* Unary: `-x`, `+x`, `!x` (only if x âˆˆ {0,1}).
* Comparisons: `<, <=, >, >=, ==, !=` â†’ 0/1.
* Singleâ€‘arg math: `abs, ceil, floor, sqrt, exp, log, trunc, round, sin, cos, asin, acos, atan` (with degreesâ†’radians where declared).
* Complex math: `min, max, pow, clamp, lerp, hermiteBlend, minAngle`.
* Cached trig with constant input collapses to `ConstantIR` too.

**Outcome:** subtrees become `ConstantIR`, enabling larger folds (e.g., chained `+`/`-`).

### 6.2 Algebraic Simplification

Local algebraic identities, e.g.:

* `x*0 â†’ 0`, `0*x â†’ 0`, `x*1 â†’ x`, `1*x â†’ x`, `x*2 â†’ x+x`.
* `x+0 â†’ x`, `0+x â†’ x`.
* `x-0 â†’ x`, `0-y â†’ -y`.
* `x/1 â†’ x`, `0/y â†’ 0`.
* `-(-y) â†’ y`.

> Note: there is **no** global CSE pass; instead we rely on the compiler context's **refCount-driven local materialization** during emission (see below).

---

## 7) Accessor Registration & Specialization

When we see a `BoundFloatAccessorExpression`:

* We `registerAccessor(accessor, target)` once and get an **index**. The context records:

    * `accessors[]` & `targets[]` arrays **and** a **perâ€‘accessor** `AccessorInfo`.
* During class emission, for each accessor we also create **specialized instance fields** (`accessor$N`, `target$N`) and store them in the constructor. Emission chooses:

    * **Specialized fields** path (fast): `GETFIELD accessor$N` + `GETFIELD target$N` + optional `CHECKCAST targetClass` + `INVOKEINTERFACE FloatAccessor.apply(Object)F`.
    * **Array** path (generic): `GETFIELD accessors[]` / `targets[]` + `AALOAD` indirection.

The instance also seeds **perâ€‘evaluation caches**:

* `accessorValueLocals[index]` â€” once an accessor is evaluated, the **float** is cached in a local for subsequent uses in the same `evaluate()` call.
* `accessorTrigCacheMap` â€” maps accessor index â†’ **trig cache slot** for `CachedTrigIR`.

---

## 8) Local Allocation Strategy

* `assignLocals(IR)`: pre-walk consults the metadata map and assigns a local when the tracked `refCount` exceeds 1.
* Emission path `emitIR(ir, mv)`:

    1. If the metadata says the local is already initialized, we `FLOAD` it and return.
    2. Otherwise we emit the node, `DUP`, `FSTORE` into the assigned local, and flip the metadata flag to initialized.

---

## 9) Generated Class Layout & Emission

The compiler generates a concrete class:

```text
class dev.omega.arcane.generated.CompiledExpression$<N> implements CompiledEvaluator {
  // ctor params
  private final MolangExpression[] captured;
  private final FloatAccessor[] accessors;
  private final Object[] targets;

  // optional perâ€‘accessor fast fields
  private final FloatAccessor accessor$0; // ... per registered accessor
  private final Object       target$0;    // ... per registered accessor

  public float evaluate();
}
```

### 9.1 Constructor Emission

* Stores arrays into fields.
* For each accessor, also stores **specialized** `accessor$N` and `target$N` into fields to enable the fast path.

### 9.2 `evaluate()` Emission

* Emits code for the **optimized IR tree** using the helpers.
* Returns a **single float**.

### 9.3 Helpers

* `pushFloat(mv, v)` and `pushInt(mv, v)` select the most compact bytecode (`FCONST_0/1/2`, `ICONST_*`, `BIPUSH/SIPUSH`, or `LDC`).

---

## 10) Error Handling & Fallback

* `compile(...)` wraps everything in a `try/catch(Throwable)`; on any failure we **log** and **return the interpreter** (`expression` unchanged).
* `FallbackIR` lets us **partially** compile unknown subtrees while delegating complex pieces back to the interpreter via the `captured[]` array.

---

## 11) Worked Example A â€” Pure Arithmetic

**Expression**

```
math.sin(45.0) + 3.0 * 5.0 - 2.0
```

### 11.1 AST (conceptual)

```text
      (-)
     /   \
   (+)    2.0
  /   \
 sin   (*)
  |   /   \
 45  3.0  5.0
```

### 11.2 Initial IR

```text
BinaryOpIR(FSUB,
  left = BinaryOpIR(FADD,
            MathFuncIR(func="sin", input=ConstantIR(45.0), needsRadians=true),
            BinaryOpIR(FMUL, ConstantIR(3.0), ConstantIR(5.0))
         ),
  right = ConstantIR(2.0)
)
```

### 11.3 Optimization

* `BinaryOpIR(FMUL, 3.0, 5.0)` â†’ **`ConstantIR(15.0)`** via constant folding.
* `MathFuncIR(sin, 45Â°, needsRadians=true)` â†’ **`ConstantIR(sin(Ï€/4))` = `0.70710677f`**.
* Now `0.70710677 + 15.0 - 2.0` â†’ **`ConstantIR(13.707107f)`** after successive folds.

### 11.4 Emitted Bytecode (shape)

The entire method reduces to **load constant â†’ return**:

```text
LDC 13.707107f
FRETURN
```

### 11.5 Evaluation

* `evaluate()` is O(1) with **no temps, no calls**. This is the ideal steadyâ€‘state for pureâ€‘constant arithmetic + foldable intrinsics.

---

## 12) Worked Example B â€” Repeated Accessor + Trig

**Expression**

```text
math.cos(query.value) + math.cos(query.value) + math.cos(query.value)
```

Assume `query.value` is compiled as `BoundFloatAccessorExpression(accessor=query.value, boundValue=ctx)`.

### 12.1 AST (conceptual)

```text
        (+)
      /     \
    (+)     cos
   /   \      \
 cos   cos    query.value
  |     |
 query.value query.value
```

### 12.2 Initial IR (with sharing)

Let `A` be the shared `AccessorIR(index=0)` for `query.value`.

```text
BinaryOpIR(FADD,
  left = BinaryOpIR(FADD,
           CachedTrigIR(func="cos", input=A, cacheIndex=0),
           CachedTrigIR(func="cos", input=A, cacheIndex=0)
         ),
  right = CachedTrigIR(func="cos", input=A, cacheIndex=0)
)
```

Notes:

* The **same accessor** appears three times â†’ **one** `AccessorIR` object in the IR cache with compiler-context metadata `refCount = 3`.
* Because the input of `cos` is an **accessor**, `buildCachedTrigIR(...)` gives all three cosâ€‘nodes the **same** `cacheIndex` (0).

### 12.3 `assignLocals` Outcome

* `AccessorIR(A).refCount = 3` â‡’ `A.local = L1` (materialize once).
* Each `CachedTrigIR` may not get its own persistent local; instead it relies on the **trig cache locals** maintained in the context maps during emission:

    * `trigInputLocals[0] = L2` (last input)
    * `trigOutputLocals[0] = L3` (last output)

### 12.4 Emission Walkthrough (per `evaluate()` call)

1. **First `CachedTrigIR`** for `cos(A)`

    * `emitIR(A)` loads accessor value.

        * Since this is the first use, the compiler records `accessorValueLocals[0] = L1` and stores the float there.
    * No existing trig cache â†’ compute:

        * `F2D` â†’ multiply by `DEG2RAD` â†’ `INVOKESTATIC Math.cos(D)D` â†’ `D2F`.
        * Store `input` into `L2` and `output` into `L3`.

2. **Second `CachedTrigIR`** for `cos(A)`

    * Loads `A` again, but now `accessorValueLocals[0]` is present â†’ **`FLOAD L1`** (no reâ€‘apply of accessor).
    * Stores to a **newInputLocal** (shortâ€‘lived), compares with `L2`:

        * If **equal** â‡’ **reuse `FLOAD L3`** (skip `Math.cos`).
        * Else â‡’ recompute and update `L2`/`L3`.

3. **Third `CachedTrigIR`** behaves like the second, reusing the cache again.

4. The three results are **summed** by two `FADD`s and returned.

### 12.5 What We Avoided

* **Only one accessor application** for `query.value` due to `accessorValueLocals[0]`.
* **At most one trig call** (`Math.cos`) thanks to `CachedTrigIR` + cache slot **0**.
* No boxing, no intermediate objects.

> **Future tweak** (not in current code): algebraically rewrite `cos(x)+cos(x)+cos(x) â†’ 3*cos(x)` and then apply `x*2 â†’ x+x` to remove a multiply; however, the current pass doesnâ€™t perform semantic duplicateâ€‘subtree contraction across commutative sums.

---

## 13) Emission Snippets (Shape)

> Pseudocodeâ€‘style snippets for the interesting pieces. Variable declarations shown with `var`.

### 13.1 Accessor Fast Path (specialized fields)

```text
ALOAD 0                      ; this
GETFIELD this.accessor$0     ; FloatAccessor
ALOAD 0
GETFIELD this.target$0       ; Object
CHECKCAST <targetClass>
INVOKEINTERFACE FloatAccessor.apply (Object)F
; cache in accessorValueLocals[0] on first use
```

### 13.2 Cached Trig (reused input)

```text
; first time (no trig locals yet)
FLOAD L1                ; accessor value already cached
FSTORE L2               ; trigInputLocals[0] = L2
FLOAD L2
F2D ; * DEG2RAD
LDC 0.017453292519943295
DMUL
INVOKESTATIC Math.cos (D)D
D2F
DUP
FSTORE L3               ; trigOutputLocals[0] = L3

; subsequent calls
; compute newInputLocal and compare to L2, reuse L3 if equal
```

---

## 14) Edge Cases & Notes

* **Truthiness**: boolean contexts are float 0/1 in IR.
* **NaN handling**: constant folding returns `Float.NaN` to signal â€œdonâ€™t foldâ€.
* **`ReferenceExpression`**: currently stubbed to `0.0f`; if future semantics appear, update `buildIR` + folding.
* **Precision**: all trig converts **degrees â†’ radians** explicitly; singleâ€‘arg `MathFuncIR` can be configured with `needsRadians`.
* **`FallbackIR`** preserves correctness for unknown constructs without abandoning compilation for the rest.

---

## 15) Practical Tips (Extending/Debugging)

* To add a new math op:

    1. Extend `ArithmeticExpression` subtype.
    2. Map in `buildMathIR(...)` to either a new `IR` or `ComplexMathIR` case.
    3. Add folding in `computeMathFunc` / `computeComplexMath` if pure.
* To improve CSE further:

    * Add a hashâ€‘consing / DAG builder keyed by **semantic** op + operands to force merge of identical subtrees (commutativity aware for `+`/`*`).
* To tune trig cache:

    * You can widen equality (e.g., `FCMPL`==0 checks exact float equality; consider epsilon compare if source jitters slightly).
* Logging spots:

    * Before/after `optimize(IR)`; dump `refCount`, `local`, and a textual walk.

---

## 16) Endâ€‘toâ€‘End Trace Summaries

### 16.1 Arithmetic Example (final)

* **Optimized IR**: `ConstantIR(13.707107f)`
* **Bytecode**: `LDC 13.707107f; FRETURN`
* **Calls**: none.

### 16.2 Repeated Cos Example (final)

* **Optimized IR**: three `CachedTrigIR(cos, input=AccessorIR#0, slot=0)` summed by two `FADD`s.
* **Bytecode calls**: at most **one** `Math.cos` per evaluation.
* **Accessor calls**: at most **one** `.apply(...)` per evaluation.

---

## 17) Glossary

* **IR**: Intermediate Representation â€” compilerâ€™s internal tree.
* **refCount** (tracked in the context): how many parents refer to a node; drives local materialization.
* **Local materialization**: compute once, store in a JVM local, reuse.
* **Specialization**: replacing array indirections with direct instance fields.
* **Memoization**: caching function results keyed by inputs within one `evaluate()`.

---

## 18) Appendix â€” Mini IR Legend

```
ConstantIR(v)                     ; push float v
AccessorIR(accessorIndex)         ; load via specialized or array path; cache value local
UnaryOpIR(operand, op)            ; -x, +x, !x
BinaryOpIR(op, a, b)              ; a (op) b with FADD/FMUL/etc.
ComparisonIR(a,b,jumpOpcode)      ; returns 0/1 float
TernaryIR(cond, t, f)             ; cond!=0 ? t : f
MathFuncIR(input, name, rad?)     ; singleâ€‘arg math
CachedTrigIR(input, name, slot)   ; trig with perâ€‘slot memoization
ComplexMathIR([...], kind)        ; min, max, pow, clamp, lerp, ...
FallbackIR(capturedIndex)         ; delegate to captured[].evaluate()
```

---

*End of document.*










