// Guhit Studio engine - M1a: GPU canvas, brush stamping, stroke buffer, undo/redo, export.
//
// Pipeline:
//   stamps -> stroke buffer (premultiplied, opacity applied later)
//   stroke end -> composite(layer[cur], stroke) -> layer[1-cur]  (ping-pong)
//   undo/redo store before/after pixels of the stroke's bounding rect.
// Texture row 0 == canvas TOP. Canvas pixel coords: (0,0) top-left, y down.
#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <deque>
#include <vector>

#define LOG_TAG "GuhitEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kSize = 2048;
constexpr size_t kMaxHistoryBytes = 256u * 1024u * 1024u;
constexpr size_t kMaxHistoryEntries = 200;
constexpr size_t kStampFlushCount = 4096;

// ---------- shaders ----------
const char* kDisplayVs = R"(#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uView;
uniform vec2 uT;
uniform float uS;
uniform float uR;
out vec2 vUv;
void main() {
    float c = cos(uR);
    float s = sin(uR);
    vec2 p = uT + uS * vec2(c * aPos.x - s * aPos.y, s * aPos.x + c * aPos.y);
    gl_Position = vec4(p.x / uView.x * 2.0 - 1.0, 1.0 - p.y / uView.y * 2.0, 0.0, 1.0);
    vUv = aPos / 2048.0 + 0.5;
}
)";

const char* kFullscreenVs = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kCompositeFs = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uLayer;
uniform sampler2D uStroke;
uniform float uOpacity;
uniform float uErase;
uniform float uPaper;
uniform float uStrokeActive;
out vec4 outColor;
void main() {
    vec4 l = texture(uLayer, vUv);
    vec4 s = texture(uStroke, vUv) * uOpacity * uStrokeActive;
    vec4 res = (uErase > 0.5) ? l * (1.0 - s.a) : s + l * (1.0 - s.a);
    if (uPaper > 0.5) {
        res = res + vec4(1.0) * (1.0 - res.a);
    }
    outColor = res;
}
)";

const char* kStampVs = R"(#version 300 es
layout(location = 0) in vec4 aStamp;   // x, y (canvas px), diameter px, alpha
out vec2 vLocal;
out float vAlpha;
out float vHalf;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    vLocal = c;
    vAlpha = aStamp.w;
    vHalf = aStamp.z * 0.5;
    vec2 p = aStamp.xy + c * vHalf;
    gl_Position = vec4(p.x / 1024.0 - 1.0, p.y / 1024.0 - 1.0, 0.0, 1.0);
}
)";

const char* kStampFs = R"(#version 300 es
precision highp float;
in vec2 vLocal;
in float vAlpha;
in float vHalf;
uniform vec3 uColor;
uniform float uHardness;
out vec4 outColor;
void main() {
    float r = length(vLocal);
    float soft = clamp(1.5 / max(vHalf, 0.5), 0.0, 1.0);
    float h = max(min(uHardness, 1.0 - soft), 0.0);
    float a = (1.0 - smoothstep(h, 1.0, r)) * vAlpha;
    outColor = vec4(uColor * a, a);
}
)";

// ---------- GL state ----------
GLuint gDisplayProg = 0, gCommitProg = 0, gStampProg = 0;
GLuint gQuadVao = 0, gQuadVbo = 0, gEmptyVao = 0, gStampVao = 0, gStampVbo = 0;
GLuint gTex[2] = {0, 0}, gFbo[2] = {0, 0};
GLuint gStrokeTex = 0, gStrokeFbo = 0;
int gCur = 0;
int gWidth = 1, gHeight = 1;

struct CompositeLocs { GLint layer, stroke, opacity, erase, paper, active; };
struct DisplayLocs { GLint view, t, s, r; CompositeLocs c; };
DisplayLocs gDisp{};
CompositeLocs gCommit{};
GLint gStampColor = -1, gStampHardness = -1;

// ---------- brush / stroke ----------
struct Brush {
    float size = 12, hardness = 0.9f, spacing = 0.06f, flow = 1, sizeP = 1, flowP = 0;
    float r = 0, g = 0, b = 0, opacity = 1;
    bool erase = false;
} gBrush;

struct Pt { float x, y, p; };
Pt gPts[3];
Pt gDrawn{0, 0, 1};
int gN = 0;
bool gStroke = false;
float gCarry = 0;
std::vector<float> gStampBuf;
float gMinX, gMinY, gMaxX, gMaxY;

// ---------- history ----------
struct HistEntry {
    int x, y, w, h;
    std::vector<uint8_t> before, after;
    size_t bytes() const { return before.size() + after.size(); }
};
std::deque<HistEntry> gUndo, gRedo;
size_t gHistBytes = 0;

