#extension GL_OES_EGL_image_external : require
#extension GL_OES_standard_derivatives : enable
precision highp float;

varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;

// NVCam look. Same file name as the CCDCam shader so the nv flavor's asset overrides it.
// constants kept in sync with tools/nv_sim.py
// Image-intensifier tube, kept subtle: P43 green phosphor, lifted blacks, soft detail,
// small halos around lights, fine scintillation grain, gentle barrel bulge and vignette.
// Deliberately no hard round tube edge, scanlines or reticle.
const float BARREL = 0.08;           // lens bulge, 0 = flat
const float SOFT = 0.0016;           // blur tap spacing, fraction of the short side
const float GAIN = 1.30;             // light amplification before the curve
const float GAMMA = 0.75;            // <1 lifts shadows
const float HALO_THRESHOLD = 0.80;
const float HALO_RADIUS = 0.018;     // fraction of the short side
const float HALO_STRENGTH = 0.35;
const float GRAIN_AMP = 0.10;
const float SPARKLE_RATE = 0.0015;   // share of 2x2 px cells that flash per frame
const float SPARKLE_AMP = 0.30;
const float VIGNETTE_STRENGTH = 0.55;
const float SATURATION = 0.40;       // 1 = full P43 green, 0 = gray
const vec3  PHOSPHOR_LOW  = vec3(0.02, 0.07, 0.02);
const vec3  PHOSPHOR_MID  = vec3(0.34, 0.74, 0.16);
const vec3  PHOSPHOR_HIGH = vec3(0.85, 1.00, 0.60);

float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

float tap(vec2 uv) { return luma(texture2D(sTexture, uv).rgb); }

void main() {
    // One screen pixel in texture space. The texture can be rotated against the output,
    // so pixel offsets go through the derivatives instead of 1/uResolution.
    vec2 ex = dFdx(vTexCoord);
    vec2 ey = dFdy(vTexCoord);
    float shortSide = min(uResolution.x, uResolution.y);

    // Barrel bulge, circular on screen, corners stay put.
    vec2 s = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;
    float rc2 = 0.25 * (uResolution.x / uResolution.y) * (uResolution.x / uResolution.y) + 0.25;
    float k = (1.0 + BARREL * dot(s, s)) / (1.0 + BARREL * rc2);
    vec2 uv = 0.5 + (vTexCoord - 0.5) * k;

    // 3x3 gaussian (1 2 1 / 2 4 2 / 1 2 1), taps under 2 px apart so edges blur instead of doubling
    vec2 a = ex * SOFT * shortSide;
    vec2 b = ey * SOFT * shortSide;
    float l = (4.0 * tap(uv)
            + 2.0 * (tap(uv + a) + tap(uv - a) + tap(uv + b) + tap(uv - b))
            + tap(uv + a + b) + tap(uv + a - b) + tap(uv - a + b) + tap(uv - a - b)) / 16.0;

    float hr = HALO_RADIUS * shortSide;
    float halo = 0.0;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        vec2 d = ex * cos(a) + ey * sin(a);
        halo += 0.5 * smoothstep(HALO_THRESHOLD, 1.0, tap(uv + d * hr));
        halo += smoothstep(HALO_THRESHOLD, 1.0, tap(uv + d * hr * 0.5));
    }
    l += halo / 12.0 * HALO_STRENGTH;

    l = pow(clamp(l * GAIN, 0.0, 1.0), GAMMA);

    vec2 px = uv * uResolution;
    l += (hash(floor(px * 0.5) + uTime) - 0.5) * GRAIN_AMP * (1.0 - 0.5 * l);
    l += step(1.0 - SPARKLE_RATE, hash(floor(px * 0.5) + uTime * 1.3)) * SPARKLE_AMP;
    l = clamp(l, 0.0, 1.0);

    vec3 col = mix(mix(PHOSPHOR_LOW, PHOSPHOR_MID, clamp(l * 2.0, 0.0, 1.0)),
                   PHOSPHOR_HIGH, clamp(l * 2.0 - 1.0, 0.0, 1.0));
    col = mix(vec3(luma(col)), col, SATURATION);

    vec2 q = vTexCoord - 0.5;
    col *= 1.0 - dot(q, q) * VIGNETTE_STRENGTH;

    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
