package com.claudewidgets.core;

import java.util.Collections;
import java.util.List;

/** El modelo normalizado del contrato (`spec/usage-model.schema.json`). */
public final class UsageModel {
    public final Source source;
    public final Bar session;
    public final Bar weekly;
    public final List<ScopedLimit> scoped;
    public final List<BreakdownRow> breakdown;

    public UsageModel(Source source, Bar session, Bar weekly,
                      List<ScopedLimit> scoped, List<BreakdownRow> breakdown) {
        this.source = source;
        this.session = session;
        this.weekly = weekly;
        this.scoped = Collections.unmodifiableList(scoped);
        this.breakdown = Collections.unmodifiableList(breakdown);
    }
}
