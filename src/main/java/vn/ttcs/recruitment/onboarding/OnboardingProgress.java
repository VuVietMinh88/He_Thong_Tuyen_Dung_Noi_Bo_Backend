package vn.ttcs.recruitment.onboarding;

/**
 * Pure policy for onboarding checklist progress.
 */
public final class OnboardingProgress {

    private OnboardingProgress() {
    }

    /**
     * Returns completion percentage rounded down, so 100 is only reported when every item is done.
     * An empty checklist reports 0.
     */
    public static int percent(int completed, int total) {
        validate(completed, total);
        if (total == 0) {
            return 0;
        }
        // Long intermediate avoids overflow for counts near Integer.MAX_VALUE;
        // operands are non-negative, so integer division is a floor.
        return (int) (completed * 100L / total);
    }

    /**
     * Returns true only when a non-empty checklist is fully completed.
     */
    public static boolean canComplete(int completed, int total) {
        validate(completed, total);
        return total > 0 && completed == total;
    }

    private static void validate(int completed, int total) {
        if (total < 0 || completed < 0 || completed > total) {
            throw new IllegalArgumentException("Invalid onboarding progress counts");
        }
    }
}
