#extension GL_OES_EGL_image_external : require
precision highp float;

varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;

// constants kept in sync with tools/sim.py
// CCD source resolution emulation (Hi8 was ~720x480, MiniDV ~720x480)
const float CCD_X = 540.0;
const float CCD_Y = 480.0;
const float LINES = 480.0;
const float SMEAR_THRESHOLD = 0.70;
const float SMEAR_STRENGTH = 2.0;
const int   SMEAR_SAMPLES = 8;
const float SMEAR_RANGE = 0.50;
const float FLARE_THRESHOLD = 0.78;
const float FLARE_RANGE = 0.18;
const int   FLARE_SAMPLES = 5;
const float FLARE_STRENGTH = 0.65;
const float CHROMA_NOISE_AMP = 0.09;
const float LUMA_GRAIN_AMP = 0.06;
const float BLACK_LIFT = 0.06;
const vec3  WARM_GRADE = vec3(1.08, 1.01, 0.92);
const float DESAT = 0.88;
const float SCANLINE_AMP = 0.06;
const float VIGNETTE_STRENGTH = 0.75;
const float CHROMA_SHIFT = 0.0025;  // horizontal R/B shift for color bleed

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

// quantize the sample UV to a coarse CCD grid (~720x480 cell centers).
// the GPU's bilinear filter inside each cell gives the bilinear downsample look,
// and sampling at the cell center gives the chunky nearest-upsample feel afterwards.
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

    // 1. base sample with chroma shift (NTSC chroma bleed): R lags, B leads horizontally
    float r = texture2D(sTexture, ccdGrid(baseUv + vec2( CHROMA_SHIFT, 0.0))).r;
    float g = texture2D(sTexture, uv).g;
    float b = texture2D(sTexture, ccdGrid(baseUv + vec2(-CHROMA_SHIFT, 0.0))).b;
    vec3 col = vec3(r, g, b);

    // 2. vertical CCD smear
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

    // 3. horizontal flare on highlights
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

    // 4. chroma noise on R/B
    float n = (hash(uv * uResolution + uTime) - 0.5) * CHROMA_NOISE_AMP;
    col.r += n * 0.6;
    col.b -= n * 0.6;

    // 5. luma grain
    float gr = (hash(uv * uResolution * 2.0 + uTime * 1.3) - 0.5) * LUMA_GRAIN_AMP;
    col += gr;

    // 6. color grade
    col = col * (1.0 - BLACK_LIFT) + BLACK_LIFT;
    col *= WARM_GRADE;
    float l = luma(col);
    col = mix(vec3(l), col, DESAT);

    // 7. scanlines tied to vertical CCD line count for visible structure
    float scan = (1.0 - SCANLINE_AMP) + SCANLINE_AMP * sin(uv.y * LINES * 3.14159);
    col *= scan;

    // 8. vignette
    vec2 q = uv - 0.5;
    col *= 1.0 - dot(q, q) * VIGNETTE_STRENGTH;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
