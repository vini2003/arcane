package dev.omega.arcane.compiler;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.FloatAccessor;
import dev.omega.arcane.reference.ReferenceType;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CompilerConcurrencyTests {
    private static final ExpressionBindingContext CONTEXT = ExpressionBindingContext.create()
            .registerReferenceResolver(ReferenceType.QUERY, "value", Value.class, (FloatAccessor<Value>) Value::value);

    @Test
    void Compiling_SameNewShapeConcurrently_GeneratesOneClass() throws Exception {
        MolangExpression source = MolangParser.parse("math.abs(query.value) * 3.0625 - query.value / 7.25", MolangParser.FLAG_SIMPLIFY);
        int before = Compiler.CLASS_COUNTER.get();
        int threads = 8;
        var ready = new CountDownLatch(threads);
        var go = new CountDownLatch(1);

        var executor = Executors.newFixedThreadPool(threads);

        try {
            var results = new ArrayList<Future<MolangExpression>>();

            for (int thread = 0; thread < threads; thread++) {
                float input = thread;

                results.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return Compiler.compile(source.bind(CONTEXT, new Value(input)));
                }));
            }

            ready.await();
            go.countDown();

            for (int thread = 0; thread < threads; thread++) {
                MolangExpression compiled = results.get(thread).get();

                Assertions.assertInstanceOf(CompiledExpression.class, compiled);
                Assertions.assertEquals(Math.abs(thread) * 3.0625f - thread / 7.25f, compiled.evaluate(), 1.0e-5f);
            }
        } finally {
            executor.shutdown();
        }

        Assertions.assertEquals(before + 1, Compiler.CLASS_COUNTER.get());
    }

    private record Value(float value) {
    }
}
