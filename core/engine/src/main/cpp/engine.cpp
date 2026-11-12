// Guhit Studio engine - M2: layers.
//
//  layers[i] (RGBA8 premultiplied, one GPU texture each)  ->  recomposite(rect)  ->  flat texture
//  recomposite walks the visible layers bottom->top through two scratch buffers (blend modes need
//  the backdrop), restricted by scissor to a dirty rect, then blits that rect into `flat`.
//  The screen only ever samples `flat` (+ white paper), so pan/zoom costs one texture read.
//
//  Strokes: stamps -> stroke buffer (opacity applied later). While drawing, the active layer's pass
//  includes the stroke (live preview). On commit the stroke is blended INTO the layer in place with
//  fixed-function blending (normal / erase / alpha lock). Undo keeps the stroke rect's previous pixels
//  in a small GPU texture (GPU->GPU blits, no CPU readback, no stall); undo/redo swap that texture with
//  the layer region. Stamps are batched: one flush + recomposite per touch event, not per point.
//
// Texture row 0 == canvas TOP. Canvas pixel coords: (0,0) top-left, y down.
#include <jni.h>
#include <GLES3/gl3.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <utility>
#include <vector>

#define LOG_TAG "GuhitEngine"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr size_t kMaxHistoryBytes = 256u * 1024u * 1024u;
constexpr size_t kMaxHistoryEntries = 200;
constexpr size_t kStampFlushCount = 4096;
constexpr size_t kTextureBudgetBytes = 512u * 1024u * 1024u;
constexpr int kFixedTextures = 4;  // 2 scratch + flat + stroke

// ---------------------------------------------------------------- shaders
const char* kDisplayVs = R"(#version 300 es
layout(location = 0) in vec2 aPos;
uniform vec2 uView;
uniform vec2 uT;
uniform float uS;
uniform float uR;
uniform vec2 uCanvas;
out vec2 vUv;
void main() {
    float c = cos(uR);
    float s = sin(uR);
    vec2 p = uT + uS * vec2(c * aPos.x - s * aPos.y, s * aPos.x + c * aPos.y);
    gl_Position = vec4(p.x / uView.x * 2.0 - 1.0, 1.0 - p.y / uView.y * 2.0, 0.0, 1.0);
    vUv = aPos / uCanvas + 0.5;
}
)";

const char* kDisplayFs = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uFlat;
out vec4 outColor;
void main() {
    vec4 c = texture(uFlat, vUv);
    outColor = c + vec4(1.0) * (1.0 - c.a);   // premultiplied over white paper
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

const char* kStrokeFs = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uStroke;
uniform float uOpacity;
out vec4 outColor;
void main() {
    outColor = texture(uStroke, vUv) * uOpacity;
}
)";

const char* kLayerFs = R"(#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uBackdrop;
uniform sampler2D uLayer;
uniform sampler2D uStroke;
uniform sampler2D uClip;
uniform float uOpacity;
uniform int uBlend;
uniform int uUseClip;
uniform int uStrokeOn;
uniform float uStrokeOpacity;
uniform int uStrokeErase;
uniform int uStrokeLock;
out vec4 outColor;

float lum(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
vec3 clipColor(vec3 c) {
    float l = lum(c);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = l + (c - l) * l / (l - n);
    if (x > 1.0) c = l + (c - l) * (1.0 - l) / (x - l);
    return c;
}
vec3 setLum(vec3 c, float l) { return clipColor(c + (l - lum(c))); }
float sat(vec3 c) { return max(max(c.r, c.g), c.b) - min(min(c.r, c.g), c.b); }
vec3 setSat(vec3 c, float s) {
    float mx = max(max(c.r, c.g), c.b);
    float mn = min(min(c.r, c.g), c.b);
    if (mx > mn) return (c - mn) * s / (mx - mn);
    return vec3(0.0);
}
float burn(float b, float s) {
    if (s <= 0.0) return b >= 1.0 ? 1.0 : 0.0;
    return 1.0 - min(1.0, (1.0 - b) / s);
}
float dodge(float b, float s) {
    if (b <= 0.0) return 0.0;
    if (s >= 1.0) return 1.0;
    return min(1.0, b / (1.0 - s));
}
vec3 hardLight(vec3 cb, vec3 cs) {
    return mix(cb * 2.0 * cs, 1.0 - 2.0 * (1.0 - cb) * (1.0 - cs), step(0.5, cs));
}
vec3 softLight(vec3 cb, vec3 cs) {
    vec3 d = mix(((16.0 * cb - 12.0) * cb + 4.0) * cb, sqrt(cb), step(0.25, cb));
    return mix(cb - (1.0 - 2.0 * cs) * cb * (1.0 - cb), cb + (2.0 * cs - 1.0) * (d - cb), step(0.5, cs));
}
vec3 blendRgb(vec3 cb, vec3 cs, int m) {
    if (m == 1) return cb * cs;
    if (m == 2) return min(cb, cs);
    if (m == 3) return vec3(burn(cb.r, cs.r), burn(cb.g, cs.g), burn(cb.b, cs.b));
    if (m == 4) return max(cb + cs - 1.0, 0.0);
    if (m == 5) return cb + cs - cb * cs;
    if (m == 6) return max(cb, cs);
    if (m == 7) return vec3(dodge(cb.r, cs.r), dodge(cb.g, cs.g), dodge(cb.b, cs.b));
    if (m == 8) return min(cb + cs, 1.0);
    if (m == 9) return hardLight(cs, cb);
    if (m == 10) return softLight(cb, cs);
    if (m == 11) return hardLight(cb, cs);
    if (m == 12) return abs(cb - cs);
    if (m == 13) return cb + cs - 2.0 * cb * cs;
    if (m == 14) return setLum(setSat(cs, sat(cb)), lum(cb));
    if (m == 15) return setLum(setSat(cb, sat(cs)), lum(cb));
    if (m == 16) return setLum(cs, lum(cb));
    if (m == 17) return setLum(cb, lum(cs));
    return cs;
}

void main() {
    vec4 b = texture(uBackdrop, vUv);
    vec4 l = texture(uLayer, vUv);
    if (uStrokeOn == 1) {
        vec4 s = texture(uStroke, vUv) * uStrokeOpacity;
        if (uStrokeErase == 1) l = l * (1.0 - s.a);
        else if (uStrokeLock == 1) l = vec4(s.rgb * l.a + l.rgb * (1.0 - s.a), l.a);
        else l = s + l * (1.0 - s.a);
    }
    vec4 src = l * uOpacity;
    if (uUseClip == 1) src *= texture(uClip, vUv).a;
    if (src.a <= 0.0001) { outColor = b; return; }
    vec3 cs = src.rgb / src.a;
    vec3 cb = b.a > 0.0001 ? b.rgb / b.a : vec3(0.0);
    vec3 mixedRgb = (uBlend == 0) ? cs : blendRgb(cb, cs, uBlend);
    vec3 rgb = src.a * (1.0 - b.a) * cs + src.a * b.a * mixedRgb + (1.0 - src.a) * b.rgb;
    float a = src.a + b.a * (1.0 - src.a);
    outColor = vec4(rgb, a);
}
)";

