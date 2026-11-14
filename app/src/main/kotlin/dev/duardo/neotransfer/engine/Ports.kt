package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.ActivityRow
import dev.duardo.neotransfer.core.model.ContactRecord
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.core.sms.SmsRecord
import javax.crypto.Cipher

/** The modem. Android: `TelephonyManager.sendUssdRequest` and `ACTION_CALL`; tests: a fake. */
interface UssdModem {
    /** Sends a `REQUEST` command and suspends until the network answers; the caller bounds the wait. */
    suspend fun request(command: UssdCommand): UssdReply

    /**
     * Dials a `DIAL` command: the system shows the menu or the bank's question, the app never sees the reply.
     * Returns why nothing was dialed, ready to show; null once the system took it.
     */
    fun dial(command: UssdCommand): String?
}

sealed interface UssdReply {
    /** The acknowledgement or answer text. */
    data class Text(val text: String) : UssdReply

    /** The phone or the network refused the request; [reason] is ready to show. */
    data class Failure(val reason: String) : UssdReply
}

/** A bank row of the SMS inbox: `_id`, `date`, `date_sent` (epoch millis), body and sender. */
data class InboxSms(val id: Long, val date: Long, val dateSent: Long, val body: String, val address: String = SmsRecord.SENDER)

interface SmsInbox {
    /** Rows of [SmsRecord.SENDERS] with `_id >= fromId`. Throws [SecurityException] without READ_SMS. */
    fun read(fromId: Long): List<InboxSms>
}

/** What the system prompt asks on this phone: the fingerprint alone, the fingerprint or the screen lock, or nothing (no secure lock). */
enum class PhoneLock { FINGERPRINT, ANY, NONE }

/**
 * Keystore storage of the bank keys, one blob for all banks. Under the phone's lock it is decrypted only through a
 * cipher the system prompt authorized ([phoneLock], every use); under the app code ([AppCode.protectsKeys]) through the
 * key derived from the code.
 */
interface KeyVault {
    /** What the prompt that opens or seals the keys allows; [PhoneLock.NONE]: they cannot be saved under the phone's lock. */
    fun phoneLock(): PhoneLock
    /** What the prompt authorizing [cipher] (one of [encryptCipher] or [decryptCipher]) must allow: its own key's, fixed when it was made. */
    fun lockOf(cipher: Cipher): PhoneLock
    fun hasKeys(): Boolean
    /** What is saved is under the app code, not the phone's lock. */
    fun underCode(): Boolean
    /** Null when enrolling a new fingerprint invalidated the Keystore key: the vault is then empty. */
    fun decryptCipher(): Cipher?
    fun decrypt(authorized: Cipher): ByteArray
    fun encryptCipher(): Cipher
    /**
     * @throws VaultMoved when this phone's Keystore turned down the fingerprint key right after the fingerprint: the
     * vault moved for good to a key the fingerprint or the screen lock opens, and the save goes again with a new prompt.
     */
    fun encrypt(authorized: Cipher, plaintext: ByteArray)
    /**
     * The blob under the code's key: the current one or, when a change of code was cut short after the code was set, the
     * one staged for it (which then becomes current).
     * @throws KeysLost when no blob opens with [code] (a change cut short, a Keystore that lost its key)
     */
    fun decrypt(code: CodeKey): ByteArray
    fun encrypt(code: CodeKey, plaintext: ByteArray)
    /** Writes the blob under a new code's key beside the current one; [promote] makes it current. */
    fun stage(code: CodeKey, plaintext: ByteArray)
    fun promote()
    /** The lock of the key that holds the saved keys; null when nothing is saved or it is under the code. */
    fun savedLock(): PhoneLock?
    fun reset()
}

/** The keys under the code can never be opened again; anything else that fails to open them is not this. */
class KeysLost : IllegalStateException("Se borraron las claves guardadas")

/** The save did not happen: the vault now needs a new [KeyVault.encryptCipher] and its prompt. */
class VaultMoved : IllegalStateException("No se pudo guardar la clave")

