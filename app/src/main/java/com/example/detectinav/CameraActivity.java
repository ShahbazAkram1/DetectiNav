package com.example.detectinav;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.provider.MediaStore;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.io.IOException;
import java.util.Locale;

public class CameraActivity extends Activity implements CameraBridgeViewBase.CvCameraViewListener2 {

    private Mat rgba;
    private ObjectDetectionClass objectDetection;
    private CameraBridgeViewBase openCvCamView;
    private TextToSpeech warning;

    private BaseLoaderCallback loaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    openCvCamView.enableView();
                }
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //Ask for camera permission on device
        int myPermissionsRequestCamera = 0;
        if (ContextCompat.checkSelfPermission(CameraActivity.this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(CameraActivity.this, new String[]{Manifest.permission.CAMERA},
                    myPermissionsRequestCamera);
        }

        setContentView(R.layout.activity_camera);
        openCvCamView = (CameraBridgeViewBase) findViewById(R.id.frame_Surface);
        openCvCamView.setVisibility(SurfaceView.VISIBLE);
        openCvCamView.setCvCameraViewListener(this);

        try {
            warning = new TextToSpeech(getApplicationContext(), status -> {
                if(status != TextToSpeech.ERROR) {
                    warning.setLanguage(Locale.UK);
                }
            });
            objectDetection = new ObjectDetectionClass(warning, getAssets(),
                    "ssd_mobilenet_v1.tflite", "labelmap.txt", 300);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (OpenCVLoader.initDebug()) {
            loaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        } else {
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_4_0, this, loaderCallback);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (openCvCamView != null) {
            openCvCamView.disableView();
        }
    }

    public void onDestroy() {
        if(warning != null){
            warning.stop();
            warning.shutdown();
        }

        super.onDestroy();
        if (openCvCamView != null) {
            openCvCamView.disableView();
        }
    }

    public void onCameraViewStarted(int w, int h) {
        rgba = new Mat(h, w, CvType.CV_8UC4);
    }

    public void onCameraViewStopped() {
        rgba.release();
    }

    public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
        rgba = inputFrame.rgba();

        Mat output = new Mat();
        output = objectDetection.recogniseImage(rgba);

        return output;
    }
}