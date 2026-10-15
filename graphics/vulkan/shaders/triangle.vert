// SPDX-License-Identifier: MIT
#version 450
layout(push_constant) uniform Parameters { float angle; float aspect; float preRotation; } parameters;
layout(location=0) out vec3 color;
void main() {
    vec2 positions[3]=vec2[](vec2(0,-0.65),vec2(0.6,0.5),vec2(-0.6,0.5));
    vec3 colors[3]=vec3[](vec3(1,0.1,0.1),vec3(0.1,1,0.1),vec3(0.1,0.1,1));
    float c=cos(parameters.angle),s=sin(parameters.angle);
    vec2 p=mat2(c,s,-s,c)*positions[gl_VertexIndex];
    vec2 clip=vec2(p.x/parameters.aspect,p.y);
    float pc=cos(parameters.preRotation),ps=sin(parameters.preRotation);
    gl_Position=vec4(mat2(pc,ps,-ps,pc)*clip,0,1);color=colors[gl_VertexIndex];
}