const char* kStampVs = R"(#version 300 es
layout(location = 0) in vec4 aStamp;   // x, y (canvas px), diameter px, alpha
uniform vec2 uCanvas;
out vec2 vLocal;
out float vAlpha;
out float vHalf;
void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1)) * 2.0 - 1.0;
    vLocal = c;
    vAlpha = aStamp.w;
    vHalf = aStamp.z * 0.5;
    vec2 p = aStamp.xy + c * vHalf;
    gl_Position = vec4(p.x / uCanvas.x * 2.0 - 1.0, p.y / uCanvas.y * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kStampFs = R"(#version 300 es
precision mediump float;
in vec2 vLocal;
in float vAlpha;
in float vHalf;
uniform vec3 uColor;
uniform float uHardness;
out vec4 outColor;
void main() {
    float r = length(vLocal);
    if (r >= 1.0) discard;
    float soft = clamp(1.5 / max(vHalf, 0.5), 0.0, 1.0);
    float h = max(min(uHardness, 1.0 - soft), 0.0);
    float a = (1.0 - smoothstep(h, 1.0, r)) * vAlpha;
    outColor = vec4(uColor * a, a);
}
)";

// ---------------------------------------------------------------- state
struct Rect { int x = 0, y = 0, w = 0, h = 0; };

struct Layer {
    int id = 0;
    GLuint tex = 0, fbo = 0;
    bool visible = true;
    float opacity = 1.0f;
    int blend = 0;
    bool alphaLock = false;
    bool clip = false;
};

struct LayerLocs {
    GLint backdrop, layer, stroke, clip, opacity, blend, useClip, strokeOn, strokeOpacity, strokeErase, strokeLock;
};

GLuint gDisplayProg = 0, gLayerProg = 0, gStrokeProg = 0, gStampProg = 0;
GLuint gQuadVao = 0, gQuadVbo = 0, gEmptyVao = 0, gStampVao = 0, gStampVbo = 0;
GLint uView = -1, uT = -1, uS = -1, uR = -1, uDispCanvas = -1, uFlat = -1;
LayerLocs gLL{};
GLint gStrokeLocStroke = -1, gStrokeLocOpacity = -1;
GLint gStampColor = -1, gStampHardness = -1, gStampCanvas = -1;

int gWidth = 1, gHeight = 1;  // screen viewport
int gCw = 0, gCh = 0;         // canvas size
int gMaxTex = 2048;
int gMaxLayers = 1;

std::vector<Layer> gLayers;  // bottom -> top
int gActiveId = -1;
GLuint gAccTex[2] = {0, 0}, gAccFbo[2] = {0, 0};
GLuint gFlatTex = 0, gFlatFbo = 0;
GLuint gStrokeTex = 0, gStrokeFbo = 0;

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
int gStrokeLayer = -1;
float gCarry = 0;
std::vector<float> gStampBuf;
float gMinX, gMinY, gMaxX, gMaxY;

struct HistEntry {
    int layerId = 0;
    Rect r;
    GLuint tex = 0, fbo = 0;  // pixels of `r` that are currently NOT in the layer (swapped on undo/redo)
    size_t bytes() const { return static_cast<size_t>(r.w) * r.h * 4; }
};
std::deque<HistEntry> gUndo, gRedo;
size_t gHistBytes = 0;

// ---------------------------------------------------------------- helpers
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

GLint loc(GLuint prog, const char* name) { return glGetUniformLocation(prog, name); }

bool makeTex(GLuint& tex, GLuint& fbo, GLint filter) {
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, gCw, gCh);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
    return glGetError() == GL_NO_ERROR;
}

void destroyTex(GLuint& tex, GLuint& fbo) {
    if (fbo) glDeleteFramebuffers(1, &fbo);
    if (tex) glDeleteTextures(1, &tex);
    fbo = 0;
    tex = 0;
}

int findLayer(int id) {
    for (size_t i = 0; i < gLayers.size(); ++i)
        if (gLayers[i].id == id) return static_cast<int>(i);
    return -1;
}

Rect fullRect() { return Rect{0, 0, gCw, gCh}; }

