package com.sequred.identity.data

import com.sequred.identity.crypto.CoreBridge
import com.sequred.identity.crypto.ExportEnvelopeBytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ─── Export envelope (matches iOS EncryptedVaultExport byte-for-byte) ────────

@Serializable
data class EncryptedVaultExport(
    val version: Int = 2,
    @Serializable(with = Base64Serializer::class) val salt: ByteArray,
    @Serializable(with = Base64Serializer::class) val nonce: ByteArray,
    @Serializable(with = Base64Serializer::class) val ciphertext: ByteArray,
)

@Serializable
data class VaultExportFile(
    val version: Int = 1,
    val exportedAt: AppleDate = AppleDate.now(),
    val entries: List<VaultEntry>,
    // Android-only addition vs iOS: include authEntries so a full round-trip
    // doesn't lose TOTP codes that were added on the Auth tab. iOS ignores
    // unknown keys, so it stays backward-compatible there.
    val authEntries: List<AuthenticatorEntry> = emptyList(),
)

// ─── Export ──────────────────────────────────────────────────────────────────

object VaultExporter {

    /** Serialise + encrypt the user's vault into a single JSON envelope. */
    fun export(payload: VaultPayload, pin: String): String {
        val inner = VaultExportFile(entries = payload.entries, authEntries = payload.authEntries)
        val plaintext = vaultJson.encodeToString(inner).toByteArray(Charsets.UTF_8)
        val sealed = CoreBridge.exportSeal(plaintext, pin)
        val envelope = EncryptedVaultExport(salt = sealed.salt, nonce = sealed.nonce, ciphertext = sealed.ciphertext)
        return vaultJson.encodeToString(envelope)
    }

    /** Decrypt + parse a SeQured `.sqvault` envelope back into entries. */
    fun import(envelopeJson: String, pin: String): VaultExportFile {
        val env = vaultJson.decodeFromString<EncryptedVaultExport>(envelopeJson)
        val plaintext = CoreBridge.exportOpen(env.salt, env.nonce, env.ciphertext, pin)
        return vaultJson.decodeFromString(String(plaintext, Charsets.UTF_8))
    }
}

// ─── Import (auto-detect across all supported formats) ───────────────────────

data class ParsedImport(
    val source: ImportSource,
    val entries: List<VaultEntry>,
    val authEntries: List<AuthenticatorEntry> = emptyList(),
    val skipped: Int = 0,
)

enum class ImportSource { SeQured, Bitwarden, OnePasswordCsv, LastPassCsv, GenericCsv, GenericJson }

class ImportException(message: String) : Exception(message)

object VaultImportParser {

    private val laxJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Auto-detect by content sniffing. Filename is an optional hint (file
     * extension breaks tie when content is ambiguous).
     *
     * Order: SeQured envelope → Bitwarden JSON → generic JSON array →
     * 1Password CSV → LastPass CSV → generic CSV. Each parser short-circuits
     * if its structural fingerprint doesn't match.
     */
    fun parse(bytes: ByteArray, filename: String?, pin: String?): ParsedImport {
        val text = String(bytes, Charsets.UTF_8).trim()

        // SeQured envelope — JSON with salt/nonce/ciphertext keys.
        if (text.startsWith("{") && text.contains("\"ciphertext\"") && text.contains("\"salt\"")) {
            val p = pin ?: throw ImportException("This is a SeQured encrypted export — re-open Import with your PIN to decrypt.")
            val file = try { VaultExporter.import(text, p) } catch (t: Throwable) {
                throw ImportException("Couldn't decrypt — wrong PIN or corrupted file.")
            }
            return ParsedImport(
                source = ImportSource.SeQured,
                entries = file.entries,
                authEntries = file.authEntries,
            )
        }

        // Bitwarden — { "items": [{ "type": 1, "login": {…} }, …] }
        if (text.startsWith("{") && text.contains("\"items\"")) {
            runCatching { parseBitwarden(text) }.getOrNull()?.let { return it }
        }

        // Generic JSON array of credential-ish objects.
        if (text.startsWith("[")) {
            runCatching { parseGenericJson(text) }.getOrNull()?.let { return it }
        }

        // CSV fallbacks. Header sniff distinguishes 1Password / LastPass /
        // generic. RFC 4180 parser handles quoted commas + CRLF.
        if (looksLikeCsv(text)) {
            val rows = parseCsv(text)
            if (rows.isNotEmpty()) {
                val header = rows[0].map { it.trim().lowercase() }
                return when {
                    header.containsAll(listOf("title", "username")) ->
                        parseColumnCsv(rows, ImportSource.OnePasswordCsv,
                            siteKeys = listOf("url", "title"),
                            userKeys = listOf("username"),
                            totpKeys = listOf("otpauth", "otp"),
                        )
                    header.containsAll(listOf("url", "username")) && header.contains("totp") ->
                        parseColumnCsv(rows, ImportSource.LastPassCsv,
                            siteKeys = listOf("url"),
                            userKeys = listOf("username"),
                            totpKeys = listOf("totp"),
                        )
                    else ->
                        parseColumnCsv(rows, ImportSource.GenericCsv,
                            siteKeys = listOf("url", "website", "site", "domain"),
                            userKeys = listOf("username", "email", "user"),
                            totpKeys = listOf("otpauth", "otp", "totp", "2fa"),
                        )
                }
            }
        }

        throw ImportException("Unrecognised format. Supported: SeQured .sqvault, Bitwarden JSON, generic CSV (LastPass / 1Password / Chrome / KeePass).")
    }

