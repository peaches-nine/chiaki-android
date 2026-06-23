#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;

uniform samplerExternalOES inputTexture;
uniform vec2 inputTextureSize;
uniform vec2 outputTextureSize;
uniform float sharpness; // 0.0-1.0

in vec2 aTexCoords;
out vec4 fragColor;

// 4-tap Lanczos-like sampling with edge-aware blending
highp vec4 NISSample(samplerExternalOES tex, vec2 uv, vec2 texelSize) {
    vec2 coord = uv * inputTextureSize - 0.5;
    vec2 f = fract(coord);
    coord -= f;

    // Lanczos-2 weights for sub-pixel offset
    vec4 w;
    w.x = (0.5 + f.x * f.x * (-2.0 + f.x)) * 0.5;
    w.y = (0.5 + (1.0-f.x) * (1.0-f.x) * (-2.0 + (1.0-f.x))) * 0.5;
    w.z = (0.5 + f.y * f.y * (-2.0 + f.y)) * 0.5;
    w.w = (0.5 + (1.0-f.y) * (1.0-f.y) * (-2.0 + (1.0-f.y))) * 0.5;

    vec2 t0 = (coord) * texelSize;
    vec2 t1 = (coord + vec2(1.0, 0.0)) * texelSize;
    vec2 t2 = (coord + vec2(0.0, 1.0)) * texelSize;
    vec2 t3 = (coord + vec2(1.0, 1.0)) * texelSize;

    highp vec4 s0 = texture(tex, t0);
    highp vec4 s1 = texture(tex, t1);
    highp vec4 s2 = texture(tex, t2);
    highp vec4 s3 = texture(tex, t3);

    return (s0 * w.x * w.z + s1 * w.y * w.z + s2 * w.x * w.w + s3 * w.y * w.w)
           / (w.x*w.z + w.y*w.z + w.x*w.w + w.y*w.w);
}

// Simplified CAS: contrast-adaptive sharpening
highp vec4 CASFilter(samplerExternalOES tex, vec2 uv, vec2 texelSize, float amount) {
    highp vec3 a = NISSample(tex, uv + vec2(-texelSize.x, texelSize.y), texelSize).rgb;
    highp vec3 b = NISSample(tex, uv + vec2(0.0, texelSize.y), texelSize).rgb;
    highp vec3 c = NISSample(tex, uv + vec2(texelSize.x, texelSize.y), texelSize).rgb;
    highp vec3 d = NISSample(tex, uv + vec2(-texelSize.x, 0.0), texelSize).rgb;
    highp vec3 e = NISSample(tex, uv, texelSize).rgb;
    highp vec3 f = NISSample(tex, uv + vec2(texelSize.x, 0.0), texelSize).rgb;
    highp vec3 g = NISSample(tex, uv + vec2(-texelSize.x, -texelSize.y), texelSize).rgb;
    highp vec3 h = NISSample(tex, uv + vec2(0.0, -texelSize.y), texelSize).rgb;
    highp vec3 i = NISSample(tex, uv + vec2(texelSize.x, -texelSize.y), texelSize).rgb;

    vec3 mn = min(min(min(a,b),min(c,d)),min(min(e,f),min(g,h)));
    vec3 mx = max(max(max(a,b),max(c,d)),max(max(e,f),max(g,h)));
    vec3 amp = min(vec3(amount), 1.0 / (mx - mn + 0.001));

    vec3 filter = e + amp * (
        0.25 * (b + d + f + h - 4.0 * e) +
        0.125 * (a + c + g + i - 4.0 * e)
    );

    return vec4(clamp(mix(e, clamp(filter, mn, mx), amount), 0.0, 1.0), 1.0);
}

void main() {
    vec2 texelSize = 1.0 / inputTextureSize;

    if (sharpness > 0.01) {
        fragColor = CASFilter(inputTexture, aTexCoords, texelSize, sharpness * 0.5);
    } else {
        fragColor = NISSample(inputTexture, aTexCoords, texelSize);
    }
}
