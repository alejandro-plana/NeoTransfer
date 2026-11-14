package dev.duardo.neotransfer.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoConfirmSheet
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.PinDots
import dev.duardo.neotransfer.designsystem.component.SecureWindow
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BrandMark
import dev.duardo.neotransfer.ui.model.CodeUi
import dev.duardo.neotransfer.ui.model.ForgotCodeUi
import dev.duardo.neotransfer.ui.model.LockUi
import dev.duardo.neotransfer.ui.onboarding.PinKeypad

/** Digits of the app code. */
const val CODE_DIGITS = 6

/**
 * The app code typed on the app's keypad: what is asked right above the dots, what went wrong under them, the keypad.
 * [spaced]: on a whole screen that group (with [header] over it) sits in the middle of the room left; in a sheet
 * everything follows on. While [CodeUi.waiting] the keypad takes nothing and shows it.
 */
@Composable
fun CodePad(
    state: CodeUi,
    onKey: (KeypadKey) -> Unit,
    modifier: Modifier = Modifier,
    spaced: Boolean = true,
    fingerprint: (() -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (spaced) Spacer(Modifier.weight(1f))
        header?.invoke()
        Text(
            state.title,
            Modifier.fillMaxWidth().padding(horizontal = NeoSpace.gutter),
            style = if (spaced) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (spaced) NeoSpace.xxl else NeoSpace.l))
        PinDots(CODE_DIGITS, state.entered, error = state.error != null && !state.waiting)
        Column(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(NeoSpace.xs),
        ) {
            listOfNotNull(state.error, state.note).forEach {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = Neo.colors.danger, textAlign = TextAlign.Center)
            }
        }
        if (spaced) Spacer(Modifier.weight(1f))
        PinKeypad(onKey, Modifier.alpha(if (state.waiting) 0.38f else 1f), fingerprint = fingerprint)
    }
}

/**
 * The lock with the app code on: the mark, «Escribe el código», the dots and what went wrong as one group in the middle;
 * the fingerprint in the keypad when it stands in for the code; «¿Olvidaste el código?» below. A new code after the
 * fingerprint is set as in Ajustes. Hidden from screenshots.
 */
@Composable
internal fun CodeLockScreen(state: LockUi.Code, actions: LockActions, modifier: Modifier = Modifier) {
    if (state.creating) return CodeScreen(state.code, object : CodeActions {
        override fun key(key: KeypadKey) = actions.key(key)
        override fun close() = actions.cancelCreate()
    }, modifier)
    SecureWindow()
    Box(modifier.fillMaxSize()) {
        BrandBackdrop {
            CodePad(
                state.code, actions::key, Modifier.weight(1f),
                fingerprint = if (state.fingerprint) actions::fingerprint else null,
                header = { BrandMark(64.dp, Modifier.padding(bottom = NeoSpace.xl)) },
            )
            NeoTextButton(
                "¿Olvidaste el código?", actions::forgot,
                Modifier.fillMaxWidth().padding(horizontal = NeoSpace.gutter).padding(vertical = NeoSpace.s),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    state.forgot?.let { ForgotCodeSheet(it, actions) }
}

/**
 * «¿Olvidaste el código?»: the fingerprint, when it still stands for the code, leads to a new one; erasing the app's
 * data is confirmed in the same sheet, which opens on that confirmation when there is no fingerprint to offer.
 */
@Composable
fun ForgotCodeSheet(state: ForgotCodeUi, actions: LockActions) {
    if (state.erasing) return NeoConfirmSheet(
        title = "¿Borrar los datos de la app?",
        text = "Sin el código solo puedes empezar de nuevo: se borran todos los datos de NeoTransfer",
        confirm = "Borrar",
        dismiss = "Cancelar",
        onConfirm = actions::confirmErase,
        onDismiss = actions::closeForgot,
        destructive = true,
    )
    NeoBottomSheet(onDismissRequest = actions::closeForgot) {
        Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
            Text("¿Olvidaste el código?", style = MaterialTheme.typography.headlineSmall)
            if (state.erasesKeys) Text(
                "Si entras con la huella se borrarán las claves guardadas", Modifier.padding(top = NeoSpace.s),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NeoButton(
                "Entrar con la huella", actions::forgotFingerprint, Modifier.padding(top = NeoSpace.xxl),
                style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_fingerprint,
            )
            NeoButton("Borrar los datos de la app", actions::eraseData, Modifier.padding(top = NeoSpace.m), style = NeoButtonStyle.Danger)
            NeoTextButton("Cancelar", actions::closeForgot, Modifier.fillMaxWidth().padding(top = NeoSpace.xs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

interface CodeActions {
    fun key(key: KeypadKey)
    fun close()
}

/**
 * Setting or changing the app code: «Código de acceso» under the ✕, then what is asked (the current one first when
 * changing, then the new one twice) right above the dots. Hidden from screenshots.
 */
@Composable
fun CodeScreen(state: CodeUi, actions: CodeActions, modifier: Modifier = Modifier) {
    SecureWindow()
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::close, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar") },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(bottom = NeoSpace.l)) {
            Text("Código de acceso", Modifier.padding(horizontal = NeoSpace.gutter), style = MaterialTheme.typography.headlineMedium)
            CodePad(state, actions::key, Modifier.weight(1f))
        }
    }
}

/**
 * The app code asked where it stands in for the fingerprint, or to confirm turning it off: [title] says what for,
 * [subtitle] to whom; the prompt, smaller, under them.
 */
@Composable
fun CodeSheet(title: String, state: CodeUi, onKey: (KeypadKey) -> Unit, onDismiss: () -> Unit, subtitle: String? = null) {
    SecureWindow()
    NeoBottomSheet(onDismissRequest = onDismiss, title = title) {
        subtitle?.let {
            Text(it, Modifier.padding(horizontal = NeoSpace.gutter), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        CodePad(state, onKey, Modifier.padding(top = NeoSpace.xl), spaced = false)
    }
}
