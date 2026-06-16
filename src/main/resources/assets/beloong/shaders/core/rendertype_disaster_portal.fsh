#version 150

uniform sampler2D Sampler0;

in vec2 uv;

out vec4 fragColor;

void main() {
    vec4 base = texture(Sampler0, uv);
    fragColor = vec4(base.rgb * base.a, 1.0);
}
