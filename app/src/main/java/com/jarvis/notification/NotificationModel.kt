package com.jarvis.notification

import java.util.Locale

enum class WhatsAppPlatform { STANDARD, BUSINESS, UNKNOWN }

enum class NotificationCategory {
    PRIORITY,
    PROMOTION,
    SYSTEM
}

data class NotificationItem(
    val id: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val timestamp: Long,
    val isOngoing: Boolean,
    val platform: WhatsAppPlatform = WhatsAppPlatform.UNKNOWN,
    val category: NotificationCategory = NotificationCategory.PRIORITY
)

data class NotificationDigest(
    val priorityCount: Int,
    val promotionCount: Int,
    val totalCount: Int,
    val priorityItems: List<NotificationItem>,
    val conversationalSummary: String
)

object NotificationClassifier {
    fun classify(packageName: String, title: String, text: String): NotificationCategory {
        val lowerPkg = packageName.lowercase(Locale.ROOT)
        val combined = "$title $text".lowercase(Locale.ROOT)

        // OTP / Financial / Urgent codes are always PRIORITY regardless of app
        val isUrgentCode = listOf("otp", "code", "verification", "passcode", "secret", "cvv").any { combined.contains(it) }
        if (isUrgentCode) {
            return NotificationCategory.PRIORITY
        }

        // Known E-commerce, Food & Delivery apps that generate marketing clutter
        val promoPackages = listOf(
            "com.zomato", "in.swiggy", "com.zepto", "com.grofers", "com.blinkit", "com.flipkart",
            "amazon.mshop", "com.myntra", "com.tatacliq", "com.ajio", "com.nykaa",
            "com.meesho", "com.snapdeal", "com.dominospizza", "com.pizzahut", "com.kfc"
        )
        if (promoPackages.any { lowerPkg.contains(it) }) {
            return NotificationCategory.PROMOTION
        }

        // Promotional marketing keywords in content
        val promoKeywords = listOf(
            "discount", "coupon", "flat 50%", "flat 40%", "cashback", "sale is live",
            "offer expires", "limited time offer", "hurry up", "mega sale", "free delivery"
        )
        if (promoKeywords.any { combined.contains(it) }) {
            return NotificationCategory.PROMOTION
        }

        // System Packages
        if (lowerPkg.contains("android.system") || lowerPkg.contains("systemui") || lowerPkg.contains("settings")) {
            return NotificationCategory.SYSTEM
        }

        // Default: Personal messaging, calls, email, reminders
        return NotificationCategory.PRIORITY
    }
}

object NotificationStore {
    private const val MAX_CAPACITY = 30
    private val buffer = mutableListOf<NotificationItem>()

    @Synchronized
    fun addNotification(item: NotificationItem) {
        if (item.title.isBlank() && item.text.isBlank()) return
        // Remove existing notification with same id to avoid duplicate stacking
        buffer.removeAll { it.id == item.id }
        buffer.add(0, item) // newest first
        while (buffer.size > MAX_CAPACITY) {
            buffer.removeAt(buffer.size - 1)
        }
    }

    @Synchronized
    fun removeNotification(id: String) {
        buffer.removeAll { it.id == id }
    }

    @Synchronized
    fun getRecentNotifications(limit: Int = 10, appFilter: String? = null): List<NotificationItem> {
        val nonOngoing = buffer.filter { !it.isOngoing }
        val filtered = if (!appFilter.isNullOrBlank()) {
            val lowerFilter = appFilter.lowercase(Locale.ROOT).trim()
            nonOngoing.filter {
                it.packageName.lowercase(Locale.ROOT).contains(lowerFilter) ||
                it.appName.lowercase(Locale.ROOT).contains(lowerFilter)
            }
        } else {
            nonOngoing
        }
        return filtered.take(limit.coerceIn(1, MAX_CAPACITY))
    }

    @Synchronized
    fun getPriorityNotifications(limit: Int = 10): List<NotificationItem> {
        return buffer.filter { !it.isOngoing && it.category == NotificationCategory.PRIORITY }
            .take(limit.coerceIn(1, MAX_CAPACITY))
    }

    @Synchronized
    fun clearPromotions(): Int {
        val before = buffer.size
        buffer.removeAll { it.category == NotificationCategory.PROMOTION }
        return before - buffer.size
    }

    @Synchronized
    fun getDigestSummary(): NotificationDigest {
        val nonOngoing = buffer.filter { !it.isOngoing }
        val priorityList = nonOngoing.filter { it.category == NotificationCategory.PRIORITY }
        val promoList = nonOngoing.filter { it.category == NotificationCategory.PROMOTION }

        val summary = if (nonOngoing.isEmpty()) {
            "You have no unread notifications right now."
        } else {
            buildString {
                append("You have ${priorityList.size} priority notification${if (priorityList.size == 1) "" else "s"}")
                if (priorityList.isNotEmpty()) {
                    val snippets = priorityList.take(3).map {
                        val sender = if (it.title.isNotBlank()) it.title else it.appName
                        "$sender on ${it.appName}"
                    }
                    append(": ${snippets.joinToString(", ")}")
                }
                if (promoList.isNotEmpty()) {
                    append(". Plus ${promoList.size} promotional notification${if (promoList.size == 1) "" else "s"} from apps like ${promoList.map { it.appName }.distinct().take(3).joinToString(", ")}.")
                } else {
                    append(".")
                }
            }
        }

        return NotificationDigest(
            priorityCount = priorityList.size,
            promotionCount = promoList.size,
            totalCount = nonOngoing.size,
            priorityItems = priorityList,
            conversationalSummary = summary
        )
    }

    @Synchronized
    fun getAll(): List<NotificationItem> = buffer.toList()

    @Synchronized
    fun clear() {
        buffer.clear()
    }
}
