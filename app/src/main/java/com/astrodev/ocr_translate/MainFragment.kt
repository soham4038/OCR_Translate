package com.astrodev.ocr_translate

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import com.astrodev.ocr_translate.util.GraphicOverlay
import com.astrodev.ocr_translate.util.Language
import com.astrodev.ocr_translate.util.ScopedExecutor
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetBehavior.*

import kotlinx.android.synthetic.main.bottom_sheet.*
import kotlinx.android.synthetic.main.main_fragment.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class MainFragment : Fragment() {

    companion object {
        fun newInstance() =
            MainFragment()

        private const val REQUEST_CODE_PERMISSIONS = 10

        // This is an array of all the permission specified in the manifest
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
        private const val TAG = "MainFragment"
    }

    private var displayId: Int = -1
    private val viewModel: MainViewModel by viewModels()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private lateinit var container: CoordinatorLayout
    private lateinit var viewFinder: PreviewView
    private lateinit var graphicOverlay: GraphicOverlay

    /** Blocking camera operations are performed using this executor */
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<ConstraintLayout>
    private lateinit var scopedExecutor: ScopedExecutor


    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.main_fragment, container, false)
    }

    override fun onDestroyView() {
        super.onDestroyView()

        // Shut down our background executor
        cameraExecutor.shutdown()
        scopedExecutor.shutdown()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        container = view as CoordinatorLayout
        viewFinder = container.findViewById(R.id.viewfinder)

        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        scopedExecutor =
            ScopedExecutor(
                cameraExecutor
            )
        graphicOverlay = container.findViewById(R.id.graphic_overlay)
        // Request camera permissions
        if (allPermissionsGranted()) {
            // Wait for the views to be properly laid out
            viewFinder.post {
                // Keep track of the display in which this view is attached
                displayId = viewFinder.display.displayId

                // Set up the camera and its use cases
                setUpCamera()
            }
        } else {
            requestPermissions(
                REQUIRED_PERMISSIONS,
                REQUEST_CODE_PERMISSIONS
            )
        }

        // Get available language list and set up the target language spinner
        // with default selections.
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item, viewModel.availableLanguages
        )
        bottomSheetBehavior = from(bottom_sheet)
        bottomSheetBehavior.peekHeight = getNavigationBarHeight(this.requireContext())
        bottomSheetBehavior.state = STATE_COLLAPSED

        targetLangSelector.adapter = adapter
        targetLangSelector.setSelection(
            adapter.getPosition(
                Language(
                    "en"
                )
            )
        )
        targetLangSelector.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                viewModel.targetLang.value = adapter.getItem(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }


        viewModel.sourceLang.observe(viewLifecycleOwner, Observer { srcLang.text = it.displayName })
        viewModel.translatedText.observe(viewLifecycleOwner, Observer { resultOrError ->
            resultOrError?.let {
                if (it.error != null) {
                    translatedText.error = resultOrError.error?.localizedMessage
                } else {
                    translatedText.text = resultOrError.result
                }
            }
        })
        viewModel.modelDownloading.observe(viewLifecycleOwner, Observer { isDownloading ->
            progressBar.visibility = if (isDownloading) {
                View.VISIBLE
            } else {
                View.INVISIBLE
            }
            progressText.visibility = progressBar.visibility
        })

        val bottomSheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                graphic_overlay.isVisible = true
                when (newState) {
                    STATE_HIDDEN -> {

                    }
                    STATE_EXPANDED -> {

                        graphic_overlay.isVisible = false

                    }
                    STATE_COLLAPSED -> {


                    }
                    STATE_DRAGGING -> {

                    }
                    STATE_SETTLING -> {

                    }

                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                arrow.rotation = slideOffset * 180
            }
        }
        bottomSheetBehavior.addBottomSheetCallback(bottomSheetCallback)

    }

    /** Initialize CameraX, and prepare to bind the camera use cases  */
    private fun setUpCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener(Runnable {

            // CameraProvider
            cameraProvider = cameraProviderFuture.get()

            // Build and bind the camera use cases
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider
            ?: throw IllegalStateException("Camera initialization failed.")

        // Get screen metrics used to setup camera for full screen resolution
        val metrics = DisplayMetrics().also { viewFinder.display.getRealMetrics(it) }
        Log.d(TAG, "Screen metrics: ${metrics.widthPixels} x ${metrics.heightPixels}")

        val screenAspectRatio = aspectRatio(metrics.widthPixels, metrics.heightPixels)
        Log.d(TAG, "Preview aspect ratio: $screenAspectRatio")

        val rotation = viewFinder.display.rotation

        val preview = Preview.Builder()
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .build()

        // Build the image analysis use case and instantiate our analyzer
        imageAnalyzer = ImageAnalysis.Builder()
            // We request aspect ratio but no resolution
            .setTargetAspectRatio(screenAspectRatio)
            .setTargetRotation(rotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(
                    cameraExecutor
                    , TextAnalyzer(
                        requireContext(),
                        lifecycle,
                        viewModel.sourceText,
                        graphicOverlay

                    )
                )
            }
        viewModel.sourceText.observe(viewLifecycleOwner, Observer { srcText.text = it })


        // Select back camera since text detection does not work with front camera
        val cameraSelector =
            CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        try {
            // Unbind use cases before rebinding
            cameraProvider.unbindAll()

            // Bind use cases to camera
            camera = cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalyzer
            )
            preview.setSurfaceProvider(viewFinder.createSurfaceProvider())
        } catch (exc: IllegalStateException) {
            Log.e(TAG, "Use case binding failed. This must be running on main thread.", exc)
        }
    }


    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = ln(max(width, height).toDouble() / min(width, height))
        if (abs(previewRatio - ln(RATIO_4_3_VALUE))
            <= abs(previewRatio - ln(RATIO_16_9_VALUE))
        ) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }


    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                viewFinder.post {
                    // Keep track of the display in which this view is attached
                    displayId = viewFinder.display.displayId

                    // Set up the camera and its use cases
                    setUpCamera()
                }
            } else {
                Toast.makeText(
                    context,
                    "Permissions not granted by the user.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun getNavigationBarHeight(context: Context): Int {
        val resources: Resources = context.resources
        var orientation = context.resources.configuration.orientation
        val id: Int = resources.getIdentifier(
            if (orientation == Configuration.ORIENTATION_PORTRAIT) "navigation_bar_height" else "navigation_bar_height_landscape",
            "dimen", "android"
        )
        return if (id > 0) {
            resources.getDimensionPixelSize(id)
        } else 0
    }


    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(
            requireContext(), it
        ) == PackageManager.PERMISSION_GRANTED
    }
}