    // ─── Bitwarden ────────────────────────────────────────────────────────────

    private fun parseBitwarden(text: String): ParsedImport {
        val root = laxJson.parseToJsonElement(text).jsonObject
        val items = root["items"]?.jsonArray ?: throw ImportException("Bitwarden export missing `items` array.")
        val entries = mutableListOf<VaultEntry>()
        var skipped = 0
        for (raw in items) {
            val obj = raw as? JsonObject ?: continue
            val type = obj["type"]?.jsonPrimitive?.intOrNull
            if (type != 1) { skipped++; continue } // 1 = login; others are notes/cards/identities
            val login = obj["login"]?.jsonObject
            if (login == null) { skipped++; continue }
            val username = login["username"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val uriHost = login["uris"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("uri")?.jsonPrimitive?.contentOrNull
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
            val site = cleanSite(uriHost ?: name.orEmpty())
            if (site.isBlank() || username.isBlank()) { skipped++; continue }
            val totpRaw = login["totp"]?.jsonPrimitive?.contentOrNull
            entries += freshEntry(site = site, username = username, totp = normaliseTotp(totpRaw))
        }
        return ParsedImport(source = ImportSource.Bitwarden, entries = entries, skipped = skipped)
    }

    // ─── Generic JSON ─────────────────────────────────────────────────────────

    private fun parseGenericJson(text: String): ParsedImport {
        val arr = laxJson.parseToJsonElement(text).jsonArray
        val entries = mutableListOf<VaultEntry>()
        var skipped = 0
        for (raw in arr) {
            val obj = raw as? JsonObject ?: continue
            val site = cleanSite(firstString(obj, "url", "website", "site", "domain"))
            val username = firstString(obj, "username", "email", "user").trim()
            if (site.isBlank() || username.isBlank()) { skipped++; continue }
            val totp = normaliseTotp(firstString(obj, "otpauth", "otp", "totp", "2fa"))
            entries += freshEntry(site = site, username = username, totp = totp)
        }
        return ParsedImport(source = ImportSource.GenericJson, entries = entries, skipped = skipped)
    }

    private fun firstString(obj: JsonObject, vararg keys: String): String {
        for (k in keys) obj[k]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) return it }
        return ""
    }

    // ─── CSV ──────────────────────────────────────────────────────────────────

    private fun parseColumnCsv(
        rows: List<List<String>>,
        source: ImportSource,
        siteKeys: List<String>,
        userKeys: List<String>,
        totpKeys: List<String>,
    ): ParsedImport {
        val header = rows[0].map { it.trim().lowercase() }
        fun colOf(keys: List<String>): Int = keys.firstNotNullOfOrNull { k -> header.indexOf(k).takeIf { it >= 0 } } ?: -1
        val siteCol = colOf(siteKeys)
        val userCol = colOf(userKeys)
        val totpCol = colOf(totpKeys)
        if (siteCol < 0 || userCol < 0) throw ImportException("CSV missing site or username column.")
        val entries = mutableListOf<VaultEntry>()
        var skipped = 0
        for (i in 1 until rows.size) {
            val r = rows[i]
            if (r.all { it.isBlank() }) continue
            val site = cleanSite(r.getOrNull(siteCol).orEmpty())
            val username = r.getOrNull(userCol).orEmpty().trim()
            if (site.isBlank() || username.isBlank()) { skipped++; continue }
            val totp = if (totpCol >= 0) normaliseTotp(r.getOrNull(totpCol)) else null
            entries += freshEntry(site = site, username = username, totp = totp)
        }
        return ParsedImport(source = source, entries = entries, skipped = skipped)
    }