// ---------- helpers ----------
GLuint compile(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = 0;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[1024];
        glGetShaderInfoLog(sh, sizeof(buf), nullptr, buf);
        LOGE("Shader compile failed: %s", buf);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

GLuint link(const char* vs, const char* fs) {
    GLuint v = compile(GL_VERTEX_SHADER, vs);
    GLuint f = compile(GL_FRAGMENT_SHADER, fs);
    if (!v || !f) return 0;
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    glDeleteShader(v);
    glDeleteShader(f);
    if (!ok) {
        LOGE("Program link failed");
        return 0;
    }
    return p;
}

CompositeLocs compositeLocs(GLuint p) {
    return {glGetUniformLocation(p, "uLayer"), glGetUniformLocation(p, "uStroke"),
            glGetUniformLocation(p, "uOpacity"), glGetUniformLocation(p, "uErase"),
            glGetUniformLocation(p, "uPaper"), glGetUniformLocation(p, "uStrokeActive")};
}

void makeTexture(GLuint& tex, GLuint& fbo) {
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, kSize, kSize);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
}

void clearStroke() {
    glBindFramebuffer(GL_FRAMEBUFFER, gStrokeFbo);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
}

void readRect(GLuint fbo, int x, int y, int w, int h, std::vector<uint8_t>& out) {
    out.resize(static_cast<size_t>(w) * h * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
}

void uploadRect(GLuint tex, const HistEntry& e, const std::vector<uint8_t>& px) {
    glBindTexture(GL_TEXTURE_2D, tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, e.x, e.y, e.w, e.h, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
}

// ---------- stamping ----------
float sizeAt(float pressure) {
    float pr = std::clamp(pressure, 0.0f, 1.0f);
    float s = gBrush.size * (1.0f - gBrush.sizeP + gBrush.sizeP * std::max(pr, 0.05f));
    return std::max(s, 1.0f);
}

void flushStamps() {
    if (gStampBuf.empty()) return;
    glBindFramebuffer(GL_FRAMEBUFFER, gStrokeFbo);
    glViewport(0, 0, kSize, kSize);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glUseProgram(gStampProg);
    glUniform3f(gStampColor, gBrush.r, gBrush.g, gBrush.b);
    glUniform1f(gStampHardness, std::clamp(gBrush.hardness, 0.0f, 0.99f));
    glBindVertexArray(gStampVao);
    glBindBuffer(GL_ARRAY_BUFFER, gStampVbo);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(gStampBuf.size() * sizeof(float)),
                 gStampBuf.data(), GL_STREAM_DRAW);
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, static_cast<GLsizei>(gStampBuf.size() / 4));
    glBindVertexArray(0);
    glDisable(GL_BLEND);
    glViewport(0, 0, gWidth, gHeight);
    gStampBuf.clear();
}

void stampAt(float x, float y, float pressure) {
    float pr = std::clamp(pressure, 0.0f, 1.0f);
    float size = sizeAt(pr);
    float alpha = gBrush.flow * (1.0f - gBrush.flowP + gBrush.flowP * pr);
    gStampBuf.push_back(x);
    gStampBuf.push_back(y);
    gStampBuf.push_back(size);
    gStampBuf.push_back(alpha);
    float h = size * 0.5f + 2.0f;
    gMinX = std::min(gMinX, x - h);
    gMinY = std::min(gMinY, y - h);
    gMaxX = std::max(gMaxX, x + h);
    gMaxY = std::max(gMaxY, y + h);
    if (gStampBuf.size() / 4 >= kStampFlushCount) flushStamps();
}

