#version 450
// Vulkan renderer: full-screen triangle-strip quad, rotated/flipped in the
// vertex shader. The 4-vertex positions are derived from gl_VertexIndex so
// there is no VBO. Per-draw state is delivered as a push constant (see
// vulkan_renderer.cpp).

layout(push_constant) uniform PushConstants {
    uint flags;      // bit 0=flipX, bit 1=flipY, bit 2=crtEnabled, bit 3=aaEnabled
    uint rotation;   // 0=0 deg, 1=90 CW, 2=180, 3=270 CW
    float crtStrength;
    float texelWidth;
    float texelHeight;
} pc;

layout(location = 0) out vec2 vTexCoord;

void main() {
    // gl_VertexIndex in [0..3] for a triangle-strip quad.
    //   0 -> top-left,    1 -> top-right
    //   2 -> bottom-left, 3 -> bottom-right
    //
    // NDC layout matches the standard NDC convention: (-1,-1) BL, (+1,-1) BR.
    vec2 ndc = vec2(
        (gl_VertexIndex == 1 || gl_VertexIndex == 3) ? 1.0 : -1.0,
        (gl_VertexIndex == 0 || gl_VertexIndex == 1) ? 1.0 : -1.0
    );

    // Texture coordinate convention used by the GL renderer's vertex
    // buffer (gl_renderer.cpp, initBuffers): the top-left of the screen
    // corresponds to uv(0,0), the bottom-left to uv(0,1). The comment
    // there notes "origin at top-left for RGB565" -- meaning the texture
    // is interpreted as if uv(0,0) is the top-left of the visible image.
    //
    // In OpenGL ES, uv(0,1) is the bottom of the texture (bottom-left
    // origin), and the GL renderer's `g_flip_y = true` default flips v
    // in the shader so that the top of the screen samples the *top* of
    // the visible content. In Vulkan, uv(0,0) is the top of the texture
    // (top-left origin), which is the opposite convention.
    //
    // To produce the same screen output in both renderers for the same
    // value of the `flags.flipY` bit, this shader does the OPPOSITE
    // thing from the GL shader: it inverts the v axis by default so
    // that, with `flipY=true`, the top of the screen samples the
    // *bottom* of the texture in Vulkan (which, due to Vulkan's
    // top-left origin, is the BOTTOM of the visible content) -- no
    // wait, that's still wrong. Let me think again.
    //
    // Empirically: in this project, the framebuffer data is laid out
    // top-row first in memory (the 3DO emulator's first scanline is at
    // the lowest address). When uploaded to a texture, the data
    // occupies rows 0..239 of the texture object. In GL (bottom-left
    // origin) that means the visible top is at uv.v = 1. In Vulkan
    // (top-left origin) the visible top is at uv.v = 0.
    //
    // The GL renderer's "natural" v is inverted to match this:
    // vertex 0 (screen TL) raw uv = (0, 0); after `flipY=true` it
    // becomes (0, 1) which (in GL) is the top of the visible content.
    //
    // For the Vulkan shader to produce the same screen output with
    // `flipY=true`, vertex 0 (screen TL) should output uv(0, 0) which
    // (in Vulkan) is the top of the visible content. The "natural"
    // (no user flip) v in this shader is therefore (0, 1) at vertex 0
    // (the BOTTOM of the visible content in Vulkan), and `flipY=true`
    // flips it to (0, 0) which is the correct top.
    // The "natural" v (with no user flip) at vertex 0 (screen top-left)
    // is 1.0. With `flipY=true` (the default in unified_renderer.cpp)
    // the shader flips v to 0.0, which in Vulkan's top-left-origin
    // texture convention is the top of the visible content. This makes
    // the Vulkan shader produce the same screen output as the GL
    // renderer for the same value of `flags.flipY`.
    vec2 uv = vec2(
        (gl_VertexIndex == 1 || gl_VertexIndex == 3) ? 1.0 : 0.0,
        (gl_VertexIndex == 0 || gl_VertexIndex == 1) ? 1.0 : 0.0
    );

    // Flip in the texture's local UV space (matches GL renderer's
    // uFlipX/uFlipY semantics, applied before rotation so flip and
    // rotation compose in the way users expect).
    if ((pc.flags & 1u) != 0u) uv.x = 1.0 - uv.x;
    if ((pc.flags & 2u) != 0u) uv.y = 1.0 - uv.y;

    // Rotate the framebuffer on screen. The mapping is the "where on the
    // texture does this SCREEN position sample" question, expressed in
    // terms of the screen's NDC coordinates.
    //
    // The mapping below gives the FRAMEBUFFER-RELATIVE UVs (the same
    // coordinate system the framebuffer is uploaded in: Vulkan's
    // top-left origin, u=0 is left, v=0 is top, v=1 is bottom). The
    // flipY bit above has already been applied to `uv`, so (u=0, v=0)
    // is the top-left of the framebuffer's visible content.
    //
    // We rotate the texture sample, NOT the NDC quad. The 4:3 viewport
    // letterbox is applied AFTER this shader to the un-rotated quad.
    // For each rotation we want:
    //   rot=1 (90° CW):   top row -> screen's RIGHT, left col -> screen's TOP
    //                    u = (1 - ndc.y) / 2,  v = (1 - ndc.x) / 2
    //   rot=2 (180°):     top row -> screen's BOTTOM, left col -> screen's RIGHT
    //                    u = (1 - ndc.x) / 2,  v = (1 - ndc.y) / 2
    //   rot=3 (270° CW):  top row -> screen's LEFT, left col -> screen's BOTTOM
    //                    u = (1 + ndc.y) / 2,  v = (1 + ndc.x) / 2
    vec2 rotUv = uv;
    if (pc.rotation == 1u) {
        // 90° CW: framebuffer's top row lands on the screen's right edge,
        // framebuffer's left column lands on the screen's top edge.
        //   u = (1 - ndc.y) / 2    (ndc.y=+1 screen-top -> u=0 left; ndc.y=-1 -> u=1 right)
        //   v = (1 - ndc.x) / 2    (ndc.x=+1 screen-right -> v=0 top; ndc.x=-1 -> v=1 bottom)
        rotUv = vec2((1.0 - ndc.y) * 0.5, (1.0 - ndc.x) * 0.5);
    } else if (pc.rotation == 2u) {
        // 180°: framebuffer's top row -> screen's bottom, framebuffer's left
        // column -> screen's right.
        rotUv = vec2((1.0 - ndc.x) * 0.5, (1.0 - ndc.y) * 0.5);
    } else if (pc.rotation == 3u) {
        // 270° CW (90° CCW): framebuffer's top row lands on the screen's left
        // edge, framebuffer's left column lands on the screen's bottom edge.
        //   u = (1 + ndc.y) / 2    (ndc.y=-1 screen-bottom -> u=0 left; ndc.y=+1 -> u=1 right)
        //   v = (1 + ndc.x) / 2    (ndc.x=-1 screen-left -> v=0 top; ndc.x=+1 -> v=1 bottom)
        rotUv = vec2((1.0 + ndc.y) * 0.5, (1.0 + ndc.x) * 0.5);
    }

    gl_Position = vec4(ndc, 0.0, 1.0);
    vTexCoord   = rotUv;
}
