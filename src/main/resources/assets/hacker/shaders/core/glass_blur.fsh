#version 150

uniform sampler2D Sampler0;
uniform vec2 TexelSize;
uniform float BlurRadius;
uniform vec4 ColorModulator;

in vec2 texCoord;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    vec2 stepSize = TexelSize * BlurRadius;

    vec4 color = texture(Sampler0, texCoord) * 0.18;
    color += texture(Sampler0, texCoord + vec2(-stepSize.x, -stepSize.y)) * 0.08;
    color += texture(Sampler0, texCoord + vec2(0.0, -stepSize.y)) * 0.11;
    color += texture(Sampler0, texCoord + vec2(stepSize.x, -stepSize.y)) * 0.08;
    color += texture(Sampler0, texCoord + vec2(-stepSize.x, 0.0)) * 0.11;
    color += texture(Sampler0, texCoord + vec2(stepSize.x, 0.0)) * 0.11;
    color += texture(Sampler0, texCoord + vec2(-stepSize.x, stepSize.y)) * 0.08;
    color += texture(Sampler0, texCoord + vec2(0.0, stepSize.y)) * 0.11;
    color += texture(Sampler0, texCoord + vec2(stepSize.x, stepSize.y)) * 0.08;

    color.rgb = mix(color.rgb, vec3(1.0, 1.0, 1.0), 0.18);
    color.rgb = mix(color.rgb, vec3(0.82, 0.93, 1.0), 0.05);
    fragColor = vec4(color.rgb, 0.58) * vertexColor * ColorModulator;
}
