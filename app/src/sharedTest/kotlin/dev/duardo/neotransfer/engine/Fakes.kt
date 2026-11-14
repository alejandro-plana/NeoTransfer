package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.ActivityRow
import dev.duardo.neotransfer.core.ledger.Operation
import dev.duardo.neotransfer.core.model.ContactRecord
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.data.ClassicCard
import dev.duardo.neotransfer.data.Preferences
import dev.duardo.neotransfer.data.Snapshot
import dev.duardo.neotransfer.data.Store
import dev.duardo.neotransfer.data.StoredSms
import dev.duardo.neotransfer.data.WalletCard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher

const val ACK = "Su solicitud esta siendo procesada, espere un SMS"

/** Modem double: records what it is handed and answers after [latency]; a null answer never comes. */
class FakeModem(var latency: Long = 2_000) : UssdModem {
    val sent = CopyOnWriteArrayList<UssdCommand>()
    val dialed = CopyOnWriteArrayList<UssdCommand>()
    var answer: (UssdCommand) -> UssdReply? = { UssdReply.Text(ACK) }

    /** Service numbers handed to the modem, in order (null for Cubacel codes). */
    val services: List<Int?> get() = sent.map { it.service }

    override suspend fun request(command: UssdCommand): UssdReply {
        sent += command
        delay(latency)
        return answer(command) ?: awaitCancellation()
    }

    /** What every dial answers: why nothing was dialed, or null. */
    var dialFailure: String? = null

    override fun dial(command: UssdCommand): String? {
        dialed += command
        return dialFailure
    }
}

/** Inbox double: PAGOxMOVIL rows with increasing `_id`; remembers where each read started. */
class FakeInbox : SmsInbox {
    private val rows = CopyOnWriteArrayList<InboxSms>()
    val reads = CopyOnWriteArrayList<Long>()
    @Volatile var granted = true
    private var nextId = 100L

    @Synchronized
    fun deliver(body: String, sentAt: Instant, receivedAt: Instant): Long =
        nextId++.also { rows += InboxSms(it, receivedAt.toEpochMilli(), sentAt.toEpochMilli(), body) }

    override fun read(fromId: Long): List<InboxSms> {
        if (!granted) throw SecurityException("READ_SMS")
        reads += fromId
        return rows.filter { it.id >= fromId }
    }
}

/**
 * Keeps the blob in memory; ciphers are never used for real. Under the code it remembers the code's key and refuses
 * another, as the real vault's GCM tag would. [lock] is what the phone has.
 */
class FakeVault(@Volatile var lock: PhoneLock = PhoneLock.FINGERPRINT) : KeyVault {
    @Volatile private var blob: ByteArray? = null
    @Volatile private var codeKey: ByteArray? = null
    @Volatile private var staged: Pair<ByteArray, ByteArray>? = null
    /** What opening the blob under the code throws next, besides a wrong key (a storage or Keystore hiccup). */
    @Volatile var failure: Exception? = null
    override fun phoneLock() = lock
    override fun lockOf(cipher: Cipher) = lock
    override fun hasKeys() = blob != null
    override fun underCode() = blob != null && codeKey != null
    override fun decryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    override fun decrypt(authorized: Cipher): ByteArray = checkNotNull(blob).also { check(codeKey == null) }.copyOf()
    override fun encryptCipher(): Cipher {
        check(lock != PhoneLock.NONE) { "Sin bloqueo" }
        return Cipher.getInstance("AES/GCM/NoPadding")
    }
    override fun encrypt(authorized: Cipher, plaintext: ByteArray) {
        check(lock != PhoneLock.NONE) { "Sin bloqueo" }
        blob = plaintext.copyOf()
        codeKey = null
    }
    override fun decrypt(code: CodeKey): ByteArray {
        failure?.let { failure = null; throw it }
        if (codeKey?.contentEquals(code.bytes) == true) return checkNotNull(blob).copyOf().also { staged = null }
        val (key, plaintext) = staged ?: throw KeysLost()
        if (!key.contentEquals(code.bytes)) throw KeysLost()
        promote()
        return plaintext.copyOf()
    }
    override fun stage(code: CodeKey, plaintext: ByteArray) { staged = code.bytes.copyOf() to plaintext.copyOf() }
    override fun promote() {
        val (key, plaintext) = staged ?: return
        codeKey = key
        blob = plaintext
        staged = null
    }
    override fun savedLock(): PhoneLock? = if (blob == null || codeKey != null) null else lock
    override fun encrypt(code: CodeKey, plaintext: ByteArray) {
        blob = plaintext.copyOf()
        codeKey = code.bytes.copyOf()
    }
    override fun reset() {
        blob = null
        codeKey = null
    }
    fun contains(text: String) = blob?.toString(Charsets.US_ASCII)?.contains(text) == true
}

