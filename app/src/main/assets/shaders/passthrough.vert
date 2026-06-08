attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTexCoord;
uniform mat4 uTexMatrix;

// CameraX gives us an aspect-correct rotated UV via uTexMatrix. That's geometrically
// right but kills the vertical-Insta-Hi8 stretch we want. Zoom in on the middle 72%
// of the rotated sample range vertically so the visible portion gets stretched to
// fill the full portrait viewport.
const float STRETCH = 0.72;

void main() {
    gl_Position = aPosition;
    vec2 base = (uTexMatrix * aTexCoord).xy;
    vTexCoord = vec2(base.x, 0.5 + (base.y - 0.5) * STRETCH);
}
