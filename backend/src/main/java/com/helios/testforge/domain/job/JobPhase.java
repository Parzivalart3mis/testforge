package com.helios.testforge.domain.job;

/**
 * The pipeline stage a provisioning job is in.
 *
 * <p>Phases are ordered and each carries the fraction of overall progress it
 * represents, so the console can render a single percentage without the backend
 * having to invent one. The weights are rough measurements from seeding a
 * 22-table schema: generation and seeding dominate, everything else is setup.
 */
public enum JobPhase {

    QUEUED("Queued", 0),
    INTROSPECTING("Introspecting schema", 6),
    PLANNING("Planning generation", 4),
    PROVISIONING("Provisioning database", 12),
    APPLYING_DDL("Applying schema", 8),
    GENERATING("Generating rows", 30),
    SEEDING("Seeding rows", 26),
    VERIFYING("Verifying integrity", 6),
    SNAPSHOTTING("Exporting snapshot", 5),
    LEASING("Issuing lease", 3),
    DONE("Complete", 0);

    private final String label;
    private final int weight;

    JobPhase(String label, int weight) {
        this.label = label;
        this.weight = weight;
    }

    public String label() {
        return label;
    }

    public int weight() {
        return weight;
    }

    /**
     * Overall completion percentage when this phase is {@code fraction} done.
     *
     * @param fraction progress within the phase, clamped to [0, 1]
     */
    public int overallPercent(double fraction) {
        double clamped = Math.max(0d, Math.min(1d, fraction));
        int completed = 0;
        for (JobPhase phase : values()) {
            if (phase.ordinal() < ordinal()) {
                completed += phase.weight;
            }
        }
        int total = 0;
        for (JobPhase phase : values()) {
            total += phase.weight;
        }
        if (total == 0) {
            return 100;
        }
        double percent = (completed + weight * clamped) * 100d / total;
        return (int) Math.round(Math.min(100d, percent));
    }
}
