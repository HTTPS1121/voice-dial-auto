package com.example.voicedialauto.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.Contacts
import android.provider.ContactsContract.Data
import androidx.core.content.ContextCompat

/**
 * Reads every contact that has a phone number from ContactsContract. Which numbers become dial
 * targets is decided by [NumberSelector] (default number > single mobile > every distinct number).
 * Also loads nickname / phonetic / given / family as match aliases (not shown in the UI).
 * Recent calls / favorites are intentionally NOT used.
 */
class ContactsRepository(private val context: Context) {

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    /** Blocking; call off the main thread. Always uses the app context (safe from CarContext). */
    fun loadDialableContacts(): List<ContactEntry> {
        if (!hasPermission()) return emptyList()
        val app = context.applicationContext
        val projection = arrayOf(
            Phone.CONTACT_ID,
            Phone.DISPLAY_NAME_PRIMARY,
            Phone.NUMBER,
            Phone.TYPE,
            Phone.LABEL,
            Phone.IS_SUPER_PRIMARY,
        )
        data class Row(val id: Long, val name: String, val number: String, val type: Int, val label: String?, val superPrimary: Boolean)

        val rows = ArrayList<Row>()
        app.contentResolver.query(Phone.CONTENT_URI, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(Phone.CONTACT_ID)
            val iName = c.getColumnIndexOrThrow(Phone.DISPLAY_NAME_PRIMARY)
            val iNum = c.getColumnIndexOrThrow(Phone.NUMBER)
            val iType = c.getColumnIndexOrThrow(Phone.TYPE)
            val iLabel = c.getColumnIndexOrThrow(Phone.LABEL)
            val iSp = c.getColumnIndexOrThrow(Phone.IS_SUPER_PRIMARY)
            while (c.moveToNext()) {
                val name = c.getString(iName) ?: continue
                val number = c.getString(iNum) ?: continue
                if (number.isBlank()) continue
                rows += Row(c.getLong(iId), name, number, c.getInt(iType), c.getString(iLabel), c.getInt(iSp) != 0)
            }
        }
        if (rows.isEmpty()) return emptyList()

        val aliasesById = runCatching { loadAliases(app, rows.map { it.id }.toSet()) }
            .onFailure { android.util.Log.w("ContactsRepository", "aliases failed", it) }
            .getOrDefault(emptyMap())

        // v15: account copies ("… Me", "meapi - …") collapse to one clean entry per number.
        return ContactDedupe.dedupe(rows.groupBy { it.id }.flatMap { (id, list) ->
            val byNumber = list.associateBy { it.number }
            val aliases = aliasesById[id].orEmpty()
            NumberSelector.select(list.map { PhoneRow(it.number, it.type == Phone.TYPE_MOBILE, it.superPrimary) })
                .map { sel ->
                    val r = byNumber.getValue(sel.number)
                    val label = Phone.getTypeLabel(app.resources, r.type, r.label).toString()
                    ContactEntry(r.id, r.name, r.number, label, aliases)
                }
        })
    }

    /** Nicknames, phonetic names, given/family — keyed by contact id. */
    private fun loadAliases(app: Context, ids: Set<Long>): Map<Long, List<String>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, MutableList<String>>()
        fun add(id: Long, value: String?) {
            val v = value?.trim().orEmpty()
            if (v.isNotEmpty()) out.getOrPut(id) { ArrayList() }.add(v)
        }

        // v11: Contacts.PHONETIC_NAME is derived from StructuredName's phonetic parts (read below),
        // so the extra full scan of the Contacts table was dropped. Aliases = ONE Data query in total.
        // Nickname + StructuredName given/family/phonetic parts.
        val sel = "${Data.MIMETYPE}=? OR ${Data.MIMETYPE}=?"
        val args = arrayOf(Nickname.CONTENT_ITEM_TYPE, StructuredName.CONTENT_ITEM_TYPE)
        app.contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(
                Data.CONTACT_ID, Data.MIMETYPE,
                Nickname.NAME,
                StructuredName.GIVEN_NAME, StructuredName.FAMILY_NAME,
                StructuredName.PHONETIC_GIVEN_NAME, StructuredName.PHONETIC_FAMILY_NAME,
            ),
            sel, args, null,
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Data.CONTACT_ID)
            val iMime = c.getColumnIndexOrThrow(Data.MIMETYPE)
            val iNick = c.getColumnIndex(Nickname.NAME)
            val iGiven = c.getColumnIndex(StructuredName.GIVEN_NAME)
            val iFamily = c.getColumnIndex(StructuredName.FAMILY_NAME)
            val iPGiven = c.getColumnIndex(StructuredName.PHONETIC_GIVEN_NAME)
            val iPFamily = c.getColumnIndex(StructuredName.PHONETIC_FAMILY_NAME)
            while (c.moveToNext()) {
                val id = c.getLong(iId)
                if (id !in ids) continue
                when (c.getString(iMime)) {
                    Nickname.CONTENT_ITEM_TYPE -> if (iNick >= 0) add(id, c.getString(iNick))
                    StructuredName.CONTENT_ITEM_TYPE -> {
                        if (iGiven >= 0) add(id, c.getString(iGiven))
                        if (iFamily >= 0) add(id, c.getString(iFamily))
                        if (iPGiven >= 0) add(id, c.getString(iPGiven))
                        if (iPFamily >= 0) add(id, c.getString(iPFamily))
                    }
                }
            }
        }
        return out.mapValues { (_, list) -> list.distinct() }
    }
}