Pt mid(const Pt& a, const Pt& b) { return {(a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f, (a.p + b.p) * 0.5f}; }

// Walk a quadratic bezier a -> b (control c), emitting stamps every `spacing` px.
void walk(const Pt& a, const Pt& c, const Pt& b) {
    float len = std::hypot(c.x - a.x, c.y - a.y) + std::hypot(b.x - c.x, b.y - c.y);
    int steps = std::clamp(static_cast<int>(std::ceil(len)), 1, 4096);
    float px = a.x, py = a.y;
    for (int i = 1; i <= steps; ++i) {
        float t = static_cast<float>(i) / steps;
        float u = 1.0f - t;
        float x = u * u * a.x + 2 * u * t * c.x + t * t * b.x;
        float y = u * u * a.y + 2 * u * t * c.y + t * t * b.y;
        float p = a.p + (b.p - a.p) * t;
        gCarry += std::hypot(x - px, y - py);
        px = x;
        py = y;
        float spacingPx = std::max(0.5f, sizeAt(p) * gBrush.spacing);
        while (gCarry >= spacingPx) {
            stampAt(x, y, p);
            gCarry -= spacingPx;
        }
    }
}

void trimHistory() {
    while (!gUndo.empty() && (gUndo.size() > kMaxHistoryEntries || gHistBytes > kMaxHistoryBytes)) {
        gHistBytes -= gUndo.front().bytes();
        gUndo.pop_front();
    }
}

void commitStroke() {
    flushStamps();
    int x0 = std::max(0, static_cast<int>(std::floor(gMinX)));
    int y0 = std::max(0, static_cast<int>(std::floor(gMinY)));
    int x1 = std::min(kSize, static_cast<int>(std::ceil(gMaxX)));
    int y1 = std::min(kSize, static_cast<int>(std::ceil(gMaxY)));
    gStroke = false;
    if (x1 <= x0 || y1 <= y0) {
        clearStroke();
        return;
    }
    int src = gCur, dst = 1 - gCur;
    glBindFramebuffer(GL_FRAMEBUFFER, gFbo[dst]);
    glViewport(0, 0, kSize, kSize);
    glDisable(GL_BLEND);
    glUseProgram(gCommitProg);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gTex[src]);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, gStrokeTex);
    glUniform1i(gCommit.layer, 0);
    glUniform1i(gCommit.stroke, 1);
    glUniform1f(gCommit.opacity, gBrush.opacity);
    glUniform1f(gCommit.erase, gBrush.erase ? 1.0f : 0.0f);
    glUniform1f(gCommit.paper, 0.0f);
    glUniform1f(gCommit.active, 1.0f);
    glBindVertexArray(gEmptyVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glViewport(0, 0, gWidth, gHeight);

    HistEntry e;
    e.x = x0; e.y = y0; e.w = x1 - x0; e.h = y1 - y0;
    readRect(gFbo[src], e.x, e.y, e.w, e.h, e.before);
    readRect(gFbo[dst], e.x, e.y, e.w, e.h, e.after);
    for (auto& r : gRedo) gHistBytes -= r.bytes();
    gRedo.clear();
    gHistBytes += e.bytes();
    gUndo.push_back(std::move(e));
    trimHistory();

    gCur = dst;
    clearStroke();
}

