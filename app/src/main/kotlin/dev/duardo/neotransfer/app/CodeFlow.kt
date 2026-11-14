package dev.duardo.neotransfer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.format.NoBreak
import dev.duardo.neotransfer.engine.CodeEntry
import dev.duardo.neotransfer.engine.PhoneLock
import dev.duardo.neotransfer.ui.lock.CODE_DIGITS
import dev.duardo.neotransfer.ui.lock.CodeActions
import dev.duardo.neotransfer.ui.lock.CodeScreen
import dev.duardo.neotransfer.ui.model.CodeSettingsUi
import dev.duardo.neotransfer.ui.model.CodeUi
import dev.duardo.neotransfer.ui.model.ForgotCodeUi
import dev.duardo.neotransfer.ui.settings.CodeSettingsActions
import dev.duardo.neotransfer.ui.settings.CodeSettingsScreen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

internal const val ENTER_CODE = "Escribe el código"
internal const val KEYS_ERASED = "Se borraron las claves guardadas"

/** What [AppModel.seal] did. */
sealed interface Seal {
    data object Saved : Seal
    data object Cancelled : Seal
    /** Too many failed fingerprints: the sensor rests for a while. */
    data object LockedOut : Seal
    data class Failed(val message: String) : Seal
}

/** Digits of the app code as they are typed. */
class CodeDigits {
    private val digits = CharArray(CODE_DIGITS)
    var entered by mutableIntStateOf(0)
        private set

    /** Takes [key]; once the code has every digit, returns it (the caller wipes it) and starts empty again. */
    fun key(key: KeypadKey): CharArray? {
        when (key) {
            KeypadKey.Backspace -> if (entered > 0) entered--
            is KeypadKey.Digit -> if (entered < CODE_DIGITS) {
                digits[entered] = '0' + key.value
                entered++
                if (entered == CODE_DIGITS) return digits.copyOf().also { clear() }
            }
            KeypadKey.Decimal -> Unit
        }
        return null
    }

    fun clear() {
        digits.fill('\u0000')
        entered = 0
    }
}

/**
 * The app code typed against [dev.duardo.neotransfer.engine.AppController.enterCode]: «Código incorrecto»; from the 5th
 * wrong one in a row the wait, counting down live; with the keys under the code the tries left and the erase. [onRight]
 * once it is right.
 */
class CodeCheck(private val app: AppModel, private val onRight: () -> Unit) {
    private val digits = CodeDigits()
    private var error by mutableStateOf<String?>(null)
    private var note by mutableStateOf<String?>(null)
    private var waiting by mutableStateOf(false)
    private var checking = false
    private var countdown: Job? = null

    fun ui(title: String = ENTER_CODE) = CodeUi(title, digits.entered, error, note, waiting)

    fun key(key: KeypadKey) {
        if (waiting || checking) return
        if (key is KeypadKey.Digit && digits.entered == 0) {
            error = null
            note = null
        }
        val code = digits.key(key) ?: return
        checking = true
        app.launch {
            try {
                when (val entry = app.controller.enterCode(code)) {
                    is CodeEntry.Right -> {
                        if (entry.keysErased) app.message(KEYS_ERASED)
                        onRight()
                    }
                    is CodeEntry.Wrong -> {
                        note = when {
                            entry.erased -> KEYS_ERASED
                            entry.left == 1 -> "Te queda 1 intento antes de borrar las claves"
                            entry.left != null -> "Te quedan ${entry.left} intentos antes de borrar las claves"
                            else -> null
                        }
                        val pause = entry.wait
                        if (pause != null) wait(pause) else error = "Código incorrecto"
                    }
                    is CodeEntry.Waiting -> wait(entry.left)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A Keystore or storage failure, never a crash on the lock screen.
                app.message(e.message?.takeIf { e is IllegalStateException || e is IllegalArgumentException } ?: "No se pudo comprobar el código")
            } finally {
                checking = false
            }
        }
    }

    /** A wait that still runs (the lock came back, or the process restarted) shows at once. */
    fun resume() {
        app.launch { runCatching { app.controller.codeAttempts() }.getOrNull()?.wait?.let(::wait) }
    }

    fun dispose() {
        countdown?.cancel()
        digits.clear()
    }

    /** Counts [left] down second by second; typing after it re-checks what is left of the wait. */
    private fun wait(left: Duration) {
        countdown?.cancel()
        countdown = app.launch {
            var seconds = (left.toMillis() + 999) / 1000
            while (seconds > 0) {
                waiting = true
                error = "Demasiados intentos. Prueba en ${waitLabel(seconds)}"
                delay(1_000)
                seconds--
            }
            waiting = false
            error = null
        }
    }
}

/** «30 s», «2 min», «1 min 59 s», never split across lines. */
internal fun waitLabel(seconds: Long): String = when {
    seconds < 60 -> "$seconds s"
    seconds % 60 == 0L -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}.replace(' ', NoBreak)

/** A new code typed twice: «Crea un código de 6 dígitos», «Repite el código»; when they differ it starts over. [onSet] wipes it. */
class NewCode(private val onSet: (CharArray) -> Unit) {
    private val digits = CodeDigits()
    private var first: CharArray? = null
    private var repeating by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    fun ui() = CodeUi(if (repeating) "Repite el código" else "Crea un código de $CODE_DIGITS dígitos", digits.entered, error)