/** The key derived from the app code, in memory while the app is unlocked; [wipe] when done. */
class CodeKey(val bytes: ByteArray) {
    fun wipe() = bytes.fill(0)
}

/** Consecutive wrong codes and the wait they impose; they outlive the process. */
/**
 * Consecutive wrong codes and the wait they impose: [wait] is what is left of it when read, and the whole wait when
 * saved. Measured on the phone's clock since boot, never its wall clock; after a reboot the whole wait starts again.
 */
data class CodeAttempts(val failures: Int = 0, val wait: java.time.Duration? = null)

/** A code whose key is derived but not set yet ([AppCode.prepare]): what is under the old one moves before [AppCode.commit]. */
class PreparedCode(val key: CodeKey, val salt: ByteArray, val rounds: Int)

/**
 * The app code («Código de acceso»): 6 digits, never stored; a right one is recognized by decrypting a known blob under
 * the key derived from it. The counter and the wait are kept here; what they mean is the controller's.
 */
interface AppCode {
    fun active(): Boolean
    /** The saved keys are under the code instead of the phone's lock. */
    fun protectsKeys(): Boolean
    fun setProtectsKeys(on: Boolean)
    /** The key of [code] when it is the right one; null otherwise. Counts nothing. */
    fun check(code: CharArray): CodeKey?
    /** Derives the key of a new [code] without setting it. */
    fun prepare(code: CharArray): PreparedCode
    /** Sets the [prepared] code in place of any other. */
    fun commit(prepared: PreparedCode)
    /** Sets [code] at once and returns its key. */
    fun create(code: CharArray): CodeKey = prepare(code).also(::commit).key
    /** Turns the code off. */
    fun clear()
    fun attempts(): CodeAttempts
    fun saveAttempts(attempts: CodeAttempts)
    /**
     * The cipher the fingerprint authorizes to open the app instead of the code; null when it is not offered: no
     * fingerprint, a phone whose fingerprint keys never authorize, or a fingerprint enrolled after the code was set.
     */
    fun fingerprintCipher(): Cipher?
    /** After the prompt: false when the Keystore turned the fingerprint down (the fingerprint is not offered again). */
    fun fingerprintPassed(authorized: Cipher): Boolean
    /** The right code was just typed: the fingerprint key is made again if a new enrollment had killed it. */
    fun renewFingerprint()
}

enum class NoticeKind { CREDIT, RESULT }

/**
 * A notification. [key] identifies it (posting the same key again replaces it); [publicTitle]
 * is all the lock screen shows; [route] is the screen it opens ([Routes]).
 */
data class Notice(val key: String, val kind: NoticeKind, val title: String, val text: String?, val publicTitle: String, val route: String)

fun interface Notifier {
    fun post(notice: Notice)
}

/** Files the user picked with the system document picker, by content URI. */
interface Documents {
    fun read(uri: String): ByteArray
    fun write(uri: String, bytes: ByteArray)
}

/** Someone in the phone's address book: [key] is the contact's lookup key, [phones] its Cuban mobiles (8 digits). */
data class PhoneContact(val key: String, val name: String, val phones: List<String>)

/** The phone's address book, read whole: it is the source of names and phones; the app only adds cards. */
fun interface PhoneBook {
    /** Everyone with a name and a Cuban mobile. Throws [SecurityException] without READ_CONTACTS. */
    fun read(): List<PhoneContact>
}

/** The author's other app (NeoTransfer+), whose stored contacts this one syncs with ([dev.duardo.neotransfer.core.model.ContactSync]). */
fun interface PeerContacts {
    /**
     * Every contact it keeps, tombstones included; null when it is not installed. Throws [SecurityException] when it is
     * not signed like this app.
     */
    fun read(): List<ContactRecord>?
}

/** The operations the author's other app (NeoTransfer+) made, for this app's activity ([dev.duardo.neotransfer.core.ledger.ActivitySync]). */
fun interface PeerActivity {
    /** Its rows, newest first; null when it is not installed. Throws [SecurityException] when it is not signed like this app. */
    fun read(): List<ActivityRow>?
}