void destroyAll() {
    gUndo.clear();
    gRedo.clear();
    gHistBytes = 0;
    gStampBuf.clear();
    gStroke = false;
    gCur = 0;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeInit(JNIEnv*, jobject) {
    destroyAll();
    gDisplayProg = link(kDisplayVs, kCompositeFs);
    gCommitProg = link(kFullscreenVs, kCompositeFs);
    gStampProg = link(kStampVs, kStampFs);
    if (!gDisplayProg || !gCommitProg || !gStampProg) return;

    gDisp.view = glGetUniformLocation(gDisplayProg, "uView");
    gDisp.t = glGetUniformLocation(gDisplayProg, "uT");
    gDisp.s = glGetUniformLocation(gDisplayProg, "uS");
    gDisp.r = glGetUniformLocation(gDisplayProg, "uR");
    gDisp.c = compositeLocs(gDisplayProg);
    gCommit = compositeLocs(gCommitProg);
    gStampColor = glGetUniformLocation(gStampProg, "uColor");
    gStampHardness = glGetUniformLocation(gStampProg, "uHardness");

    const float h = kSize / 2.0f;
    const float quad[8] = {-h, -h, h, -h, -h, h, h, h};
    glGenVertexArrays(1, &gQuadVao);
    glBindVertexArray(gQuadVao);
    glGenBuffers(1, &gQuadVbo);
    glBindBuffer(GL_ARRAY_BUFFER, gQuadVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glBindVertexArray(0);

    glGenVertexArrays(1, &gEmptyVao);

    glGenVertexArrays(1, &gStampVao);
    glBindVertexArray(gStampVao);
    glGenBuffers(1, &gStampVbo);
    glBindBuffer(GL_ARRAY_BUFFER, gStampVbo);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 4, GL_FLOAT, GL_FALSE, 0, nullptr);
    glVertexAttribDivisor(0, 1);
    glBindVertexArray(0);

    makeTexture(gTex[0], gFbo[0]);
    makeTexture(gTex[1], gFbo[1]);
    makeTexture(gStrokeTex, gStrokeFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeResize(JNIEnv*, jobject, jint w, jint h) {
    gWidth = w > 0 ? w : 1;
    gHeight = h > 0 ? h : 1;
    glViewport(0, 0, gWidth, gHeight);
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeDraw(JNIEnv*, jobject, jfloat tx, jfloat ty,
                                                     jfloat scale, jfloat rotation) {
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, gWidth, gHeight);
    glDisable(GL_BLEND);
    glClearColor(0.11f, 0.11f, 0.12f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!gDisplayProg || !gTex[gCur]) return;
    glUseProgram(gDisplayProg);
    glUniform2f(gDisp.view, static_cast<float>(gWidth), static_cast<float>(gHeight));
    glUniform2f(gDisp.t, tx, ty);
    glUniform1f(gDisp.s, scale);
    glUniform1f(gDisp.r, rotation);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, gTex[gCur]);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, gStrokeTex);
    glUniform1i(gDisp.c.layer, 0);
    glUniform1i(gDisp.c.stroke, 1);
    glUniform1f(gDisp.c.opacity, gBrush.opacity);
    glUniform1f(gDisp.c.erase, gBrush.erase ? 1.0f : 0.0f);
    glUniform1f(gDisp.c.paper, 1.0f);
    glUniform1f(gDisp.c.active, gStroke ? 1.0f : 0.0f);
    glBindVertexArray(gQuadVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeSetBrush(JNIEnv*, jobject, jfloat size,
                                                         jfloat hardness, jfloat spacing,
                                                         jfloat flow, jfloat sizeP, jfloat flowP,
                                                         jfloat r, jfloat g, jfloat b,
                                                         jfloat opacity, jboolean erase) {
    if (gStroke) return;
    gBrush.size = size;
    gBrush.hardness = hardness;
    gBrush.spacing = spacing;
    gBrush.flow = flow;
    gBrush.sizeP = sizeP;
    gBrush.flowP = flowP;
    gBrush.r = r;
    gBrush.g = g;
    gBrush.b = b;
    gBrush.opacity = opacity;
    gBrush.erase = erase == JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeStrokeBegin(JNIEnv*, jobject) {
    if (!gStampProg) return;
    gStroke = true;
    gN = 0;
    gCarry = 0;
    gStampBuf.clear();
    gMinX = gMinY = 1e9f;
    gMaxX = gMaxY = -1e9f;
    clearStroke();
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeStrokeAdd(JNIEnv*, jobject, jfloat x, jfloat y,
                                                          jfloat pressure) {
    if (!gStroke) return;
    Pt q{x, y, pressure};
    if (gN == 0) {
        gPts[0] = q;
        gN = 1;
        gDrawn = q;
        stampAt(q.x, q.y, q.p);
    } else if (gN == 1) {
        gPts[1] = q;
        gN = 2;
        Pt m = mid(gPts[0], gPts[1]);
        walk(gPts[0], mid(gPts[0], m), m);
        gDrawn = m;
    } else {
        gPts[2] = q;
        Pt m0 = mid(gPts[0], gPts[1]);
        Pt m1 = mid(gPts[1], gPts[2]);
        walk(m0, gPts[1], m1);
        gDrawn = m1;
        gPts[0] = gPts[1];
        gPts[1] = gPts[2];
    }
    flushStamps();
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeStrokeEnd(JNIEnv*, jobject) {
    if (!gStroke) return;
    if (gN >= 2) {
        walk(gDrawn, mid(gDrawn, gPts[1]), gPts[1]);
    }
    commitStroke();
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeStrokeCancel(JNIEnv*, jobject) {
    if (!gStroke) return;
    gStampBuf.clear();
    gStroke = false;
    clearStroke();
}

JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeUndo(JNIEnv*, jobject) {
    if (gStroke || gUndo.empty()) return JNI_FALSE;
    HistEntry e = std::move(gUndo.back());
    gUndo.pop_back();
    uploadRect(gTex[gCur], e, e.before);
    gRedo.push_back(std::move(e));
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeRedo(JNIEnv*, jobject) {
    if (gStroke || gRedo.empty()) return JNI_FALSE;
    HistEntry e = std::move(gRedo.back());
    gRedo.pop_back();
    uploadRect(gTex[gCur], e, e.after);
    gUndo.push_back(std::move(e));
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeUndoCount(JNIEnv*, jobject) {
    return static_cast<jint>(gUndo.size());
}

JNIEXPORT jint JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeRedoCount(JNIEnv*, jobject) {
    return static_cast<jint>(gRedo.size());
}

// Fills `out` (kSize*kSize*4 bytes) with premultiplied RGBA, row 0 = canvas top.
JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeReadPixels(JNIEnv* env, jobject, jbyteArray out) {
    const jsize need = kSize * kSize * 4;
    if (!gFbo[gCur] || env->GetArrayLength(out) < need) return JNI_FALSE;
    std::vector<uint8_t> buf;
    readRect(gFbo[gCur], 0, 0, kSize, kSize, buf);
    env->SetByteArrayRegion(out, 0, need, reinterpret_cast<const jbyte*>(buf.data()));
    return JNI_TRUE;
}

}  // extern "C"
