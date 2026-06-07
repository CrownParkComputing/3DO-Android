#version 450
// Vulkan renderer fragment shader. Samples the bound 2D image at the
// interpolated vTexCoord. CRT/AA/sharpen effects live in the GL renderer
// today and are intentionally out of scope here.

layout(set = 0, binding = 0) uniform sampler2D uTexture;
layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

layout(push_constant) uniform PushConstants {
    uint flags;
    uint rotation;
    float crtStrength;
    float texelWidth;
    float texelHeight;
} pc;

void main() {
    vec3 baseColor = texture(uTexture, vTexCoord).rgb;

    if ((pc.flags & 4u) != 0u) { // CRT enabled
        // Simple scanlines
        float pos = vTexCoord.y / pc.texelHeight;
        float scanline = 0.5 + 0.5 * sin(pos * 6.283185 * 1.0);
        scanline = mix(1.0, scanline, 0.4 * pc.crtStrength);

        // Phosphor mask (RGB triad)
        float mask_pos = vTexCoord.x / pc.texelWidth * 3.0;
        vec3 mask = vec3(1.0);
        float m = fract(mask_pos);
        if (m < 0.33) mask = vec3(1.0, 0.6, 0.6);
        else if (m < 0.66) mask = vec3(0.6, 1.0, 0.6);
        else mask = vec3(0.6, 0.6, 1.0);
        mask = mix(vec3(1.0), mask, 0.3 * pc.crtStrength);

        // Vignette
        vec2 dist = abs(vTexCoord - 0.5);
        float vignette = clamp(1.0 - dot(dist, dist) * 1.5, 0.0, 1.0);

        fragColor = vec4(baseColor * scanline * mask * vignette, 1.0);
    } else {
        fragColor = vec4(baseColor, 1.0);
    }
}
