package dev.aidos.executor

/**
 * A subscriber's interest in the event bus (RFC-0004, "Subscription Model").
 *
 * An empty [topicPatterns] or [eventTypes] means "no filter on this dimension" — matching every
 * topic, or every type, respectively. Delivery itself is future work (RFC-0004's own "Future
 * Work" section: the MVP is request/response, not a live push channel); this registry is the
 * matching layer a later delivery mechanism, or the Scheduler (RFC-0005), consults.
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
 *
 * Not persisted: RFC-0004 marks live delivery as Future Work, so nothing here needs to survive a
 * restart yet — a subscriber that cares about durability re-subscribes, or replays past events
 * via [EventStore] instead.
 */
class SubscriptionRegistry {

    private val subscriptions = mutableMapOf<String, Subscription>()

    fun subscribe(subscription: Subscription) {
        subscriptions[subscription.id] = subscription
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
}
