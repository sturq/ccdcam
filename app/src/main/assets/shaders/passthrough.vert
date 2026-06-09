attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTexCoord;
uniform mat4 uTexMatrix;
uniform float uStretch;        // <1.0 zooms in horizontally (anamorphic CCD squish)
uniform float uRotationRad;    // applied to gl_Position; 0 for display, ±π/2 for landscape encoder

void main() {
    float c = cos(uRotationRad), s = sin(uRotationRad);
    vec2 p = aPosition.xy;
    gl_Position = vec4(c * p.x - s * p.y, s * p.x + c * p.y, 0.0, 1.0);
    vec2 base = (uTexMatrix * aTexCoord).xy;
    vTexCoord = vec2(0.5 + (base.x - 0.5) * uStretch, base.y);
}
