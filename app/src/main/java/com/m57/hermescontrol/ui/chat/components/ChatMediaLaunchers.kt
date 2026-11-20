package com.m57.hermescontrol.ui.chat.components

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.m57.hermescontrol.ExternalActivityLifecycleGuard
import com.m57.hermescontrol.R
import com.m57.hermescontrol.data.model.Attachment
import com.m57.hermescontrol.ui.chat.VoiceNoteRecorder
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

class ChatMediaLaunchers(
    val isListening: Boolean,
    val isRecordingVoice: Boolean,
    val isVoiceNoteLocked: Boolean,
    val onMicTap: () -> Unit,
    val onStartVoice: () -> Unit = {},
    val onCameraTap: () -> Unit,
    val onImageTap: () -> Unit,
    val onFileTap: () -> Unit,
    val onMicHoldStart: () -> Unit = {},
    val onMicHoldEnd: () -> Unit = {},
    val onMicHoldCancel: () -> Unit = {},
    val onMicLock: () -> Unit = {},
    /** Normalized 0–1 mic level while recording; read fresh at draw time. */
    val amplitudeProvider: () -> Float = { 0f },
)

@Composable
fun rememberChatMediaLaunchers(
    inputState: TextFieldState,
    onAddAttachment: (uri: String, name: String, mimeType: String, size: Long) -> Unit,
    onAddAttachments: (List<Attachment>) -> Unit,
    onVoiceNoteRecorded: (file: File) -> Unit,
    onShowMessage: (String) -> Unit,
    launchExternalActivity: (() -> Unit) -> Unit,
    isTranscribingVoiceNote: Boolean,
    context: Context = LocalContext.current,
): ChatMediaLaunchers {
    var isListening by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val currentOnAddAttachment by rememberUpdatedState(onAddAttachment)
    val currentOnAddAttachments by rememberUpdatedState(onAddAttachments)
    val currentOnVoiceNoteRecorded by rememberUpdatedState(onVoiceNoteRecorded)
    val currentOnShowMessage by rememberUpdatedState(onShowMessage)
    val currentLaunchExternalActivity by rememberUpdatedState(launchExternalActivity)

    val sttPermissionDeniedMsg = stringResource(R.string.stt_permission_denied)
    val cameraErrorMsg = stringResource(R.string.chat_camera_error)
    val voiceRecordFailedMsg = stringResource(R.string.chat_voice_record_failed)

    // Hands-free/press-to-talk voice recorder: every clip uploads to the
    // dashboard for server-side transcription. The on-device recognizer is not
    // used at all — a tap starts a locked recording, a hold records while the
    // finger stays down, and the action button submits either one.
    val voiceNoteRecorder = remember { VoiceNoteRecorder(context) }
    var isRecordingVoice by remember { mutableStateOf(false) }

    // Locked = the recording continues after the finger lifts (slide up
    // during the hold); the action button then submits it.
    var isVoiceNoteLocked by remember { mutableStateOf(false) }
    var autoSubmitAfterSilence by remember { mutableStateOf(false) }
    var voiceHasSpeech by remember { mutableStateOf(false) }
    var voiceSilenceStartedAt by remember { mutableStateOf(0L) }

    fun finishVoiceRecording() {
        val recordedFile = voiceNoteRecorder.stop()
        isRecordingVoice = false
        isVoiceNoteLocked = false
        autoSubmitAfterSilence = false
        voiceHasSpeech = false
        voiceSilenceStartedAt = 0L
        if (recordedFile != null) {
            currentOnVoiceNoteRecorded(recordedFile)
        }
    }

    // App-owned max-duration cap: the timer drives the same
    // finishVoiceRecording() path as the hold release, so exactly one code
    // path ever calls stop() — MediaRecorder's own auto-stop used to race
    // the release and discard a valid max-length clip (review, PR #1250).
    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            delay(VoiceNoteRecorder.MAX_DURATION_MS.toLong())
            if (voiceNoteRecorder.isActive) {
                finishVoiceRecording()
            }
        }
    }

    DisposableEffect(voiceNoteRecorder) {
        onDispose {
            voiceNoteRecorder.cancel()
        }
    }

    // Mic permission launcher
    val micPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (granted) {
                // Granting starts the same server-transcribed recording the tap
                // would have started — the on-device recognizer is never used.
                if (voiceNoteRecorder.start()) {
                    isRecordingVoice = true
                    isVoiceNoteLocked = true
                } else {
                    currentOnShowMessage(voiceRecordFailedMsg)
                }
            } else {
                currentOnShowMessage(sttPermissionDeniedMsg)
            }
        }

    // Hold-to-record permission: unlike the launcher above, granting does NOT
    // auto-start dictation — the user records by holding the mic again.
    val voiceNotePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            if (!granted) {
                currentOnShowMessage(sttPermissionDeniedMsg)
            }
        }

    // Multi-file picker for attachments (issue #195)
    val filePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetMultipleContents(),
        ) { uris: List<Uri> ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            val attachments =
                uris.mapNotNull { uri ->
                    runCatching {
                        var name = uri.lastPathSegment ?: "file"
                        var size = 0L
                        context.contentResolver
                            .query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                                null,
                                null,
                                null,
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) {
                                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                                    if (nameIdx >= 0 && !cursor.isNull(nameIdx)) name = cursor.getString(nameIdx)
                                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                                }
                            }
                        Attachment(
                            uri = uri.toString(),
                            name = name,
                            mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream",
                            size = size,
                        )
                    }.onFailure { error ->
                        Log.w("ChatScreen", "Skipping unreadable picked attachment", error)
                    }.getOrNull()
                }
            currentOnAddAttachments(attachments)
        }

    // Camera photo launcher (issue #195)
    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicture(),
        ) { success ->
            ExternalActivityLifecycleGuard.externalActivityReturned()
            val uri = pendingCameraUri
            pendingCameraUri = null
            if (success && uri != null) {
                try {
                    val fileName =
                        "photo_${
                            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(
                                Date(),
                            )
                        }.jpg"
                    val inputStream = context.contentResolver.openInputStream(uri)
                    val size = inputStream?.use { it.available().toLong() } ?: 0L
                    currentOnAddAttachment(uri.toString(), fileName, "image/jpeg", size)
                } catch (e: Exception) {
                    Log.e("ChatScreen", "Camera capture failed", e)
                    currentOnShowMessage(cameraErrorMsg)
                }
            }
        }

    val onMicTap: () -> Unit = {
        autoSubmitAfterSilence = false
        if (isTranscribingVoiceNote) {
            // Single-flight: a server transcription owns the voice pipeline
            // until it lands (review, PR #1250).
        } else if (isVoiceNoteLocked) {
            // The locked recording's action button submits the note.
            finishVoiceRecording()
        } else if (isRecordingVoice) {
            // A tap while recording discards the in-flight voice note.
            voiceNoteRecorder.cancel()
            isRecordingVoice = false
        } else if (isListening) {
            isListening = false
        } else if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            // The on-device recognizer is deliberately bypassed: it resolves to
            // a cloud STT provider that is unreachable here. A tap starts a
            // locked (hands-free) recording instead, and the action button
            // submits it to the dashboard's server-side transcription.
            if (voiceNoteRecorder.start()) {
                isRecordingVoice = true
                isVoiceNoteLocked = true
            } else {
                currentOnShowMessage(voiceRecordFailedMsg)
            }
        } else {
            currentLaunchExternalActivity {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val onStartVoice: () -> Unit = {
        if (!isTranscribingVoiceNote && !isListening && !isRecordingVoice && !voiceNoteRecorder.isActive) {
            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO,
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                if (voiceNoteRecorder.start()) {
                    isRecordingVoice = true
                    isVoiceNoteLocked = true
                    autoSubmitAfterSilence = true
                    voiceHasSpeech = false
                    voiceSilenceStartedAt = 0L
                } else {
                    currentOnShowMessage(voiceRecordFailedMsg)
                }
            } else {
                currentLaunchExternalActivity {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    val onMicHoldStart: () -> Unit = {
        autoSubmitAfterSilence = false
        if (isTranscribingVoiceNote) {
            // Single-flight: no new recording while a transcription runs.
        } else if (voiceNoteRecorder.isActive || isListening) {
            // A voice note or a dictation session is already running.
        } else if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            if (voiceNoteRecorder.start()) {
                isRecordingVoice = true
            } else {
                currentOnShowMessage(voiceRecordFailedMsg)
            }
        } else {
            // The denial message comes from voiceNotePermissionLauncher's
            // result callback only — showing it here fired it before the
            // dialog even opened (review, PR #1250).
            currentLaunchExternalActivity {
                voiceNotePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val onMicHoldEnd: () -> Unit = {
        if (voiceNoteRecorder.isActive) {
            finishVoiceRecording()
        }
    }

    val onMicHoldCancel: () -> Unit = {
        if (voiceNoteRecorder.isActive) {
            voiceNoteRecorder.cancel()
        }
        isRecordingVoice = false
        isVoiceNoteLocked = false
        autoSubmitAfterSilence = false
        voiceHasSpeech = false
        voiceSilenceStartedAt = 0L
    }

    // Locking keeps the recorder running once the finger lifts; only the
    // panel's delete action or the action button can end it then.
    val onMicLock: () -> Unit = {
        if (voiceNoteRecorder.isActive) {
            isVoiceNoteLocked = true
        }
    }

    // Mic-level feed for the recording blob (VoiceMicBlob). MediaRecorder's
    // maxAmplitude is the PEAK since the last read, so this poller must stay
    // the ONLY caller while recording. sqrt compression spreads speech
    // dynamics the way Telegram's RMS/1800 normalization does.
    val recordingAmplitude = remember { mutableStateOf(0f) }
    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            while (isRecordingVoice) {
                val level =
                    sqrt(voiceNoteRecorder.currentAmplitude().coerceAtLeast(0) / 32767f)
                recordingAmplitude.value = level
                if (autoSubmitAfterSilence && isRecordingVoice) {
                    val now = SystemClock.elapsedRealtime()
                    if (level >= AUTO_SUBMIT_SPEECH_LEVEL) {
                        voiceHasSpeech = true
                        voiceSilenceStartedAt = 0L
                    } else if (voiceHasSpeech) {
                        if (voiceSilenceStartedAt == 0L) {
                            voiceSilenceStartedAt = now
                        } else if (now - voiceSilenceStartedAt >= AUTO_SUBMIT_SILENCE_MS) {
                            finishVoiceRecording()
                        }
                    }
                }
                delay(AMPLITUDE_POLL_MS)
            }
        } else {
            recordingAmplitude.value = 0f
        }
    }

    val onCameraTap: () -> Unit = {
        try {
            val timeStamp =
                SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val photoFile =
                File.createTempFile("camera_${timeStamp}_", ".jpg", context.cacheDir)
            val uri =
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    photoFile,
                )
            pendingCameraUri = uri
            currentLaunchExternalActivity {
                cameraLauncher.launch(uri)
            }
        } catch (e: Exception) {
            Log.e("ChatScreen", "Camera launch failed", e)
        }
    }

    val onImageTap: () -> Unit = {
        currentLaunchExternalActivity {
            filePickerLauncher.launch("image/*")
        }
    }

    val onFileTap: () -> Unit = {
        currentLaunchExternalActivity {
            filePickerLauncher.launch("*/*")
        }
    }

    return remember(isListening, isRecordingVoice, isVoiceNoteLocked, isTranscribingVoiceNote) {
        ChatMediaLaunchers(
            isListening = isListening || isRecordingVoice,
            isRecordingVoice = isRecordingVoice,
            isVoiceNoteLocked = isVoiceNoteLocked,
            onMicTap = onMicTap,
            onStartVoice = onStartVoice,
            onCameraTap = onCameraTap,
            onImageTap = onImageTap,
            onFileTap = onFileTap,
            onMicHoldStart = onMicHoldStart,
            onMicHoldEnd = onMicHoldEnd,
            onMicHoldCancel = onMicHoldCancel,
            onMicLock = onMicLock,
            amplitudeProvider = { recordingAmplitude.value },
        )
    }
}

/** Telegram samples their amplitude pipeline roughly this fast. */
private const val AMPLITUDE_POLL_MS = 60L
private const val AUTO_SUBMIT_SILENCE_MS = 2_200L
private const val AUTO_SUBMIT_SPEECH_LEVEL = 0.05f
