package dev.mx3.nomessages.runtime

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.mx3.nomessages.R

@Composable
fun CameraCaptureScreen(onPhoto: (ByteArray) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    var permitted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permitted = it }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var error by remember { mutableStateOf(false) }
    var taking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    DisposableEffect(permitted, lifecycle) {
        val future = ProcessCameraProvider.getInstance(context)
        var disposed = false
        if (permitted) future.addListener({
            if (!disposed) try {
                val provider = future.get()
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (_: Exception) { error = true }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            if (future.isDone) try { future.get().unbindAll() } catch (_: Exception) { }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(stringResource(R.string.capture_camera_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            if (permitted && !error) AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
            else Text(stringResource(R.string.capture_camera_permission), Modifier.weight(1f).padding(16.dp))
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.capture_cancel)) }
                Button(enabled = permitted && !error && !taking, onClick = {
                    taking = true
                    imageCapture.takePicture(ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                onPhoto(bytes)
                            } catch (_: Exception) { error = true; taking = false }
                            finally { image.close() }
                        }
                        override fun onError(exception: ImageCaptureException) { error = true; taking = false }
                    })
                }) { Text(stringResource(R.string.capture_photo)) }
            }
        }
    }
}
