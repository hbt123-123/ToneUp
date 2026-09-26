package com.toneup.app.ui.feature.aiphoto

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import java.io.File

/** 相机权限状态 */
enum class CameraPermissionState {
    UNKNOWN, GRANTED, DENIED_RATIONALE, PERMANENTLY_DENIED
}

/**
 * FR-AI-05 权限三态引导 + CameraX 取景拍照（FR-AI-01）。
 * 申请前用途说明前置弹窗；拒绝置灰；永久拒绝跳系统设置。
 */
@Composable
fun rememberCameraPermissionFlow(): CameraPermissionFlowState {
    val context = LocalContext.current
    var state by remember {
        mutableStateOf(
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) CameraPermissionState.GRANTED else CameraPermissionState.UNKNOWN
        )
    }
    var showPrePrompt by remember { mutableStateOf(state == CameraPermissionState.UNKNOWN) }
    var denialDismissed by remember { mutableStateOf(false) }
    // M-134：请求被拒次数，用于区分首次拒绝与真正的永久拒绝
    var denialCount by remember { mutableStateOf(0) }

    // M-133：权限状态此前仅在首次组合时采样一次，用户去系统设置开启权限后返回页面不会刷新；
    // 改为每次回到前台重新检查，已授权则升级为 GRANTED（未授权时保留既有引导状态）
    LifecycleResumeEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            state = CameraPermissionState.GRANTED
        }
        onPauseOrDispose { }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            state = CameraPermissionState.GRANTED
        } else {
            denialDismissed = false
            denialCount++
            val activity = context.findActivity()
            val shouldShowRationale = activity?.let {
                androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    it, Manifest.permission.CAMERA
                )
            } ?: false
            // M-134：部分 OEM ROM/系统版本在首次拒绝（甚至勾选"不再询问"前）也可能返回
            // rationale = false，单次 false 即判永久拒绝会误判；仅当已多次被拒且仍无理由
            // 弹窗时才判定为永久拒绝，首次拒绝按可再解释处理
            state =
                if (shouldShowRationale || denialCount < 2) CameraPermissionState.DENIED_RATIONALE
                else CameraPermissionState.PERMANENTLY_DENIED
        }
    }

    return remember(state, showPrePrompt, denialDismissed, launcher) {
        CameraPermissionFlowState(
            state = state,
            showPrePrompt = showPrePrompt,
            showDenialDialog = !denialDismissed,
            dismissPrePrompt = { showPrePrompt = false },
            dismissDenialDialog = { denialDismissed = true },
            request = {
                showPrePrompt = false
                launcher.launch(Manifest.permission.CAMERA)
            },
            openSettings = { context.openAppSettings() }
        )
    }
}

data class CameraPermissionFlowState(
    val state: CameraPermissionState,
    val showPrePrompt: Boolean,
    /** 拒绝/永久拒绝弹窗是否仍展示；用户取消后隐藏，由页面兜底引导 */
    val showDenialDialog: Boolean,
    val dismissPrePrompt: () -> Unit,
    val dismissDenialDialog: () -> Unit,
    val request: () -> Unit,
    val openSettings: () -> Unit
)

@Composable
fun CameraPermissionDialogs(flow: CameraPermissionFlowState) {
    if (flow.showPrePrompt && flow.state != CameraPermissionState.GRANTED) {
        AlertDialog(
            onDismissRequest = flow.dismissPrePrompt,
            title = { Text("需要相机权限") },
            text = { Text("用于拍摄你的手写答案以进行 AI 诊断") },
            confirmButton = { Button(onClick = flow.request) { Text("允许") } },
            dismissButton = {
                OutlinedButton(onClick = flow.dismissPrePrompt) { Text("暂不") }
            }
        )
    } else if (flow.state == CameraPermissionState.DENIED_RATIONALE && flow.showDenialDialog) {
        AlertDialog(
            onDismissRequest = flow.dismissDenialDialog,
            title = { Text("相机权限被拒绝") },
            text = { Text("拍照纠错需要使用相机。你可以重新发起授权，其他功能不受影响。") },
            confirmButton = { Button(onClick = flow.request) { Text("去开启") } },
            dismissButton = { OutlinedButton(onClick = flow.dismissDenialDialog) { Text("取消") } }
        )
    } else if (flow.state == CameraPermissionState.PERMANENTLY_DENIED && flow.showDenialDialog) {
        AlertDialog(
            onDismissRequest = flow.dismissDenialDialog,
            title = { Text("相机权限已被永久拒绝") },
            text = { Text("请在系统设置中手动开启相机权限后返回") },
            confirmButton = { Button(onClick = flow.openSettings) { Text("打开设置") } },
            dismissButton = { OutlinedButton(onClick = flow.dismissDenialDialog) { Text("取消") } }
        )
    }
}

/** CameraX 取景与拍照；[onCaptured] 返回临时文件 */
@Composable
fun CameraCaptureView(
    onCaptured: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val imageCapture = remember { ImageCapture.Builder().build() }
    val previewView = remember { mutableStateOf<PreviewView?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // H-39：以 previewView 是否就绪为 key——future 回调可能在 AndroidView factory
    // 之前执行，此时 PreviewView 尚未创建，直接绑定会导致预览黑屏；factory 就绪后
    // 本 effect 重启再行绑定
    DisposableEffect(lifecycleOwner, previewView.value) {
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            // listener 与 onDispose 同在主线程执行：disposed 标志保证离开组合后
            // 迟到的 future 回调不会再绑定相机（否则预览将持续供帧至 activity 销毁）
            if (disposed) return@addListener
            val surfaceProvider = previewView.value?.surfaceProvider ?: return@addListener
            try {
                val provider = providerFuture.get()
                boundProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(surfaceProvider)
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                errorMessage = "相机启动失败：${e.message}"
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            // 离开组合即解绑相机，避免后台持续供帧耗电与重复绑定异常
            try {
                boundProvider?.unbindAll()
            } catch (_: Exception) {
            }
        }
    }

    Box(modifier.fillMaxSize()) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { previewView.value = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 取景参考框提示
        Text(
            "将手写答案对齐参考框内",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
        )

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = {
                val file = File.createTempFile("toneup_ai_", ".jpg", context.cacheDir)
                imageCapture.takePicture(
                    ImageCapture.OutputFileOptions.Builder(file).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            onCaptured(file)
                        }

                        override fun onError(exception: ImageCaptureException) {
                            // M-135：拍照失败时删除本次已创建的临时文件，避免 cacheDir 泄漏
                            file.delete()
                            errorMessage = "拍照失败：${exception.message}"
                        }
                    }
                )
            }) {
                Text("拍照")
            }
        }
    }
}

private fun Context.findActivity(): android.app.Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is android.app.Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    val intent = android.content.Intent(
        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        android.net.Uri.fromParts("package", packageName, null)
    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    startActivity(intent)
}
