#version 450
// Vulkan renderer fragment shader. Samples the bound 2D image at the
// interpolated vTexCoord. CRT/AA/sharpen effects live in the GL renderer
// today and are intentionally out of scope here.

layout(set = 0, binding = 0) uniform sampler2D uTexture;
layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vTexCoord);
}
