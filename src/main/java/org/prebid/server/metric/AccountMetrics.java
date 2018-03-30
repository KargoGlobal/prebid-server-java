package org.prebid.server.metric;

import com.codahale.metrics.MetricRegistry;
import org.prebid.server.bidder.BidderCatalog;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Registry of {@link AdapterMetrics} for account metrics support.
 */
public class AccountMetrics extends UpdatableMetrics {

    private final Function<String, AdapterMetrics> adapterMetricsCreator;
    // not thread-safe maps are intentionally used here because it's harmless in this particular case - eventually
    // this all boils down to metrics lookup by underlying metric registry and that operation is guaranteed to be
    // thread-safe
    private final Map<String, AdapterMetrics> adapterMetrics;

    AccountMetrics(MetricRegistry metricRegistry, CounterType counterType, BidderCatalog bidderCatalog,
                   String account) {
        super(Objects.requireNonNull(metricRegistry), Objects.requireNonNull(counterType),
                nameCreator(Objects.requireNonNull(account)));
        Objects.requireNonNull(bidderCatalog);

        this.adapterMetricsCreator = adapterType -> createAdapterMetrics(metricRegistry, counterType,
                bidderCatalog, account, adapterType);
        adapterMetrics = new HashMap<>();
    }

    private static Function<MetricName, String> nameCreator(String account) {
        return metricName -> String.format("account.%s.%s", account, metricName.name());
    }

    private static AdapterMetrics createAdapterMetrics(MetricRegistry metricRegistry, CounterType counterType,
                                                       BidderCatalog bidderCatalog, String account,
                                                       String adapterType) {
        return bidderCatalog.isActive(adapterType)
                ? new AdapterMetrics(metricRegistry, counterType, account, adapterType)
                : new DisabledAdapterMetrics(metricRegistry, counterType, adapterType);
    }

    /**
     * Returns existing or creates a new {@link AdapterMetrics}.
     */
    public AdapterMetrics forAdapter(String adapterType) {
        return adapterMetrics.computeIfAbsent(adapterType, adapterMetricsCreator);
    }
}
