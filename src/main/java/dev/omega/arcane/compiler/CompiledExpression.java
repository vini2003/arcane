package dev.omega.arcane.compiler;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.reference.ExpressionBindingContext;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public record CompiledExpression(MolangExpression source, CompiledEvaluator evaluator) implements MolangExpression {
    private static final int MAX_BIND_CACHE_ENTRIES = 512;
    private static final Map<BindCacheKey, MolangExpression> BIND_CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BindCacheKey, MolangExpression> eldest) {
            return size() > MAX_BIND_CACHE_ENTRIES;
        }
    };

    @Override
    public float evaluate() {
        return evaluator.evaluate();
    }

    @Override
    public MolangExpression bind(ExpressionBindingContext context, Object... values) {
        var key = new BindCacheKey(source, context, values);

        synchronized (BIND_CACHE) {
            var cached = BIND_CACHE.get(key);
            if (cached != null) {
                return cached;
            }
        }

        // When binding, we must update the source expression; not this one.
        MolangExpression bound = source.bind(context, values);

        if (bound == source) {
            synchronized (BIND_CACHE) {
                BIND_CACHE.put(key, this);
            }
            return this;
        }

        // Keep the historical "bind returns compiled when changed" contract.
        // Compiler.compile now reuses generated evaluator classes by expression shape
        // and only re-instantiates them with new bound targets/accessors.
        var compiled = Compiler.compile(bound);

        synchronized (BIND_CACHE) {
            BIND_CACHE.put(key, compiled);
        }

        return compiled;
    }

    private static final class BindCacheKey {
        private final MolangExpression source;
        private final ExpressionBindingContext context;
        private final Object[] values;
        private final Class<?>[] valueTypes;
        private final int hash;

        private BindCacheKey(MolangExpression source, ExpressionBindingContext context, Object[] values) {
            this.source = source;
            this.context = context;
            this.values = values != null ? values.clone() : new Object[0];
            this.valueTypes = new Class<?>[this.values.length];

            int hash = 17;
            hash = 31 * hash + System.identityHashCode(source);
            hash = 31 * hash + System.identityHashCode(context);
            hash = 31 * hash + this.values.length;

            for (int i = 0; i < this.values.length; i++) {
                var value = this.values[i];
                var type = value != null ? value.getClass() : null;
                this.valueTypes[i] = type;
                hash = 31 * hash + System.identityHashCode(value);
                hash = 31 * hash + System.identityHashCode(type);
            }

            this.hash = hash;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof BindCacheKey other)) {
                return false;
            }

            if (source != other.source || context != other.context) {
                return false;
            }

            if (!Arrays.equals(valueTypes, other.valueTypes)) {
                return false;
            }

            if (values.length != other.values.length) {
                return false;
            }

            for (int i = 0; i < values.length; i++) {
                if (values[i] != other.values[i]) {
                    return false;
                }
            }

            return true;
        }
    }
}
