package dev.aidos.executor

import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.Permission
import kotlinx.datetime.Instant

/**
 * A subscriber's interest in the event bus (RFC-0004, "Subscription Model").
 */
data class Subscription(
    val id: String,
    val subscriberId: String,
    val topicPatterns: List<String> = emptyList(),
    val eventTypes: List<String> = emptyList(),
    val isPersistent: Boolean = false,
)

/**
 * In-memory registry of subscriptions, and the topic/type matching that decides which
 * subscriptions a published event satisfies (RFC-0004 MVP item 4).
 */
class SubscriptionRegistry {

    private val subscriptions = mutableMapOf<String, Subscription>()

    fun subscribe(subscription: Subscription) {
        subscriptions[subscription.id] = subscription
    }

    /**
     * Capability-checked subscription (RFC-0004 "Subscription Authorization").
     *
     * Frontends ([ActorKind.USER]) and runtime components ([ActorKind.RUNTIME]) receive everything
     * without requiring a session capability. Sessions/workers subscribing beyond their own
     * `session:<id>` topic must hold [Permission.EVENT_SUBSCRIBE].
     */
    suspend fun subscribeAuthorized(
        subscription: Subscription,
        subscriberKind: ActorKind,
        nowIso: String,
        capabilityManager: CapabilityManager? = null,
    ) {
        if (subscriberKind == ActorKind.SESSION || subscriberKind == ActorKind.WORKER) {
            val ownExact = "session:${subscription.subscriberId}"
            val ownPrefix = "session:${subscription.subscriberId}:"
            val externalPatterns = subscription.topicPatterns.filter {
                it != ownExact && !it.startsWith(ownPrefix)
            }
            if (subscription.topicPatterns.isEmpty() || externalPatterns.isNotEmpty()) {
                val mgr = capabilityManager
                    ?: throw IllegalArgumentException("CapabilityManager required for external session subscriptions")
                val now = Instant.parse(nowIso)
                val allowedPatterns = mgr.loadForSubject(subscription.subscriberId)
                    .filter { cap ->
                        cap.permission == Permission.EVENT_SUBSCRIBE &&
                            cap.revokedAt == null &&
                            (cap.expiresAt == null || cap.expiresAt!! > now)
                    }
                    .flatMap { (it.scope as? CapabilityScope.Events)?.topicPatterns ?: emptyList() }
                if (allowedPatterns.isEmpty()) {
                    throw IllegalArgumentException(
                        "Subscriber ${subscription.subscriberId} lacks EVENT_SUBSCRIBE capability (RFC-0004)"
                    )
                }
                if (subscription.topicPatterns.isEmpty() && "*" !in allowedPatterns && "**" !in allowedPatterns) {
                    throw IllegalArgumentException(
                        "Subscriber ${subscription.subscriberId} lacks wildcard EVENT_SUBSCRIBE capability"
                    )
                }
                for (pattern in externalPatterns) {
                    val covered = allowedPatterns.any { allowed ->
                        allowed == pattern || TopicMatcher.matches(allowed, pattern)
                    }
                    if (!covered) {
                        throw IllegalArgumentException(
                            "Subscriber ${subscription.subscriberId} not authorized for topic '$pattern'"
                        )
                    }
                }
            }
        }
        subscribe(subscription)
    }

    fun unsubscribe(id: String) {
        subscriptions.remove(id)
    }

    fun subscription(id: String): Subscription? = subscriptions[id]

    fun all(): List<Subscription> = subscriptions.values.toList()

    /** Subscriptions whose topic patterns and event types both admit an event of [type] on [topic]. */
    fun matchingSubscribers(topic: String?, type: String): List<Subscription> =
        subscriptions.values.filter { sub ->
            (sub.eventTypes.isEmpty() || type in sub.eventTypes) &&
                TopicMatcher.matchesAny(sub.topicPatterns, topic)
        }

    /**
     * Visibility-filtered matching subscribers (RFC-0004 "Subscription Authorization").
     * Ensures wildcard subscriptions never leak `PRIVILEGED` events to sessions.
     */
    fun matchingAuthorizedSubscribers(
        event: EventRow,
        eventStore: EventStore,
        resolveSubscriberKind: (String) -> ActorKind = { ActorKind.SESSION },
    ): List<Subscription> =
        matchingSubscribers(event.topic, event.type).filter { sub ->
            eventStore.canDeliver(event, resolveSubscriberKind(sub.subscriberId), sub.subscriberId)
        }
}
