package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.auth.ProcessIdentityInterceptor
import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.*
import ai.rever.boss.plugin.api.LogDataProvider
import ai.rever.boss.plugin.api.LogFilterData
import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Kernel-side bridge for `LogService`.
 *
 * **Every call requires a verified caller identity (BossConsole#53)**, the same requirement and
 * helper shape introduced for the Secret Service in PR #505. [watchLogs] and [exportLogs] are why
 * this matters more than most of this bridge's siblings: they hand out the live and full-history
 * stdout/stderr stream of the running BOSS process, unfiltered by anything short of what each
 * individual `logger.info`/`warn`/`error` call site happened to sanitize on its own - not a
 * guarantee that every line in a large, ever-growing codebase is free of stack traces, file paths
 * or other data nobody meant to expose over IPC. Before this bridge checked identity at all, any
 * process able to open a connection to the kernel IPC server - not only the plugins the host
 * itself loaded - could watch or export it.
 */
// One method per RPC the generated service base class declares, plus one small private helper.
@Suppress("TooManyFunctions")
class LogServiceBridge(
    private val provider: LogDataProvider,
) : LogServiceGrpcKt.LogServiceCoroutineImplBase() {
    override fun watchLogs(request: Empty): Flow<LogListResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
            provider.logs.collect { logs ->
                if (currentIdentity.invoke() != caller) {
                    throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
                }
                emit(
                    LogListResponse
                        .newBuilder()
                        .addAllEntries(
                            logs.map { entry ->
                                LogEntryProto
                                    .newBuilder()
                                    .setTimestamp(entry.timestamp)
                                    .setMessage(entry.message)
                                    .setSource(
                                        when (entry.source) {
                                            ai.rever.boss.plugin.api.LogSourceData.STDOUT -> LogSourceProto.LOG_SOURCE_STDOUT
                                            ai.rever.boss.plugin.api.LogSourceData.STDERR -> LogSourceProto.LOG_SOURCE_STDERR
                                        },
                                    ).build()
                            },
                        ).build(),
                )
            }
        }
    }

    override fun watchFilter(request: Empty): Flow<LogFilterProto> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
            provider.filter.collect { filter ->
                if (currentIdentity.invoke() != caller) {
                    throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
                }
                emit(
                    LogFilterProto
                        .newBuilder()
                        .setFilter(
                            when (filter) {
                                LogFilterData.ALL -> LogFilterType.LOG_FILTER_TYPE_ALL
                                LogFilterData.STDOUT -> LogFilterType.LOG_FILTER_TYPE_STDOUT
                                LogFilterData.STDERR -> LogFilterType.LOG_FILTER_TYPE_STDERR
                            },
                        ).build(),
                )
            }
        }
    }

    override fun watchSearchQuery(request: Empty): Flow<LogStringResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
            provider.searchQuery.collect { query ->
                if (currentIdentity.invoke() != caller) {
                    throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
                }
                emit(LogStringResponse.newBuilder().setValue(query).build())
            }
        }
    }

    override fun watchAutoScroll(request: Empty): Flow<LogBoolResponse> {
        val currentIdentity = ProcessIdentityInterceptor.CURRENT_IDENTITY.get()
        return flow {
            val caller =
                currentIdentity?.invoke()
                    ?: throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
            provider.autoScroll.collect { enabled ->
                if (currentIdentity.invoke() != caller) {
                    throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
                }
                emit(LogBoolResponse.newBuilder().setValue(enabled).build())
            }
        }
    }

    override suspend fun setFilter(request: LogFilterProto): Empty {
        authenticatedCallerOrRefuse("setFilter")
        val filter =
            when (request.filter) {
                LogFilterType.LOG_FILTER_TYPE_STDOUT -> LogFilterData.STDOUT
                LogFilterType.LOG_FILTER_TYPE_STDERR -> LogFilterData.STDERR
                else -> LogFilterData.ALL
            }
        provider.setFilter(filter)
        return Empty.getDefaultInstance()
    }

    override suspend fun setSearchQuery(request: LogStringRequest): Empty {
        authenticatedCallerOrRefuse("setSearchQuery")
        provider.setSearchQuery(request.value)
        return Empty.getDefaultInstance()
    }

    override suspend fun toggleAutoScroll(request: Empty): Empty {
        authenticatedCallerOrRefuse("toggleAutoScroll")
        provider.toggleAutoScroll()
        return Empty.getDefaultInstance()
    }

    override suspend fun clearLogs(request: Empty): Empty {
        authenticatedCallerOrRefuse("clearLogs")
        provider.clearLogs()
        return Empty.getDefaultInstance()
    }

    override suspend fun exportLogs(request: Empty): LogStringResponse {
        authenticatedCallerOrRefuse("exportLogs")
        val result = provider.exportLogs()
        return LogStringResponse.newBuilder().setValue(result).build()
    }

    /**
     * The verified identity behind this call, or a thrown `PERMISSION_DENIED` when there is none.
     *
     * Mirrors the helper introduced by PR #505 (BossConsole#53) - fails closed rather than let a
     * request with no credential fall through to [provider] with nothing to attribute it to.
     */
    private fun authenticatedCallerOrRefuse(rpc: String): String =
        ProcessIdentityInterceptor.AUTHENTICATED_PROCESS_ID.get() ?: run {
            logger.warn(
                LogCategory.AUTH,
                "Refused $rpc: no verified process identity on this call",
                mapOf("rpc" to rpc),
            )
            throw StatusException(Status.PERMISSION_DENIED.withDescription(NO_IDENTITY))
        }

    private companion object {
        val logger = BossLogger.forComponent("LogServiceBridge")

        const val NO_IDENTITY = "This call presented no verified process identity"
    }
}