/**
 * The app code in memory: its "key" is the code's digits, so another code opens nothing. Waits run on [elapsed], the
 * clock since boot.
 */
class FakeAppCode(private val elapsed: () -> Long = System::currentTimeMillis) : AppCode {
    @Volatile private var code: String? = null
    @Volatile private var protects = false
    @Volatile private var attempts = CodeAttempts()
    @Volatile private var waitFrom = 0L
    /** The fingerprint stands in for the code; false as after a new enrollment or on a phone that turns it down. */
    @Volatile var fingerprint = true
    override fun active() = code != null
    override fun protectsKeys() = active() && protects
    override fun setProtectsKeys(on: Boolean) { protects = on }
    override fun check(code: CharArray): CodeKey? = String(code).takeIf { it == this.code }?.let { CodeKey(it.toByteArray()) }
    override fun prepare(code: CharArray) = PreparedCode(CodeKey(String(code).toByteArray()), ByteArray(0), 0)
    override fun commit(prepared: PreparedCode) {
        code = String(prepared.key.bytes)
        fingerprint = true
    }
    override fun clear() {
        code = null
        protects = false
        attempts = CodeAttempts()
    }
    override fun attempts(): CodeAttempts {
        val wait = attempts.wait ?: return attempts
        val left = (wait.toMillis() - (elapsed() - waitFrom)).coerceIn(0, wait.toMillis())
        return attempts.copy(wait = java.time.Duration.ofMillis(left).takeIf { left > 0 })
    }
    override fun saveAttempts(attempts: CodeAttempts) {
        this.attempts = attempts
        waitFrom = elapsed()
    }
    override fun fingerprintCipher(): Cipher? = if (active() && fingerprint) Cipher.getInstance("AES/GCM/NoPadding") else null
    override fun fingerprintPassed(authorized: Cipher) = fingerprint
    override fun renewFingerprint() { fingerprint = true }
}

class FakeNotifier : Notifier {
    val posted = CopyOnWriteArrayList<Notice>()
    override fun post(notice: Notice) { posted += notice }
}

class FakeDocuments : Documents {
    val files = ConcurrentHashMap<String, ByteArray>()
    override fun read(uri: String): ByteArray = checkNotNull(files[uri]) { "No existe $uri" }
    override fun write(uri: String, bytes: ByteArray) { files[uri] = bytes }
}

/** The durable state of one "device", shared by the controllers of successive processes. */
class MemoryStore : Store {
    val operations = ConcurrentHashMap<String, Operation>()
    val sms = ConcurrentHashMap<Long, StoredSms>()
    @Volatile var cards = emptyList<WalletCard>()
    val classics = CopyOnWriteArrayList<ClassicCard>()
    /** Tombstones included. */
    val contacts = ConcurrentHashMap<String, ContactRecord>()
    val services = CopyOnWriteArrayList<ServiceReference>()
    @Volatile var preferences = Preferences()

