package com.ritense.valtimoplugins.graphmail

import com.fasterxml.jackson.databind.ObjectMapper
import com.ritense.plugin.repository.PluginConfigurationRepository
import com.ritense.plugin.service.PluginService
import com.ritense.resource.service.TemporaryResourceStorageService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.EnableAspectJAutoProxy
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

// IMPORTANT: this file uses line comments only, on purpose. Kotlin block comments nest, so
// an Ant path pattern (such as the security matcher below, which ends with a double star)
// written inside a KDoc block comment would open an inner comment that is never closed and
// swallows the rest of the file. Keeping all doc text in line comments avoids that trap.
@AutoConfiguration
@EnableAspectJAutoProxy
@EnableConfigurationProperties(GraphMailHttpProperties::class)
class GraphMailAutoConfiguration {

    private val logger = LoggerFactory.getLogger(GraphMailAutoConfiguration::class.java)

    // Fired once after the full application context is ready.
    // Reminds operators to size the job-executor thread pool correctly: the plugin's
    // retry backoff uses Thread.sleep(), which blocks the calling job-executor thread
    // for up to 30s (regular send) or 120s (upload-session flow for attachments > 2 MB).
    @EventListener(ApplicationReadyEvent::class)
    fun warnOnStartup() {
        logger.warn(
            "[Graph Mail Plugin] IMPORTANT: this plugin blocks Operaton job-executor threads during " +
                "retry backoff (up to 30s per send, 120s for large attachments). " +
                "Set operaton.bpm.job-executor.core-pool-size >= 20 and max-pool-size >= 50 " +
                "to prevent job-executor starvation under load, and configure a " +
                "failedJobRetryTimeCycle on the send-email service task. " +
                "See documentation/plugin.md for details."
        )
    }

