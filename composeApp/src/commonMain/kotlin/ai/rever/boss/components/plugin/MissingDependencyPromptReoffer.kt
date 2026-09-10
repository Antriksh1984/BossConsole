package ai.rever.boss.components.plugin

import kotlinx.coroutines.delay

/** Throttle the rejecting collector's next receive, after returning the prompt to the bus. */
private const val MISSING_DEPENDENCY_REOFFER_DELAY_MS = 150L

/**
 * Return another window's prompt before suspending, so closing this collector during the
 * throttle delay cannot abandon it. This still uses the bus's lossy report admission;
 * queue overflow, duplicate replacement and unbounded routing hops remain unresolved.
 */
internal suspend fun reofferMissingDependencyPrompt(
    bus: PluginDependencyBus,
    prompt: MissingDependencyPrompt,
) {
    bus.report(prompt)
    delay(MISSING_DEPENDENCY_REOFFER_DELAY_MS)
}
