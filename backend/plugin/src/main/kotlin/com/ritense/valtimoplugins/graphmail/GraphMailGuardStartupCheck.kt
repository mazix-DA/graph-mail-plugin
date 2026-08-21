package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.node.ObjectNode
import com.ritense.plugin.service.PluginService
import org.slf4j.LoggerFactory
import org.springframework.aop.support.AopUtils
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * Fails startup if [AllowedSendersChangeGuard] cannot actually take effect.
 *
 * The guard's pointcut binds to a Valtimo-internal method signature. Should Valtimo rename or
 * reshape `PluginService.updatePluginConfiguration`, or stop proxying the bean, the pointcut simply
 * stops matching — no error, no warning, and the sender allowlist becomes editable without the
 * client secret again. A security control that disappears quietly on an upgrade is worse than one
 * that was never claimed, because the documentation and the release notes keep asserting it.
 *
 * So this check makes that failure loud and immediate. Refusing to start is deliberate: an
 * application that will not boot gets fixed, while a warning in a startup log does not.
 */
class GraphMailGuardStartupCheck(
    private val pluginService: PluginService,
) {
    private val logger = LoggerFactory.getLogger(GraphMailGuardStartupCheck::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun verifyAllowedSendersGuardIsActive() {
        val target = AopUtils.getTargetClass(pluginService)

        val interceptableMethod =
            target.methods.firstOrNull { method ->
                method.name == "updatePluginConfiguration" &&
                    method.parameterTypes.any { ObjectNode::class.java.isAssignableFrom(it) }
            }

        if (interceptableMethod == null) {
            throw IllegalStateException(
                "[Graph Mail Plugin] The sender-allowlist guard cannot be applied: " +
                    "${target.name} has no updatePluginConfiguration method taking an ObjectNode. " +
                    "The Valtimo API this guard depends on has changed, so changing the allowlist " +
                    "would no longer require the client secret. Refusing to start rather than " +
                    "running with a security control that is silently inactive — see " +
                    "AllowedSendersChangeGuard.",
            )
        }

        if (!AopUtils.isAopProxy(pluginService)) {
            throw IllegalStateException(
                "[Graph Mail Plugin] The sender-allowlist guard cannot be applied: PluginService is " +
                    "not an AOP proxy, so the around-advice never runs. Changing the allowlist " +
                    "would no longer require the client secret. Refusing to start — see " +
                    "AllowedSendersChangeGuard.",
            )
        }

        logger.info(
            "[Graph Mail Plugin] Sender-allowlist guard active: changing 'allowedSenders' requires " +
                "the client secret to be supplied again.",
        )
    }
}