    override suspend fun load() =
        Snapshot(operations.values.toList(), sms.values.toList(), cards, contacts.values.toList(), services.toList(), preferences, classics.toList())
    override suspend fun saveOperation(operation: Operation) { operations[operation.id] = operation }
    override suspend fun saveSms(rows: List<StoredSms>) = rows.forEach { sms[it.id] = it }
    override suspend fun saveCards(cards: List<WalletCard>) { this.cards = cards }
    override suspend fun deleteCard(number: String) { cards = cards.filterNot { it.card.number == number } }
    override suspend fun saveClassic(card: ClassicCard) { if (card !in classics) classics += card }
    override suspend fun deleteClassic(number: String) { classics.removeIf { it.number == number } }
    override suspend fun saveContacts(contacts: List<ContactRecord>) = contacts.forEach { this.contacts[it.contact.id] = it }
    override suspend fun saveServices(services: List<ServiceReference>) {
        services.forEach { new -> this.services.removeIf { it.kind == new.kind && it.value == new.value }; this.services += new }
    }
    override suspend fun deleteService(service: ServiceReference) { services.removeIf { it.kind == service.kind && it.value == service.value } }
    override suspend fun savePreferences(preferences: Preferences) { this.preferences = preferences }
}

/** One process of the app on a device made of [store], [inbox], [vault] and [code]. */
class Rig(
    val store: Store = MemoryStore(),
    val inbox: FakeInbox = FakeInbox(),
    val vault: FakeVault = FakeVault(),
    clock: () -> Instant,
    state: CoroutineDispatcher,
    io: CoroutineDispatcher,
    val modem: FakeModem = FakeModem(),
    val code: FakeAppCode = FakeAppCode(),
) {
    val notifier = FakeNotifier()
    val documents = FakeDocuments()
    /** The phone's address book; null as if READ_CONTACTS were denied. */
    var addressBook: List<PhoneContact>? = emptyList()
    /** What NeoTransfer+ keeps; null as if it were not installed. */
    var peer: () -> List<ContactRecord>? = { null }
    /** NeoTransfer+'s operations; null as if it were not installed. */
    var peerActivity: () -> List<ActivityRow>? = { null }
    val controller = AppController(store, modem, inbox, vault, code, notifier, documents, { addressBook ?: throw SecurityException() }, { peer() },
        { peerActivity() }, clock, state, io)

    fun status(id: String) = controller.operations.value.first { it.operation.id == id }
    fun state(id: String) = status(id).state
}

/** Synthetic PAGOxMOVIL bodies with the real templates; numbers and references are made up. */
object BankSms {
    fun busy() = "Usted tiene una solicitud en proceso, en breve recibira respuesta y podra realizar otra operacion. "
    fun balanceBpa(value: String) = "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n Saldo Disponible: CR $value CUP"
    fun balanceBandec(card: String, available: String) =
        "Banco Bandec La consulta de saldo fue completada. \n Cuenta;Saldo Contable;Saldo Disponible;Moneda \n${card.take(6)}XXXXXX${card.takeLast(4)}; CR $available ; CR $available ;CUP | \n"
    fun received(to: String, amount: String, ref: String, from: String = "5350000009") =
        "El titular del telefono $from le ha realizado una transferencia a la cuenta $to de $amount CUP. Nro. Transaccion $ref. Fecha: 15/10/2026."
    fun rejectedTransfer() = "Fallo la transferencia. Banco Bandec: LA CUENTA NO ESTA ASOCIADA A UNA TARJETA MAGNETICA"
    fun transferDone(to: String, amount: String, ref: String = "KW000TEST3") = "Banco Bandec:  La Transferencia fue completada. \n Fecha: 15/10/2026 \n " +
        "Beneficiario: ${to.take(4)}XXXXXXXX${to.takeLast(4)} \n Ordenante: CUP \n Monto: $amount CUP \n Nro. Transaccion: $ref \n Saldo restante: CR 700.00 CUP "
    fun registration(key: String) =
        "Banco Bandec:  La operacion de registro fue completada. Bienvenido a Transfermovil.Clave de Autenticacion: $key Guarde esta clave."
}
