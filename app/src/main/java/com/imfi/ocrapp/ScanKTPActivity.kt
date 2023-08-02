package com.imfi.ocrapp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.imfi.ocrapp.commons.CommonFunction
import com.imfi.ocrapp.databinding.ActivityScanKtpactivityBinding
import com.imfi.ocrapp.model.KTPModel
import com.imfi.ocrapp.utils.extractEktp
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
            @ExperimentalGetImage val bitmapp = CommonFunction.capturedImageToBitmap(imageProxy)
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            val localModel =
                LocalModel.Builder().setAssetFilePath("trained_model/object_labeler.tflite").build()
            var option =
                CustomObjectDetectorOptions.Builder(localModel)
                    .setDetectorMode(CustomObjectDetectorOptions.SINGLE_IMAGE_MODE)
                    .enableClassification()
                    .setMaxPerObjectLabelCount(1)
                    .build()
            val objectDetector = ObjectDetection.getClient(option)
            val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val listEktp = mutableListOf<KTPModel>()

            objectDetector.process(image).addOnSuccessListener { detectedObjects ->
                if(detectedObjects.isNullOrEmpty() || detectedObjects.firstOrNull()?.labels?.firstOrNull()?.text !in listOf("Driver's license","Passport")){
                    Toast.makeText(this, "KTP tidak ditemukan", Toast.LENGTH_SHORT).show();
                    imageProxy.close()
                }else{
                    Toast.makeText(this, "KTP ditemukan", Toast.LENGTH_SHORT).show();
                    val box = detectedObjects.first().boundingBox
                    val matrix = Matrix()
                    matrix.postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    val rotatedBitmap = Bitmap.createBitmap(bitmapp, 0, 0, bitmapp.getWidth(), bitmapp.getHeight(), matrix, true);
                    val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, box.left, box.top, box.width(), box.height())
//                    Log.d(TAG, "analyzeKTP: " + box.top + " " + box.height() + " " + bitmap.height)
//                    val croppedBitmap = Bitmap.createBitmap(bitmap, box.left, box.top ,box.right, box.bottom)
//                    showImageDialog(bitmap, imageProxy.imageInfo.rotationDegrees )

                    val textInputImage = InputImage.fromMediaImage(mediaImage,imageProxy.imageInfo.rotationDegrees)
                    textRecognizer.process(textInputImage).addOnSuccessListener {
                        Log.d(TAG, "analyzeKTP: ${it.text}")
                        if ("\\d{16}".toRegex().containsMatchIn(it.text)) {
                            listEktp.add(it.extractEktp().apply {
                                bitmap = croppedBitmap
                            })
                        }
                    }.addOnCompleteListener{
                        val ektp = listEktp.findBestResult()
                        ektp?.let {
                            showImageDialog(it)
                        } ?: Toast.makeText(this, "Scan KTP gagal", Toast.LENGTH_SHORT).show()
                        imageProxy.close()
                    }


                }


            }
        }



    }

    private fun List<KTPModel>.findBestResult(): KTPModel? {
        val highestConfidence = maxByOrNull {
            it.confidence
        }?.apply {
            if (provinsi.isNullOrEmpty()) provinsi = firstNotNullOfOrNull { it.provinsi }
            if (kabKot.isNullOrEmpty()) kabKot = firstNotNullOfOrNull { it.kabKot }
            if (nama.isNullOrEmpty()) nama = maxByOrNull { it.nama?.length ?: 0 }?.nama
            if (tempatLahir.isNullOrEmpty()) tempatLahir = firstNotNullOfOrNull { it.tempatLahir }
            if (tglLahir.isNullOrEmpty()) tglLahir = firstNotNullOfOrNull { it.tglLahir }
            if (jenisKelamin.isNullOrEmpty()) jenisKelamin = maxByOrNull { it.jenisKelamin?.length ?: 0 }?.jenisKelamin
            if (alamat.isNullOrEmpty()) alamat = firstNotNullOfOrNull { it.alamat }
            if (rt.isNullOrEmpty()) rt = firstNotNullOfOrNull { it.rt }
            if (rw.isNullOrEmpty()) rw = firstNotNullOfOrNull { it.rw }
            if (kelurahan.isNullOrEmpty()) kelurahan = firstNotNullOfOrNull { it.kelurahan }
            if (kecamatan.isNullOrEmpty()) kecamatan = firstNotNullOfOrNull { it.kecamatan }
            if (agama.isNullOrEmpty()) agama = firstNotNullOfOrNull { it.agama }
            if (statusPerkawinan.isNullOrEmpty()) statusPerkawinan = firstNotNullOfOrNull { it.statusPerkawinan }
            if (pekerjaan.isNullOrEmpty()) pekerjaan = firstNotNullOfOrNull { it.pekerjaan }
            if (kewarganegaraan.isNullOrEmpty()) kewarganegaraan = firstNotNullOfOrNull { it.kewarganegaraan }
            if (berlakuHingga.isNullOrEmpty()) berlakuHingga = firstNotNullOfOrNull { it.berlakuHingga }
        }
        return highestConfidence
    }

    private fun showImageDialog(ktp : KTPModel){
        val image = ImageView(this)
        ktp.bitmap.let {
            image.setImageBitmap(it)
        }


        val builder: AlertDialog.Builder = AlertDialog.Builder(this).setMessage(ktp.toString())
            .setView(image)
        builder.create().show()
    }

    private fun showImageDialog(bitmap : Bitmap, rotation : Int){
        val image = ImageView(this)
        image.setImageBitmap(bitmap)
        image.rotation = rotation.toFloat()

        val builder: AlertDialog.Builder = AlertDialog.Builder(this).setMessage("KTP")
            .setView(image)
        builder.create().show()
    }




}