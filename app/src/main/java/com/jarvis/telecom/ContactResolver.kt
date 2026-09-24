package com.jarvis.telecom

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.ContactsContract
import com.jarvis.wakeword.RaphaelPhoneticMatcher
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class ContactInfo(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val type: String = "Mobile"
)

class ContactResolver(private val context: Context? = null) {

    // Builtin default/emergency cache for instant resolution and offline resilience
    private val defaultCache = mutableListOf(
        ContactInfo("c_mom", "Mom", "+18005550101", "Family"),
        ContactInfo("c_dad", "Dad", "+18005550102", "Family"),
        ContactInfo("c_home", "Home", "+18005550103", "Home"),
        ContactInfo("c_emergency", "Emergency", "911", "Emergency"),
        ContactInfo("c_police", "Police", "100", "Emergency"),
        ContactInfo("c_ambulance", "Ambulance", "102", "Emergency")
    )

    fun addCachedContact(contact: ContactInfo) {
        defaultCache.removeAll { it.name.equals(contact.name, ignoreCase = true) }
        defaultCache.add(0, contact)
    }

    fun resolveContact(query: String): ContactInfo? {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        if (cleanQuery.isBlank()) return null

        // 1. Direct phone number check — try cache first for a named match
        val digitsOnly = cleanQuery.filter { it.isDigit() || it == '+' }
        if (digitsOnly.length >= 7 && digitsOnly.length == cleanQuery.length) {
            val cachedByNumber = defaultCache.firstOrNull {
                it.phoneNumber.filter { c -> c.isDigit() } == digitsOnly.filter { c -> c.isDigit() }
            }
            if (cachedByNumber != null) return cachedByNumber
            return ContactInfo(id = "num_$digitsOnly", name = digitsOnly, phoneNumber = digitsOnly)
        }

        // 2. Search Device Contacts via ContentProvider if permitted
        val deviceMatches = queryDeviceContacts(cleanQuery)
        if (deviceMatches.isNotEmpty()) {
            return deviceMatches.first()
        }

        // 3. Search Cache with exact / contains
        val cacheExact = defaultCache.firstOrNull { it.name.equals(cleanQuery, ignoreCase = true) }
        if (cacheExact != null) return cacheExact

        val cacheContains = defaultCache.firstOrNull {
            it.name.lowercase(Locale.ROOT).contains(cleanQuery) || cleanQuery.contains(it.name.lowercase(Locale.ROOT))
        }
        if (cacheContains != null) return cacheContains

        // 4. Phonetic & Fuzzy Search across cache and device contacts
        val candidatePool = defaultCache + fetchAllDeviceContacts(150)
        var bestMatch: ContactInfo? = null
        var lowestDistance = Int.MAX_VALUE

        val queryPhonetic = RaphaelPhoneticMatcher.phoneticKey(cleanQuery)

        for (contact in candidatePool) {
            val contactNameLower = contact.name.lowercase(Locale.ROOT)
            val tokens = contactNameLower.split(" ").filter { it.isNotBlank() }
            val nameTokensAndFull = tokens + listOf(contactNameLower)

            for (token in nameTokensAndFull) {
                val tokenPhonetic = RaphaelPhoneticMatcher.phoneticKey(token)
                if (queryPhonetic.isNotBlank() && tokenPhonetic.isNotBlank() && queryPhonetic == tokenPhonetic) {
                    return contact
                }

                val dist = com.jarvis.foundation.TextDistance.levenshtein(cleanQuery, token)
                if (dist < lowestDistance && dist <= max(1, cleanQuery.length / 3)) {
                    lowestDistance = dist
                    bestMatch = contact
                }
            }
        }

        return bestMatch
    }

    fun searchContacts(query: String, limit: Int = 10): List<ContactInfo> {
        val clean = query.trim().lowercase(Locale.ROOT)
        val results = mutableListOf<ContactInfo>()

        // Check device contacts first
        results.addAll(queryDeviceContacts(clean))

        // Check cache
        for (c in defaultCache) {
            if (results.none { it.name.equals(c.name, ignoreCase = true) }) {
                if (clean.isBlank() || c.name.lowercase(Locale.ROOT).contains(clean) || c.phoneNumber.contains(clean)) {
                    results.add(c)
                }
            }
        }

        return results.take(limit)
    }

    private fun queryDeviceContacts(query: String): List<ContactInfo> {
        val ctx = context ?: return emptyList()
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val results = mutableListOf<ContactInfo>()
        var cursor: Cursor? = null
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            cursor = ctx.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.let {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext() && results.size < 10) {
                    val id = if (idIdx >= 0) it.getString(idIdx) else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else ""
                    val number = if (numIdx >= 0) it.getString(numIdx) else ""
                    if (name.isNotBlank() && number.isNotBlank()) {
                        results.add(ContactInfo(id = id, name = name, phoneNumber = number))
                    }
                }
            }
        } catch (_: Throwable) {
            // ContentProvider query failed gracefully
        } finally {
            cursor?.close()
        }

        return results
    }

    private fun fetchAllDeviceContacts(limit: Int = 150): List<ContactInfo> {
        val ctx = context ?: return emptyList()
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val results = mutableListOf<ContactInfo>()
        var cursor: Cursor? = null
        try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            cursor = ctx.contentResolver.query(uri, projection, null, null, null)
            cursor?.let {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext() && results.size < limit) {
                    val id = if (idIdx >= 0) it.getString(idIdx) else ""
                    val name = if (nameIdx >= 0) it.getString(nameIdx) else ""
                    val number = if (numIdx >= 0) it.getString(numIdx) else ""
                    if (name.isNotBlank() && number.isNotBlank()) {
                        results.add(ContactInfo(id = id, name = name, phoneNumber = number))
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            cursor?.close()
        }
        return results
    }

    private fun levenshtein(s: String, t: String): Int =
        com.jarvis.foundation.TextDistance.levenshtein(s, t)
}
