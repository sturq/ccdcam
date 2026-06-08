attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTexCoord;
uniform mat4 uTexMatrix;
uniform float uStretch; // <1.0 zooms in vertically (taller, more squished feel)

void main() {
    gl_Position = aPosition;
    vec2 base = (uTexMatrix * aTexCoord).xy;
    vTexCoord = vec2(base.x, 0.5 + (base.y - 0.5) * uStretch);
}
