#extension GL_OES_EGL_image_external : require
precision highp float;

varying vec2 vScreenUv;
uniform samplerExternalOES sTexture;
uniform mat4 uTexMatrix;
uniform vec2 uResolution;
uniform float uTime;
uniform float uDisplayAspect; // surface w / h
uniform float uContentAspect; // displayed content w / h (post-rotation)
uniform float uRotationDeg;   // 0/90/180/270 — rotation applied before texMatrix
uniform float uMirror;        // 1.0 to mirror horizontally (front camera)

// constants kept in sync with tools/sim.py
const float LINES = 480.0;
const float SMEAR_THRESHOLD = 0.88;
const float SMEAR_STRENGTH = 1.6;
const int   SMEAR_SAMPLES = 8;
const float SMEAR_RANGE = 0.45;
const float FLARE_THRESHOLD = 0.93;
const float FLARE_RANGE = 0.15;
const int   FLARE_SAMPLES = 5;
const float FLARE_STRENGTH = 0.55;
const float CHROMA_NOISE_AMP = 0.07;
const float LUMA_GRAIN_AMP = 0.05;
const float BLACK_LIFT = 0.06;
const vec3  WARM_GRADE = vec3(1.07, 1.02, 0.94);
const float DESAT = 0.9;
const float SCANLINE_AMP = 0.025;
const float VIGNETTE_STRENGTH = 0.45;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec2 toTex(vec2 uv) {
    vec2 c = uv - 0.5;
    float a = radians(uRotationDeg);
    float ca = cos(a), sa = sin(a);
    vec2 r = vec2(ca * c.x - sa * c.y, sa * c.x + ca * c.y) + 0.5;
    if (uMirror > 0.5) r.x = 1.0 - r.x;
    return (uTexMatrix * vec4(r, 0.0, 1.0)).xy;
}

vec3 sampleCam(vec2 uv) {
    return texture2D(sTexture, toTex(clamp(uv, vec2(0.0), vec2(1.0)))).rgb;
}

float brightMask(vec2 uv, float threshold) {
    float l = luma(sampleCam(uv));
    return smoothstep(threshold, 1.0, l);
}

void main() {
    // letterbox in screen space: map screen UV -> content UV preserving aspect
    vec2 screenUv = vScreenUv;
    vec2 contentUv;
    if (uDisplayAspect < uContentAspect) {
        float frac = uDisplayAspect / uContentAspect;
        float off = (1.0 - frac) * 0.5;
        float my = (screenUv.y - off) / frac;
        if (my < 0.0 || my > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        contentUv = vec2(screenUv.x, my);
    } else {
        float frac = uContentAspect / uDisplayAspect;
        float off = (1.0 - frac) * 0.5;
        float mx = (screenUv.x - off) / frac;
        if (mx < 0.0 || mx > 1.0) { gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
        contentUv = vec2(mx, screenUv.y);
    }
    vec2 uv = contentUv;

    // 1. base sample
    vec3 col = sampleCam(uv);

    // 2. vertical CCD smear
    float smear = 0.0;
    for (int i = 1; i <= SMEAR_SAMPLES; i++) {
        float t = float(i) / float(SMEAR_SAMPLES);
        float dy = t * SMEAR_RANGE;
        float mu = brightMask(vec2(uv.x, uv.y - dy), SMEAR_THRESHOLD);
        float md = brightMask(vec2(uv.x, uv.y + dy), SMEAR_THRESHOLD);
        smear += max(mu, md) * (1.0 - t);
    }
    smear = smear / float(SMEAR_SAMPLES) * SMEAR_STRENGTH;
    col += vec3(smear * 0.9, smear * 0.95, smear * 1.1);

    // 3. horizontal flare on extreme highlights
    float flare = 0.0;
    for (int i = 1; i <= FLARE_SAMPLES; i++) {
        float t = float(i) / float(FLARE_SAMPLES);
        float dx = t * FLARE_RANGE;
        float ml = brightMask(vec2(uv.x - dx, uv.y), FLARE_THRESHOLD);
        float mr = brightMask(vec2(uv.x + dx, uv.y), FLARE_THRESHOLD);
        flare += max(ml, mr) * (1.0 - t) * (1.0 - t);
    }
    flare = flare / float(FLARE_SAMPLES) * FLARE_STRENGTH;
    col += vec3(flare, flare, flare * 1.05);

    // 4. chroma noise on R/B
    float n = (hash(uv * uResolution + uTime) - 0.5) * CHROMA_NOISE_AMP;
    col.r += n * 0.6;
    col.b -= n * 0.6;

    // 5. luma grain
    float g = (hash(uv * uResolution * 2.0 + uTime * 1.3) - 0.5) * LUMA_GRAIN_AMP;
    col += g;

    // 6. color grade
    col = col * (1.0 - BLACK_LIFT) + BLACK_LIFT;
    col *= WARM_GRADE;
    float l = luma(col);
    col = mix(vec3(l), col, DESAT);

    // 7. scanline
    float scan = (1.0 - SCANLINE_AMP) + SCANLINE_AMP * sin(uv.y * LINES * 3.14159);
    col *= scan;

    // 8. vignette
    vec2 q = uv - 0.5;
    col *= 1.0 - dot(q, q) * VIGNETTE_STRENGTH;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
