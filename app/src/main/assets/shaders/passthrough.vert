attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vScreenUv;
void main() {
    gl_Position = aPosition;
    vScreenUv = aTexCoord.xy;
}
