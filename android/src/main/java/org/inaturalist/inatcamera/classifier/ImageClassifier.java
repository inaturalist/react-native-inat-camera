package org.inaturalist.inatcamera.classifier;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;
import timber.log.*;
import java.util.Date;

import org.tensorflow.lite.Interpreter;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

/** Classifies images with Tensorflow Lite. */
public class ImageClassifier {

    /** Tag for the {@link Log}. */
    private static final String TAG = "ImageClassifier";

    /** Dimensions of inputs. */
    private static final int DIM_BATCH_SIZE = 1;

    private static final int DIM_PIXEL_SIZE = 3;

    public static final int DIM_IMG_SIZE_X = 299;
    public static final int DIM_IMG_SIZE_Y = 299;

    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128.0f;

    private final Taxonomy mTaxonomy;
    private final String mModelFilename;
    private final String mTaxonomyFilename;
    private final String mTaxonMappingFilename;
    private final String mOfflineFrequencyFilename;
    private int mModelSize;
    private OfflineFrequency mOfflineFrequency;
    private Date mFrequencyDate;
    private float mFrequencyLatitude;
    private float mFrequencyLongitude;

    /* Preallocated buffers for storing image data in. */
    private int[] intValues = new int[DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y];

    /** An instance of the driver class to run model inference with Tensorflow Lite. */
    private Interpreter mTFlite;

    /** A ByteBuffer to hold image data, to be feed into Tensorflow Lite as inputs. */
    private ByteBuffer imgData;

    public void setFilterByTaxonId(Integer taxonId) {
        mTaxonomy.setFilterByTaxonId(taxonId);
    }

    public void setThreshold(Float threshold) {
        mTaxonomy.setThreshold(threshold);
    }

    public Integer getFilterByTaxonId() {
        return mTaxonomy.getFilterByTaxonId();
    }

    public void setNegativeFilter(boolean negative) {
        mTaxonomy.setNegativeFilter(negative);
    }

    public boolean getNegativeFilter() {
        return mTaxonomy.getNegativeFilter();
    }

    public void setFrequencyDate(Date date) {
        mFrequencyDate = date;
    }

    public void setFrequencyLocation(float latitude, float longitude) {
        mFrequencyLatitude = latitude;
        mFrequencyLongitude = longitude;
    }

    /** Initializes an {@code ImageClassifier}. */
    public ImageClassifier(String modelPath, String taxonomyPath, String offlineFrequencyPath, String taxonMappingFilename) throws IOException {
        mModelFilename = modelPath;
        mTaxonomyFilename = taxonomyPath;
        mTaxonMappingFilename = taxonMappingFilename;
        mOfflineFrequencyFilename = offlineFrequencyPath;
        if (mOfflineFrequencyFilename != null) mOfflineFrequency = new OfflineFrequency(mOfflineFrequencyFilename);
        mTFlite = new Interpreter(loadModelFile());
        imgData =
                ByteBuffer.allocateDirect(
                        4 * DIM_BATCH_SIZE * DIM_IMG_SIZE_X * DIM_IMG_SIZE_Y * DIM_PIXEL_SIZE);
        imgData.order(ByteOrder.nativeOrder());
        Timber.tag(TAG).d("Created a Tensorflow Lite Image Classifier.");

        mTaxonomy = new Taxonomy(new FileInputStream(mTaxonomyFilename), mTaxonMappingFilename != null ? new FileInputStream(mTaxonMappingFilename) : null);
        mModelSize = mTaxonomy.getModelSize();
    }

    /** Classifies a frame from the preview stream. */
    public List<Prediction> classifyFrame(Bitmap bitmap) {
        if (mTFlite == null) {
            Timber.tag(TAG).e("Image classifier has not been initialized; Skipped.");
            return null;
        }
        if (bitmap == null) {
            Timber.tag(TAG).e("Null input bitmap");
            return null;
        }

        convertBitmapToByteBuffer(bitmap);
        long startTime = SystemClock.uptimeMillis();

        byte[] arr = new byte[imgData.remaining()];
        imgData.get(arr);

        Map<Integer, Object> expectedOutputs = new HashMap<>();
        for (int i = 0; i < 1; i++) {
            expectedOutputs.put(i, new float[1][mModelSize]);
        }

        Object[] input = { imgData };
        try {
            mTFlite.runForMultipleInputsOutputs(input, expectedOutputs);
        } catch (Exception exc) {
            exc.printStackTrace();
            return new ArrayList<Prediction>();
        }

        List<JsonObject> frequencyResults = null;
        if (mOfflineFrequency != null) {
            frequencyResults = mOfflineFrequency.query(mFrequencyDate, mFrequencyLatitude, mFrequencyLongitude);
        }
        List<Prediction> predictions = mTaxonomy.predict(expectedOutputs, frequencyResults);
        long endTime = SystemClock.uptimeMillis();

        return predictions;
    }

    /** Closes tflite to release resources. */
    public void close() {
        mTFlite.close();
        mTFlite = null;
    }

    /** Memory-map the model file in Assets. */
    private MappedByteBuffer loadModelFile() throws IOException {
        FileInputStream inputStream = new FileInputStream(mModelFilename);
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
        Timber.tag(TAG).d("Timecost to put values into ByteBuffer: " + Long.toString(endTime - startTime));
    }

}

