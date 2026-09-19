package ai.rever.boss.mcp.sandbox

/**
 * Whether a shell command line reads as destructive, for [DefaultMcpRiskEvaluator].
 *
 * This is a wording heuristic, not a shell parser: both HIGH and CRITICAL require approval, and
 * CRITICAL is only the tier that says "this could not be undone". [command] is expected trimmed and
 * lower-cased, which is how the evaluator calls it.
 */
internal object DestructiveShellCommands {
    fun matches(command: String): Boolean {
        if (command.isEmpty()) return false
        return command.contains("rm -rf") ||
            command.contains("del /s") ||
            command.contains("format ") ||
            command.contains("mkfs") ||
            command.contains("git push --force") ||
            command.contains("git push -f") ||
            command.contains("dd if=") ||
            command.contains("chmod -r 777")
    }
}
