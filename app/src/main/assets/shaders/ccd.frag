#extension GL_OES_EGL_image_external : require
precision mediump float;

varying vec2 vTexCoord;
uniform samplerExternalOES sTexture;
uniform vec2 uResolution;
uniform float uTime;

// hash-based noise
float hash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

vec3 sampleTex(vec2 uv) {
    return texture2D(sTexture, uv).rgb;
}

// luma weight
float luma(vec3 c) {
    return dot(c, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec2 uv = vTexCoord;

    // --- 1. Resolution downsample (camcorder-ish ~480 lines) ---
    float lines = 480.0;
    vec2 pix = vec2(lines * uResolution.x / uResolution.y, lines);
    vec2 uvQuant = floor(uv * pix) / pix;

    // base sample
    vec3 col = sampleTex(uvQuant);

    // --- 2. CCD vertical smear ---
    // For each pixel, look ABOVE (and below) along the column for bright highlights
    // and add a vertical streak when found.
    float smear = 0.0;
    const int SAMPLES = 24;
    for (int i = 1; i <= SAMPLES; i++) {
        float t = float(i) / float(SAMPLES);
        float dy = t * 0.5; // search half the screen vertically
        vec2 up   = vec2(uvQuant.x, uvQuant.y - dy);
        vec2 down = vec2(uvQuant.x, uvQuant.y + dy);
        float lu = luma(sampleTex(clamp(up,   vec2(0.0), vec2(1.0))));
        float ld = luma(sampleTex(clamp(down, vec2(0.0), vec2(1.0))));
        // only very bright pixels contribute, falloff with distance
        float contribUp   = smoothstep(0.85, 1.0, lu) * (1.0 - t);
        float contribDown = smoothstep(0.85, 1.0, ld) * (1.0 - t);
        smear += max(contribUp, contribDown);
    }
    smear = smear / float(SAMPLES) * 3.5;
    col += vec3(smear * 0.9, smear * 0.95, smear * 1.1); // slight cyan tint to smear

    // --- 3. Chroma noise (per-frame jitter on U/V-ish axis) ---
    float n = (hash(uvQuant * uResolution + uTime) - 0.5) * 0.08;
    col.r += n * 0.6;
    col.b -= n * 0.6;

    // --- 4. Luma grain ---
    float g = (hash(uvQuant * uResolution * 2.0 + uTime * 1.3) - 0.5) * 0.06;
    col += g;

    // --- 5. Color grade: lift blacks, warm tint, slight desat ---
    col = col * 0.92 + 0.04;                          // lift blacks
    col *= vec3(1.08, 1.02, 0.93);                    // warm
    float l = luma(col);
    col = mix(vec3(l), col, 0.88);                    // desaturate slightly

    // --- 6. Soft horizontal scanline ---
    float scan = 0.96 + 0.04 * sin(uvQuant.y * lines * 3.14159);
    col *= scan;

    // --- 7. Vignette ---
    vec2 q = uv - 0.5;
    float vig = 1.0 - dot(q, q) * 0.6;
    col *= vig;

    // clamp
    col = clamp(col, 0.0, 1.0);
    gl_FragColor = vec4(col, 1.0);
}
