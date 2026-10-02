package itemcounters.core;

public final class CounterMath {
    private CounterMath() {}
    public static long add(long a, long b) {
        if (a < 0 || b < 0) throw new IllegalArgumentException("Negative counter");
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }
    public static double healthLoss(double health, double finalDamage) {
        if (!Double.isFinite(health) || !Double.isFinite(finalDamage)) return 0;
        return Math.min(Math.max(health, 0), Math.max(finalDamage, 0));
    }
    public static double addDamage(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || a < 0 || b < 0)
            throw new IllegalArgumentException("Invalid damage");
        return a > Double.MAX_VALUE - b ? Double.MAX_VALUE : a + b;
    }
    public static double[] distribute(double damage, int pieces) {
        if (!Double.isFinite(damage) || damage < 0 || pieces < 1 || pieces > 4)
            throw new IllegalArgumentException("Invalid distribution");
        double[] shares = new double[pieces];
        double allocated = 0;
        for (int i = 0; i < pieces - 1; i++) {
            shares[i] = damage / pieces;
            allocated += shares[i];
        }
        shares[pieces - 1] = damage - allocated;
        return shares;
    }
}
