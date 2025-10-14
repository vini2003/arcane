package dev.omega.arcane.benchmark;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.ReferenceType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class MolangEvaluationBenchmark {
    private static final String ARITHMETIC_EXPRESSION = "math.sin(45.0) + 3.0 * 5.0 - 2.0";
    private static final String QUERY_EXPRESSION = "math.cos(query.value) + math.cos(query.value) + math.cos(query.value)";

    private MolangExpression interpretedArithmetic;
    private MolangExpression compiledArithmetic;

    private MolangExpression interpretedQuery;
    private MolangExpression compiledQuery;

    private BenchmarkContext benchmarkContext;

    @Setup
    public void setup() throws MolangLexException, MolangParseException {
        interpretedArithmetic = MolangParser.parse(ARITHMETIC_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY);
        compiledArithmetic = MolangParser.parse(ARITHMETIC_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);

        benchmarkContext = new BenchmarkContext();

        ExpressionBindingContext context = ExpressionBindingContext.create()
                .registerReferenceResolver(ReferenceType.QUERY, "value", BenchmarkContext.class, BenchmarkContext::currentValue);

        interpretedQuery = MolangParser.parse(QUERY_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY)
                .bind(context, benchmarkContext);
        compiledQuery = MolangParser.parse(QUERY_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE)
                .bind(context, benchmarkContext);
    }

    @Benchmark
    public float interpretedArithmetic() {
        return interpretedArithmetic.evaluate();
    }

    @Benchmark
    public float compiledArithmetic() {
        return compiledArithmetic.evaluate();
    }

    @Benchmark
    public float interpretedQuery() {
        return interpretedQuery.evaluate();
    }

    @Benchmark
    public float compiledQuery() {
        return compiledQuery.evaluate();
    }

    @Benchmark
    public float nativeArithmetic() {
        return (float) (Math.sin(Math.toRadians(45.0)) + 3.0 * 5.0 - 2.0);
    }

    @Benchmark
    public float nativeQuery() {
        float value = benchmarkContext.currentValue();
        double radians = Math.toRadians(value);
        double cosValue = Math.cos(radians);
        return (float) (cosValue + cosValue + cosValue);
    }

    public static final class BenchmarkContext {
        public float currentValue() {
            return 42.0f;
        }
    }
}