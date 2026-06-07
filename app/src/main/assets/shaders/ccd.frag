#extension GL_OES_EGL_image_external : require
precision highp float;

varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;
uniform float uStretch;

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

float brightMask(vec2 uv, float threshold) {
    vec2 cl = clamp(uv, vec2(0.001), vec2(0.999));
    float l = luma(texture2D(sTexture, cl).rgb);
    return smoothstep(threshold, 1.0, l);
}

void main() {
    // apply user stretch: >1 zoom in horizontally, <1 widen field of view
    vec2 uv = vec2((vTexCoord.x - 0.5) / max(uStretch, 0.1) + 0.5, vTexCoord.y);
    uv = clamp(uv, vec2(0.0), vec2(1.0));

    // 1. base sample (no quantize — let the GPU bilinear handle it; quantize was causing precision issues)
    vec3 col = texture2D(sTexture, uv).rgb;

    // 2. vertical CCD smear: small number of samples up & down, only blown-highlight pixels contribute
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
