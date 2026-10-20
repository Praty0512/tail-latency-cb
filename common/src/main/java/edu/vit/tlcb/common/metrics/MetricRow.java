package edu.vit.tlcb.common.metrics;

import java.util.Locale;

/**
 * One exported sample: the statistics of one latency series over one export interval.
 *
 * <p>The first ten CSV columns are the schema required by the study
 * ({@code timestamp, service, p50, p95, p99, avg, error_rate, cpu_pct, cb_state, config_id}); the
 * remaining columns identify the series and give the raw counts needed to aggregate rows correctly.
 * Latencies are in milliseconds; {@code cpuPct} is percent of one core; empty values are {@code null}.
 */
public record MetricRow(
        String timestamp,
        String service,
        Double p50,
        Double p95,
        Double p99,
        Double avg,
        Double errorRate,
        Double cpuPct,
        String cbState,
        String configId,
        String direction,
        String endpoint,
        long count,
        long errors,
        long rejected,
        double tSec,
        int cbThreshold) {

    public static final String CSV_HEADER =
            "timestamp,service,p50,p95,p99,avg,error_rate,cpu_pct,cb_state,config_id,"
                    + "direction,endpoint,count,errors,rejected,t_s,cb_threshold";

    /** Converts NaN to null so that JSON stays valid and CSV cells stay empty. */
    static Double nn(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? null : v;
    }

    private static String num(Double v) {
        return v == null ? "" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    public String toCsvLine() {
        return String.join(",",
                timestamp, csv(service), num(p50), num(p95), num(p99), num(avg),
                errorRate == null ? "" : String.format(Locale.ROOT, "%.5f", errorRate),
                num(cpuPct), csv(cbState), csv(configId),
                direction, csv(endpoint), Long.toString(count), Long.toString(errors), Long.toString(rejected),
                String.format(Locale.ROOT, "%.1f", tSec), cbThreshold < 0 ? "" : Integer.toString(cbThreshold));
    }
}
