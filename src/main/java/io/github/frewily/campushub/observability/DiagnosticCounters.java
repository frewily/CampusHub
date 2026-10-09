package io.github.frewily.campushub.observability;

import java.util.concurrent.atomic.LongAdder;

/** Fixed enum vocabulary, process-local event counts; never queries a dependency at scrape time. */
public final class DiagnosticCounters<E extends Enum<E>> {
    private final LongAdder[] values;

    public DiagnosticCounters(Class<E> type) {
        values = new LongAdder[type.getEnumConstants().length];
        for (int i = 0; i < values.length; i++) values[i] = new LongAdder();
    }

    public void increment(E event) { values[event.ordinal()].increment(); }
    public long count(E event) { return values[event.ordinal()].sum(); }
}
