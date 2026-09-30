package com.cachecleaner.app.accessibility

/**
 * Pure retry policy for one package attempt, mirroring the reference
 * (XCleaner 2.6):
 * - Missing/timeout signals get exactly one reopen-and-retry, then a
 *   terminal outcome.
 * - A successful "Clear cache" click is terminal: the click is
 *   fire-and-forget, there is no post-click verification.
 * - A disabled "Clear cache" button means the app has nothing to clear:
 *   terminal SKIPPED, never retried (retrying a disabled button just
 *   burns the per-package watchdog).
 * - SERVICE_LOST (accessibility service unbound mid-attempt) is handled
 *   inline by the engine (wait for rebind, then retry the package); the
 *   mapping here only keeps the `when` exhaustive.
 */

/** Internal signals driving one package attempt. */
internal enum class AttemptSignal {
    STORAGE_MISSING,
    CLEAR_CACHE_MISSING,
    CACHE_CLEARED,
    TIMEOUT,
    /** Clear-cache button persistently disabled: nothing to clear. */
    SKIPPED_CLEAN,
    /** Accessibility service unbound mid-attempt (handled inline). */
    SERVICE_LOST
}

/** What the engine should do after an attempt signal. */
internal sealed interface Step {
    data class Terminal(val outcome: PackageOutcome) : Step
    data object Retry : Step
}

internal enum class PackageOutcome { CLEANED, SKIPPED, FAILED }

/** Pure retry policy. */
internal fun nextStep(
    signal: AttemptSignal,
    alreadyRetried: Boolean,
    @Suppress("UNUSED_PARAMETER") isMiui: Boolean
): Step = when (signal) {
    AttemptSignal.CACHE_CLEARED -> Step.Terminal(PackageOutcome.CLEANED)
    AttemptSignal.SKIPPED_CLEAN -> Step.Terminal(PackageOutcome.SKIPPED)
    AttemptSignal.SERVICE_LOST -> Step.Retry
    AttemptSignal.STORAGE_MISSING,
    AttemptSignal.CLEAR_CACHE_MISSING,
    AttemptSignal.TIMEOUT ->
        if (alreadyRetried) Step.Terminal(PackageOutcome.FAILED)
        else Step.Retry
}
