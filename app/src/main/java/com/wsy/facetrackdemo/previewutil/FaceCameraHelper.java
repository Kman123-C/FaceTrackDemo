package com.wsy.facetrackdemo.previewutil;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.hardware.Camera;
import android.support.annotation.ColorInt;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.arcsoft.facerecognition.AFR_FSDKEngine;
import com.arcsoft.facerecognition.AFR_FSDKFace;
import com.arcsoft.facetracking.AFT_FSDKEngine;
import com.arcsoft.facetracking.AFT_FSDKFace;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author wsy9057
 * @date 2018/5/2
 */

public class FaceCameraHelper implements Camera.PreviewCallback {
    private Camera mCamera;
    private int mCameraId;
    private AFT_FSDKEngine ftEngine;
    private AFR_FSDKEngine frEngine;
    private SurfaceView surfaceViewPreview, surfaceViewRect;
    private Activity activity;
    private Camera.Size previewSize;
    private int surfaceWidth, surfaceHeight;
    private int cameraOrientation = 0;
    private int faceRectColor = Color.YELLOW;
    private int faceRectThickness = 5;
    private List<AFT_FSDKFace> ftFaceList = new ArrayList<>();
    private Integer specificCameraId = null;
    private ExecutorService executor;
    private FaceTrackListener faceTrackListener;
    private static final int MAX_FRTHREAD_COUNT = 5;
    private LinkedBlockingQueue<FaceRecognizeRunnable> faceRecognizeRunnables = new LinkedBlockingQueue<FaceRecognizeRunnable>(MAX_FRTHREAD_COUNT);
    //trackId相关
    private int currentTrackId = 0;
    private List<Integer> formerTrackIdList = new ArrayList<>();
    private List<Integer> currentTrackIdList = new ArrayList<>();
    private ConcurrentHashMap<Integer, String> nameMap = new ConcurrentHashMap<Integer, String>();
    private List<Rect> formerFaceRectList = new ArrayList<>();
    private static final float SIMILARITY_RECT = 0.3f;

    public interface FaceTrackListener {

        /**
         * 回传相机预览数据和人脸框位置
         *
         * @param nv21        相机预览数据
         * @param ftFaceList  待处理的人脸列表
         * @param trackIdList 人脸追踪ID列表
         */
        void onPreviewData(byte[] nv21, List<AFT_FSDKFace> ftFaceList, List<Integer> trackIdList);


        /**
         * 当出现异常时执行
         *
         * @param e 异常信息
         */
        void onFail(Exception e);


        /**
         * 当相机打开时执行
         *
         * @param camera 相机实例
         */
        void onCameraOpened(Camera camera);

        /**
         * 根据自己的需要可以删除部分人脸，比如指定区域、留下最大人脸等
         *
         * @param ftFaceList  人脸列表
         * @param trackIdList 人脸追踪ID列表
         */
        void adjustFaceRectList(List<AFT_FSDKFace> ftFaceList, List<Integer> trackIdList);

        /**
         * 请求人脸特征后的回调
         *
         * @param frFace    人脸特征数据
         * @param requestId 请求码
         */
        void onFaceFeatureInfoGet(@Nullable AFR_FSDKFace frFace, Integer requestId);
    }

    /**
     * 设置人脸框的颜色
     *
     * @param faceRectColor 人脸框的颜色
     */

    public void setFaceRectColor(@ColorInt int faceRectColor) {
        this.faceRectColor = faceRectColor;
    }

    /**
     * 设置指定的相机ID
     *
     * @param specificCameraId 指定的相机ID
     */
    public void setSpecificCameraId(int specificCameraId) {
        this.specificCameraId = specificCameraId;
    }

    /**
     * 设置绘制的人脸框宽度
     *
     * @param faceRectThickness 人脸框宽度
     */
    public void setFaceRectThickness(int faceRectThickness) {
        this.faceRectThickness = faceRectThickness;
    }

    /**
     * 设置FR引擎
     *
     * @param frEngine
     */
    public void setFrEngine(AFR_FSDKEngine frEngine) {
        this.frEngine = frEngine;
    }

    /**
     * 请求获取人脸特征数据，需要传入FR的参数，以下参数同 AFR_FSDKEngine.AFR_FSDK_ExtractFRFeature
     *
     * @param nv21      NV21格式的图像数据
     * @param faceRect  人脸框
     * @param width     图像宽度
     * @param height    图像高度
     * @param format    图像格式
     * @param ori       人脸在图像中的朝向
     * @param requestId 请求人脸特征的请求码
     */
    public void requestFaceFeature(byte[] nv21, Rect faceRect, int width, int height, int format, int ori, Integer requestId) {
        if (faceTrackListener != null) {
            if (frEngine != null && faceRecognizeRunnables.size()<MAX_FRTHREAD_COUNT) {
                byte[]nv21Data = new byte[nv21.length];
                System.arraycopy(nv21, 0, nv21Data, 0, nv21.length);
                faceRecognizeRunnables.add(new FaceRecognizeRunnable(nv21Data,faceRect, width, height, format, ori, requestId));
                executor.execute(faceRecognizeRunnables.poll());
            } else {
                faceTrackListener.onFaceFeatureInfoGet(null, requestId);
            }
        }
    }


