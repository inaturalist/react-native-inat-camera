package org.inaturalist.native_camera.nativecameraandroid.classifier;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.tensorflow.lite.Interpreter;

/** Classifies images with Tensorflow Lite. */
public class ImageClassifier {

    /** Tag for the {@link Log}. */
    private static final String TAG = "TfLiteCameraDemo";

    /** Dimensions of inputs. */
    private static final int DIM_BATCH_SIZE = 1;

    private static final int DIM_PIXEL_SIZE = 3;

    public static final int DIM_IMG_SIZE_X = 299;
    public static final int DIM_IMG_SIZE_Y = 299;

    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128.0f;

    private static final int[] EXPECTED_MODEL_OUTPUT_SIZES = { 6, 9, 7, 41, 76, 190, 199 };

    private final Taxonomy mTaxonomy;
    private final String modelFilename;
    private final String taxonomyFilename;

    /* Preallocated buffers for storing image data in. */
    private int[] intValues = new int[DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y];

    /** An instance of the driver class to run model inference with Tensorflow Lite. */
    private Interpreter tflite;

    /** A ByteBuffer to hold image data, to be feed into Tensorflow Lite as inputs. */
    private ByteBuffer imgData;


    /** Initializes an {@code ImageClassifier}. */
    public ImageClassifier(Activity activity, String modelPath, String taxonomyPath) throws IOException {
        modelFilename = modelPath;
        taxonomyFilename = taxonomyPath;
        tflite = new Interpreter(loadModelFile(activity));
        imgData =
                ByteBuffer.allocateDirect(
                        4 * DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE);
        imgData.order(ByteOrder.nativeOrder());
        Log.d(TAG, "Created a Tensorflow Lite Image Classifier.");

        mTaxonomy = new Taxonomy(new FileInputStream(taxonomyFilename));
    }

    /** Classifies a frame from the preview stream. */
    public Collection<Prediction> classifyFrame(Bitmap bitmap) {
        if (tflite == null) {
            // TODO
            Log.e(TAG, "Image classifier has not been initialized; Skipped.");
            return null;
        }
        convertBitmapToByteBuffer(bitmap);
        long startTime = SystemClock.uptimeMillis();

        byte[] arr = new byte[imgData.remaining()];
        imgData.get(arr);

        Map<Integer, Object> expectedOutputs = new HashMap<>();
        for (int i = 0; i < EXPECTED_MODEL_OUTPUT_SIZES.length; i++) {
            expectedOutputs.put(i, new float[1][EXPECTED_MODEL_OUTPUT_SIZES[i]]);
        }

        Object[] input = { imgData };
        tflite.runForMultipleInputsOutputs(input, expectedOutputs);
        Collection<Prediction> predictions = mTaxonomy.predict(expectedOutputs);
        long endTime = SystemClock.uptimeMillis();

        return predictions;
    }

    /** Closes tflite to release resources. */
    public void close() {
        tflite.close();
        tflite = null;
    }

    /** Memory-map the model file in Assets. */
    private MappedByteBuffer loadModelFile(Activity activity) throws IOException {
        FileInputStream inputStream = new FileInputStream(modelFilename);
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, inputStream.available());
    }

    /** Writes Image data into a {@code ByteBuffer}. */
    private void convertBitmapToByteBuffer(Bitmap bitmap) {
        if (imgData == null) {
            return;
        }
        imgData.rewind();
        bitmap.getPixels(intValues, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
        // Convert the image to floating point.
        int pixel = 0;
        long startTime = SystemClock.uptimeMillis();
        for (int i = 0; i < DIM_IMG_SIZE_X; ++i) {
            for (int j = 0; j < DIM_IMG_SIZE_Y; ++j) {
                final int val = intValues[pixel++];
                imgData.putFloat((((val >> 16) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                imgData.putFloat((((val >> 8) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
                imgData.putFloat((((val) & 0xFF)-IMAGE_MEAN)/IMAGE_STD);
            }
        }
        long endTime = SystemClock.uptimeMillis();
        Log.d(TAG, "Timecost to put values into ByteBuffer: " + Long.toString(endTime - startTime));
    }

}

