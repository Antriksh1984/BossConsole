package ai.rever.boss.mcp

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * [McpPolicyEngine.setToolPolicyIfAbsent] is the proactive path's add-only-if-absent guard
 * (review on #636): a candidate offered by the bottom bar's policy manager is stamped with
 * [McpPolicyEngine.revocationVersion] at offer time, but that counter alone does not protect an
 * intervening explicit decision - only a revoke moves it, so an operator answering the reactive
 * approval dialog's "Always Allow"/"Always Deny" for the same tool in between never trips it.
 * These tests exercise exactly that race: capture a candidate, let an intervening explicit
 * decision land, then confirm the queued proactive write is refused and the intervening decision
 * - not the stale candidate - is what survives, including across a reload from disk.
 */
class McpPolicyEngineAddOnlyIfAbsentTest {
    private val tempFiles = mutableListOf<File>()

    private fun createTempPolicyFile(): File {
        val dir =
            kotlin.io.path
                .createTempDirectory("mcp-policy-absent-test")
                .toFile()
        return File(dir, "mcp-tool-policy.json").also { tempFiles.add(it) }
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.parentFile?.deleteRecursively() }
        tempFiles.clear()
    }

    @Test
    fun `writes when the tool still has no rule, and it survives reload`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        val generation = engine.revocationVersion("run_command")

        val outcome = engine.setToolPolicyIfAbsent("run_command", McpPolicyAction.ALLOW, generation)

        assertEquals(McpProactivePolicyOutcome.Saved, outcome)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("run_command"))
        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ALLOW, reloaded.policyFor("run_command"))
    }

    @Test
    fun `an intervening explicit ASK is not overwritten by a stale candidate`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        // Captured while "k8s_delete" had no rule - this is the candidate's generation.
        val generation = engine.revocationVersion("k8s_delete")

        // The operator separately answers the reactive approval dialog for the very same tool.
        // setToolPolicy neither bumps revocationVersion nor sees this proactive write coming.
        engine.setToolPolicy("k8s_delete", McpPolicyAction.ASK)

        val outcome = engine.setToolPolicyIfAbsent("k8s_delete", McpPolicyAction.ALLOW, generation)

        assertEquals(McpProactivePolicyOutcome.Refused, outcome)
        assertEquals(McpPolicyAction.ASK, engine.policyFor("k8s_delete"))
        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ASK, reloaded.policyFor("k8s_delete"))
    }

    @Test
    fun `an intervening explicit ALLOW is not overwritten by a stale candidate`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        val generation = engine.revocationVersion("helm_upgrade")

        engine.setToolPolicy("helm_upgrade", McpPolicyAction.ALLOW)

        val outcome = engine.setToolPolicyIfAbsent("helm_upgrade", McpPolicyAction.DENY, generation)

        assertEquals(McpProactivePolicyOutcome.Refused, outcome)
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("helm_upgrade"))
        val reloaded = McpPolicyEngine(policyFile = file)
        assertEquals(McpPolicyAction.ALLOW, reloaded.policyFor("helm_upgrade"))
    }

    @Test
    fun `an intervening explicit DENY is not overwritten by a stale candidate`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        val generation = engine.revocationVersion("docker_rm")

        engine.setToolPolicy("docker_rm", McpPolicyAction.DENY)

        val outcome = engine.setToolPolicyIfAbsent("docker_rm", McpPolicyAction.ALLOW, generation)

        assertEquals(McpProactivePolicyOutcome.Refused, outcome)
        assertEquals(McpPolicyAction.DENY, engine.policyFor("docker_rm"))
    }

    @Test
    fun `a revoke between offer and write is still caught by the revocation check`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        engine.setToolPolicy("git_status", McpPolicyAction.DENY)
        // Candidate offered describing the CURRENT (post-DENY) generation, then the operator
        // revokes that DENY before the proactive write reaches disk.
        val generation = engine.revocationVersion("git_status")
        engine.revokePersistedPolicy("git_status")

        val outcome = engine.setToolPolicyIfAbsent("git_status", McpPolicyAction.ALLOW, generation)

        assertEquals(McpProactivePolicyOutcome.Refused, outcome)
        // The revoke, not the stale candidate, is what the tool resolves to: no rule of its own,
        // so it falls to its default - ALLOW, since git_status is read-only, not the DENY the
        // stale candidate never got to write, and not the ASK a mutating tool would fall back to.
        assertEquals(McpPolicyAction.ALLOW, engine.policyFor("git_status"))
    }

    @Test
    fun `a genuine disk failure is distinguishable from a guard refusal`() {
        val file = createTempPolicyFile()
        val engine = McpPolicyEngine(policyFile = file)
        val generation = engine.revocationVersion("run_command")
        // atomicWriteText creates missing parent directories, so turning the policy file's own
        // path into a directory is what makes the final atomic move fail - same trick
        // `a failed revocation write is reported, not silently swallowed` uses.
        file.mkdirs()

        val outcome = engine.setToolPolicyIfAbsent("run_command", McpPolicyAction.ALLOW, generation)

        assertIs<McpProactivePolicyOutcome.Failed>(outcome)
    }

    @Test
    fun `a stale revocation is refused without touching the fault channel`() {
        val file = createTempPolicyFile()
        var reportedFault: McpPolicyFault? = null
        val engine = McpPolicyEngine(policyFile = file, onFault = { reportedFault = it })
        val staleGeneration = engine.revocationVersion("run_command") + 1

        val outcome = engine.setToolPolicyIfAbsent("run_command", McpPolicyAction.ALLOW, staleGeneration)

        assertEquals(McpProactivePolicyOutcome.Refused, outcome)
        // A refusal is not a fault - nothing was actually attempted on disk.
        assertNull(reportedFault)
    }
}
