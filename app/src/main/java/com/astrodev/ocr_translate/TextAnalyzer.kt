/*
 * Copyright 2019 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package com.astrodev.ocr_translate

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import com.astrodev.ocr_translate.util.GraphicOverlay
import com.astrodev.ocr_translate.util.TextGraphic

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition


/**
 * Analyzes the frames passed in from the camera and returns any detected text within the requested
 * crop region.
 */
class TextAnalyzer(
    private val context: Context,
    private val lifecycle: Lifecycle,
    private val result: MutableLiveData<String>,
    private var mGraphicOverlay: GraphicOverlay
) : ImageAnalysis.Analyzer {

    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private val detector = TextRecognition.getClient()

    init {
        lifecycle.addObserver(detector)
    }

    @androidx.camera.core.ExperimentalGetImage
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return
        var needUpdateGraphicOverlayImageSourceInfo = true


        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        if (needUpdateGraphicOverlayImageSourceInfo) {
            val isImageFlipped = lensFacing == CameraSelector.LENS_FACING_FRONT
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees == 0 || rotationDegrees == 180) {
                mGraphicOverlay.setImageSourceInfo(
                    imageProxy.width,
                    imageProxy.height,
                    isImageFlipped
                )
            } else {
                mGraphicOverlay.setImageSourceInfo(
                    imageProxy.height,
                    imageProxy.width,
                    isImageFlipped
                )
            }
            needUpdateGraphicOverlayImageSourceInfo = false
        }

        recognizeText(image).addOnCompleteListener {
            imageProxy.close()
        }
    }

    private fun recognizeText(
        image: InputImage
    ): Task<Text> {
        // Pass image to an ML Kit Vision API
        return detector.process(image)
            .addOnSuccessListener { text ->
                // Task completed successfully
                result.value = text.text
                processTextRecognitionResult(text)
            }
            .addOnFailureListener { exception ->
                // Task failed with an exception
                Log.e(TAG, "Text recognition error", exception)
                val message = getErrorMessage(exception)
                message?.let {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
    }

    private fun processTextRecognitionResult(texts: Text) {
        val blocks: List<Text.TextBlock> =
            texts.textBlocks
        mGraphicOverlay.clear()
        val textGraphic: GraphicOverlay.Graphic =
            TextGraphic(
                mGraphicOverlay,
                texts
            )
        mGraphicOverlay.add(textGraphic)
    }

    private fun getErrorMessage(exception: Exception): String? {
        val mlKitException = exception as? MlKitException ?: return exception.message
        return if (mlKitException.errorCode == MlKitException.UNAVAILABLE) {
            "Waiting for text recognition model to be downloaded"
        } else exception.message
    }

    companion object {
        private const val TAG = "TextAnalyzer"
    }
}
