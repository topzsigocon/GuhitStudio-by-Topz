// Guhit Studio engine - M0: GL context, transform pipeline, test canvas.
#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#define LOG_TAG "GuhitEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr float kCanvasSize = 2048.0f;

GLuint gProgram = 0;
GLuint gVao = 0;
GLuint gVbo = 0;
int gWidth = 1;
int gHeight = 1;
GLint uView = -1, uT = -1, uS = -1, uR = -1;

const char* kVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPos;      // canvas space, origin = canvas center
uniform vec2 uView;                     // viewport size in px
uniform vec2 uT;                        // screen translation in px
uniform float uS;                       // scale
uniform float uR;                       // rotation (radians)
out vec2 vUv;
void main() {
    float c = cos(uR);
    float s = sin(uR);
    vec2 p = uT + uS * vec2(c * aPos.x - s * aPos.y, s * aPos.x + c * aPos.y);
    gl_Position = vec4(p.x / uView.x * 2.0 - 1.0, 1.0 - p.y / uView.y * 2.0, 0.0, 1.0);
    vUv = aPos / 2048.0 + 0.5;
}
)";

const char* kFragmentShader = R"(#version 300 es
precision mediump float;
in vec2 vUv;
out vec4 outColor;
void main() {
    vec2 g = floor(vUv * 32.0);
    float checker = mod(g.x + g.y, 2.0);
    vec3 col = mix(vec3(0.93), vec3(0.82), checker);
    // orientation marker (top-left) so rotation is visible
    if (length(vUv - vec2(0.08)) < 0.04) col = vec3(0.9, 0.2, 0.2);
    // canvas border
    vec2 e = min(vUv, 1.0 - vUv);
    if (min(e.x, e.y) < 0.002) col = vec3(0.2);
    outColor = vec4(col, 1.0);
}
)";

GLuint compile(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetShaderInfoLog(sh, sizeof(buf), nullptr, buf);
        LOGE("Shader compile failed: %s", buf);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeInit(JNIEnv*, jobject) {
    GLuint vs = compile(GL_VERTEX_SHADER, kVertexShader);
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFragmentShader);
    if (!vs || !fs) return;
    gProgram = glCreateProgram();
    glAttachShader(gProgram, vs);
    glAttachShader(gProgram, fs);
    glLinkProgram(gProgram);
    GLint ok = 0;
    glGetProgramiv(gProgram, GL_LINK_STATUS, &ok);
    if (!ok) {
        LOGE("Program link failed");
        gProgram = 0;
        return;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    uView = glGetUniformLocation(gProgram, "uView");
    uT = glGetUniformLocation(gProgram, "uT");
    uS = glGetUniformLocation(gProgram, "uS");
    uR = glGetUniformLocation(gProgram, "uR");

    const float h = kCanvasSize / 2.0f;
    const float quad[8] = {-h, -h, h, -h, -h, h, h, h};
    glGenVertexArrays(1, &gVao);
    glBindVertexArray(gVao);
    glGenBuffers(1, &gVbo);
    glBindBuffer(GL_ARRAY_BUFFER, gVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glBindVertexArray(0);

    glClearColor(0.11f, 0.11f, 0.12f, 1.0f);
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    gWidth = w > 0 ? w : 1;
    gHeight = h > 0 ? h : 1;
    glViewport(0, 0, gWidth, gHeight);
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeDraw(JNIEnv*, jobject,
                                                     jfloat tx, jfloat ty,
                                                     jfloat scale, jfloat rotation) {
    glClear(GL_COLOR_BUFFER_BIT);
    if (!gProgram) return;
    glUseProgram(gProgram);
    glUniform2f(uView, static_cast<float>(gWidth), static_cast<float>(gHeight));
    glUniform2f(uT, tx, ty);
    glUniform1f(uS, scale);
    glUniform1f(uR, rotation);
    glBindVertexArray(gVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

}  // extern "C"
