#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;

// constants kept in sync with tools/sim.py
const float LINES = 480.0;
const float SMEAR_THRESHOLD = 0.92;
const float SMEAR_STRENGTH = 1.4;
const int   SMEAR_SAMPLES = 20;
const float SMEAR_RANGE = 0.45;
const float FLARE_THRESHOLD = 0.95;
const float FLARE_RANGE = 0.18;
const int   FLARE_SAMPLES = 16;
const float FLARE_STRENGTH = 0.5;
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

vec3 sampleTex(vec2 uv) { return texture2D(sTexture, uv).rgb; }
float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// returns 0..1 mask of "this pixel is a true blown highlight surrounded by other bright pixels"
float clusterMask(vec2 uv, vec2 px, float threshold) {
    float c  = luma(sampleTex(uv));
    float u  = luma(sampleTex(uv + vec2(0.0, -px.y)));
    float d  = luma(sampleTex(uv + vec2(0.0,  px.y)));
    float l  = luma(sampleTex(uv + vec2(-px.x, 0.0)));
    float r  = luma(sampleTex(uv + vec2( px.x, 0.0)));
    float m = min(min(min(min(c, u), d), l), r);
    return smoothstep(threshold, 1.0, m);
}

void main() {
    vec2 uv = vTexCoord;

    // 1. resolution quantize
    vec2 pix = vec2(LINES * uResolution.x / uResolution.y, LINES);
    vec2 uvQ = floor(uv * pix) / pix;
    vec2 onePx = 1.0 / uResolution;

    vec3 col = sampleTex(uvQ);

    // 2. vertical CCD smear (point-source only, cluster filtered)
    float smear = 0.0;
    for (int i = 1; i <= SMEAR_SAMPLES; i++) {
        float t = float(i) / float(SMEAR_SAMPLES);
        float dy = t * SMEAR_RANGE;
        vec2 up   = clamp(vec2(uvQ.x, uvQ.y - dy), vec2(0.0), vec2(1.0));
        vec2 down = clamp(vec2(uvQ.x, uvQ.y + dy), vec2(0.0), vec2(1.0));
        float mu = clusterMask(up,   onePx, SMEAR_THRESHOLD);
        float md = clusterMask(down, onePx, SMEAR_THRESHOLD);
        float falloff = (1.0 - t);
        smear += max(mu, md) * falloff;
    }
    smear = smear / float(SMEAR_SAMPLES) * SMEAR_STRENGTH;
    col += vec3(smear * 0.9, smear * 0.95, smear * 1.1);

    // 3. horizontal flare (extreme highlights only)
    float flare = 0.0;
    for (int i = 1; i <= FLARE_SAMPLES; i++) {
        float t = float(i) / float(FLARE_SAMPLES);
        float dx = t * FLARE_RANGE;
        vec2 left  = clamp(vec2(uvQ.x - dx, uvQ.y), vec2(0.0), vec2(1.0));
        vec2 right = clamp(vec2(uvQ.x + dx, uvQ.y), vec2(0.0), vec2(1.0));
        float ml = clusterMask(left,  onePx, FLARE_THRESHOLD);
        float mr = clusterMask(right, onePx, FLARE_THRESHOLD);
        float falloff = (1.0 - t) * (1.0 - t);
        flare += max(ml, mr) * falloff;
    }
    flare = flare / float(FLARE_SAMPLES) * FLARE_STRENGTH;
    col += vec3(flare, flare, flare * 1.05);

    // 4. chroma noise on R/B
    float n = (hash(uvQ * uResolution + uTime) - 0.5) * CHROMA_NOISE_AMP;
    col.r += n * 0.6;
    col.b -= n * 0.6;

    // 5. luma grain
    float g = (hash(uvQ * uResolution * 2.0 + uTime * 1.3) - 0.5) * LUMA_GRAIN_AMP;
    col += g;

    // 6. color grade: lift blacks, warm tint, desat
    col = col * (1.0 - BLACK_LIFT) + BLACK_LIFT;
    col *= WARM_GRADE;
    float l = luma(col);
    col = mix(vec3(l), col, DESAT);

    // 7. scanline
    float scan = (1.0 - SCANLINE_AMP) + SCANLINE_AMP * sin(uvQ.y * LINES * 3.14159);
    col *= scan;

    // 8. vignette
    vec2 q = uv - 0.5;
    col *= 1.0 - dot(q, q) * VIGNETTE_STRENGTH;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
