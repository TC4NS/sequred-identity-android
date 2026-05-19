package co.sequred.identity.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID

/**
 * Kotlin mirrors of the canonical JSON wire format defined in the Rust core
 * (`core/src/vault.rs`). The Rust side is the source of truth — these classes
 * exist only so the UI can read and mutate vault data on this side of the
 * FFI boundary. Any field added in Rust must be added here too.
 */

/** Apple-reference-date seconds (matches Swift `JSONEncoder` default). */
@Serializable(with = AppleDateSerializer::class)
data class AppleDate(val unixSeconds: Double) {
    companion object {
        const val APPLE_REF_EPOCH_OFFSET_SECS = 978_307_200.0
        fun now() = AppleDate(System.currentTimeMillis() / 1000.0)
    }
}

object AppleDateSerializer : KSerializer<AppleDate> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("AppleDate", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: AppleDate) {
        encoder.encodeDouble(value.unixSeconds - AppleDate.APPLE_REF_EPOCH_OFFSET_SECS)
    }
    override fun deserialize(decoder: Decoder): AppleDate =
        AppleDate(decoder.decodeDouble() + AppleDate.APPLE_REF_EPOCH_OFFSET_SECS)
}

/** UUID serialized as Swift's uppercase dashed form. */
@Serializable(with = SwiftUuidSerializer::class)
data class VaultUuid(val value: UUID) {
    override fun toString(): String = value.toString().uppercase()
    companion object { fun random() = VaultUuid(UUID.randomUUID()) }
}

object SwiftUuidSerializer : KSerializer<VaultUuid> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("VaultUuid", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: VaultUuid) {
        encoder.encodeString(value.value.toString().uppercase())
    }
    override fun deserialize(decoder: Decoder): VaultUuid =
        VaultUuid(UUID.fromString(decoder.decodeString()))
}

/** Bytes serialized as standard-alphabet base64 (matches Swift `Data`). */
object Base64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("Base64Bytes", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: ByteArray) {
        encoder.encodeString(Base64.getEncoder().encodeToString(value))
    }
    override fun deserialize(decoder: Decoder): ByteArray =
        Base64.getDecoder().decode(decoder.decodeString())
}

@Serializable
enum class VaultCategory {
    @SerialName("none") None,
    @SerialName("personal") Personal,
    @SerialName("work") Work,
    @SerialName("entertainment") Entertainment,
    @SerialName("travel") Travel,
    @SerialName("finance") Finance,
    @SerialName("social") Social,
    @SerialName("shopping") Shopping,
    @SerialName("health") Health,
    @SerialName("education") Education,
    @SerialName("gaming") Gaming,
    @SerialName("developer") Developer;

    val label: String
        get() = when (this) {
            None -> "All"
            Personal -> "Personal"
            Work -> "Work"
            Entertainment -> "Entertainment"
            Travel -> "Travel"
            Finance -> "Finance"
            Social -> "Social"
            Shopping -> "Shopping"
            Health -> "Health"
            Education -> "Education"
            Gaming -> "Gaming"
            Developer -> "Developer"
        }
}

@Serializable
data class VaultEntry(
    val id: VaultUuid = VaultUuid.random(),
    val site: String,
    /**
     * Primary identifier. Required to be non-blank only if `email` is blank.
     * Always the derivation input — switching between username and email
     * would change the derived password, so we commit to one identifier at
     * create-time and let `email` carry the secondary value.
     */
    val username: String,
    /**
     * Optional secondary identifier. Many sites accept either a username or
     * an email at login; some require both at registration. Either field
     * being non-blank satisfies the "have an identifier" requirement.
     * Adding this as nullable is fully backward-compatible — older vaults
     * deserialize with email = null.
     */
    val email: String? = null,
    val passwordLength: Int = 20,
    val useUpper: Boolean = true,
    val useLower: Boolean = true,
    val useDigits: Boolean = true,
    val useSymbols: Boolean = true,
    val createdAt: AppleDate = AppleDate.now(),
    val updatedAt: AppleDate = AppleDate.now(),
    val version: Int = 1,
    val totpSecret: String? = null,
    val isPassphrase: Boolean = false,
    val passphraseWordCount: Int = 6,
    val passphraseSeparator: String = "-",
    val category: VaultCategory = VaultCategory.None,
    @Serializable(with = Base64Serializer::class)
    val passwordHash: ByteArray? = null,
) {
    /** The string we present in lists / autofill chips when no preference. */
    val displayId: String get() = username.ifBlank { email.orEmpty() }
}

@Serializable
data class AuthenticatorEntry(
    val id: VaultUuid = VaultUuid.random(),
    val issuer: String,
    val account: String,
    val secret: String,
    val createdAt: AppleDate = AppleDate.now(),
)

@Serializable
data class VaultPayload(
    val entries: List<VaultEntry> = emptyList(),
    val authEntries: List<AuthenticatorEntry> = emptyList(),
)

@Serializable
data class AccountVault(
    val accountId: String,
    val isOwner: Boolean,
    val familyMode: Boolean = false,
    @Serializable(with = Base64Serializer::class) val salt: ByteArray,
    @Serializable(with = Base64Serializer::class) val nonce: ByteArray,
    @Serializable(with = Base64Serializer::class) val ciphertext: ByteArray,
    val entryCount: Int,
    @Serializable(with = Base64Serializer::class) val fcNonce: ByteArray? = null,
    @Serializable(with = Base64Serializer::class) val fcCipher: ByteArray? = null,
    val kdf: String? = null,
    val argon2Memory: Int? = null,
    val argon2Iters: Int? = null,
)

@Serializable
data class MultiAccountVaultFile(
    val version: Int = 4,
    val kdf: String = "argon2id",
    val kdfIterations: Int,
    val accounts: List<AccountVault>,
)

/** Shared JSON config — `encodeDefaults = true` so optional fields round-trip. */
val vaultJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
