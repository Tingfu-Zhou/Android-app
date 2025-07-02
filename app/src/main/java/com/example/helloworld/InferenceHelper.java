package com.example.helloworld;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import com.google.mlkit.vision.pose.PoseDetector;
import com.google.mlkit.vision.pose.PoseLandmark;
import com.google.mlkit.vision.common.InputImage;
// ML Kit Pose Detection



public class InferenceHelper {

    private PoseDetector poseDetector;
    private static final String TAG = "InferenceHelper";


    // ST-GCN++ 输入
    private static final int STGCN_BATCH = 1, STGCN_CHANNEL = 3, STGCN_TIME = 32, STGCN_VERTEX = 17, STGCN_M = 1;

    // Tiny-ST-GCN++ 二分类模型常量
    private static final int BIN_BATCH  = 1;
    private static final int BIN_CHAN   = 3;
    private static final int BIN_TIME   = 8;      // ★ 8 帧
    private static final int BIN_VERT   = 17;
    private static final int BIN_M      = 1;

    private OrtSession tinyBinSession;            // ★ 新增


    private OrtEnvironment env;
    private OrtSession poseSession, stgcnSession;

    public interface OnPoseResultListener {
        void onResult(float[][][][] keypointsRaw);  // null 表示无人
    }

    public void setPoseDetector(PoseDetector detector) {
        this.poseDetector = detector;
    }

    public InferenceHelper(Context context) {
        try {
            env = OrtEnvironment.getEnvironment();
            stgcnSession = createSession(context, "stgcnpp.onnx");
            // ★ Binary-ST-GCN++ 二分类
            tinyBinSession = createSession(context, "stgcnpp_binary.onnx");
        } catch (Exception e) {
            Log.e(TAG, "模型初始化失败: " + e.getMessage());
        }
    }

    private OrtSession createSession(Context context, String modelAssetName) throws OrtException, IOException {
        OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
        sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        byte[] modelBytes = loadModelFile(context, modelAssetName);
        return env.createSession(modelBytes, sessionOptions);
    }

    private byte[] loadModelFile(Context context, String assetName) throws IOException {
        InputStream is = context.getAssets().open(assetName);
        byte[] buffer = new byte[is.available()];
        is.read(buffer);
        is.close();
        return buffer;
    }


