package com.simplelink.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.simplelink.app.session.Role
import com.simplelink.app.session.SessionCode
import com.simplelink.app.session.SessionController
import com.simplelink.app.session.SessionUiState

private val Accent = Color(0xFF8EF0B1)
private val Panel = Color(0xFF12161B)
private val PanelRaised = Color(0xFF181D23)
private val SoftText = Color(0xFFAAB3BE)

@Composable
fun SimpleLinkRoot(
    state: SessionUiState,
    controller: SessionController,
    onEnableControl: () -> Unit,
    remoteContent: @Composable (Role) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0D1115),
                            Color(0xFF080A0D),
                            Color(0xFF08090B)
                        )
                    )
                )
        ) {
            AnimatedContent(
                targetState = state,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "screen"
            ) { current ->
                when (current) {
                    SessionUiState.Home -> HomeScreen(
                        onShare = controller::startSharing,
                        onAccess = controller::startJoining
                    )
                    is SessionUiState.Sharing -> ShareScreen(current, controller, onEnableControl)
                    is SessionUiState.Joining -> JoinScreen(current, controller)
                    is SessionUiState.Remote -> remoteContent(current.role)
                    is SessionUiState.Error -> ErrorScreen(current.message, controller::backHome)
                }
            }
        }
    }
}

@Composable
private fun BrandHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(Accent)
        )
        Text(
            text = "SIMPLELINK",
            fontSize = 12.sp,
            letterSpacing = 1.9.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun HomeScreen(onShare: () -> Unit, onAccess: (String) -> Unit) {
    var joinMode by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current

    if (joinMode) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 32.dp)
        ) {
            BrandHeader()
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Enter the code.",
                fontSize = 34.sp,
                lineHeight = 38.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.6).sp,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Type the 6-digit code shown on the other phone.",
                color = SoftText,
                fontSize = 15.sp,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(22.dp))

            val submit = {
                if (SessionCode.isValid(code)) {
                    keyboard?.hide()
                    onAccess(code)
                }
            }

            CodeEntryCard(
                code = code,
                onCodeChange = {
                    val normalized = SessionCode.normalize(it)
                    code = normalized
                    if (SessionCode.isValid(normalized)) keyboard?.hide()
                },
                onSubmit = submit
            )
            Spacer(Modifier.height(12.dp))
            GlassActionButton(
                text = "Connect",
                primary = true,
                enabled = SessionCode.isValid(code),
                onClick = submit
            )
            Spacer(Modifier.height(10.dp))
            GlassActionButton(
                text = "Cancel",
                onClick = {
                    keyboard?.hide()
                    joinMode = false
                    code = ""
                }
            )
            Spacer(Modifier.height(20.dp))
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 38.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                BrandHeader()
                Spacer(Modifier.height(44.dp))
                Text(
                    text = "Remote help.\nMade simple.",
                    fontSize = 38.sp,
                    lineHeight = 42.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.8).sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "One code, one approval, then you’re connected.",
                    color = SoftText,
                    fontSize = 16.sp,
                    lineHeight = 23.sp
                )
                Spacer(Modifier.height(34.dp))
                InfoStrip()
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassActionButton(
                    text = "Share This Phone",
                    subtitle = "Show a code for someone you trust",
                    onClick = onShare,
                    primary = true
                )
                GlassActionButton(
                    text = "Access Another Phone",
                    subtitle = "Enter the code shown on their phone",
                    onClick = { joinMode = true }
                )
            }
        }
    }
}

@Composable
private fun InfoStrip() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.07f)),
                RoundedCornerShape(20.dp)
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center
        ) {
            Text("✓", color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text(
            text = "The other phone always has to approve the connection.",
            color = SoftText,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
    }
}

@Composable
private fun CodeEntryCard(code: String, onCodeChange: (String) -> Unit, onSubmit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Panel)
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                RoundedCornerShape(24.dp)
            )
            .padding(16.dp)
    ) {
        Text(
            text = "6-DIGIT CODE",
            color = SoftText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.3.sp
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text(
                    "000 000",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    color = Color(0xFF56606C)
                )
            },
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                color = MaterialTheme.colorScheme.onBackground,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 7.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent.copy(alpha = 0.8f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.10f),
                focusedContainerColor = Color(0xFF0D1014),
                unfocusedContainerColor = Color(0xFF0D1014),
                cursorColor = Accent
            )
        )
    }
}

