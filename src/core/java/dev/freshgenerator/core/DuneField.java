package dev.freshgenerator.core;

/** Transverse dunes of a sand sea (erg), a pure function of the world seed (user request 26.11: "real deserts are
 * wide and large with constant changes of height").
 * <ul>
 * <li><b>Wind.</b> One prevailing direction per world (from the seed): crests run across it, as in the transverse and
 * barchanoid dune fields of the Sahara, Namib or Rub' al Khali (Bagnold 1941; Lancaster 1995).</li>
 * <li><b>Profile.</b> Asymmetric: a long gentle windward (stoss) slope up to the crest at 72% of the spacing and a
 * short steep slip face beyond it (the lee side stands near the angle of repose of dry sand, ~32 degrees).</li>
 * <li><b>Plan.</b> A two-scale warp of the across-wind coordinate bends crests, joins and splits them (Y-junctions,
 * terminations); crest height changes along the crest, and where it drops low the ridge breaks into separate
 * crescent barchans.</li>
 * </ul>
 * {@link #height} is 0 in the troughs and up to 1 on the highest crests; the density graph scales it in blocks. */
public final class DuneField {
    /** Crest position as a share of the dune spacing (windward share). */
    /** The crest at 60% of the period: with the spacing of 96 a 12-block dune has a lee side of ~33 degrees (angle of
     * repose of dry sand) and a windward side of ~18 (code audit 26.11 KO-08: 43 and 20 degrees at 0.72 and 72). */
    public static final double CREST = 0.60;
    private final double cos, sin, spacing;
    private final GradientNoise bend, detail, crest;

    public DuneField(long seed, double spacing) {
        if (!(spacing > 8)) throw new IllegalArgumentException("Dune spacing too small: " + spacing);
        // The full turn: the profile is asymmetric, so a half turn gave every world a wind from the same half (OpenAI 26.11).
        double angle = GradientNoise.unit(seed, 1, 2) * 2 * Math.PI;
        cos = Math.cos(angle); sin = Math.sin(angle);
        this.spacing = spacing;
        bend = new GradientNoise(seed ^ 0x3C6EF372FE94F82AL);
        detail = new GradientNoise(seed ^ 0x6A09E667F3BCC909L);
        crest = new GradientNoise(seed ^ 0x510E527FADE682D1L);
    }

    public double height(double x, double z) {
        double s = x * cos + z * sin, t = -x * sin + z * cos;
        // Bent crests: the warp (in spacings) varies slowly along and across the field.
        double warp = 1.5 * bend.fractal(x, z, 6.0 * spacing, 2) + 0.35 * detail.value(x / (1.8 * spacing), z / (1.8 * spacing));
        double phase = s / spacing + warp;
        double u = phase - Math.floor(phase);
        double h = u < CREST ? 0.5 - 0.5 * Math.cos(Math.PI * u / CREST) : 0.5 + 0.5 * Math.cos(Math.PI * (u - CREST) / (1 - CREST));
        // Crest height along the crest (period ~3 spacings along, 8 across); low parts break into barchans.
        double m = crest.value(t / (3.0 * spacing), s / (8.0 * spacing));
        double hc = clamp01(0.62 + 0.9 * m);
        return h * hc;
    }

    public double spacing() { return spacing; }
    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
