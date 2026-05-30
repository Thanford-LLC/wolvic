/* -*- Mode: C++; tab-width: 20; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "VRVideo.h"
#include "DeviceDelegate.h"
#include "VRLayer.h"
#include "VRLayerNode.h"
#include "vrb/ConcreteClass.h"
#include "vrb/Color.h"
#include "vrb/CreationContext.h"
#include "vrb/Geometry.h"
#include "vrb/Matrix.h"
#include "vrb/ModelLoaderAndroid.h"
#include "vrb/Program.h"
#include "vrb/ProgramFactory.h"
#include "vrb/RenderState.h"
#include "vrb/RenderContext.h"
#include "vrb/TextureGL.h"
#include "vrb/TextureSurface.h"
#include "vrb/Toggle.h"
#include "vrb/Transform.h"
#include "vrb/VertexArray.h"

#include "Quad.h"
#include "Widget.h"
#include "vrb/Logger.h"

// EAC blit shaders: sample GL_TEXTURE_EXTERNAL_OES (video) → GL_TEXTURE_CUBE_MAP (6 faces).
// Fragment applies EAC forward mapping: linear face coord s → atan(s)/π + 0.5, then
// maps into the 3×2 face layout via uFaceOffset / (3, 2).
static const char* kEACBlitVert = R"glsl(
attribute vec2 aPosition;
varying vec2 vTexCoord;
void main() {
    vTexCoord = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)glsl";

static const char* kEACBlitFrag = R"glsl(
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uVideo;
uniform vec2 uFaceOffset;
varying vec2 vTexCoord;
const float kPI = 3.14159265358979;
void main() {
    vec2 st = vTexCoord * 2.0 - 1.0;
    float u_face = atan(st.x) / kPI + 0.5;
    float v_face = atan(st.y) / kPI + 0.5;
    vec2 uv = (uFaceOffset + vec2(u_face, v_face)) / vec2(3.0, 2.0);
    gl_FragColor = texture2D(uVideo, uv);
}
)glsl";

static const GLfloat kEACQuadVerts[] = { -1.0f, -1.0f,  1.0f, -1.0f,  -1.0f, 1.0f,  1.0f, 1.0f };

// (col, row) offsets in the 3×2 EAC layout for GL face indices 0-5
// (+X=0, -X=1, +Y=2, -Y=3, +Z=4, -Z=5)
static const float kEACFaceOffsets[6][2] = {
  { 0.0f, 0.0f }, { 0.0f, 1.0f },  // +X, -X
  { 1.0f, 0.0f }, { 1.0f, 1.0f },  // +Y, -Y
  { 2.0f, 0.0f }, { 2.0f, 1.0f },  // +Z, -Z
};

static GLuint CompileEACShader(GLenum type, const char* source) {
  GLuint s = glCreateShader(type);
  glShaderSource(s, 1, &source, nullptr);
  glCompileShader(s);
  GLint ok = 0;
  glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
  if (!ok) { glDeleteShader(s); return 0; }
  return s;
}

namespace crow {

struct VRVideo::State {
  vrb::CreationContextWeak context;
  std::weak_ptr<DeviceDelegate> deviceWeak;
  WidgetPtr window;
  VRVideoProjection projection;
  vrb::TransformPtr root;
  vrb::TogglePtr leftEye;
  vrb::TogglePtr rightEye;
  VRLayerPtr layer;
  device::EyeRect layerTextureBackup[2];
  bool mUseSameLayerForBothEyesBackup;
  float mWorldWidthBackup;
  float mWorlHeightBackup;
  // Milestone 2b: EAC per-frame blit state (VIDEO_PROJECTION_CUBEMAP + cube layer only)
  VRLayerCubePtr cubemapLayer;
  GLuint mEACFBO          = 0;
  GLuint mEACBlitProg     = 0;
  GLuint mEACQuadVBO      = 0;
  GLint  mEACPositionLoc  = -1;
  GLint  mEACFaceOffsetLoc = -1;
  GLint  mEACVideoTexLoc  = -1;

  State()
    : mWorldWidthBackup(0)
    , mWorlHeightBackup(0)
    , mUseSameLayerForBothEyesBackup(true)
  {
  }

  void Initialize(const WidgetPtr& aWindow, const VRVideoProjection aProjection) {
    vrb::CreationContextPtr create = context.lock();
    window = aWindow;
    projection = aProjection;
    root = vrb::Transform::Create(create);
    VRLayerSurfacePtr windowLayer = aWindow->GetLayer();
    if (windowLayer) {
      layerTextureBackup[0] = windowLayer->GetTextureRect(device::Eye::Left);
      layerTextureBackup[1] = windowLayer->GetTextureRect(device::Eye::Right);
      mWorldWidthBackup = windowLayer->GetWorldWidth();
      mWorlHeightBackup = windowLayer->GetWorldHeight();
      updateProjectionLayer();
    } else {
      updateProjection();
    }
    root->AddNode(leftEye);
    if (rightEye) {
      root->AddNode(rightEye);
    }
  }

  void updateProjection() {
    switch (projection) {
      case VRVideoProjection::VIDEO_PROJECTION_3D_SIDE_BY_SIDE:
        leftEye = createQuadProjection(device::EyeRect(0.0f, 0.0f, 0.5f, 1.0f));
        rightEye = createQuadProjection(device::EyeRect(0.5f, 0.0f, 0.5f, 1.0f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_3D_TOP_BOTTOM:
        leftEye = createQuadProjection(device::EyeRect(0.0f, 0.0f, 1.0f, 0.5f));
        rightEye = createQuadProjection(device::EyeRect(0.0f, 0.5f, 1.0f, 0.5f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_360:
        leftEye = createSphereProjection(false, device::EyeRect(0.0f, 0.0f, 1.0f, 1.0f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_360_STEREO:
        leftEye = createSphereProjection(false, device::EyeRect(0.0f, 0.5f, 1.0f, 0.5f));
        rightEye = createSphereProjection(false, device::EyeRect(0.0f, 0.0f, 1.0f, 0.5f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_180:
        leftEye = createSphereProjection(true, device::EyeRect(0.0f, 0.0f, 1.0f, 1.0f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_180_STEREO_LEFT_RIGHT:
        leftEye = createSphereProjection(true, device::EyeRect(0.0f, 0.0f, 0.5f, 1.0f));
        rightEye = createSphereProjection(true, device::EyeRect(0.5f, 0.0f, 0.5f, 1.0f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_180_STEREO_TOP_BOTTOM:
        leftEye = createSphereProjection(true, device::EyeRect(0.0f, 0.5f, 1.0f, 0.5f));
        rightEye = createSphereProjection(true, device::EyeRect(0.0f, 0.0f, 1.0f, 0.5f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_CUBEMAP:
        leftEye = createEACProjection();
        break;
      case VRVideoProjection::VIDEO_PROJECTION_MESH:
        // No mesh distortion data at this layer; render as equirectangular 360 fallback.
        leftEye = createSphereProjection(false, device::EyeRect(0.0f, 0.0f, 1.0f, 1.0f));
        break;
    }
  }

  void updateProjectionLayer() {
    switch (projection) {
      case VRVideoProjection::VIDEO_PROJECTION_3D_SIDE_BY_SIDE:
        leftEye = createQuadProjectionLayer(device::EyeRect(0.0f, 0.0f, 0.5f, 1.0f), device::EyeRect(0.5f, 0.0f, 0.5f, 1.0f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_3D_TOP_BOTTOM:
        leftEye = createQuadProjectionLayer(device::EyeRect(0.0f, 0.0f, 1.0f, 0.5f), device::EyeRect(0.0f, 0.5f, 1.0f, 0.5f));
        break;
      case VRVideoProjection::VIDEO_PROJECTION_360:
        create360ProjectionLayer();
        break;
      case VRVideoProjection::VIDEO_PROJECTION_360_STEREO:
        create360StereoProjectionLayer();
        break;
      case VRVideoProjection::VIDEO_PROJECTION_180:
        create180ProjectionLayer();
        break;
      case VRVideoProjection::VIDEO_PROJECTION_180_STEREO_LEFT_RIGHT:
        create180LRProjectionLayer();
        break;
      case VRVideoProjection::VIDEO_PROJECTION_180_STEREO_TOP_BOTTOM:
        create180TBProjectionLayer();
        break;
      case VRVideoProjection::VIDEO_PROJECTION_CUBEMAP:
        createCubemapEACProjectionLayer();
        break;
      case VRVideoProjection::VIDEO_PROJECTION_MESH:
        create360ProjectionLayer();
        break;
    }
  }

  vrb::TogglePtr createSphereProjection(bool half, device::EyeRect aUVRect) {
    const int kCols = 70;
    const int kRows = 70;
    const float kRadius = 10.0f;

    vrb::CreationContextPtr create = context.lock();
    vrb::VertexArrayPtr array = vrb::VertexArray::Create(create);

    for (float row = 0; row <= kRows; row+= 1.0f) {
      const float alpha = row * (float)M_PI / kRows;
      const float sinAlpha = sinf(alpha);
      const float cosAlpha = cosf(alpha);

      for (float col = 0; col <= kCols; col++) {
        const float beta = col * (half ? 1.0f : 2.0f) * (float)M_PI / kCols;
        const float sinBeta = sinf(beta);
        const float cosBeta = cosf(beta);

        vrb::Vector vertex;
        vrb::Vector uv;
        vrb::Vector normal;
        normal.x() = cosBeta * sinAlpha;
        normal.y() = cosAlpha;
        normal.z() = sinBeta * sinAlpha;
        uv.x() = aUVRect.mX + (col / kCols) * aUVRect.mWidth;  // u
        uv.y() = aUVRect.mY + (row / kRows) * aUVRect.mHeight; // v
        vertex.x() = kRadius * normal.x();
        vertex.y() = kRadius * normal.y();
        vertex.z() = kRadius * normal.z();

        array->AppendVertex(vertex);
        array->AppendUV(uv);
        array->AppendNormal(vertex.Normalize());
      }
    }

    std::vector<int> indices;

    vrb::ProgramPtr program = create->GetProgramFactory()->CreateProgram(create, vrb::FeatureSurfaceTexture | vrb::FeatureHighPrecision);
    vrb::RenderStatePtr state = vrb::RenderState::Create(create);
    state->SetProgram(program);
    state->SetLightsEnabled(false);
    vrb::TexturePtr texture = std::dynamic_pointer_cast<vrb::Texture>(window->GetSurfaceTexture());
    state->SetTexture(texture);
    vrb::GeometryPtr geometry = vrb::Geometry::Create(create);
    geometry->SetVertexArray(array);
    geometry->SetRenderState(state);

    for (int row = 0; row < kRows; row++) {
      for (int col = 0; col < kCols; col++) {
        int first = 1 + (row * (kCols + 1)) + col;
        int second = first + kCols + 1;

        indices.clear();
        indices.push_back(first);
        indices.push_back(second);
        indices.push_back(first + 1);

        indices.push_back(second);
        indices.push_back(second + 1);
        indices.push_back(first + 1);
        geometry->AddFace(indices, indices, indices);
      }
    }

    vrb::TransformPtr transform = vrb::Transform::Create(create);
    if (half) {
      vrb::Matrix matrix = vrb::Matrix::Rotation(vrb::Vector(0.0f, 1.0f, 0.0f), (float) M_PI);
      transform->SetTransform(matrix);
    } else {
      transform->SetTransform(vrb::Matrix::Rotation(vrb::Vector(0.0f, 1.0f, 0.0f), (float) M_PI * -0.5f));
    }
    transform->AddNode(geometry);

    vrb::TogglePtr result = vrb::Toggle::Create(create);
    result->AddNode(transform);
    return result;
  }

  // Equi-angular cubemap (EAC) sphere mesh.
  //
  // The 3×2 face layout used here (YouTube / cbmp compact):
  //   Row 0: +X (col 0) | +Y (col 1) | +Z (col 2)
  //   Row 1: -X (col 0) | -Y (col 1) | -Z (col 2)
  //
  // Face s,t projections follow the OpenGL cubemap spec (s = sc/|ma|, t = tc/|ma|):
  //   +X: sc=-rz, tc=-ry   -X: sc=+rz, tc=-ry
  //   +Y: sc=+rx, tc=+rz   -Y: sc=+rx, tc=-rz
  //   +Z: sc=+rx, tc=-ry   -Z: sc=-rx, tc=-ry
  //
  // EAC correction (equi-angular atan remapping):
  //   u_face = (1/π)·atan(s) + 0.5   v_face = (1/π)·atan(t) + 0.5   both ∈ [0,1]
  //
  vrb::TogglePtr createEACProjection() {
    // Face layout constants (GL face order: +X=0, -X=1, +Y=2, -Y=3, +Z=4, -Z=5).
    // 3×2 compact layout: row 0 = positive faces, row 1 = negative; cols 0/1/2 = X/Y/Z.
    static const float kEACFaceCol[6] = { 0.0f, 0.0f, 1.0f, 1.0f, 2.0f, 2.0f };
    static const float kEACFaceRow[6] = { 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f };
    const int kCols = 70;
    const int kRows = 70;
    const float kRadius = 10.0f;

    vrb::CreationContextPtr create = context.lock();
    vrb::VertexArrayPtr array = vrb::VertexArray::Create(create);

    for (int row = 0; row <= kRows; row++) {
      const float alpha = row * (float)M_PI / kRows;
      const float sinAlpha = sinf(alpha);
      const float cosAlpha = cosf(alpha);

      for (int col = 0; col <= kCols; col++) {
        const float beta = col * 2.0f * (float)M_PI / kCols;
        const float sinBeta = sinf(beta);
        const float cosBeta = cosf(beta);

        // Sphere vertex direction (object space, before the -π/2 Y rotation).
        const float nx = cosBeta * sinAlpha;
        const float ny = cosAlpha;
        const float nz = sinBeta * sinAlpha;

        // Find dominant axis and compute OpenGL-spec face (s, t) ∈ [-1, 1].
        const float ax = fabsf(nx), ay = fabsf(ny), az = fabsf(nz);
        int face;
        float s, t;
        if (ax >= ay && ax >= az) {
          if (nx > 0.0f) { face = 0; s = -nz / nx;     t = -ny / nx; }      // +X
          else           { face = 1; s =  nz / (-nx);  t = -ny / (-nx); }   // -X
        } else if (ay >= ax && ay >= az) {
          if (ny > 0.0f) { face = 2; s =  nx / ny;     t =  nz / ny; }      // +Y
          else           { face = 3; s =  nx / (-ny);  t = -nz / (-ny); }   // -Y
        } else {
          if (nz > 0.0f) { face = 4; s =  nx / nz;     t = -ny / nz; }      // +Z
          else           { face = 5; s = -nx / (-nz);  t = -ny / (-nz); }   // -Z
        }

        // EAC: equi-angular atan remapping → [0, 1] within the face.
        const float u_face = atanf(s) / (float)M_PI + 0.5f;
        const float v_face = atanf(t) / (float)M_PI + 0.5f;

        // Map to global video UV using the 3×2 face layout.
        const float u = (kEACFaceCol[face] + u_face) / 3.0f;
        const float v = (kEACFaceRow[face] + v_face) / 2.0f;

        array->AppendVertex(vrb::Vector(kRadius * nx, kRadius * ny, kRadius * nz));
        array->AppendUV(vrb::Vector(u, v, 0.0f));
        array->AppendNormal(vrb::Vector(nx, ny, nz));
      }
    }

    vrb::ProgramPtr program = create->GetProgramFactory()->CreateProgram(
        create, vrb::FeatureSurfaceTexture | vrb::FeatureHighPrecision);
    vrb::RenderStatePtr state = vrb::RenderState::Create(create);
    state->SetProgram(program);
    state->SetLightsEnabled(false);
    vrb::TexturePtr texture = std::dynamic_pointer_cast<vrb::Texture>(window->GetSurfaceTexture());
    state->SetTexture(texture);
    vrb::GeometryPtr geometry = vrb::Geometry::Create(create);
    geometry->SetVertexArray(array);
    geometry->SetRenderState(state);

    std::vector<int> indices;
    for (int row = 0; row < kRows; row++) {
      for (int col = 0; col < kCols; col++) {
        int first = 1 + (row * (kCols + 1)) + col;
        int second = first + kCols + 1;

        indices.clear();
        indices.push_back(first);
        indices.push_back(second);
        indices.push_back(first + 1);
        indices.push_back(second);
        indices.push_back(second + 1);
        indices.push_back(first + 1);
        geometry->AddFace(indices, indices, indices);
      }
    }

    vrb::TransformPtr transform = vrb::Transform::Create(create);
    transform->SetTransform(vrb::Matrix::Rotation(vrb::Vector(0.0f, 1.0f, 0.0f), (float)M_PI * -0.5f));
    transform->AddNode(geometry);

    vrb::TogglePtr result = vrb::Toggle::Create(create);
    result->AddNode(transform);
    return result;
  }

  // --- Milestone 2b: EAC native cube layer blit ---

  bool InitEACBlit() {
    GLuint vert = CompileEACShader(GL_VERTEX_SHADER, kEACBlitVert);
    if (!vert) { VRB_ERROR("EAC blit: vertex shader compile failed"); return false; }
    GLuint frag = CompileEACShader(GL_FRAGMENT_SHADER, kEACBlitFrag);
    if (!frag) { glDeleteShader(vert); VRB_ERROR("EAC blit: fragment shader compile failed"); return false; }
    mEACBlitProg = glCreateProgram();
    glAttachShader(mEACBlitProg, vert);
    glAttachShader(mEACBlitProg, frag);
    glLinkProgram(mEACBlitProg);
    glDeleteShader(vert);
    glDeleteShader(frag);
    GLint ok = 0;
    glGetProgramiv(mEACBlitProg, GL_LINK_STATUS, &ok);
    if (!ok) {
      VRB_ERROR("EAC blit: program link failed");
      glDeleteProgram(mEACBlitProg);
      mEACBlitProg = 0;
      return false;
    }
    mEACPositionLoc   = glGetAttribLocation(mEACBlitProg, "aPosition");
    mEACFaceOffsetLoc = glGetUniformLocation(mEACBlitProg, "uFaceOffset");
    mEACVideoTexLoc   = glGetUniformLocation(mEACBlitProg, "uVideo");

    glGenFramebuffers(1, &mEACFBO);
    glGenBuffers(1, &mEACQuadVBO);
    glBindBuffer(GL_ARRAY_BUFFER, mEACQuadVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kEACQuadVerts), kEACQuadVerts, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return true;
  }

  void DrawEACBlit_Internal() {
    if (!cubemapLayer) return;
    GLuint cubeTexture = cubemapLayer->GetTextureHandle();
    if (!cubeTexture) return;

    vrb::TexturePtr videoTex = std::dynamic_pointer_cast<vrb::Texture>(window->GetSurfaceTexture());
    if (!videoTex) return;
    GLuint videoHandle = videoTex->GetHandle();
    if (!videoHandle) return;

    if (!mEACBlitProg && !InitEACBlit()) return;

    const int32_t faceSize = cubemapLayer->GetWidth();

    // Save critical GL state
    GLint savedFBO = 0, savedProg = 0, savedVBO = 0;
    GLint savedVP[4];
    GLboolean depthWasEnabled  = glIsEnabled(GL_DEPTH_TEST);
    GLboolean blendWasEnabled  = glIsEnabled(GL_BLEND);
    glGetIntegerv(GL_FRAMEBUFFER_BINDING, &savedFBO);
    glGetIntegerv(GL_CURRENT_PROGRAM,     &savedProg);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &savedVBO);
    glGetIntegerv(GL_VIEWPORT,             savedVP);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glUseProgram(mEACBlitProg);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoHandle);
    glUniform1i(mEACVideoTexLoc, 0);

    glBindBuffer(GL_ARRAY_BUFFER, mEACQuadVBO);
    glEnableVertexAttribArray(mEACPositionLoc);
    glVertexAttribPointer(mEACPositionLoc, 2, GL_FLOAT, GL_FALSE, 0, nullptr);

    glBindFramebuffer(GL_FRAMEBUFFER, mEACFBO);
    glViewport(0, 0, faceSize, faceSize);

    for (int face = 0; face < 6; ++face) {
      glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                             GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, cubeTexture, 0);
      glUniform2f(mEACFaceOffsetLoc, kEACFaceOffsets[face][0], kEACFaceOffsets[face][1]);
      glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    }

    // Restore GL state
    glDisableVertexAttribArray(mEACPositionLoc);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, savedFBO);
    glBindBuffer(GL_ARRAY_BUFFER, savedVBO);
    glUseProgram(savedProg);
    glViewport(savedVP[0], savedVP[1], savedVP[2], savedVP[3]);
    if (depthWasEnabled) glEnable(GL_DEPTH_TEST);
    if (blendWasEnabled) glEnable(GL_BLEND);

    // Signal the cube layer is ready for compositor submission this frame.
    cubemapLayer->SetLoaded(true);
    cubemapLayer->RequestDraw();
  }

  void CleanupEACBlit() {
    if (mEACFBO)      { glDeleteFramebuffers(1, &mEACFBO);  mEACFBO = 0; }
    if (mEACQuadVBO)  { glDeleteBuffers(1, &mEACQuadVBO);   mEACQuadVBO = 0; }
    if (mEACBlitProg) { glDeleteProgram(mEACBlitProg);       mEACBlitProg = 0; }
    cubemapLayer = nullptr;
  }

  void createCubemapEACProjectionLayer() {
    vrb::CreationContextPtr create = context.lock();
    DeviceDelegatePtr device = deviceWeak.lock();
    if (!device) { leftEye = createEACProjection(); return; }

    VRLayerSurfacePtr windowLayer = window->GetLayer();
    int32_t faceSize = 1024;
    if (windowLayer) {
      faceSize = std::min(windowLayer->GetWidth() / 3, windowLayer->GetHeight() / 2);
      if (faceSize <= 0) faceSize = 1024;
    }

    cubemapLayer = device->CreateLayerCube(faceSize, faceSize, GL_RGBA8);
    if (!cubemapLayer) {
      // Layers disabled — fall back to sphere mesh.
      VRB_DEBUG("GW_VR EAC: CreateLayerCube returned null (layers disabled) -> sphere-mesh fallback");
      leftEye = createEACProjection();
      return;
    }
    VRB_DEBUG("GW_VR EAC: native cube layer created faceSize=%d", faceSize);
    layer = cubemapLayer;

    leftEye = vrb::Toggle::Create(create);
    leftEye->AddNode(VRLayerNode::Create(create, cubemapLayer));
  }

  void create360ProjectionLayer() {
    vrb::CreationContextPtr create = context.lock();
    DeviceDelegatePtr device = deviceWeak.lock();
    VRLayerEquirectPtr equirect = device->CreateLayerEquirect(window->GetLayer());
    layer = equirect;

    leftEye = vrb::Toggle::Create(create);
    leftEye->AddNode(VRLayerNode::Create(create, equirect));
  }

  void create360StereoProjectionLayer() {
    vrb::CreationContextPtr create = context.lock();
    DeviceDelegatePtr device = deviceWeak.lock();
    VRLayerEquirectPtr equirect = device->CreateLayerEquirect(window->GetLayer());
    layer = equirect;

    vrb::Matrix leftTransform = vrb::Matrix::Identity();
    leftTransform.ScaleInPlace(vrb::Vector(1.0f, 0.5f, 1.0f));
    equirect->SetUVTransform(device::Eye::Left, leftTransform);

    vrb::Matrix rightTransform =  vrb::Matrix::Position(vrb::Vector(0.0f, 0.5f, 0.0f));
    rightTransform.ScaleInPlace(vrb::Vector(1.0f, 0.5f, 1.0f));
    equirect->SetUVTransform(device::Eye::Right, rightTransform);

    leftEye = vrb::Toggle::Create(create);
    leftEye->AddNode(VRLayerNode::Create(create, equirect));
    rightEye = vrb::Toggle::Create(create);
    rightEye->AddNode(VRLayerNode::Create(create, equirect));
  }

  vrb::TogglePtr create180LayerToggle(const VRLayerEquirectPtr& aLayer) {
    vrb::CreationContextPtr create = context.lock();
    vrb::TogglePtr result = vrb::Toggle::Create(create);
    vrb::Matrix rotation = vrb::Matrix::Rotation(vrb::Vector(0.0f, 1.0f, 0.0f), -(float)M_PI * 0.5f);
    vrb::TransformPtr transform = vrb::Transform::Create(create);
    transform->AddNode(VRLayerNode::Create(create, aLayer));
    transform->SetTransform(rotation);
    result->AddNode(transform);
    return result;
  }

  void create180ProjectionLayer() {
    vrb::CreationContextPtr create = context.lock();
    DeviceDelegatePtr device = deviceWeak.lock();
    VRLayerEquirectPtr equirect = device->CreateLayerEquirect(window->GetLayer());
    layer = equirect;

    vrb::Matrix uvTransform = vrb::Matrix::Identity();
    uvTransform.ScaleInPlace(vrb::Vector(2.0f, 1.0f, 1.0f));

    equirect->SetUVTransform(device::Eye::Left, uvTransform);
    equirect->SetUVTransform(device::Eye::Right, uvTransform);

    leftEye = create180LayerToggle(equirect);
  }

#ifdef OPENXR
  void create180LRProjectionLayer() {
    vrb::CreationContextPtr create = context.lock();
    DeviceDelegatePtr device = deviceWeak.lock();
    VRLayerEquirectPtr equirect = device->CreateLayerEquirect(window->GetLayer());
    layer = equirect;

    equirect->SetTextureRect(device::Eye::Left, device::EyeRect(0.0f, 0.0f, 0.5f, 1.0f));
    equirect->SetTextureRect(device::Eye::Right, device::EyeRect(0.5f, 0.0f, 0.5f, 1.0f));
    auto UVtransform = vrb::Matrix::Identity().Scale(vrb::Vector(2.0f, 1.0f, 1.0f)).Translate(vrb::Vector(-0.5, 0.0, 0.0));
    equirect->SetUVTransform(device::Eye::Left, UVtransform);
    equirect->SetUVTransform(device::Eye::Right, UVtransform);
    equirect->SetUseSameLayerForBothEyes(false);

    leftEye = create180LayerToggle(equirect);
  }
#else
  void create180LRProjectionLayer() {
    vrb::CreationContextPtr create = context.lock();
    DeviceDelegatePtr device = deviceWeak.lock();
    VRLayerEquirectPtr equirect = device->CreateLayerEquirect(window->GetLayer());
    layer = equirect;

    equirect->SetTextureRect(device::Eye::Left, device::EyeRect(0.0f, 0.0f, 0.5f, 1.0f));
    equirect->SetTextureRect(device::Eye::Right, device::EyeRect(0.5f, 0.0f, 0.5f, 1.0f));
    equirect->SetUVTransform(device::Eye::Right, vrb::Matrix::Position(vrb::Vector(0.5f, 0.0f, 0.0f)));

    leftEye = create180LayerToggle(equirect);
    rightEye = create180LayerToggle(equirect);
  }
#endif

  void create180TBProjectionLayer() {
    vrb::CreationContextPtr create = context.lock();
    DeviceDelegatePtr device = deviceWeak.lock();
    VRLayerEquirectPtr equirect = device->CreateLayerEquirect(window->GetLayer());
    layer = equirect;

    equirect->SetTextureRect(device::Eye::Right, device::EyeRect(0.0f, 0.5f, 1.0f, 0.5f));
    equirect->SetTextureRect(device::Eye::Left, device::EyeRect(0.0f, 0.0f, 1.0f, 0.5f));

    vrb::Matrix uvTransform = vrb::Matrix::Identity();
    uvTransform.ScaleInPlace(vrb::Vector(2.0f, 0.5f, 1.0f));
    equirect->SetUVTransform(device::Eye::Left, uvTransform);
    uvTransform.TranslateInPlace(vrb::Vector(0.0f, 0.5f, 0.0f));
    equirect->SetUVTransform(device::Eye::Right, uvTransform);

    leftEye = create180LayerToggle(equirect);
    rightEye = create180LayerToggle(equirect);
  }

  vrb::TogglePtr createQuadProjectionLayer(const device::EyeRect& aLeftUVRect, const device::EyeRect& aRightUVRect) {
    vrb::CreationContextPtr create = context.lock();
    VRLayerSurfacePtr windowLayer = window->GetLayer();
    windowLayer->SetTextureRect(device::Eye::Left, aLeftUVRect);
    windowLayer->SetTextureRect(device::Eye::Right, aRightUVRect);
    mUseSameLayerForBothEyesBackup = windowLayer->GetUseSameLayerForBothEyes();
    windowLayer->SetUseSameLayerForBothEyes(false);
    layer = windowLayer;

    vrb::TransformPtr transform = vrb::Transform::Create(create);
    transform->SetTransform(window->GetTransform());
    transform->AddNode(VRLayerNode::Create(create, layer));
    vrb::TogglePtr result = vrb::Toggle::Create(create);
    result->AddNode(transform);

    return result;
  }

  vrb::TogglePtr createQuadProjection(device::EyeRect aUVRect) {
    vrb::CreationContextPtr create = context.lock();
    vrb::Vector min, max;
    window->GetWidgetMinAndMax(min, max);
    vrb::GeometryPtr geometry = Quad::CreateGeometry(create, min, max, aUVRect);
    vrb::ProgramPtr program = create->GetProgramFactory()->CreateProgram(create, vrb::FeatureSurfaceTexture);
    vrb::RenderStatePtr state = vrb::RenderState::Create(create);
    state->SetProgram(program);
    state->SetLightsEnabled(false);
    vrb::TexturePtr texture = std::dynamic_pointer_cast<vrb::Texture>(window->GetSurfaceTexture());
    state->SetTexture(texture);
    geometry->SetRenderState(state);

    vrb::TransformPtr transform = vrb::Transform::Create(create);
    transform->SetTransform(window->GetTransform());
    transform->AddNode(geometry);
    vrb::TogglePtr result = vrb::Toggle::Create(create);
    result->AddNode(transform);
    return result;
  }
};

void
VRVideo::SelectEye(device::Eye aEye) {
  if (m.layer) {
    m.layer->SetCurrentEye(aEye);
  }

  if (m.leftEye) {
    m.leftEye->ToggleAll(aEye == device::Eye::Left || !m.rightEye);
  }

  if (m.rightEye) {
    m.rightEye->ToggleAll(aEye == device::Eye::Right);
  }
}

vrb::NodePtr
VRVideo::GetRoot() const {
  return m.root;
}

void
VRVideo::Exit() {
  m.CleanupEACBlit();
  VRLayerSurfacePtr windowLayer = m.window->GetLayer();
  if (windowLayer) {
    windowLayer->SetTextureRect(device::Eye::Left, m.layerTextureBackup[0]);
    windowLayer->SetTextureRect(device::Eye::Right, m.layerTextureBackup[1]);
    windowLayer->SetWorldSize(m.mWorldWidthBackup, m.mWorlHeightBackup);
    windowLayer->SetUseSameLayerForBothEyes(m.mUseSameLayerForBothEyesBackup);
  }
  if (m.layer && m.layer != windowLayer) {
    DeviceDelegatePtr device = m.deviceWeak.lock();
    device->DeleteLayer(m.layer);
  }
}

void
VRVideo::DrawEACBlit() {
  m.DrawEACBlit_Internal();
}

void
VRVideo::SetReorientTransform(const vrb::Matrix& transform) {
  m.root->SetTransform(transform);
}

VRVideoPtr
VRVideo::Create(vrb::CreationContextPtr aContext,
                const WidgetPtr& aWindow,
                const VRVideoProjection aProjection,
                const DeviceDelegatePtr& aDevice) {
  VRVideoPtr result = std::make_shared<vrb::ConcreteClass<VRVideo, VRVideo::State> >(aContext);
  result->m.deviceWeak = aDevice;
  result->m.Initialize(aWindow, aProjection);
  return result;
}


VRVideo::VRVideo(State& aState, vrb::CreationContextPtr& aContext) : m(aState) {
  m.context = aContext;
}

} // namespace crow
