package edu.vit.tlcb.common.metrics;

/**
 * Lets a service add its own section to the per-run JSON export (for example the order service's
 * threshold-adaptation log) without the common module knowing about it.
 */
public interface RunExportContributor {

    /** Key of the section in the exported JSON document. */
    String key();

    /** Immutable, Jackson-serialisable snapshot of the section. */
    Object snapshot();

    /** Called when a new run starts; implementations clear their per-run state. */
    void reset(long originEpochMs);
}
