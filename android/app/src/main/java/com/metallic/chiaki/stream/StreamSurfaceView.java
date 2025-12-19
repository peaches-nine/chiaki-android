package com.metallic.chiaki.stream;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;

public class StreamSurfaceView extends SurfaceView {
    private long lastFrameTime = 0;
    private int frameCount = 0;
    private float fps = 0;

    public StreamSurfaceView(Context context) {
        super(context);
    }

    public StreamSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long currentTime = System.nanoTime();
        frameCount++;
        // 计算帧率
        if (lastFrameTime != 0) {
            long deltaTime = currentTime - lastFrameTime;
            if (deltaTime > 1000000000) { // 每秒钟计算一次帧率
                fps = (frameCount * 1000000000f) / deltaTime;
                frameCount = 0;
                lastFrameTime = currentTime;
            }
        }
        // 例如在屏幕上显示 FPS 信息
        Log.i("axixi","FPS: " + fps);
    }
}