Rect clampRect(int x0, int y0, int x1, int y1) {
    x0 = std::max(0, x0);
    y0 = std::max(0, y0);
    x1 = std::min(gCw, x1);
    y1 = std::min(gCh, y1);
    if (x1 <= x0 || y1 <= y0) return Rect{};
    return Rect{x0, y0, x1 - x0, y1 - y0};
}

void readRect(GLuint fbo, const Rect& r, std::vector<uint8_t>& out) {
    out.resize(static_cast<size_t>(r.w) * r.h * 4);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(r.x, r.y, r.w, r.h, GL_RGBA, GL_UNSIGNED_BYTE, out.data());
}

// Allocates a w x h RGBA8 texture + FBO for holding a copy of a layer region. False on failure.
bool makeRegionTex(const Rect& r, GLuint& tex, GLuint& fbo) {
    while (glGetError() != GL_NO_ERROR) {}
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, r.w, r.h);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
    if (glGetError() != GL_NO_ERROR) {
        destroyTex(tex, fbo);
        return false;
    }
    return true;
}

// GPU-side copy of a same-sized rect between two framebuffers (no CPU involved).
void copyRegion(GLuint srcFbo, const Rect& src, GLuint dstFbo, const Rect& dst) {
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, srcFbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dstFbo);
    glBlitFramebuffer(src.x, src.y, src.x + src.w, src.y + src.h, dst.x, dst.y, dst.x + dst.w, dst.y + dst.h,
                      GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

void clearStroke() {
    glBindFramebuffer(GL_FRAMEBUFFER, gStrokeFbo);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);
}

void bindTex(int unit, GLuint tex) {
    glActiveTexture(GL_TEXTURE0 + unit);
    glBindTexture(GL_TEXTURE_2D, tex);
}

void setLayerUniforms(float opacity, int blend) {
    glUniform1i(gLL.backdrop, 0);
    glUniform1i(gLL.layer, 1);
    glUniform1i(gLL.stroke, 2);
    glUniform1i(gLL.clip, 3);
    glUniform1f(gLL.opacity, opacity);
    glUniform1i(gLL.blend, blend);
}

// ---------------------------------------------------------------- compositing
// Recomposites all visible layers inside `r` into the flat texture.
void recomposite(const Rect& r) {
    if (!gFlatFbo || r.w <= 0 || r.h <= 0) return;
    glDisable(GL_BLEND);
    glEnable(GL_SCISSOR_TEST);
    glScissor(r.x, r.y, r.w, r.h);
    glViewport(0, 0, gCw, gCh);

    int src = 0, dst = 1;
    glBindFramebuffer(GL_FRAMEBUFFER, gAccFbo[src]);
    glClearColor(0, 0, 0, 0);
    glClear(GL_COLOR_BUFFER_BIT);

    glUseProgram(gLayerProg);
    glBindVertexArray(gEmptyVao);
    for (int i = 0; i < static_cast<int>(gLayers.size()); ++i) {
        const Layer& L = gLayers[i];
        if (!L.visible) continue;
        const Layer* base = nullptr;
        if (L.clip) {
            for (int j = i - 1; j >= 0; --j) {
                if (!gLayers[j].clip) { base = &gLayers[j]; break; }
            }
            if (base && !base->visible) continue;  // clipped to a hidden base: hidden too
        }
        glBindFramebuffer(GL_FRAMEBUFFER, gAccFbo[dst]);
        bindTex(0, gAccTex[src]);
        bindTex(1, L.tex);
        bindTex(2, gStrokeTex);
        bindTex(3, base ? base->tex : L.tex);
        setLayerUniforms(L.opacity, L.blend);
        glUniform1i(gLL.useClip, base ? 1 : 0);
        const bool strokeOn = gStroke && L.id == gStrokeLayer;
        glUniform1i(gLL.strokeOn, strokeOn ? 1 : 0);
        glUniform1f(gLL.strokeOpacity, gBrush.opacity);
        glUniform1i(gLL.strokeErase, gBrush.erase ? 1 : 0);
        glUniform1i(gLL.strokeLock, L.alphaLock ? 1 : 0);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        std::swap(src, dst);
    }
    glBindVertexArray(0);
    glDisable(GL_SCISSOR_TEST);

    glBindFramebuffer(GL_READ_FRAMEBUFFER, gAccFbo[src]);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, gFlatFbo);
    glBlitFramebuffer(r.x, r.y, r.x + r.w, r.y + r.h, r.x, r.y, r.x + r.w, r.y + r.h,
                      GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
}

// ---------------------------------------------------------------- stamping
float sizeAt(float pressure) {
    float pr = std::clamp(pressure, 0.0f, 1.0f);
    float s = gBrush.size * (1.0f - gBrush.sizeP + gBrush.sizeP * std::max(pr, 0.05f));
    return std::max(s, 1.0f);
}

Rect unionRect(const Rect& a, const Rect& b) {
    int x0 = std::min(a.x, b.x), y0 = std::min(a.y, b.y);
    int x1 = std::max(a.x + a.w, b.x + b.w), y1 = std::max(a.y + a.h, b.y + b.h);
    return Rect{x0, y0, x1 - x0, y1 - y0};
}