@Composable
private fun ShareScreen(
    state: SessionUiState.Sharing,
    controller: SessionController,
    onEnableControl: () -> Unit
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
    ) {
        val compact = maxHeight < 700.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = 22.dp,
                    end = 22.dp,
                    top = if (compact) 16.dp else 22.dp,
                    bottom = if (compact) 18.dp else 30.dp
                )
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
            ) {
                BrandHeader()
                Spacer(Modifier.height(if (compact) 16.dp else 30.dp))
                Text(
                    text = "Share this code",
                    fontSize = if (compact) 27.sp else 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(Modifier.height(if (compact) 5.dp else 8.dp))
                Text(
                    text = "Give this code only to the person helping you.",
                    color = SoftText,
                    fontSize = if (compact) 14.sp else 15.sp,
                    lineHeight = if (compact) 19.sp else 21.sp
                )
                Spacer(Modifier.height(if (compact) 16.dp else 24.dp))

                CodeCard(
                    label = "YOUR CODE",
                    code = SessionCode.format(state.code),
                    status = state.status,
                    compact = compact
                )

                Spacer(Modifier.height(if (compact) 12.dp else 18.dp))

                if (state.rescueInternetPrompt) {
                    SetupCard(
                        title = "Internet is off",
                        body = "Open the Internet panel, turn it on, and SimpleLink will continue automatically.",
                        action = "Turn On Internet",
                        onAction = controller::openInternetHelp,
                        primary = true,
                        compact = compact
                    )
                }

                Spacer(Modifier.height(12.dp))
            }

            GlassActionButton(
                text = "Stop Sharing",
                subtitle = if (compact) null else "Close this code and return home",
                onClick = controller::stopSharing,
                compact = compact
            )
        }
    }

    state.pending?.let { request ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Connection request") },
            text = { Text(request.label + " wants to access this phone.") },
            confirmButton = {
                Button(onClick = controller::allowPending) { Text("Allow") }
            },
            dismissButton = {
                TextButton(onClick = controller::denyPending) { Text("Deny") }
            }
        )
    }
}

@Composable
private fun JoinScreen(state: SessionUiState.Joining, controller: SessionController) {
    LaunchedEffect(state.code) {
        if (state.code.isBlank()) controller.backHome()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 38.dp)
    ) {
        BrandHeader()
        Spacer(Modifier.height(30.dp))
        Text(
            text = "Connecting…",
            fontSize = 30.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Waiting for the other phone to approve you.",
            color = SoftText,
            fontSize = 15.sp
        )
        Spacer(Modifier.height(24.dp))

        CodeCard(
            label = "CONNECTING TO",
            code = SessionCode.format(state.code),
            status = state.status
        )

        Spacer(Modifier.weight(1f))

        if (state.rescueAvailable) {
            GlassActionButton(
                text = "Send Rescue SMS",
                subtitle = "Use when the other phone has no internet",
                onClick = { controller.requestRescueSms(state.code) },
                primary = true
            )
            Spacer(Modifier.height(12.dp))
        }

        GlassActionButton(
            text = "Cancel",
            subtitle = "Stop trying and return home",
            onClick = controller::backHome
        )
    }
}

@Composable
private fun CodeCard(
    label: String,
    code: String,
    status: String,
    compact: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF171C21),
                        Color(0xFF11151A)
                    )
                )
            )
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)),
                RoundedCornerShape(28.dp)
            )
            .padding(
                horizontal = if (compact) 16.dp else 20.dp,
                vertical = if (compact) 16.dp else 24.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            color = SoftText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp
        )
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        Text(
            text = code,
            fontSize = if (compact) 36.sp else 42.sp,
            lineHeight = if (compact) 40.sp else 48.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 5.sp,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Accent.copy(alpha = 0.10f))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Accent)
            )
            Text(
                text = status,
                color = Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun SetupCard(
    title: String,
    body: String,
    action: String,
    onAction: () -> Unit,
    primary: Boolean = false,
    compact: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(PanelRaised)
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                RoundedCornerShape(24.dp)
            )
            .padding(if (compact) 14.dp else 18.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = if (compact) 16.sp else 17.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        Text(
            text = body,
            color = SoftText,
            fontSize = if (compact) 12.sp else 13.sp,
            lineHeight = if (compact) 17.sp else 19.sp
        )
        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
        GlassActionButton(
            text = action,
            onClick = onAction,
            primary = primary,
            compact = true
        )
    }
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(22.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(Panel)
                .padding(22.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Something went wrong",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = message,
                    color = SoftText,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                GlassActionButton("Back", onBack, primary = true)
            }
        }
    }
}
