package com.android.helper.opengl;

import android.opengl.GLES20;
import com.android.helper.util.AffineMatrix;
import java.nio.Buffer;
import java.nio.FloatBuffer;

/* JADX INFO: loaded from: classes.dex */
public class AffineOpenGLFilter implements OpenGLFilter {
    static final /* synthetic */ boolean $assertionsDisabled = false;
    private int program;
    private FloatBuffer texCoordsBuffer;
    private int texCoordsInLoc;
    private int texLoc;
    private int texMatrixLoc;
    private final float[] userMatrix;
    private int userMatrixLoc;
    private FloatBuffer vertexBuffer;
    private int vertexPosLoc;

    public AffineOpenGLFilter(AffineMatrix affineMatrix) {
        this.userMatrix = affineMatrix.to4x4();
    }

    @Override // com.android.helper.opengl.OpenGLFilter
    public void init() throws OpenGLException {
        int iCreateProgram = GLUtils.createProgram("#version 100\nattribute vec4 vertex_pos;\nattribute vec4 tex_coords_in;\nvarying vec2 tex_coords;\nuniform mat4 tex_matrix;\nuniform mat4 user_matrix;\nvoid main() {\n    gl_Position = vertex_pos;\n    tex_coords = (tex_matrix * user_matrix * tex_coords_in).xy;\n}", "#version 100\n#extension GL_OES_EGL_image_external : require\nprecision highp float;\nuniform samplerExternalOES tex;\nvarying vec2 tex_coords;\nvoid main() {\n    if (tex_coords.x >= 0.0 && tex_coords.x <= 1.0\n            && tex_coords.y >= 0.0 && tex_coords.y <= 1.0) {\n        gl_FragColor = texture2D(tex, tex_coords);\n    } else {\n        gl_FragColor = vec4(0.0);\n    }\n}");
        this.program = iCreateProgram;
        if (iCreateProgram == 0) {
            throw new OpenGLException("Cannot create OpenGL program");
        }
        this.vertexBuffer = GLUtils.createFloatBuffer(new float[]{-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f});
        this.texCoordsBuffer = GLUtils.createFloatBuffer(new float[]{0.0f, 0.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f});
        this.vertexPosLoc = GLES20.glGetAttribLocation(this.program, "vertex_pos");
        this.texCoordsInLoc = GLES20.glGetAttribLocation(this.program, "tex_coords_in");
        this.texLoc = GLES20.glGetUniformLocation(this.program, "tex");
        this.texMatrixLoc = GLES20.glGetUniformLocation(this.program, "tex_matrix");
        this.userMatrixLoc = GLES20.glGetUniformLocation(this.program, "user_matrix");
    }

    @Override // com.android.helper.opengl.OpenGLFilter
    public void draw(int i, float[] fArr) {
        GLES20.glUseProgram(this.program);
        GLUtils.checkGlError();
        GLES20.glEnableVertexAttribArray(this.vertexPosLoc);
        GLUtils.checkGlError();
        GLES20.glEnableVertexAttribArray(this.texCoordsInLoc);
        GLUtils.checkGlError();
        GLES20.glVertexAttribPointer(this.vertexPosLoc, 2, 5126, false, 0, (Buffer) this.vertexBuffer);
        GLUtils.checkGlError();
        GLES20.glVertexAttribPointer(this.texCoordsInLoc, 2, 5126, false, 0, (Buffer) this.texCoordsBuffer);
        GLUtils.checkGlError();
        GLES20.glActiveTexture(33984);
        GLUtils.checkGlError();
        GLES20.glBindTexture(36197, i);
        GLUtils.checkGlError();
        GLES20.glUniform1i(this.texLoc, 0);
        GLUtils.checkGlError();
        GLES20.glUniformMatrix4fv(this.texMatrixLoc, 1, false, fArr, 0);
        GLUtils.checkGlError();
        GLES20.glUniformMatrix4fv(this.userMatrixLoc, 1, false, this.userMatrix, 0);
        GLUtils.checkGlError();
        GLES20.glClear(16384);
        GLUtils.checkGlError();
        GLES20.glDrawArrays(5, 0, 4);
        GLUtils.checkGlError();
    }

    @Override // com.android.helper.opengl.OpenGLFilter
    public void release() {
        GLES20.glDeleteProgram(this.program);
        GLUtils.checkGlError();
    }
}