    /**
     * 使用 Google ML Kit Pose Detection 替代 MMPose，返回 [1,1,17,3] 的 COCO 关键点数组。
     * 每个点格式为 [x, y, confidence]。
     *
     * ML Kit Pose Detection 返回的是 33 点式全身骨架，需要映射成 COCO 17
     */
    public void runPoseModelViaMLKit(Bitmap bitmap, OnPoseResultListener listener) {
        if (poseDetector == null) {
            Log.e(TAG, "[MLKit] poseDetector 未初始化！");
            listener.onResult(null);
            return;
        }

        // [新增] 置信度计算相关常量
        final float POINT_CONF_THRES = 0.5f;                       // 单点置信度阈值
        final float FRAME_PASS_RATIO = 0.5f;                       // 通过比例阈值（50%）
        final int   COCO_KPT_NUM     = 17;                         // COCO 关键点数量
        final int   FRAME_PASS_CNT   = Math.round(COCO_KPT_NUM * FRAME_PASS_RATIO); // 17×0.5=12

        InputImage image = InputImage.fromBitmap(bitmap, 0);

        poseDetector.process(image)
                .addOnSuccessListener(pose -> {
                    if (pose.getAllPoseLandmarks().isEmpty()) {
                        Log.d(TAG, "[同步分析] 当前帧未检测到人体");
                        listener.onResult(null);
                        return;
                    }

                    float[][][][] result = new float[1][1][17][3];
                    int[] cocoToMLKit = {
                            PoseLandmark.NOSE, PoseLandmark.LEFT_EYE, PoseLandmark.RIGHT_EYE,
                            PoseLandmark.LEFT_EAR, PoseLandmark.RIGHT_EAR,
                            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
                            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
                            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST,
                            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
                            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
                    };

                    int validCount = 0;  // [新增] 满足置信度阈值的关键点数量

                    for (int i = 0; i < 17; i++) {
                        PoseLandmark lm = pose.getPoseLandmark(cocoToMLKit[i]);
                        if (lm != null) {
                            result[0][0][i][0] = lm.getPosition().x;
                            result[0][0][i][1] = lm.getPosition().y;
                            result[0][0][i][2] = lm.getInFrameLikelihood();

                            // [新增] 判断该关键点是否超过单点置信度阈值
                            if (lm.getInFrameLikelihood() > POINT_CONF_THRES) {
                                validCount++;
                            }
                        } else {
                            result[0][0][i][0] = 0f;
                            result[0][0][i][1] = 0f;
                            result[0][0][i][2] = 0f;
                        }
                    }

                    // [新增] 帧级置信度阈值判断
                    if (validCount < FRAME_PASS_CNT) {
                        Log.d(TAG, String.format(
                                "[同步分析] 关键点置信度不足 (%d/%d)，跳过该帧", validCount, COCO_KPT_NUM));
                        listener.onResult(null);
                        return;
                    }

                    listener.onResult(result);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "[MLKit] 推理异常: ", e);
                    listener.onResult(null);
                });
    }

    /**
     * Tiny-ST-GCN++ 二分类器
     * @param poseWindow  [T=8][V=17][C=3] 顺序的关键点数组
     * @return            Sigmoid 概率 (1 = 目标动作, 0 = Background)
     */
    public float runBinary(float[][][] poseWindow) {
        if (tinyBinSession == null) {
            Log.e(TAG, "tinyBinSession 未初始化！");
            return 1.0f;                 // 容错：全部放行
        }
        try {
            // 1) 展平成 1-D float[]
            float[] flat = new float[BIN_BATCH * BIN_CHAN * BIN_TIME * BIN_VERT * BIN_M];
            for (int c = 0; c < BIN_CHAN; c++)
                for (int t = 0; t < BIN_TIME; t++)
                    for (int v = 0; v < BIN_VERT; v++)
                        flat[((c * BIN_TIME + t) * BIN_VERT + v)] = poseWindow[t][v][c];

            // 2) 构建 Tensor
            long[] shape = {BIN_BATCH, BIN_CHAN, BIN_TIME, BIN_VERT, BIN_M};
            OnnxTensor inTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), shape);

            // 3) 推理
            OrtSession.Result res = tinyBinSession.run(Collections.singletonMap("input", inTensor));
            float[][] logits = (float[][]) res.get(0).getValue();   // [1][1] or [1][2] 皆可

            // 4) 取 logit[0] 并做 Sigmoid
            float logit = logits[0][0];
            float prob  = 1f / (1f + (float) Math.exp(-logit));

            // 5) 资源释放
            res.close();
            inTensor.close();
            return prob;
        } catch (Exception e) {
            Log.e(TAG, "runTinyBinary error: " + e.getMessage());
            return 1.0f;                 // 发生异常时宁可放行
        }
    }


    public float[] runStgcnModel(float[][][] poseWindow) {
        try {
            float[] input = new float[STGCN_BATCH * STGCN_CHANNEL * STGCN_TIME * STGCN_VERTEX * STGCN_M];
            for (int c = 0; c < STGCN_CHANNEL; c++)
                for (int t = 0; t < STGCN_TIME; t++)
                    for (int v = 0; v < STGCN_VERTEX; v++)
                        input[((c * STGCN_TIME + t) * STGCN_VERTEX + v)] = poseWindow[t][v][c];
            long[] shape = {STGCN_BATCH, STGCN_CHANNEL, STGCN_TIME, STGCN_VERTEX, STGCN_M};
            OnnxTensor inputTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape);
            OrtSession.Result results = stgcnSession.run(Collections.singletonMap("input", inputTensor));
            float[][] outputArray = (float[][]) results.get(0).getValue();
            Log.d(TAG, " STGCN++ 动作识别 logits: " + Arrays.toString(outputArray[0]));
            results.close();
            inputTensor.close();
            return outputArray[0];
        } catch (Exception e) {
            Log.e(TAG, "runStgcnModel error: " + e.getMessage());
            return null;
        }
    }




    public void close() {
        try {
            stgcnSession.close();
            if (tinyBinSession != null) tinyBinSession.close();
            env.close();
        } catch (OrtException e) {
            Log.e(TAG, "关闭 ONNX Runtime 失败: " + e.getMessage());
        }
    }
}