    public void setFaceTrackListener(FaceTrackListener faceTrackListener) {
        this.faceTrackListener = faceTrackListener;
    }

    public FaceCameraHelper(WeakReference<Activity> activity, AFT_FSDKEngine ftEngine) {
        if (ftEngine == null) {
            throw new RuntimeException("ftEngine is null");
        }
        this.ftEngine = ftEngine;
        this.activity = activity.get();
    }

    public void init(@NonNull SurfaceView surfaceViewPreview, @Nullable SurfaceView surfaceViewRect) {
        this.surfaceViewPreview = surfaceViewPreview;
        this.surfaceViewRect = surfaceViewRect;
        this.surfaceViewPreview.getHolder().addCallback(mPreviewSurfaceCallBack);

        if (this.surfaceViewRect != null) {
            this.surfaceViewRect.setZOrderMediaOverlay(true);
            this.surfaceViewRect.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        }
    }


    public void start() {
        executor = Executors.newSingleThreadExecutor();
        //相机数量为2则打开1,1则打开0,相机ID 1为前置，0为后置
        mCameraId = Camera.getNumberOfCameras() - 1;
        if (specificCameraId != null) {
            mCameraId = specificCameraId;
        }

        //没有相机
        if (mCameraId == -1) {
            if (faceTrackListener != null) {
                faceTrackListener.onFail(new RuntimeException("camera not found"));
            }
            return;
        }
        mCamera = Camera.open(mCameraId);
        cameraOrientation = getCameraOri();
        mCamera.setDisplayOrientation(cameraOrientation);
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            surfaceWidth = metrics.widthPixels;
            surfaceHeight = metrics.heightPixels;
            Camera.Parameters parameters = mCamera.getParameters();
            parameters.setPreviewFormat(ImageFormat.NV21);
            previewSize = getBestSupportedSize(parameters.getSupportedPreviewSizes(), metrics);
            parameters.setPreviewSize(previewSize.width, previewSize.height);
            mCamera.setParameters(parameters);
            mCamera.setPreviewDisplay(surfaceViewPreview.getHolder());
            mCamera.setPreviewCallback(this);
            mCamera.startPreview();
            if (faceTrackListener != null) {
                faceTrackListener.onCameraOpened(mCamera);
            }
        } catch (IOException e) {
            if (faceTrackListener != null) {
                faceTrackListener.onFail(e);
            }
        }
    }

    public void stop() {
        if (mCamera == null) {
            return;
        }
        if (!executor.isShutdown()) {
            executor.shutdown();
        }
        mCamera.stopPreview();
        try {
            mCamera.setPreviewCallback(null);
            mCamera.setPreviewDisplay(null);
            mCamera = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 一般情况下
     * landscape 0
     * portrait 90
     * reverseLandscape 180
     * reversePortrait 90
     *
     * @return 相机预览数据的展示旋转角度
     */
    private int getCameraOri() {
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0:
                degrees = 0;
                break;
            case Surface.ROTATION_90:
                degrees = 90;
                break;
            case Surface.ROTATION_180:
                degrees = 180;
                break;
            case Surface.ROTATION_270:
                degrees = 270;
                break;
            default:
                break;
        }
        int result;
        Camera.CameraInfo info = new Camera.CameraInfo();
        Camera.getCameraInfo(mCameraId, info);
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;
        } else {
            result = (info.orientation - degrees + 360) % 360;
        }
        return result;
    }


    private Camera.Size getBestSupportedSize(List<Camera.Size> sizes, DisplayMetrics metrics) {
        Camera.Size bestSize = sizes.get(0);
        float screenRatio = (float) metrics.widthPixels / (float) metrics.heightPixels;
        if (screenRatio > 1) {
            screenRatio = 1 / screenRatio;
        }

        for (Camera.Size s : sizes) {
            if (Math.abs((s.height / (float) s.width) - screenRatio) < Math.abs(bestSize.height / (float) bestSize.width - screenRatio)) {
                bestSize = s;
            }
        }
        return bestSize;
    }

    @Override
    public void onPreviewFrame(byte[] nv21, Camera camera) {
        if (faceTrackListener != null) {
            ftFaceList.clear();
            int ftCode = ftEngine.AFT_FSDK_FaceFeatureDetect(nv21, previewSize.width, previewSize.height, AFT_FSDKEngine.CP_PAF_NV21, ftFaceList).getCode();
            if (ftCode != 0) {
                faceTrackListener.onFail(new Exception("ft failed,code is " + ftCode));
            }
            refreshTrackId(ftFaceList);
            faceTrackListener.adjustFaceRectList(ftFaceList, currentTrackIdList);
            if (surfaceViewRect != null) {
                Canvas canvas = surfaceViewRect.getHolder().lockCanvas();
                if (canvas == null) {
                    faceTrackListener.onFail(new Exception("can not get canvas of surfaceViewRect"));
                    return;
                }
                canvas.drawColor(0, PorterDuff.Mode.CLEAR);
                if (ftFaceList.size() > 0) {
                    for (int i = 0; i < ftFaceList.size(); i++) {
                        Rect adjustedRect = TrackUtil.adjustRect(new Rect(ftFaceList.get(i).getRect()), previewSize.width, previewSize.height, surfaceWidth, surfaceHeight, cameraOrientation, mCameraId);
                        TrackUtil.drawFaceRect(canvas, adjustedRect, faceRectColor, faceRectThickness, currentTrackIdList.get(i), nameMap.get(currentTrackIdList.get(i)));
                    }
                }
                surfaceViewRect.getHolder().unlockCanvasAndPost(canvas);
            }

            faceTrackListener.onPreviewData(nv21, ftFaceList, currentTrackIdList);
        }
    }

    private SurfaceHolder.Callback mPreviewSurfaceCallBack = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder surfaceHolder) {
            if (ftEngine != null) {
                start();
            } else if (faceTrackListener != null) {
                faceTrackListener.onFail(new RuntimeException("ftEngine is null"));
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder surfaceHolder, int format, int width, int height) {
            surfaceWidth = width;
            surfaceHeight = height;
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder surfaceHolder) {
            stop();
        }
    };

    public class FaceRecognizeRunnable implements Runnable {
        private Rect faceRect;
        private int width;
        private int height;
        private int format;
        private int ori;
        private Integer requestId;
        private byte[]nv21Data;
        public FaceRecognizeRunnable(byte[]nv21Data,Rect faceRect, int width, int height, int format, int ori, Integer requestId) {
            if (nv21Data==null) {
                return;
            }
            this.nv21Data = new byte[nv21Data.length];
            System.arraycopy(nv21Data,0,this.nv21Data,0,nv21Data.length);
            this.faceRect = new Rect(faceRect);
            this.width = width;
            this.height = height;
            this.format = format;
            this.ori = ori;
            this.requestId = requestId;
        }

        @Override
        public void run() {
            if (faceTrackListener!=null && nv21Data!=null) {
                if (frEngine != null) {
                    AFR_FSDKFace frFace = new AFR_FSDKFace();
                    int frCode = frEngine.AFR_FSDK_ExtractFRFeature(nv21Data, width, height, format, faceRect, ori, frFace).getCode();
                    if (frCode == 0) {
                        faceTrackListener.onFaceFeatureInfoGet(frFace, requestId);
                    } else {
                        faceTrackListener.onFaceFeatureInfoGet(null, requestId);
                        faceTrackListener.onFail(new Exception("fr failed errorCode is " + frCode));
                    }
                    nv21Data = null;
                }else {
                    faceTrackListener.onFaceFeatureInfoGet(null, requestId);
                    faceTrackListener.onFail(new Exception("fr failed ,frEngine is null" ));
                }
                if (faceRecognizeRunnables.size()>0){
                    executor.execute(faceRecognizeRunnables.poll());
                }
            }
        }
    }

    /**
     * 刷新trackId
     *
     * @param ftFaceList 传入的人脸列表
     */
    public void refreshTrackId(List<AFT_FSDKFace> ftFaceList) {
        currentTrackIdList.clear();
        //每项预先填充-1
        for (int i = 0; i < ftFaceList.size(); i++) {
            currentTrackIdList.add(-1);
        }
        //前一次无人脸现在有人脸，填充新增TrackId
        if (formerTrackIdList.size() == 0) {
            for (int i = 0; i < ftFaceList.size(); i++) {
                currentTrackIdList.set(i, ++currentTrackId);
            }
        } else {
            //前后都有人脸,对于每一个人脸框
            for (int i = 0; i < ftFaceList.size(); i++) {
                //遍历上一次人脸框
                for (int j = 0; j < formerFaceRectList.size(); j++) {
                    //若是同一张人脸
                    if (TrackUtil.isSameFace(SIMILARITY_RECT, formerFaceRectList.get(j), ftFaceList.get(i).getRect())) {
                        //记录ID
                        currentTrackIdList.set(i, formerTrackIdList.get(j));
                        break;
                    }
                }
            }
        }
        //上一次人脸框不存在此人脸
        for (int i = 0; i < currentTrackIdList.size(); i++) {
            if (currentTrackIdList.get(i) == -1) {
                currentTrackIdList.set(i, ++currentTrackId);
            }
        }
        formerTrackIdList.clear();
        formerFaceRectList.clear();
        for (int i = 0; i < ftFaceList.size(); i++) {
            formerFaceRectList.add(new Rect(ftFaceList.get(i).getRect()));
            formerTrackIdList.add(currentTrackIdList.get(i));
        }
        //刷新nameMap
        clearLeftName(currentTrackIdList);
    }

    public void putName(int trackId, String name) {
        if (!nameMap.containsKey(trackId)) {
            nameMap.put(trackId, name);
        }
    }

    /**
     * 清除map中已经离开的人脸
     *
     * @param trackIdList 最新的trackIdList
     */
    private void clearLeftName(List<Integer> trackIdList) {
        Set<Integer> keySet = nameMap.keySet();
        for (Integer integer : keySet) {
            if (!trackIdList.contains(integer)) {
                nameMap.remove(integer);
            }
        }
    }

}