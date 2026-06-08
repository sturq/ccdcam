attribute vec4 aPosition;
attribute vec4 aTexCoord;
varying vec2 vTexCoord;
// CameraX's SurfaceTexture transform matrix would force aspect-correct cropping;
// we WANT the source frame stretched into the portrait viewport for the CCD look,
// so we ignore uTexMatrix and just do an explicit Y-flip (texture origin top-left
// vs GL bottom-left).
void main() {
    gl_Position = aPosition;
    vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
}