void flushStamps() {
    if (gStampBuf.empty()) return;
    const size_t n = gStampBuf.size() / 4;

    // Dirty rects: group consecutive stamps and merge groups only while that does not waste area.
    // (One union rect of a long diagonal batch can be hundreds of times bigger than the path itself.)
    std::vector<Rect> dirty;
    Rect cur;
    bool has = false;
    for (size_t i = 0; i < n; i += 8) {
        const size_t j = std::min(n, i + 8);
        float minX = 1e9f, minY = 1e9f, maxX = -1e9f, maxY = -1e9f;
        for (size_t k = i; k < j; ++k) {
            float h = gStampBuf[k * 4 + 2] * 0.5f + 2.0f;
            minX = std::min(minX, gStampBuf[k * 4] - h);
            minY = std::min(minY, gStampBuf[k * 4 + 1] - h);
            maxX = std::max(maxX, gStampBuf[k * 4] + h);
            maxY = std::max(maxY, gStampBuf[k * 4 + 1] + h);
        }
        Rect g = clampRect(static_cast<int>(std::floor(minX)), static_cast<int>(std::floor(minY)),
                           static_cast<int>(std::ceil(maxX)), static_cast<int>(std::ceil(maxY)));
        if (g.w <= 0 || g.h <= 0) continue;
        if (!has) {
            cur = g;
            has = true;
            continue;
        }
        Rect m = unionRect(cur, g);
        const long long am = static_cast<long long>(m.w) * m.h;
        const long long as = static_cast<long long>(cur.w) * cur.h + static_cast<long long>(g.w) * g.h;
        if (am * 2 <= as * 3) {
            cur = m;
        } else {
            dirty.push_back(cur);
            cur = g;
        }
    }
    if (has) dirty.push_back(cur);

    glBindFramebuffer(GL_FRAMEBUFFER, gStrokeFbo);
    glViewport(0, 0, gCw, gCh);
    glEnable(GL_BLEND);
    glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    glUseProgram(gStampProg);
    glUniform3f(gStampColor, gBrush.r, gBrush.g, gBrush.b);
    glUniform1f(gStampHardness, std::clamp(gBrush.hardness, 0.0f, 0.99f));
    glUniform2f(gStampCanvas, static_cast<float>(gCw), static_cast<float>(gCh));
    glBindVertexArray(gStampVao);
    glBindBuffer(GL_ARRAY_BUFFER, gStampVbo);
    glBufferData(GL_ARRAY_BUFFER, static_cast<GLsizeiptr>(gStampBuf.size() * sizeof(float)),
                 gStampBuf.data(), GL_STREAM_DRAW);
    glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, static_cast<GLsizei>(n));
    glBindVertexArray(0);
    glDisable(GL_BLEND);
    gStampBuf.clear();

    for (const Rect& r : dirty) recomposite(r);  // live preview of the stroke on the active layer
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

// ---------------------------------------------------------------- history
void destroyEntry(HistEntry& e) { destroyTex(e.tex, e.fbo); }

void trimHistory() {
    while (!gUndo.empty() && (gUndo.size() > kMaxHistoryEntries || gHistBytes > kMaxHistoryBytes)) {
        gHistBytes -= gUndo.front().bytes();
        destroyEntry(gUndo.front());
        gUndo.pop_front();
    }
}

// destroy=false when the GL context is gone (its objects no longer exist).
void clearHistory(bool destroy = true) {
    if (destroy) {
        for (auto& e : gUndo) destroyEntry(e);
        for (auto& e : gRedo) destroyEntry(e);
    }
    gUndo.clear();
    gRedo.clear();
    gHistBytes = 0;
}

void purgeHistory(int layerId) {
    auto purge = [&](std::deque<HistEntry>& q) {
        for (auto it = q.begin(); it != q.end();) {
            if (it->layerId == layerId) {
                gHistBytes -= it->bytes();
                destroyEntry(*it);
                it = q.erase(it);
            } else {
                ++it;
            }
        }
    };
    purge(gUndo);
    purge(gRedo);
}

bool strokeRect(Rect& out) {
    out = clampRect(static_cast<int>(std::floor(gMinX)), static_cast<int>(std::floor(gMinY)),
                    static_cast<int>(std::ceil(gMaxX)), static_cast<int>(std::ceil(gMaxY)));
    return out.w > 0 && out.h > 0;
}

