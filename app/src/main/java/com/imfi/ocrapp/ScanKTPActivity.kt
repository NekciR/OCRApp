package com.imfi.ocrapp

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.mlkit.common.model.LocalModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.custom.CustomObjectDetectorOptions
import com.imfi.ocrapp.commons.CommonFunction
import com.imfi.ocrapp.databinding.ActivityScanKtpactivityBinding
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors


class ScanKTPActivity : AppCompatActivity() {
    private lateinit var binding: ActivityScanKtpactivityBinding

    private lateinit var cameraProviderFuture : ListenableFuture<ProcessCameraProvider>
    private lateinit var cameraSelector: CameraSelector

    private var imageCapture: ImageCapture? = null
    private lateinit var imgCaptureExecutor: ExecutorService



    companion object {
        private val TAG = "ScanKTPActivity"
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val REQUEST_CODE_PERMISSIONS = 10
    }

    var startCamera: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val photo = result.data?.extras?.get("data") as? Bitmap
            photo?.let {
                //TODO: Detect KTP then Read KTP Result
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanKtpactivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        imgCaptureExecutor = Executors.newSingleThreadExecutor()

        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(
                this,
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )

        } else {
            startCamera()
        }



        binding.btnScan.setOnClickListener(View.OnClickListener {
            takePhoto()
        })


    }


    private fun takePhoto(){
        imageCapture?.let {
            it.takePicture(imgCaptureExecutor, object : ImageCapture.OnImageCapturedCallback(){
                 override fun onCaptureSuccess(imageProxy: ImageProxy) {

                     analyzeKTP(imageProxy)

                     imageProxy.close()
//                    super.onCaptureSuccess(imageProxy)
                }

                override fun onError(exception: ImageCaptureException) {
                    super.onError(exception)
                }
            })

        }
    }

    private fun startCamera(){
//        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
//        startCamera.launch(takePictureIntent)

        // listening for data from the camera
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()

            // connecting a preview use case to the preview in the xml file.
            val preview = Preview.Builder().build().also{
                it.setSurfaceProvider(binding.pvCamPreview.surfaceProvider)
            }
            try{
                // clear all the previous use cases first.
                cameraProvider.unbindAll()
                // binding the lifecycle of the camera to the lifecycle of the application.
                cameraProvider.bindToLifecycle(this,cameraSelector,preview, imageCapture)
            } catch (e: Exception) {
                Log.d(TAG, "startCamera: " + e.message )
            }

        },ContextCompat.getMainExecutor(this))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>,
        grantResults:
        IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                startCamera.launch(takePictureIntent)
            } else {
                val errMsg = "Camera access permission denied"
                Toast.makeText(
                    this,
                    errMsg,
                    Toast.LENGTH_SHORT
                ).show()

                setResult(
                    RESULT_CANCELED
                )
                finish()
            }
        }
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            baseContext, it
        ) == PackageManager.PERMISSION_GRANTED
    }


    private fun analyzeKTP(imageProxy: ImageProxy){
        @ExperimentalGetImage val mediaImage = imageProxy.image

        if (mediaImage != null) {
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val bitmap = CommonFunction.imageToBitmap(mediaImage)
            val localModel =
                LocalModel.Builder().setAssetFilePath("trained_model/object_labeler.tflite").build()
            var option =
                CustomObjectDetectorOptions.Builder(localModel)
                    .setDetectorMode(CustomObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableClassification()
                    .setMaxPerObjectLabelCount(1)
                    .build()
            val objectDetector = ObjectDetection.getClient(option)

            objectDetector.process(image).addOnSuccessListener { detectedObjects ->
                if(!detectedObjects.isNullOrEmpty()){
                    Log.d(TAG, "analyzeKTP: " + detectedObjects.firstOrNull()?.labels?.firstOrNull()?.text )
                }
                if(detectedObjects.isNullOrEmpty() || detectedObjects.firstOrNull()?.labels?.firstOrNull()?.text !in listOf("Driver's license","Passport")){
                    Toast.makeText(this, "KTP tidak ditemukan", Toast.LENGTH_SHORT).show();
                }else{
                    Toast.makeText(this, "KTP ditemukan", Toast.LENGTH_SHORT).show();
                    val box = detectedObjects.first().boundingBox
                    val croppedBitmap = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
                    val textInputImage = InputImage.fromMediaImage(mediaImage,imageProxy.imageInfo.rotationDegrees)
                }


            }
        }



    }


}