package com.example.detectinav;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.Camera;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

public class ObjectDetectionClass {
    //Load model and predict
    private Interpreter interpreter;
    //Store labels in an array
    private List<String> listOfLabels;
    private int inputSize;
    //Initialize GPU
    private GpuDelegate gpuDelegate;
    private int height = 0;
    private int width = 0;
    private TextToSpeech warning;
    private Timer timerTTS;

    ObjectDetectionClass(TextToSpeech tts, AssetManager assetManager, String modelPath, String labelPath, int sizeOfInput) throws IOException {

        warning = tts;
        inputSize = sizeOfInput;
        Interpreter.Options options = new Interpreter.Options();
        gpuDelegate = new GpuDelegate();
        options.addDelegate(gpuDelegate);
        options.setNumThreads(4);

        //Load model
        interpreter = new Interpreter(loadModelFile(assetManager, modelPath), options);

        //Load label map
        listOfLabels = loadLabelList(assetManager, labelPath);

        timerTTS = new Timer();
    }

    private List<String> loadLabelList(AssetManager assetManager, String labelPath) throws IOException {
        //Arraylist to store labels
        List<String> labelList = new ArrayList<>();

        BufferedReader reader = new BufferedReader(new InputStreamReader(assetManager.open(labelPath)));
        String flag;
        //Loops through and stores each line in the label map into the label list
        while ((flag = reader.readLine()) != null) {
            labelList.add(flag);
        }
        reader.close();
        return labelList;
    }

    private MappedByteBuffer loadModelFile(AssetManager assetManager, String modelPath) throws IOException {
        //Get description of file
        AssetFileDescriptor fileDesc = assetManager.openFd(modelPath);
        FileInputStream inputStream = new FileInputStream(fileDesc.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDesc.getStartOffset();
        long declaredLength = fileDesc.getDeclaredLength();

        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    public Mat recogniseImage(Mat matImage) {
        //Rotating images will increase prediction and detection of objects
        Mat rotatedMat = new Mat();
        Core.flip(matImage.t(), rotatedMat, 1);

        //Bitmap represents each item to one or more bits of information
        Bitmap bitmap = null;
        bitmap = Bitmap.createBitmap(rotatedMat.cols(), rotatedMat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(rotatedMat, bitmap);

        height = bitmap.getHeight();
        width = bitmap.getWidth();

        //Scale bitmap to input size of model
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, false);

        //Convert bitmap to bytebuffer to take into account model input
        ByteBuffer bytebuffer = convertBitMapToByteBuffer(scaledBitmap);

        //Define output
        Object[] input = new Object[1];
        input[0] = bytebuffer;

        Map<Integer, Object> outputMap = new TreeMap<>();

        //Boxes, Score, Classes
        //10 represents the first 10 objects identified
        //4 represents the coordinates in the image
        float[][][] boxes = new float[1][10][4];
        //Stores the scores of the objects
        float[][] scores = new float[1][10];
        //Stores the classes of the objects
        float[][] classes = new float[1][10];
        outputMap.put(0, boxes);
        outputMap.put(1, classes);
        outputMap.put(2, scores);

        //Prediction of objects
        interpreter.runForMultipleInputsOutputs(input, outputMap);

        Object value = outputMap.get(0);
        Object objectClass = outputMap.get(1);
        Object score = outputMap.get(2);

        for (int i = 0; i < 10; i++) {
            float classValues = (float) Array.get(Array.get(objectClass, 0), i);
            float scoreValues = (float) Array.get(Array.get(score, 0), i);

            if (scoreValues > 0.5) {
                Object boxOne = Array.get(Array.get(value, 0), i);
                //Multiplying with original frame of height and width
                float top = (float) Array.get(boxOne, 0) * height;
                float left = (float) Array.get(boxOne, 1) * width;
                float bottom = (float) Array.get(boxOne, 2) * height;
                float right = (float) Array.get(boxOne, 3) * width;

                //Box (start point, end point, color, thickness)
                Imgproc.rectangle(rotatedMat, new Point(left, top), new Point(right, bottom), new Scalar(255, 155, 155), 4);

                //Text of class (start point, color, size)
                Imgproc.putText(rotatedMat, listOfLabels.get((int) classValues), new Point(left, top), 3, 1, new Scalar(100, 100, 100), 3);

                //Depth Estimation
                float middleOfX= (left + right);
                float middleOfY= (top + bottom);

                if ((middleOfX > width) || (middleOfY > height)) {
                    if (!warning.isSpeaking()) {
                        TimerTask timerTask = new TimerTask() {
                            @Override
                            public void run() {
                                if (!warning.isSpeaking()) {
                                    try {
                                        Thread.sleep(500);
                                        warning.speak("Obstruction Ahead!", TextToSpeech.QUEUE_FLUSH, null, null);
                                    } catch (Exception e) {
                                    }
                                }
                            }
                        };
                        timerTTS.schedule(timerTask, 0);
                    }
                }
            }
        }
        //Rotate image back
        Core.flip(rotatedMat.t(), matImage, 0);
        return matImage;
    }

    private ByteBuffer convertBitMapToByteBuffer(Bitmap scaledBitmap) {
        ByteBuffer byteBuffer;

        int sizeImages = inputSize;

        byteBuffer = ByteBuffer.allocateDirect(1 * sizeImages * sizeImages * 3);

        byteBuffer.order(ByteOrder.nativeOrder());
        int[] intValues = new int[sizeImages * sizeImages];
        scaledBitmap.getPixels(intValues, 0, scaledBitmap.getWidth(), 0, 0, scaledBitmap.getWidth(), scaledBitmap.getHeight());
        int pixels = 0;
        for (int i = 0; i < sizeImages; i++) {
            for (int j = 0; j < sizeImages; j++) {
                final int value = intValues[pixels++];
                byteBuffer.put((byte) ((value >> 16) & 0xFF));
                byteBuffer.put((byte) ((value >> 8) & 0xFF));
                byteBuffer.put((byte) (value & 0xFF));
            }
        }
        return byteBuffer;
    }
}