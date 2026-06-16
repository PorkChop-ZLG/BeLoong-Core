#version 150

#moj_import <matrix.glsl>

uniform sampler2D Sampler0;

uniform float GameTime;

in vec4 texProj0;

out vec4 fragColor;

void main() {
    mat4 move = mat4(
        1.0, 0.0, 0.0, 0.0,
        0.0, 1.0, 0.0, GameTime * 5.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    mat4 zoom = mat4(
        4.0, 0.0, 0.0, 0.0,
        0.0, 4.0, 0.0, 0.0,
        0.0, 0.0, 1.0, 0.0,
        0.0, 0.0, 0.0, 1.0
    );

    vec4 base = textureProj(Sampler0, texProj0 * move * zoom);
    fragColor = vec4(base.rgb * base.a, 1.0);
}
