package com.metallic.chiaki.fsr;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;

import java.io.IOException;

public final class NisVideoProcessor implements VideoProcessingGLSurfaceView.VideoProcessor {
    private static final String TAG = "NisVideoProcessor";
    private static final String SHADER_DIR = "nis/";

    private final Context context;

    private GlProgram nisProgram;
    private GlProgram passthroughProgram;
    private boolean initialized;

    private float sharpness = 0.5f;
    private int outputWidth = -1;
    private int outputHeight = -1;
    private float[] outputSize = new float[] {1f, 1f};
    private boolean nisEnabled = true;

    public NisVideoProcessor(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void initialize(int glMajorVersion, int glMinorVersion, String extensions) {
        resetPrograms();

        boolean supportsExternalOesEssl3 = extensions != null
                && extensions.contains("GL_OES_EGL_image_external_essl3");

        if (!supportsExternalOesEssl3 && glMajorVersion < 3) {
            Log.w(TAG, "GLES2 without OES_EGL_image_external_essl3, NIS disabled");
            return;
        }

        try {
            nisProgram = buildProgram(
                    SHADER_DIR + "nis_vertex.glsl",
                    SHADER_DIR + "nis_fragment.glsl"
            );
            initialized = true;
            Log.i(TAG, "NIS pipeline active (single-pass)");
        } catch (IOException | GlUtil.GlException e) {
            Log.e(TAG, "Failed to initialize NIS shader", e);
            safeDeleteProgram(nisProgram);
            nisProgram = null;
        }
    }

    @Override
    public void setSurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (outputWidth == width && outputHeight == height) return;
        outputWidth = width;
        outputHeight = height;
        outputSize = new float[] {width, height};
    }

    @Override
    public void draw(int frameTexture, long frameTimestampUs,
                     int frameWidth, int frameHeight, float[] transformMatrix) {
        int vpW = Math.max(outputWidth, 1);
        int vpH = Math.max(outputHeight, 1);
        GLES20.glViewport(0, 0, vpW, vpH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        if (!nisEnabled || nisProgram == null || !initialized) {
            drawPassthrough(frameTexture, transformMatrix);
            return;
        }

        try {
            nisProgram.setSamplerTexIdUniform("inputTexture", frameTexture, 0,
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            nisProgram.setFloatsUniform("inputTextureSize", new float[] {
                    Math.max(frameWidth, 1),
                    Math.max(frameHeight, 1)
            });
            nisProgram.setFloatsUniform("outputTextureSize", outputSize);
            nisProgram.setFloatUniform("sharpness", sharpness);
            nisProgram.bindAttributesAndUniforms();
        } catch (GlUtil.GlException e) {
            Log.e(TAG, "Failed to bind NIS shader", e);
            drawPassthrough(frameTexture, transformMatrix);
            return;
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    @Override
    public void release() {
        resetPrograms();
    }

    public void setEnabled(boolean enabled) {
        nisEnabled = enabled;
    }

    public void setSharpness(float value) {
        sharpness = Math.max(0.0f, Math.min(1.0f, value));
    }

    private void drawPassthrough(int frameTexture, float[] transformMatrix) {
        try {
            GlProgram program = ensurePassthroughProgram();
            program.setSamplerTexIdUniform("inputTexture", frameTexture, 0,
                    GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            program.setMatrix4Uniform("uTexTransform", transformMatrix);
            program.setFloatUniform("uHdrToneMap", 0.0f);
            program.bindAttributesAndUniforms();
        } catch (IOException | GlUtil.GlException e) {
            return;
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    private GlProgram ensurePassthroughProgram() throws IOException {
        if (passthroughProgram == null) {
            passthroughProgram = buildProgram(
                    "fsr/2.0/opt_fsr_vertex.glsl",
                    "fsr/2.0/passthrough_fragment.glsl"
            );
        }
        return passthroughProgram;
    }

    private GlProgram buildProgram(String vertAsset, String fragAsset) throws IOException {
        GlProgram program = new GlProgram(context, vertAsset, fragAsset);
        program.setBufferAttribute("aPosition", GlUtil.getFullscreenVertices(), 2);
        program.setBufferAttribute("aTexCoords", GlUtil.getFullscreenTexCoords(), 2);
        return program;
    }

    private void resetPrograms() {
        safeDeleteProgram(nisProgram);
        nisProgram = null;
        safeDeleteProgram(passthroughProgram);
        passthroughProgram = null;
        initialized = false;
    }

    private void safeDeleteProgram(GlProgram program) {
        if (program == null) return;
        try {
            program.delete();
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to delete GL program", e);
        }
    }
}
