package ai.rever.boss.components.dialogs

import ai.rever.boss.mcp.McpPolicyAction
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Lets an operator see and undo every persistent MCP tool policy rule saved from the approval
 * dialog's "Always Allow"/"Always Deny" actions ([McpApprovalDialog]) - the inspection and
 * revocation path this repo's own AGENTS.md names as reachable only by hand-editing
 * `~/.boss/mcp-tool-policy.json` and restarting, until this dialog existed.
 *
 * [rules] is a snapshot the caller re-derives from [ai.rever.boss.mcp.McpPolicyEngine.config] on
 * every recomposition, not a copy this dialog owns - a revoke calls back into [onRevoke] and
 * waits for the same state flow to reflect it, rather than mutating a local list that could drift
 * from what is actually on disk.
 */
@Composable
@Suppress("LongMethod") // Declarative Compose layout.
fun McpPolicyManagerDialog(
    rules: Map<String, McpPolicyAction>,
    onRevoke: (toolName: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val colors = BossTheme.colors
    val radii = BossTheme.radius
    // Which tool's revoke most recently failed to persist - cleared on the next attempt for that
    // tool, so a stale error does not linger once retried.
    var failedRevoke by remember { mutableStateOf<String?>(null) }

    BossDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Surface(
            modifier = Modifier.width(480.dp).wrapContentHeight(),
            shape = RoundedCornerShape(radii.dialog),
            color = colors.panel,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Persistent MCP Tool Policies",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text =
                        "Saved from \"Always Allow\" / \"Always Deny\" in the tool approval dialog. " +
                            "Resetting a tool returns it to Ask - the next mutating call prompts again.",
                    fontSize = 12.sp,
                    color = colors.textSecondary,
                )
                Spacer(modifier = Modifier.height(16.dp))

                if (rules.isEmpty()) {
                    Text(
                        text = "No persistent rules saved. Every tool still asks on each mutating call.",
                        fontSize = 13.sp,
                        color = colors.textSecondary,
                    )
                } else {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                                .verticalScroll(rememberScrollState()),
                    ) {
                        rules.toSortedMap().forEach { (toolName, action) ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = toolName,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.textPrimary,
                                    )
                                    Text(
                                        text = action.name,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = if (action == McpPolicyAction.DENY) colors.alert else colors.signal,
                                    )
                                    if (failedRevoke == toolName) {
                                        Text(
                                            text = "Could not save - see the host log for the reason.",
                                            fontSize = 11.sp,
                                            color = colors.alert,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                TextButton(
                                    onClick = {
                                        failedRevoke = if (onRevoke(toolName)) null else toolName
                                    },
                                ) {
                                    Text("Reset to Ask", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(backgroundColor = colors.signal),
                    ) {
                        Text("Close", color = colors.onSignal, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