    private fun looksLikeCsv(text: String): Boolean {
        val firstLine = text.lineSequence().firstOrNull() ?: return false
        return firstLine.contains(",") && !firstLine.startsWith("{") && !firstLine.startsWith("[")
    }

    /** RFC 4180-ish CSV parser. Quoted fields, embedded commas, CRLF/LF endings. */
    private fun parseCsv(text: String): List<List<String>> {
        val rows = mutableListOf<MutableList<String>>()
        var cur = StringBuilder()
        var row = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cur.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == ',' -> { row.add(cur.toString()); cur = StringBuilder() }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    row.add(cur.toString()); cur = StringBuilder()
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                }
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotEmpty() || row.isNotEmpty()) {
            row.add(cur.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
        }
        return rows
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun freshEntry(site: String, username: String, totp: String?): VaultEntry =
        VaultEntry(
            site = site,
            username = username,
            // imports default to 20-char password, all charsets — matches iOS.
            passwordLength = 20,
            useUpper = true, useLower = true, useDigits = true, useSymbols = true,
            totpSecret = totp,
        )

    /** Strip scheme/path/port — keep bare host. Matches iOS cleanSite(). */
    internal fun cleanSite(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return ""
        s = s.removePrefix("http://").removePrefix("https://").removePrefix("HTTP://").removePrefix("HTTPS://")
        s = s.substringBefore('/')
        s = s.substringBefore('?')
        s = s.substringBefore('#')
        s = s.substringBefore(':')
        return s.trim()
    }

    /**
     * Accept either a raw base32 secret or a full `otpauth://totp/…?secret=…`
     * URL and return the base32 secret. Returns null for blank/garbage.
     */
    internal fun normaliseTotp(raw: String?): String? {
        val t = raw?.trim() ?: return null
        if (t.isEmpty()) return null
        if (t.startsWith("otpauth://", ignoreCase = true)) {
            // Cheap parse: locate `secret=` query param.
            val sec = Regex("[?&]secret=([^&]+)", RegexOption.IGNORE_CASE).find(t)
                ?.groupValues?.getOrNull(1) ?: return null
            return sec.replace(" ", "").uppercase()
        }
        return t.replace(" ", "").uppercase()
    }
}

// ─── Merge into the current vault with dedupe ────────────────────────────────

data class MergeReport(val added: Int, val updated: Int, val skipped: Int) {
    val total: Int get() = added + updated
}

object VaultMerger {

    /**
     * Merge imported entries into the current payload. Dedupe key is
     * (site, username) compared case-insensitively. Existing entry with the
     * same key is updated if the import carries a TOTP that the existing one
     * lacks (otherwise left alone — we never clobber the user's local
     * derivation params from an import).
     */
    fun merge(current: VaultPayload, imported: ParsedImport): Pair<VaultPayload, MergeReport> {
        var added = 0; var updated = 0; var skipped = 0
        val existing = current.entries.toMutableList()
        for (incoming in imported.entries) {
            val idx = existing.indexOfFirst {
                it.site.equals(incoming.site, ignoreCase = true) &&
                    it.username.equals(incoming.username, ignoreCase = true)
            }
            if (idx < 0) {
                existing += incoming
                added++
            } else if (existing[idx].totpSecret.isNullOrEmpty() && !incoming.totpSecret.isNullOrEmpty()) {
                existing[idx] = existing[idx].copy(totpSecret = incoming.totpSecret, updatedAt = AppleDate.now())
                updated++
            } else {
                skipped++
            }
        }
        val auths = current.authEntries.toMutableList()
        for (incoming in imported.authEntries) {
            val present = auths.any {
                it.issuer.equals(incoming.issuer, ignoreCase = true) &&
                    it.account.equals(incoming.account, ignoreCase = true) &&
                    it.secret == incoming.secret
            }
            if (!present) auths += incoming
        }
        return current.copy(entries = existing, authEntries = auths) to
            MergeReport(added = added, updated = updated, skipped = skipped + imported.skipped)
    }
}