    fun key(key: KeypadKey) {
        if (key is KeypadKey.Digit && digits.entered == 0) error = null
        val code = digits.key(key) ?: return
        val previous = first
        if (previous == null) {
            first = code
            repeating = true
            return
        }
        first = null
        repeating = false
        val same = previous.contentEquals(code)
        previous.fill('\u0000')
        if (same) {
            onSet(code)
        } else {
            code.fill('\u0000')
            error = "Los códigos no coinciden"
        }
    }

    fun clear() {
        digits.clear()
        first?.fill('\u0000')
        first = null
        repeating = false
        error = null
    }
}

/** The lock screen while the app code is on ([dev.duardo.neotransfer.ui.model.LockUi.Code]). */
class CodeLock(app: AppModel) {
    val check = CodeCheck(app) {}
    /** After «Entrar con la huella» stood in for a forgotten code: the new one. */
    val next = NewCode { code ->
        app.launch {
            runCatching { app.controller.createCode(code) }
                .onSuccess { erased -> if (erased) app.message(KEYS_ERASED) }
                .onFailure { app.message(it.message ?: "No se pudo guardar el código") }
        }
    }
    var fingerprint by mutableStateOf(false)
    var forgot by mutableStateOf<ForgotCodeUi?>(null)
    var creating by mutableStateOf(false)

    fun dispose() {
        check.dispose()
        next.clear()
    }
}

/** The app code asked in a sheet where it stands in for the fingerprint, or to confirm turning it off. */
class CodeAsk(app: AppModel, val title: String, val subtitle: String? = null) {
    val answer = CompletableDeferred<Boolean>()
    val check = CodeCheck(app) { answer.complete(true) }
}

/**
 * Setting the app code, or changing it ([change]): the current one first, then the new one twice. [onSet] once saved.
 * Hidden from screenshots.
 */
class CodeDestination(private val app: AppModel, change: Boolean, private val onSet: (CodeDestination) -> Unit) : Destination(), CodeActions {
    private var current by mutableStateOf(change)
    private val check = CodeCheck(app) { current = false }
    private val next = NewCode(::save)
    private var saving = false

    init {
        if (change) check.resume()
    }

    @Composable
    override fun Content(app: AppModel, book: Book) {
        CodeScreen(if (current) check.ui() else next.ui(), this)
    }

    override fun key(key: KeypadKey) {
        if (saving) return
        if (current) check.key(key) else next.key(key)
    }

    override fun close() {
        check.dispose()
        next.clear()
        app.navigator.remove(this)
    }

    private fun save(code: CharArray) {
        saving = true
        app.launch {
            try {
                app.controller.createCode(code)
                check.dispose()
                onSet(this)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.message(e.message?.takeIf { e is IllegalStateException || e is IllegalArgumentException } ?: "No se pudo guardar el código")
            } finally {
                saving = false
            }
        }
    }
}

/** Ajustes › Código de acceso while it is on. */
class CodeSettingsDestination(private val app: AppModel) : Destination(), CodeSettingsActions {
    private var busy = false

    @Composable
    override fun Content(app: AppModel, book: Book) {
        CodeSettingsScreen(CodeSettingsUi(book.domain.code.keysUnderCode, fingerprint = app.controller.phoneLock() == PhoneLock.FINGERPRINT), this)
    }

    override fun back() { app.navigator.remove(this) }

    override fun change() = app.navigator.push(CodeDestination(app, change = true) { app.navigator.remove(it) })

    override fun protectKeys(on: Boolean) = once { app.protectKeys(on) }

    override fun disable() = once { if (app.disableCode()) app.navigator.remove(this) }

    private fun once(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        app.launch {
            try {
                block()
            } finally {
                busy = false
            }
        }
    }
}
