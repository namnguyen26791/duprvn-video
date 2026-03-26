package com.pickleball.video.overlay

import android.graphics.*
import android.opengl.GLES20
import android.opengl.GLUtils
import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.encoder.utils.gl.GlUtil
import com.pickleball.video.data.MatchState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL filter that composites scoreboard overlay ON TOP of camera frame.
 * The base class already renders the camera; we draw our overlay after.
 */
class ScoreboardFilterRender : BaseFilterRender() {

    @Volatile var matchState: MatchState? = null
    @Volatile var courtName: String = ""

    private var overlayTextureId = -1
    private var overlayProgram = -1
    private var oPosition = -1
    private var oTexCoord = -1
    private var oTexture = -1
    private var squareVtx: FloatBuffer? = null
    private var texVtx: FloatBuffer? = null
    private var bitmap: Bitmap? = null
    private var needUpload = true
    private var lastHash = 0

    private val VERT = """
        attribute vec4 aPosition;
        attribute vec2 aTextureCoord;
        varying vec2 vTC;
        void main() { gl_Position = aPosition; vTC = aTextureCoord; }
    """.trimIndent()

    private val FRAG = """
        precision mediump float;
        varying vec2 vTC;
        uniform sampler2D uTex;
        void main() {
            vec4 c = texture2D(uTex, vTC);
            if (c.a < 0.01) discard;
            gl_FragColor = c;
        }
    """.trimIndent()

    override fun initGlFilter(context: android.content.Context?) {
        val v = floatArrayOf(-1f,-1f, 1f,-1f, -1f,1f, 1f,1f)
        squareVtx = ByteBuffer.allocateDirect(v.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
        val t = floatArrayOf(0f,1f, 1f,1f, 0f,0f, 1f,0f)
        texVtx = ByteBuffer.allocateDirect(t.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(t); position(0) }
        overlayProgram = GlUtil.createProgram(VERT, FRAG)
        oPosition = GLES20.glGetAttribLocation(overlayProgram, "aPosition")
        oTexCoord = GLES20.glGetAttribLocation(overlayProgram, "aTextureCoord")
        oTexture = GLES20.glGetUniformLocation(overlayProgram, "uTex")
        val tex = IntArray(1); GLES20.glGenTextures(1, tex, 0); overlayTextureId = tex[0]
    }

    override fun drawFilter() {
        // Base class already drew camera frame. Now overlay scoreboard on top.
        rebuildBitmap()
        val bmp = bitmap ?: return

        GLES20.glUseProgram(overlayProgram)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, overlayTextureId)
        if (needUpload) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            needUpload = false
        }
        GLES20.glUniform1i(oTexture, 0)
        squareVtx?.position(0)
        GLES20.glVertexAttribPointer(oPosition, 2, GLES20.GL_FLOAT, false, 0, squareVtx)
        GLES20.glEnableVertexAttribArray(oPosition)
        texVtx?.position(0)
        GLES20.glVertexAttribPointer(oTexCoord, 2, GLES20.GL_FLOAT, false, 0, texVtx)
        GLES20.glEnableVertexAttribArray(oTexCoord)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    override fun release() {
        if (overlayTextureId != -1) { GLES20.glDeleteTextures(1, intArrayOf(overlayTextureId), 0); overlayTextureId = -1 }
        bitmap?.recycle(); bitmap = null
    }

    private fun rebuildBitmap() {
        val m = matchState
        val h = m.hashCode() + courtName.hashCode()
        if (h == lastHash) return
        lastHash = h
        val w = 1280; val ht = 720
        val bmp = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (m != null) {
            if (m.paused) ScoreboardOverlay.drawPaused(c, w, ht, m)
            else ScoreboardOverlay.draw(c, w, ht, m)
        }
        bitmap?.recycle(); bitmap = bmp; needUpload = true
    }
}