    // ONE pooled client for the whole application, shared by the plugin action path and the
    // test-send endpoint alike.
    //
    // The JDK's own HttpClient is used rather than Apache HttpClient5 on purpose: it pools
    // connections out of the box and ships with the JVM, so the plugin does not have to assume
    // anything about which HTTP library the surrounding GZAC application happens to put on the
    // classpath. RestTemplateBuilder.build() would fall back to an unpooled
    // SimpleClientHttpRequestFactory when no third-party client is present — and, built per plugin
    // instance as it was before, there was nothing to pool in the first place.
    @Bean
    @Qualifier("graphMailRestClient")
    @ConditionalOnMissingBean(name = ["graphMailRestClient"])
    fun graphMailRestClient(
        objectMapper: ObjectMapper,
        properties: GraphMailHttpProperties,
    ): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(properties.connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER)
            // Pinned to HTTP/1.1 deliberately. The JDK client defaults to HTTP/2, which would make
            // this refactor change the wire protocol as a side effect of adding pooling — the
            // previous RestTemplate spoke HTTP/1.1. Connection reuse, the entire point here, comes
            // from keep-alive and works exactly the same on 1.1, while HTTP/2 adds a variable that
            // egress proxies and middleboxes in government networks do not always handle. Revisit
            // as a deliberate, separately tested change rather than as a silent one.
            .version(HttpClient.Version.HTTP_1_1)
            .build()

        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofSeconds(properties.readTimeoutSeconds))
        }

        return RestClient.builder()
            .requestFactory(requestFactory)
            .messageConverters { converters ->
                converters.removeIf { it is MappingJackson2HttpMessageConverter }
                converters.add(0, MappingJackson2HttpMessageConverter(objectMapper))
            }
            .build()
    }

    // Requires the client secret to be re-entered whenever the sender allowlist changes — the
    // allowlist bounds which mailboxes Mail.Send may be used for, so widening it is a privilege
    // escalation. See the class doc for why this has to be an aspect and not a plugin event.
    //
    // Guarded by a property so an operator can switch it off deliberately if it ever blocks them,
    // rather than being tempted to patch it out. Off is a real reduction in protection, so it has
    // to be an explicit, visible choice.
    @Bean
    @ConditionalOnProperty(
        prefix = "graph-mail",
        name = ["require-secret-for-allowlist-change"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(AllowedSendersChangeGuard::class)
    fun allowedSendersChangeGuard(
        pluginConfigurationRepository: PluginConfigurationRepository,
    ): AllowedSendersChangeGuard = AllowedSendersChangeGuard(pluginConfigurationRepository)

    // Deliberately tied to the same condition as the guard: when the guard is on, its absence must
    // fail startup; when an operator has switched it off, there is nothing to verify.
    @Bean
    @ConditionalOnProperty(
        prefix = "graph-mail",
        name = ["require-secret-for-allowlist-change"],
        havingValue = "true",
        matchIfMissing = true,
    )
    @ConditionalOnMissingBean(GraphMailGuardStartupCheck::class)
    fun graphMailGuardStartupCheck(pluginService: PluginService): GraphMailGuardStartupCheck =
        GraphMailGuardStartupCheck(pluginService)

    // Single shared instance — see GraphTokenCache's class doc for why the cache must be a
    // bean rather than something each GraphMailClientImpl owns: Valtimo hydrates a fresh
    // GraphMailPlugin per action invocation, so an instance-owned cache never accumulated hits.
    @Bean
    @ConditionalOnMissingBean(GraphTokenCache::class)
    fun graphTokenCache(): GraphTokenCache = GraphTokenCache()

    // Single shared instance for the same reason as graphTokenCache() above: a fresh
    // GraphMailPlugin per action invocation means an instance-owned guard would never see the
    // marker left by an earlier attempt of the same activity. See SendIdempotencyGuard's class
    // doc for what failure mode this does and does not protect against.
    @Bean
    @ConditionalOnMissingBean(SendIdempotencyGuard::class)
    fun sendIdempotencyGuard(): SendIdempotencyGuard = SendIdempotencyGuard()

    // Must be a single instance to mean anything — a per-invocation limiter would hand every
    // caller its own full set of permits and cap nothing at all.
    @Bean
    @ConditionalOnMissingBean(AttachmentConcurrencyLimiter::class)
    fun attachmentConcurrencyLimiter(properties: GraphMailHttpProperties): AttachmentConcurrencyLimiter =
        AttachmentConcurrencyLimiter(
            permits = properties.attachmentConcurrency,
            acquireTimeoutMs = properties.attachmentAcquireTimeoutSeconds * 1000,
        )

    @Bean
    @ConditionalOnMissingBean(GraphMailClient::class)
    fun graphMailClient(
        // Qualified rather than resolved by type or parameter name: this plugin runs inside a host
        // application that may well define its own RestClient, and a @Primary one there would
        // otherwise be injected here — silently sending Graph traffic through a client with
        // different timeouts, redirect handling and converters.
        @Qualifier("graphMailRestClient") graphMailRestClient: RestClient,
        graphTokenCache: GraphTokenCache,
        properties: GraphMailHttpProperties,
    ): GraphMailClient =
        GraphMailClientImpl(
            restClient = graphMailRestClient,
            tokenBaseUrl = properties.tokenBaseUrl,
            graphBaseUrl = properties.graphBaseUrl,
            tokenCache = graphTokenCache,
            requireMicrosoftUploadHost = properties.isProductionGraphEndpoint(),
        )

    @Bean
    @ConditionalOnMissingBean(GraphMailPluginFactory::class)
    fun graphMailPluginFactory(
        pluginService: PluginService,
        graphMailClient: GraphMailClient,
        resourceStorageService: TemporaryResourceStorageService,
        eventPublisher: ApplicationEventPublisher,
        sendIdempotencyGuard: SendIdempotencyGuard,
        attachmentConcurrencyLimiter: AttachmentConcurrencyLimiter,
    ): GraphMailPluginFactory =
        GraphMailPluginFactory(
            pluginService,
            graphMailClient,
            resourceStorageService,
            eventPublisher,
            sendIdempotencyGuard,
            attachmentConcurrencyLimiter,
        )

    @Bean
    @ConditionalOnMissingBean(GraphMailTestSendController::class)
    fun graphMailTestSendController(
        graphMailClient: GraphMailClient,
        pluginService: PluginService,
        eventPublisher: ApplicationEventPublisher,
    ): GraphMailTestSendController = GraphMailTestSendController(graphMailClient, pluginService, eventPublisher)

    @Order(401)
    @Bean
    @ConditionalOnMissingBean(GraphMailHttpSecurityConfigurer::class)
    fun graphMailHttpSecurityConfigurer(): GraphMailHttpSecurityConfigurer = GraphMailHttpSecurityConfigurer()
}
