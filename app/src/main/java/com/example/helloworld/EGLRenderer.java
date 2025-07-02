package com.example.helloworld;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.*;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class EGLRenderer {
    private SurfaceTexture surfaceTexture;
    private Surface surface;
    private int width, height;

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private int oesTextureId;
    private int program;
    private int positionHandle;
    private int texCoordHandle;
    private int textureHandle;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // 顶点着色器（用于全屏绘制）
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
                    "attribute vec2 aTexCoord;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = aPosition;\n" +
                    "    vTexCoord = aTexCoord;\n" +
                    "}";

    // 片元着色器（用于采样 OES 纹理）
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
                    "precision mediump float;\n" +
                    "uniform samplerExternalOES uTexture;\n" +
                    "varying vec2 vTexCoord;\n" +
                    "void main() {\n" +
                    "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
                    "}";

    // 全屏四边形顶点坐标
    private static final float[] VERTICES = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f,  1.0f,
            1.0f,  1.0f
    };

    // 对应纹理坐标（注意是 Y 翻转）
    private static final float[] TEX_COORDS = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    public EGLRenderer(int width, int height) {
        this.width = width;
        this.height = height;

        // 初始化 EGL 环境
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        EGL14.eglInitialize(eglDisplay, new int[2], 0, new int[2], 0);

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, new int[1], 0);
        EGLConfig eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

        int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, width,
                EGL14.EGL_HEIGHT, height,
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfaceAttribs, 0);
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);

        // 初始化 OES 纹理和 SurfaceTexture
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);

        surfaceTexture = new SurfaceTexture(oesTextureId);
        surface = new Surface(surfaceTexture);

        // 准备 Shader 和 Buffers
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");

        vertexBuffer = ByteBuffer.allocateDirect(VERTICES.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        vertexBuffer.put(VERTICES).position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(TEX_COORDS.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS).position(0);
    }

    public SurfaceTexture getSurfaceTexture() {
        return surfaceTexture;
    }

    public Bitmap getCurrentFrame() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        surfaceTexture.updateTexImage();

        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(program);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glUniform1i(textureHandle, 0);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);  // ✅ 渲染操作在这里

        int[] pixels = new int[width * height];
        IntBuffer ib = IntBuffer.allocate(width * height);
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ib);

        int[] src = ib.array();
        for (int i = 0; i < height; i++) {
            for (int j = 0; j < width; j++) {
                int srcIdx = i * width + j;
                int dstIdx = (height - i - 1) * width + j;
                pixels[dstIdx] = src[srcIdx];
            }
        }

        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bmp.copyPixelsFromBuffer(IntBuffer.wrap(pixels));
        return bmp;
    }

    public void release() {
        if (surface != null) surface.release();
        if (surfaceTexture != null) surfaceTexture.release();
        if (eglDisplay != null && eglSurface != null) EGL14.eglDestroySurface(eglDisplay, eglSurface);
        if (eglDisplay != null && eglContext != null) EGL14.eglDestroyContext(eglDisplay, eglContext);
        if (eglDisplay != null) EGL14.eglTerminate(eglDisplay);
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}