// Blends the stroke buffer (premultiplied, scaled by gBrush.opacity) into layer `idx` inside rect r,
// keeping the previous pixels for undo. Used by strokes and by fill.
void commitRegion(int idx, const Rect& r) {
    Layer& L = gLayers[idx];

    // Keep the layer's current pixels of this rect for undo: GPU->GPU copy, no readback.
    HistEntry e;
    e.layerId = L.id;
    e.r = r;
    const bool haveHistory = makeRegionTex(r, e.tex, e.fbo);
    if (haveHistory) copyRegion(L.fbo, r, e.fbo, Rect{0, 0, r.w, r.h});

    glBindFramebuffer(GL_FRAMEBUFFER, L.fbo);
    glViewport(0, 0, gCw, gCh);
    glEnable(GL_SCISSOR_TEST);
    glScissor(r.x, r.y, r.w, r.h);
    glUseProgram(gStrokeProg);
    bindTex(0, gStrokeTex);
    glUniform1i(gStrokeLocStroke, 0);
    glUniform1f(gStrokeLocOpacity, gBrush.opacity);
    glEnable(GL_BLEND);
    if (gBrush.erase) {
        glBlendFunc(GL_ZERO, GL_ONE_MINUS_SRC_ALPHA);
    } else if (L.alphaLock) {
        glBlendFuncSeparate(GL_DST_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
    } else {
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
    }
    glBindVertexArray(gEmptyVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glDisable(GL_BLEND);
    glDisable(GL_SCISSOR_TEST);

    for (auto& q : gRedo) {
        gHistBytes -= q.bytes();
        destroyEntry(q);
    }
    gRedo.clear();
    if (haveHistory) {
        gHistBytes += e.bytes();
        gUndo.push_back(e);
        trimHistory();
    }

    clearStroke();
    recomposite(r);
}

void commitStroke() {
    flushStamps();
    Rect r;
    int idx = findLayer(gStrokeLayer);
    bool ok = idx >= 0 && strokeRect(r);
    gStroke = false;
    if (!ok) {
        clearStroke();
        recomposite(fullRect());
        return;
    }
    commitRegion(idx, r);
}

// Undo and redo are the same operation: swap the layer's rect with the entry's saved pixels.
bool applyHistory(bool undo) {
    auto& from = undo ? gUndo : gRedo;
    auto& to = undo ? gRedo : gUndo;
    while (!from.empty()) {
        HistEntry e = from.back();
        from.pop_back();
        int idx = findLayer(e.layerId);
        if (idx < 0) {
            gHistBytes -= e.bytes();
            destroyEntry(e);
            continue;
        }
        Layer& L = gLayers[idx];
        GLuint t2 = 0, f2 = 0;
        if (!makeRegionTex(e.r, t2, f2)) {  // out of GPU memory: drop this entry
            gHistBytes -= e.bytes();
            destroyEntry(e);
            return false;
        }
        const Rect local{0, 0, e.r.w, e.r.h};
        copyRegion(L.fbo, e.r, f2, local);   // layer now -> temp
        copyRegion(e.fbo, local, L.fbo, e.r);  // saved pixels -> layer
        destroyEntry(e);
        e.tex = t2;
        e.fbo = f2;
        recomposite(e.r);
        to.push_back(e);
        return true;
    }
    return false;
}

// ---------------------------------------------------------------- layers
void removeLayerInternal(int id) {
    int idx = findLayer(id);
    if (idx < 0) return;
    destroyTex(gLayers[idx].tex, gLayers[idx].fbo);
    gLayers.erase(gLayers.begin() + idx);
    purgeHistory(id);
    if (gActiveId == id) gActiveId = -1;
}

void releaseCanvas() {
    for (auto& l : gLayers) destroyTex(l.tex, l.fbo);
    gLayers.clear();
    for (int i = 0; i < 2; ++i) destroyTex(gAccTex[i], gAccFbo[i]);
    destroyTex(gFlatTex, gFlatFbo);
    destroyTex(gStrokeTex, gStrokeFbo);
    clearHistory(true);
    gStampBuf.clear();
    gStroke = false;
    gActiveId = -1;
    gStrokeLayer = -1;
    gCw = gCh = 0;
}

void forgetAll() {  // new GL context: old object names are invalid, do not delete them
    gLayers.clear();
    gAccTex[0] = gAccTex[1] = gAccFbo[0] = gAccFbo[1] = 0;
    gFlatTex = gFlatFbo = gStrokeTex = gStrokeFbo = 0;
    clearHistory(false);
    gStampBuf.clear();
    gStroke = false;
    gActiveId = -1;
    gStrokeLayer = -1;
    gCw = gCh = 0;
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeInit(JNIEnv*, jobject) {
    forgetAll();
    gDisplayProg = link(kDisplayVs, kDisplayFs);
    gLayerProg = link(kFullscreenVs, kLayerFs);
    gStrokeProg = link(kFullscreenVs, kStrokeFs);
    gStampProg = link(kStampVs, kStampFs);
    if (!gDisplayProg || !gLayerProg || !gStrokeProg || !gStampProg) return;

    uView = loc(gDisplayProg, "uView");
    uT = loc(gDisplayProg, "uT");
    uS = loc(gDisplayProg, "uS");
    uR = loc(gDisplayProg, "uR");
    uDispCanvas = loc(gDisplayProg, "uCanvas");
    uFlat = loc(gDisplayProg, "uFlat");

    gLL = {loc(gLayerProg, "uBackdrop"), loc(gLayerProg, "uLayer"), loc(gLayerProg, "uStroke"),
           loc(gLayerProg, "uClip"), loc(gLayerProg, "uOpacity"), loc(gLayerProg, "uBlend"),
           loc(gLayerProg, "uUseClip"), loc(gLayerProg, "uStrokeOn"), loc(gLayerProg, "uStrokeOpacity"),
           loc(gLayerProg, "uStrokeErase"), loc(gLayerProg, "uStrokeLock")};
    gStrokeLocStroke = loc(gStrokeProg, "uStroke");
    gStrokeLocOpacity = loc(gStrokeProg, "uOpacity");
    gStampColor = loc(gStampProg, "uColor");
    gStampHardness = loc(gStampProg, "uHardness");
    gStampCanvas = loc(gStampProg, "uCanvas");
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &gMaxTex);

    glGenVertexArrays(1, &gQuadVao);
    glBindVertexArray(gQuadVao);
    glGenBuffers(1, &gQuadVbo);
    glBindBuffer(GL_ARRAY_BUFFER, gQuadVbo);
    glBufferData(GL_ARRAY_BUFFER, 8 * sizeof(float), nullptr, GL_DYNAMIC_DRAW);
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
    glDisable(GL_SCISSOR_TEST);
    glClearColor(0.02f, 0.027f, 0.05f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    if (!gDisplayProg || !gFlatTex || gCw <= 0) return;
    glUseProgram(gDisplayProg);
    glUniform2f(uView, static_cast<float>(gWidth), static_cast<float>(gHeight));
    glUniform2f(uT, tx, ty);
    glUniform1f(uS, scale);
    glUniform1f(uR, rotation);
    glUniform2f(uDispCanvas, static_cast<float>(gCw), static_cast<float>(gCh));
    bindTex(0, gFlatTex);
    glUniform1i(uFlat, 0);
    glBindVertexArray(gQuadVao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
}

// Allocates an empty canvas (no layers yet) of w x h. False if unsupported.
JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeNewCanvas(JNIEnv*, jobject, jint w, jint h) {
    if (!gStampProg || w < 1 || h < 1 || w > gMaxTex || h > gMaxTex) return JNI_FALSE;
    releaseCanvas();
    gCw = w;
    gCh = h;
    while (glGetError() != GL_NO_ERROR) {}
    bool ok = makeTex(gAccTex[0], gAccFbo[0], GL_NEAREST) && makeTex(gAccTex[1], gAccFbo[1], GL_NEAREST) &&
              makeTex(gFlatTex, gFlatFbo, GL_LINEAR) && makeTex(gStrokeTex, gStrokeFbo, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    if (!ok) {
        releaseCanvas();
        return JNI_FALSE;
    }
    const size_t perTexture = static_cast<size_t>(w) * h * 4;
    gMaxLayers = std::clamp(static_cast<int>(kTextureBudgetBytes / perTexture) - kFixedTextures, 1, 100);
    const float hw = w / 2.0f, hh = h / 2.0f;
    const float quad[8] = {-hw, -hh, hw, -hh, -hw, hh, hw, hh};
    glBindBuffer(GL_ARRAY_BUFFER, gQuadVbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(quad), quad, GL_DYNAMIC_DRAW);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeMaxLayers(JNIEnv*, jobject) {
    return gMaxLayers;
}

JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeAddLayer(JNIEnv*, jobject, jint id, jint index,
                                                         jboolean force) {
    if (gCw <= 0 || findLayer(id) >= 0) return JNI_FALSE;
    if (force != JNI_TRUE && static_cast<int>(gLayers.size()) >= gMaxLayers) return JNI_FALSE;
    while (glGetError() != GL_NO_ERROR) {}
    Layer l;
    l.id = id;
    if (!makeTex(l.tex, l.fbo, GL_NEAREST)) {
        destroyTex(l.tex, l.fbo);
        return JNI_FALSE;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    int at = std::clamp(static_cast<int>(index), 0, static_cast<int>(gLayers.size()));
    gLayers.insert(gLayers.begin() + at, l);
    recomposite(fullRect());
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeDuplicateLayer(JNIEnv*, jobject, jint srcId, jint newId,
                                                               jint index) {
    int s = findLayer(srcId);
    if (s < 0 || findLayer(newId) >= 0 || static_cast<int>(gLayers.size()) >= gMaxLayers) return JNI_FALSE;
    while (glGetError() != GL_NO_ERROR) {}
    Layer l = gLayers[s];
    l.id = newId;
    l.tex = 0;
    l.fbo = 0;
    if (!makeTex(l.tex, l.fbo, GL_NEAREST)) {
        destroyTex(l.tex, l.fbo);
        return JNI_FALSE;
    }
    glBindFramebuffer(GL_READ_FRAMEBUFFER, gLayers[s].fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, l.fbo);
    glBlitFramebuffer(0, 0, gCw, gCh, 0, 0, gCw, gCh, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    int at = std::clamp(static_cast<int>(index), 0, static_cast<int>(gLayers.size()));
    gLayers.insert(gLayers.begin() + at, l);
    recomposite(fullRect());
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeRemoveLayer(JNIEnv*, jobject, jint id) {
    if (gStroke) return;
    removeLayerInternal(id);
    recomposite(fullRect());
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeMoveLayer(JNIEnv*, jobject, jint id, jint index) {
    int from = findLayer(id);
    if (from < 0 || gStroke) return;
    Layer l = gLayers[from];
    gLayers.erase(gLayers.begin() + from);
    int at = std::clamp(static_cast<int>(index), 0, static_cast<int>(gLayers.size()));
    gLayers.insert(gLayers.begin() + at, l);
    recomposite(fullRect());
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeSetLayerProps(JNIEnv*, jobject, jint id, jboolean visible,
                                                              jfloat opacity, jint blend, jboolean alphaLock,
                                                              jboolean clip) {
    int i = findLayer(id);
    if (i < 0) return;
    Layer& l = gLayers[i];
    l.visible = visible == JNI_TRUE;
    l.opacity = std::clamp(static_cast<float>(opacity), 0.0f, 1.0f);
    l.blend = blend;
    l.alphaLock = alphaLock == JNI_TRUE;
    l.clip = clip == JNI_TRUE;
    recomposite(fullRect());
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeSetActive(JNIEnv*, jobject, jint id) {
    if (gStroke) return;
    gActiveId = id;
}

// Draws `upper` onto `lower` (using upper's opacity/blend), deletes `upper`. Not undoable.
JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeMergeDown(JNIEnv*, jobject, jint upperId, jint lowerId) {
    int u = findLayer(upperId), l = findLayer(lowerId);
    if (u < 0 || l < 0 || gStroke) return JNI_FALSE;
    if (gLayers[u].visible) {
        const Layer up = gLayers[u];
        const Layer lo = gLayers[l];
        glDisable(GL_BLEND);
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, gCw, gCh);
        glBindFramebuffer(GL_FRAMEBUFFER, gAccFbo[1]);
        glUseProgram(gLayerProg);
        bindTex(0, lo.tex);
        bindTex(1, up.tex);
        bindTex(2, gStrokeTex);
        bindTex(3, up.tex);
        setLayerUniforms(up.opacity, up.blend);
        glUniform1i(gLL.useClip, 0);
        glUniform1i(gLL.strokeOn, 0);
        glUniform1f(gLL.strokeOpacity, 1.0f);
        glUniform1i(gLL.strokeErase, 0);
        glUniform1i(gLL.strokeLock, 0);
        glBindVertexArray(gEmptyVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, gAccFbo[1]);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, lo.fbo);
        glBlitFramebuffer(0, 0, gCw, gCh, 0, 0, gCw, gCh, GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    purgeHistory(lowerId);
    removeLayerInternal(upperId);
    recomposite(fullRect());
    return JNI_TRUE;
}

// Loads premultiplied RGBA (w*h*4, row 0 = top) into a layer. Call nativeRecompositeAll afterwards.
JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeLoadLayerPixels(JNIEnv* env, jobject, jint id,
                                                                jbyteArray in) {
    int i = findLayer(id);
    if (i < 0 || gCw <= 0) return JNI_FALSE;
    const size_t need = static_cast<size_t>(gCw) * gCh * 4;
    if (static_cast<size_t>(env->GetArrayLength(in)) < need) return JNI_FALSE;
    jbyte* p = env->GetByteArrayElements(in, nullptr);
    if (!p) return JNI_FALSE;
    glBindTexture(GL_TEXTURE_2D, gLayers[i].tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, gCw, gCh, GL_RGBA, GL_UNSIGNED_BYTE, p);
    env->ReleaseByteArrayElements(in, p, JNI_ABORT);
    clearHistory(true);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeRecompositeAll(JNIEnv*, jobject) {
    recomposite(fullRect());
}

JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeReadLayerPixels(JNIEnv* env, jobject, jint id,
                                                                jbyteArray out) {
    int i = findLayer(id);
    if (i < 0 || gCw <= 0) return JNI_FALSE;
    const jsize need = gCw * gCh * 4;
    if (env->GetArrayLength(out) < need) return JNI_FALSE;
    std::vector<uint8_t> buf;
    readRect(gLayers[i].fbo, fullRect(), buf);
    env->SetByteArrayRegion(out, 0, need, reinterpret_cast<const jbyte*>(buf.data()));
    return JNI_TRUE;
}

// The flattened picture (all visible layers), premultiplied, transparent background.
JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeReadFlat(JNIEnv* env, jobject, jbyteArray out) {
    if (!gFlatFbo || gCw <= 0) return JNI_FALSE;
    const jsize need = gCw * gCh * 4;
    if (env->GetArrayLength(out) < need) return JNI_FALSE;
    std::vector<uint8_t> buf;
    readRect(gFlatFbo, fullRect(), buf);
    env->SetByteArrayRegion(out, 0, need, reinterpret_cast<const jbyte*>(buf.data()));
    return JNI_TRUE;
}

// Color of the flattened picture at (x, y) over white paper, as 0xAARRGGBB. 0 if outside the canvas.
JNIEXPORT jint JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeSamplePixel(JNIEnv*, jobject, jint x, jint y) {
    if (!gFlatFbo || x < 0 || y < 0 || x >= gCw || y >= gCh) return 0;
    uint8_t px[4] = {255, 255, 255, 255};
    glBindFramebuffer(GL_FRAMEBUFFER, gFlatFbo);
    glPixelStorei(GL_PACK_ALIGNMENT, 1);
    glReadPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, px);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    const int back = 255 - px[3];  // premultiplied over white
    const int r = std::min(255, px[0] + back), g = std::min(255, px[1] + back), b = std::min(255, px[2] + back);
    return static_cast<jint>((0xFFu << 24) | (static_cast<uint32_t>(r) << 16) | (static_cast<uint32_t>(g) << 8) |
                             static_cast<uint32_t>(b));
}

// Flood fill from (sx, sy) on the active layer. The region is decided on the flattened picture
// (sampleMerged) or on the active layer alone, grown by `expand` px to hide anti-aliased halos.
JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeFill(JNIEnv*, jobject, jint sx, jint sy, jfloat cr, jfloat cg,
                                                     jfloat cb, jfloat opacity, jfloat tolerance, jint expand,
                                                     jboolean sampleMerged) {
    if (gStroke || gCw <= 0) return JNI_FALSE;
    const int li = findLayer(gActiveId);
    if (li < 0 || sx < 0 || sy < 0 || sx >= gCw || sy >= gCh) return JNI_FALSE;
    const int w = gCw, h = gCh;

    std::vector<uint8_t> src;
    readRect(sampleMerged == JNI_TRUE ? gFlatFbo : gLayers[li].fbo, fullRect(), src);

    const int thr = std::clamp(static_cast<int>(tolerance * 255.0f + 0.5f), 0, 255);
    const size_t seedAt = (static_cast<size_t>(sy) * w + sx) * 4;
    const int s0 = src[seedAt], s1 = src[seedAt + 1], s2 = src[seedAt + 2], s3 = src[seedAt + 3];
    auto match = [&](int x, int y) {
        const uint8_t* p = &src[(static_cast<size_t>(y) * w + x) * 4];
        return std::abs(static_cast<int>(p[0]) - s0) <= thr && std::abs(static_cast<int>(p[1]) - s1) <= thr &&
               std::abs(static_cast<int>(p[2]) - s2) <= thr && std::abs(static_cast<int>(p[3]) - s3) <= thr;
    };

    std::vector<uint8_t> mask(static_cast<size_t>(w) * h, 0);
    std::vector<std::pair<int, int>> stack;
    stack.emplace_back(sx, sy);
    int minX = sx, maxX = sx, minY = sy, maxY = sy;
    while (!stack.empty()) {
        const auto [px, py] = stack.back();
        stack.pop_back();
        const size_t row = static_cast<size_t>(py) * w;
        if (mask[row + px] || !match(px, py)) continue;
        int l = px, r = px;
        while (l > 0 && !mask[row + l - 1] && match(l - 1, py)) --l;
        while (r < w - 1 && !mask[row + r + 1] && match(r + 1, py)) ++r;
        for (int i = l; i <= r; ++i) mask[row + i] = 1;
        minX = std::min(minX, l);
        maxX = std::max(maxX, r);
        minY = std::min(minY, py);
        maxY = std::max(maxY, py);
        for (int dy = -1; dy <= 1; dy += 2) {
            const int ny = py + dy;
            if (ny < 0 || ny >= h) continue;
            const size_t nrow = static_cast<size_t>(ny) * w;
            bool inSpan = false;
            for (int i = l; i <= r; ++i) {
                const bool ok = !mask[nrow + i] && match(i, ny);
                if (ok && !inSpan) {
                    stack.emplace_back(i, ny);
                    inSpan = true;
                } else if (!ok) {
                    inSpan = false;
                }
            }
        }
    }

    // Grow the region by `expand` px (4-neighbourhood) so anti-aliased edges are covered.
    for (int k = 0; k < std::clamp(static_cast<int>(expand), 0, 8); ++k) {
        std::vector<uint8_t> grown = mask;
        const int y0 = std::max(0, minY - 1), y1 = std::min(h - 1, maxY + 1);
        const int x0 = std::max(0, minX - 1), x1 = std::min(w - 1, maxX + 1);
        for (int y = y0; y <= y1; ++y) {
            for (int x = x0; x <= x1; ++x) {
                const size_t i = static_cast<size_t>(y) * w + x;
                if (mask[i]) continue;
                if ((x > 0 && mask[i - 1]) || (x < w - 1 && mask[i + 1]) || (y > 0 && mask[i - w]) ||
                    (y < h - 1 && mask[i + w])) {
                    grown[i] = 1;
                }
            }
        }
        mask.swap(grown);
        minX = x0; maxX = x1; minY = y0; maxY = y1;
    }

    const Rect r{minX, minY, maxX - minX + 1, maxY - minY + 1};
    std::vector<uint8_t> up(static_cast<size_t>(r.w) * r.h * 4, 0);
    const uint8_t cr8 = static_cast<uint8_t>(std::clamp(cr, 0.0f, 1.0f) * 255.0f + 0.5f);
    const uint8_t cg8 = static_cast<uint8_t>(std::clamp(cg, 0.0f, 1.0f) * 255.0f + 0.5f);
    const uint8_t cb8 = static_cast<uint8_t>(std::clamp(cb, 0.0f, 1.0f) * 255.0f + 0.5f);
    for (int y = 0; y < r.h; ++y) {
        const size_t mrow = static_cast<size_t>(r.y + y) * w + r.x;
        uint8_t* out = &up[static_cast<size_t>(y) * r.w * 4];
        for (int x = 0; x < r.w; ++x) {
            if (mask[mrow + x]) {
                out[x * 4] = cr8;
                out[x * 4 + 1] = cg8;
                out[x * 4 + 2] = cb8;
                out[x * 4 + 3] = 255;
            }
        }
    }
    clearStroke();
    glBindTexture(GL_TEXTURE_2D, gStrokeTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(GL_TEXTURE_2D, 0, r.x, r.y, r.w, r.h, GL_RGBA, GL_UNSIGNED_BYTE, up.data());

    const Brush saved = gBrush;
    gBrush.opacity = std::clamp(static_cast<float>(opacity), 0.0f, 1.0f);
    gBrush.erase = false;
    commitRegion(li, r);
    gBrush = saved;
    return JNI_TRUE;
}

// Small preview (tw x th, premultiplied RGBA, row 0 = top) of one layer, scaled on the GPU.
JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeLayerThumb(JNIEnv* env, jobject, jint id, jint tw, jint th,
                                                           jbyteArray out) {
    const int i = findLayer(id);
    if (i < 0 || gCw <= 0 || tw <= 0 || th <= 0) return JNI_FALSE;
    if (env->GetArrayLength(out) < tw * th * 4) return JNI_FALSE;
    GLuint t = 0, f = 0;
    const Rect tr{0, 0, tw, th};
    if (!makeRegionTex(tr, t, f)) return JNI_FALSE;
    glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_READ_FRAMEBUFFER, gLayers[i].fbo);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, f);
    glBlitFramebuffer(0, 0, gCw, gCh, 0, 0, tw, th, GL_COLOR_BUFFER_BIT, GL_LINEAR);
    std::vector<uint8_t> buf;
    readRect(f, tr, buf);
    destroyTex(t, f);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    env->SetByteArrayRegion(out, 0, tw * th * 4, reinterpret_cast<const jbyte*>(buf.data()));
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeSetBrush(JNIEnv*, jobject, jfloat size, jfloat hardness,
                                                         jfloat spacing, jfloat flow, jfloat sizeP,
                                                         jfloat flowP, jfloat r, jfloat g, jfloat b,
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
    if (!gStampProg || findLayer(gActiveId) < 0) return;
    gStroke = true;
    gStrokeLayer = gActiveId;
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
    // Stamps are drawn by nativeStrokeFlush() (once per touch event) or when the batch is full.
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeStrokeFlush(JNIEnv*, jobject) {
    if (gStroke) flushStamps();
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeStrokeEnd(JNIEnv*, jobject) {
    if (!gStroke) return;
    if (gN >= 2) walk(gDrawn, mid(gDrawn, gPts[1]), gPts[1]);
    commitStroke();
}

JNIEXPORT void JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeStrokeCancel(JNIEnv*, jobject) {
    if (!gStroke) return;
    gStampBuf.clear();
    Rect r;
    bool had = strokeRect(r);
    gStroke = false;
    clearStroke();
    if (had) recomposite(r);
}

JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeUndo(JNIEnv*, jobject) {
    if (gStroke) return JNI_FALSE;
    return applyHistory(true) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeRedo(JNIEnv*, jobject) {
    if (gStroke) return JNI_FALSE;
    return applyHistory(false) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeUndoCount(JNIEnv*, jobject) {
    return static_cast<jint>(gUndo.size());
}

JNIEXPORT jint JNICALL
Java_com_guhit_studio_engine_NativeEngine_nativeRedoCount(JNIEnv*, jobject) {
    return static_cast<jint>(gRedo.size());
}

}  // extern "C"
