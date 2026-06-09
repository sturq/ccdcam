#extension GL_OES_EGL_image_external : require
precision highp float;

varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;

// constants kept in sync with tools/sim.py
// Subtler Sony-Handycam aesthetic — visible CCD character but the underlying scene is
// still readable. Hi8/MiniDV had a softer look than full-on retro filters.
const float CCD_X = 960.0;
const float CCD_Y = 720.0;
const float LINES = 480.0;
const float SMEAR_THRESHOLD = 0.86;
const float SMEAR_STRENGTH = 1.3;
const int   SMEAR_SAMPLES = 8;
const float SMEAR_RANGE = 0.38;
const float FLARE_THRESHOLD = 0.90;
const float FLARE_RANGE = 0.14;
const int   FLARE_SAMPLES = 5;
const float FLARE_STRENGTH = 0.40;
const float CHROMA_NOISE_AMP = 0.020;
const float LUMA_GRAIN_AMP = 0.035;
const float BLACK_LIFT = 0.05;
const vec3  WARM_GRADE = vec3(1.06, 1.02, 0.95);
const float DESAT = 0.93;
const float SCANLINE_AMP = 0.02;
const float VIGNETTE_STRENGTH = 0.35;
const float CHROMA_SHIFT = 0.0003;

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec2 ccdGrid(vec2 uv) {
    vec2 cell = vec2(CCD_X, CCD_Y);
    return (floor(uv * cell) + 0.5) / cell;
}

float brightMask(vec2 uv, float threshold) {
    vec2 cl = clamp(ccdGrid(uv), vec2(0.001), vec2(0.999));
    float l = luma(texture2D(sTexture, cl).rgb);
    return smoothstep(threshold, 1.0, l);
}

void main() {
    vec2 baseUv = vTexCoord;
    vec2 uv = ccdGrid(baseUv);

    float r = texture2D(sTexture, ccdGrid(baseUv + vec2( CHROMA_SHIFT, 0.0))).r;
    float g = texture2D(sTexture, uv).g;
    float b = texture2D(sTexture, ccdGrid(baseUv + vec2(-CHROMA_SHIFT, 0.0))).b;
    vec3 col = vec3(r, g, b);

    float smear = 0.0;
    for (int i = 1; i <= SMEAR_SAMPLES; i++) {
        float t = float(i) / float(SMEAR_SAMPLES);
        float dy = t * SMEAR_RANGE;
        float mu = brightMask(vec2(baseUv.x, baseUv.y - dy), SMEAR_THRESHOLD);
        float md = brightMask(vec2(baseUv.x, baseUv.y + dy), SMEAR_THRESHOLD);
        smear += max(mu, md) * (1.0 - t);
    }
    smear = smear / float(SMEAR_SAMPLES) * SMEAR_STRENGTH;
    col += vec3(smear * 0.9, smear * 0.95, smear * 1.1);

    float flare = 0.0;
    for (int i = 1; i <= FLARE_SAMPLES; i++) {
        float t = float(i) / float(FLARE_SAMPLES);
        float dx = t * FLARE_RANGE;
        float ml = brightMask(vec2(baseUv.x - dx, baseUv.y), FLARE_THRESHOLD);
        float mr = brightMask(vec2(baseUv.x + dx, baseUv.y), FLARE_THRESHOLD);
        flare += max(ml, mr) * (1.0 - t) * (1.0 - t);
    }
    flare = flare / float(FLARE_SAMPLES) * FLARE_STRENGTH;
    col += vec3(flare, flare, flare * 1.05);

    float n = (hash(uv * uResolution + uTime) - 0.5) * CHROMA_NOISE_AMP;
    col.r += n * 0.6;
    col.b -= n * 0.6;

    float gr = (hash(uv * uResolution * 2.0 + uTime * 1.3) - 0.5) * LUMA_GRAIN_AMP;
    col += gr;

    col = col * (1.0 - BLACK_LIFT) + BLACK_LIFT;
    col *= WARM_GRADE;
    float l = luma(col);
    col = mix(vec3(l), col, DESAT);

    float scan = (1.0 - SCANLINE_AMP) + SCANLINE_AMP * sin(uv.y * LINES * 3.14159);
    col *= scan;

    vec2 q = uv - 0.5;
    col *= 1.0 - dot(q, q) * VIGNETTE_STRENGTH;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
