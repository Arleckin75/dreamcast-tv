/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dreamcast.nativeapp;

import androidx.media3.exoplayer.video.VideoDecoderOutputBufferRenderer;
import java.util.Arrays;
import android.opengl.EGL14;
import android.opengl.EGLExt;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import android.content.Context;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import androidx.annotation.Nullable;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Log;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicReference;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * GLSurfaceView implementing {@link VideoDecoderOutputBufferRenderer} for rendering {@link
 * VideoDecoderOutputBuffer VideoDecoderOutputBuffers}.
 *
 * <p>This view is intended for use only with decoders that produce {@link VideoDecoderOutputBuffer
 * VideoDecoderOutputBuffers}. For other use cases a {@link android.view.SurfaceView} or {@link
 * android.view.TextureView} should be used instead.
 */
@UnstableApi
// Modified from Media3 1.9.2: allocate texture storage only when plane dimensions change.
public final class ReusableTextureVideoView extends GLSurfaceView
    implements VideoDecoderOutputBufferRenderer {

  private static final String TAG = "VideoDecoderGLSV";

  private final Renderer renderer;
  public volatile int duplicateDraws;
  public volatile int replacedFrames;
  public volatile int timestampFailures;

  /**
   * @param context A {@link Context}.
   */
  public ReusableTextureVideoView(Context context) {
    this(context, /* attrs= */ null);
  }

  /**
   * @param context A {@link Context}.
   * @param attrs Custom attributes.
   */
  @SuppressWarnings({"nullness:assignment", "nullness:argument", "nullness:method.invocation"})
  public ReusableTextureVideoView(Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    renderer = new Renderer(/* surfaceView= */ this);
    setPreserveEGLContextOnPause(true);
    setEGLContextClientVersion(2);
    setRenderer(renderer);
    setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
  }

  @Override
  public void setOutputBuffer(VideoDecoderOutputBuffer outputBuffer) {
    renderer.setOutputBuffer(outputBuffer, System.nanoTime());
  }

  public void setOutputBuffer(VideoDecoderOutputBuffer outputBuffer, long presentationTimeNs) {
    renderer.setOutputBuffer(outputBuffer, presentationTimeNs);
  }

  /**
   * @deprecated This class implements {@link VideoDecoderOutputBufferRenderer} directly.
   */
  @Deprecated
  public VideoDecoderOutputBufferRenderer getVideoDecoderOutputBufferRenderer() {
    return this;
  }

  private static final class TimedFrame {
    final VideoDecoderOutputBuffer buffer;
    final long presentationTimeNs;
    TimedFrame(VideoDecoderOutputBuffer buffer, long presentationTimeNs) {
      this.buffer = buffer;
      this.presentationTimeNs = presentationTimeNs;
    }
  }

  private static final class Renderer implements GLSurfaceView.Renderer {

    private static final float[] kColorConversion601 = {
      1.164f, 1.164f, 1.164f,
      0.0f, -0.392f, 2.017f,
      1.596f, -0.813f, 0.0f,
    };

    private static final float[] kColorConversion709 = {
      1.164f, 1.164f, 1.164f,
      0.0f, -0.213f, 2.112f,
      1.793f, -0.533f, 0.0f,
    };

    private static final float[] kColorConversion2020 = {
      1.168f, 1.168f, 1.168f,
      0.0f, -0.188f, 2.148f,
      1.683f, -0.652f, 0.0f,
    };

    private static final String VERTEX_SHADER =
        "varying vec2 interp_tc_y;\n"
            + "varying vec2 interp_tc_u;\n"
            + "varying vec2 interp_tc_v;\n"
            + "attribute vec4 in_pos;\n"
            + "attribute vec2 in_tc_y;\n"
            + "attribute vec2 in_tc_u;\n"
            + "attribute vec2 in_tc_v;\n"
            + "void main() {\n"
            + "  gl_Position = in_pos;\n"
            + "  interp_tc_y = in_tc_y;\n"
            + "  interp_tc_u = in_tc_u;\n"
            + "  interp_tc_v = in_tc_v;\n"
            + "}\n";
    private static final String[] TEXTURE_UNIFORMS = {"y_tex", "u_tex", "v_tex"};
    private static final String FRAGMENT_SHADER =
        "precision mediump float;\n"
            + "varying vec2 interp_tc_y;\n"
            + "varying vec2 interp_tc_u;\n"
            + "varying vec2 interp_tc_v;\n"
            + "uniform sampler2D y_tex;\n"
            + "uniform sampler2D u_tex;\n"
            + "uniform sampler2D v_tex;\n"
            + "uniform mat3 mColorConversion;\n"
            + "void main() {\n"
            + "  vec3 yuv;\n"
            + "  yuv.x = texture2D(y_tex, interp_tc_y).r - 0.0625;\n"
            + "  yuv.y = texture2D(u_tex, interp_tc_u).r - 0.5;\n"
            + "  yuv.z = texture2D(v_tex, interp_tc_v).r - 0.5;\n"
            + "  gl_FragColor = vec4(mColorConversion * yuv, 1.0);\n"
            + "}\n";

    private static final FloatBuffer TEXTURE_VERTICES =
        GlUtil.createBuffer(new float[] {-1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f, -1.0f});

    private final ReusableTextureVideoView surfaceView;
    private final int[] yuvTextures;
    private final int[] texLocations;
    private final int[] previousWidths;
    private final int[] previousStrides;
    private final AtomicReference<TimedFrame>
        pendingOutputBufferReference;

    // Kept in field rather than a local variable in order not to get garbage collected before
    // glDrawArrays uses it.
    private final FloatBuffer[] textureCoords;
    private final int[] allocatedWidths = new int[3];
    private final int[] allocatedHeights = new int[3];
    private final int[] widths = new int[3];

    private @Nullable GlProgram program;
    private int colorMatrixLocation;

    // Accessed only from the GL thread.
    private @Nullable VideoDecoderOutputBuffer renderedOutputBuffer;

    public Renderer(ReusableTextureVideoView surfaceView) {
      this.surfaceView = surfaceView;
      yuvTextures = new int[3];
      texLocations = new int[3];
      previousWidths = new int[3];
      previousStrides = new int[3];
      pendingOutputBufferReference = new AtomicReference<>();
      textureCoords = new FloatBuffer[3];
      for (int i = 0; i < 3; i++) {
        previousWidths[i] = previousStrides[i] = -1;
      }
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
      Arrays.fill(allocatedWidths, 0);
      Arrays.fill(allocatedHeights, 0);
      Arrays.fill(previousWidths, -1);
      Arrays.fill(previousStrides, -1);
      try {
        program = new GlProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        int posLocation = program.getAttributeArrayLocationAndEnable("in_pos");
        GLES20.glVertexAttribPointer(
            posLocation,
            2,
            GLES20.GL_FLOAT,
            /* normalized= */ false,
            /* stride= */ 0,
            TEXTURE_VERTICES);
        texLocations[0] = program.getAttributeArrayLocationAndEnable("in_tc_y");
        texLocations[1] = program.getAttributeArrayLocationAndEnable("in_tc_u");
        texLocations[2] = program.getAttributeArrayLocationAndEnable("in_tc_v");
        colorMatrixLocation = program.getUniformLocation("mColorConversion");
        GlUtil.checkGlError();
        setupTextures();
        GlUtil.checkGlError();
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Failed to set up the textures and program", e);
      }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
      GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
      @Nullable
      TimedFrame pendingFrame =
          pendingOutputBufferReference.getAndSet(/* newValue= */ null);
      VideoDecoderOutputBuffer pendingOutputBuffer = pendingFrame == null ? null : pendingFrame.buffer;
      if (pendingOutputBuffer == null && renderedOutputBuffer == null) {
        // There is no output buffer to render at the moment.
        return;
      }
      if (pendingOutputBuffer == null && renderedOutputBuffer != null) surfaceView.duplicateDraws++;
      if (pendingOutputBuffer != null) {
        if (renderedOutputBuffer != null) {
          renderedOutputBuffer.release();
        }
        renderedOutputBuffer = pendingOutputBuffer;
      }

      VideoDecoderOutputBuffer outputBuffer = checkNotNull(renderedOutputBuffer);

      // Set color matrix. Assume BT709 if the color space is unknown.
      float[] colorConversion = kColorConversion709;
      switch (outputBuffer.colorspace) {
        case VideoDecoderOutputBuffer.COLORSPACE_BT601:
          colorConversion = kColorConversion601;
          break;
        case VideoDecoderOutputBuffer.COLORSPACE_BT2020:
          colorConversion = kColorConversion2020;
          break;
        case VideoDecoderOutputBuffer.COLORSPACE_BT709:
        default:
          // Do nothing.
          break;
      }
      GLES20.glUniformMatrix3fv(
          colorMatrixLocation,
          /* color= */ 1,
          /* transpose= */ false,
          colorConversion,
          /* offset= */ 0);

      int[] yuvStrides = checkNotNull(outputBuffer.yuvStrides);
      ByteBuffer[] yuvPlanes = checkNotNull(outputBuffer.yuvPlanes);

      for (int i = 0; i < 3; i++) {
        int h = (i == 0) ? outputBuffer.height : (outputBuffer.height + 1) / 2;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[i]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        if (allocatedWidths[i] != yuvStrides[i] || allocatedHeights[i] != h) {
          GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE,
              yuvStrides[i], h, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
          allocatedWidths[i] = yuvStrides[i];
          allocatedHeights[i] = h;
        }
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, yuvStrides[i], h,
            GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, yuvPlanes[i]);
      }

      widths[0] = outputBuffer.width;
      // TODO(b/142097774): Handle streams where chroma channels are not stored at half width and
      // height compared to the luma channel. U and V planes are being stored at half width compared
      // to Y.
      widths[1] = widths[2] = (widths[0] + 1) / 2;
      for (int i = 0; i < 3; i++) {
        // Set cropping of stride if either width or stride has changed.
        if (previousWidths[i] != widths[i] || previousStrides[i] != yuvStrides[i]) {
          checkState(yuvStrides[i] != 0);
          float widthRatio = (float) widths[i] / yuvStrides[i];
          // These buffers are consumed during each call to glDrawArrays. They need to be member
          // variables rather than local variables in order not to get garbage collected.
          textureCoords[i] =
              GlUtil.createBuffer(
                  new float[] {0.0f, 0.0f, 0.0f, 1.0f, widthRatio, 0.0f, widthRatio, 1.0f});
          GLES20.glVertexAttribPointer(
              texLocations[i],
              /* size= */ 2,
              GLES20.GL_FLOAT,
              /* normalized= */ false,
              /* stride= */ 0,
              textureCoords[i]);
          previousWidths[i] = widths[i];
          previousStrides[i] = yuvStrides[i];
        }
      }

      boolean timestampAccepted = EGLExt.eglPresentationTimeANDROID(EGL14.eglGetCurrentDisplay(),
          EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW),
          pendingFrame == null ? System.nanoTime() : pendingFrame.presentationTimeNs);
      if (!timestampAccepted) surfaceView.timestampFailures++;
      GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
      GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first= */ 0, /* count= */ 4);
      try {
        GlUtil.checkGlError();
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Failed to draw a frame", e);
      }
    }

    public void setOutputBuffer(VideoDecoderOutputBuffer outputBuffer, long presentationTimeNs) {
      @Nullable
      TimedFrame oldPendingOutputBuffer =
          pendingOutputBufferReference.getAndSet(new TimedFrame(outputBuffer, presentationTimeNs));
      if (oldPendingOutputBuffer != null) {
        surfaceView.replacedFrames++;
        // The old pending output buffer will never be used for rendering, so release it now.
        oldPendingOutputBuffer.buffer.release();
      }
      surfaceView.requestRender();
    }

    private void setupTextures() {
      try {
        GLES20.glGenTextures(/* n= */ 3, yuvTextures, /* offset= */ 0);
        for (int i = 0; i < 3; i++) {
          GLES20.glUniform1i(program.getUniformLocation(TEXTURE_UNIFORMS[i]), i);
          GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i);
          GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, yuvTextures[i], GLES20.GL_LINEAR);
        }
        GlUtil.checkGlError();
      } catch (GlUtil.GlException e) {
        Log.e(TAG, "Failed to set up the textures", e);
      }
    }
  }
}
