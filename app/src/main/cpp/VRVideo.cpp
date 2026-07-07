/* -*- Mode: C++; tab-width: 20; indent-tabs-mode: nil; c-basic-offset: 2 -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "VRVideo.h"
#include "DeviceDelegate.h"
#include "VRLayer.h"
#include "VRLayerNode.h"
#include "vrb/Camera.h"
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

#define GW_FISHEYE_SOURCE_DEBUG 0

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

static const char* kFisheyeScreenVert = R"glsl(
attribute vec2 aPosition;
varying vec2 vNdc;
void main() {
    vNdc = aPosition;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
)glsl";

static const char* kFisheyeScreenFrag = R"glsl(
#if GW_FISHEYE_SOURCE_DEBUG
precision highp float;
#else
#extension GL_OES_EGL_image_external : require
precision highp float;
uniform samplerExternalOES uVideo;
uniform float uEyeX;
#endif
uniform mat4 uInvProjection;
uniform mat4 uViewToVideo;
varying vec2 vNdc;
const float kPI = 3.14159265358979;
bool ProjectFisheye180(vec3 ray, out vec2 sourceUv, out float edgeFade) {
    float theta = acos(clamp(-ray.z, -1.0, 1.0));
    float rn = theta / (0.5 * kPI);
    if (rn > 1.0) {
        sourceUv = vec2(0.0, 0.0);
        edgeFade = 0.0;
        return false;
    }
    float phi = atan(ray.y, ray.x);
    sourceUv = vec2(
        0.502 + rn * 0.475 * cos(phi),
        0.500 - rn * 0.472 * sin(phi));
    edgeFade = 1.0 - smoothstep(0.985, 1.0, rn);
    return true;
}
void main() {
    vec4 view = uInvProjection * vec4(vNdc, -1.0, 1.0);
    vec3 ray = normalize((uViewToVideo * vec4(normalize(view.xyz / view.w), 0.0)).xyz);
    vec2 sourceUv;
    float edgeFade;
    if (!ProjectFisheye180(ray, sourceUv, edgeFade)) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
#if GW_FISHEYE_SOURCE_DEBUG
    float checker = mod(floor(sourceUv.x * 32.0) + floor(sourceUv.y * 32.0), 2.0);
    gl_FragColor = vec4(sourceUv.x, sourceUv.y, checker, 1.0) * edgeFade;
#else
    vec2 uv = vec2(uEyeX + sourceUv.x * 0.5, sourceUv.y);
    gl_FragColor = texture2D(uVideo, uv) * edgeFade;
#endif
}
)glsl";

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
  GLuint mFisheyeScreenProg = 0;
  GLuint mFisheyeScreenVBO = 0;
  GLint mFisheyeScreenPosLoc = -1;
  GLint mFisheyeScreenInvProjectionLoc = -1;
  GLint mFisheyeScreenViewToVideoLoc = -1;
  GLint mFisheyeScreenVideoLoc = -1;
  GLint mFisheyeScreenEyeXLoc = -1;

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
        // YouTube VR180 MESH = equidistant fisheye (not equirectangular). Rendered via a
        // forward hemisphere with fisheye UV; requires the no-layer GL TextureSurface path
        // (BrowserWorld detaches the window compositor layer before creating this VRVideo).
        leftEye = createFisheye180Projection(device::EyeRect(0.0f, 0.0f, 0.5f, 1.0f));
        rightEye = createFisheye180Projection(device::EyeRect(0.5f, 0.0f, 0.5f, 1.0f));
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

  // Equidistant-fisheye hemisphere for YouTube VR180 "MESH" content.
  //
  // Each eye's half-frame is an equidistant fisheye (NOT equirectangular): the radius from
  // the image centre is proportional to the angle from the forward axis, r = θ/(π/2), with a
  // 180° FOV. The circle is anamorphic — squeezed into the 8:9 per-eye slot — so the
  // horizontal/vertical radii differ. Geometry measured offline from a real VR180 frame
  // (JDE2nfczRl0): centre ≈ (0.497, 0.499), radii ≈ (0.462, 0.467) of the eye half.
  //
  // We build a forward-facing hemisphere (view -Z is straight ahead) and map each vertex
  // direction to fisheye UV inside aUVRect (the eye's L/R sub-rect of the SBS frame).
  vrb::TogglePtr createFisheye180Projection(device::EyeRect aUVRect) {
    const int kCols = 80;   // azimuth β around the up axis: -90°..+90° (front hemisphere)
    const int kRows = 80;   // polar α from the up pole: 0..180°
    const float kRadius = 10.0f;
    const float kHalfPI = (float)M_PI * 0.5f;
    const float cx = 0.502f, cy = 0.500f;   // fisheye circle centre (normalized, per eye half)
    const float rx = 0.475f, ry = 0.472f;   // fisheye circle radii at θ=90° (anamorphic)
    // MEASURED from the real YouTube ANDROID_VR fisheye stream (itag 137, 1920×1080 SBS).
    // Left-eye content bbox: cols 25..938, rows 31..1050 → centre (481.5, 540.5),
    // half-extent (456.5, 509.5) px. Normalised per 960×1080 half:
    //   cx=481.5/960=0.502  cy=540.5/1080=0.500  rx=456.5/960=0.475  ry=509.5/1080=0.472.
    // Corners of the bbox are black (luma<3) → it is a true inscribed ellipse, not full-frame.
    // Verified with the offline reprojector tools/vr180-calib/reproject-dome.py against a
    // captured frame: produces a clean, undistorted forward dome. Earlier values (0.462/0.467)
    // were tuned for a different test MP4 and undershot; the 0.490/0.445 guess overshot rx into
    // the black border (dark wedge) and squished ry.
    // Dome angle at which we reach the fisheye edge (rn=1): 90° = full 180° hemisphere / 1:1,
    // correct for a true equidistant 180° fisheye source.
    const float kMaxThetaRad = 90.0f * (float)M_PI / 180.0f;

    // Diagnostic: log the surface texture dimensions so we can verify expected 2:1 aspect
    {
      int32_t texW = 0, texH = 0;
      window->GetSurfaceTextureSize(texW, texH);
      float totalAspect = (texH > 0) ? (float)texW / (float)texH : 0.0f;
      float perEyeAspect = (texH > 0) ? (float)(texW / 2) / (float)texH : 0.0f;
      __android_log_print(ANDROID_LOG_DEBUG, "GW_VR",
          "fisheye180: texSize=%dx%d eye=%s totalAspect=%.3f perEyeAspect=%.3f (expected=2.0/1.0)",
          texW, texH, aUVRect.mX < 0.1f ? "LEFT" : "RIGHT", totalAspect, perEyeAspect);
    }

    vrb::CreationContextPtr create = context.lock();
    vrb::VertexArrayPtr array = vrb::VertexArray::Create(create);

    // Tessellate the FRONT hemisphere with poles on the up/down (Y) axis — never at the
    // forward gaze — so there is no pinch/seam where the user looks. View space: -Z forward,
    // +Y up, +X right. Each vertex's angle from forward maps to an equidistant-fisheye radius.
    for (int row = 0; row <= kRows; row++) {
      const float alpha = (row / (float)kRows) * (float)M_PI;       // 0 (up) .. π (down)
      const float sinA = sinf(alpha), cosA = cosf(alpha);
      for (int col = 0; col <= kCols; col++) {
        const float beta = -kHalfPI + (col / (float)kCols) * (float)M_PI;  // -90°..+90°
        const float sinB = sinf(beta), cosB = cosf(beta);
        const float dx = sinA * sinB;     // right
        const float dy = cosA;            // up
        const float dz = -sinA * cosB;    // forward (-Z) at α=90°,β=0
        const float cosTheta = -dz;                                   // angle from forward axis
        const float theta = acosf(fmaxf(-1.0f, fminf(1.0f, cosTheta)));
        float rn = theta / kMaxThetaRad;                             // equidistant radius 0..1
        if (rn > 1.0f) rn = 1.0f;  // clamp beyond the fisheye content edge (no out-of-circle garbage)
        const float phi = atan2f(dy, dx);                            // azimuth around forward
        // -sin on v flips so "up" maps to the top of the image (external texture origin).
        const float u = aUVRect.mX + (cx + rn * rx * cosf(phi)) * aUVRect.mWidth;
        const float v = aUVRect.mY + (cy - rn * ry * sinf(phi)) * aUVRect.mHeight;
        array->AppendVertex(vrb::Vector(kRadius * dx, kRadius * dy, kRadius * dz));
        array->AppendUV(vrb::Vector(u, v, 0.0f));
        array->AppendNormal(vrb::Vector(dx, dy, dz));
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

  bool InitFisheyeScreenSpace() {
    if (mFisheyeScreenProg) return true;
    GLuint vert = CompileEACShader(GL_VERTEX_SHADER, kFisheyeScreenVert);
    if (!vert) return false;
    std::string fragSource;
#if GW_FISHEYE_SOURCE_DEBUG
    fragSource = std::string("#define GW_FISHEYE_SOURCE_DEBUG 1\n") + kFisheyeScreenFrag;
#else
    fragSource = std::string("#define GW_FISHEYE_SOURCE_DEBUG 0\n") + kFisheyeScreenFrag;
#endif
    GLuint frag = CompileEACShader(GL_FRAGMENT_SHADER, fragSource.c_str());
    if (!frag) { glDeleteShader(vert); return false; }
    mFisheyeScreenProg = glCreateProgram();
    glAttachShader(mFisheyeScreenProg, vert);
    glAttachShader(mFisheyeScreenProg, frag);
    glLinkProgram(mFisheyeScreenProg);
    glDeleteShader(vert);
    glDeleteShader(frag);
    GLint ok = 0;
    glGetProgramiv(mFisheyeScreenProg, GL_LINK_STATUS, &ok);
    if (!ok) {
      glDeleteProgram(mFisheyeScreenProg);
      mFisheyeScreenProg = 0;
      return false;
    }
    mFisheyeScreenPosLoc = glGetAttribLocation(mFisheyeScreenProg, "aPosition");
    mFisheyeScreenInvProjectionLoc = glGetUniformLocation(mFisheyeScreenProg, "uInvProjection");
    mFisheyeScreenViewToVideoLoc = glGetUniformLocation(mFisheyeScreenProg, "uViewToVideo");
    mFisheyeScreenVideoLoc = glGetUniformLocation(mFisheyeScreenProg, "uVideo");
    mFisheyeScreenEyeXLoc = glGetUniformLocation(mFisheyeScreenProg, "uEyeX");
    glGenBuffers(1, &mFisheyeScreenVBO);
    glBindBuffer(GL_ARRAY_BUFFER, mFisheyeScreenVBO);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kEACQuadVerts), kEACQuadVerts, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    return true;
  }

  void DrawFisheyeScreenSpace_Internal(const vrb::Camera& aCamera, device::Eye aEye) {
    if (!InitFisheyeScreenSpace()) return;

    GLint savedProgram = 0, savedVBO = 0;
    GLint savedActiveTexture = 0;
    GLboolean depthOn = glIsEnabled(GL_DEPTH_TEST);
    GLboolean blendOn = glIsEnabled(GL_BLEND);
    glGetIntegerv(GL_CURRENT_PROGRAM, &savedProgram);
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &savedVBO);
    glGetIntegerv(GL_ACTIVE_TEXTURE, &savedActiveTexture);

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glUseProgram(mFisheyeScreenProg);
    vrb::Matrix invProjection = aCamera.GetPerspective().Inverse();
    vrb::Matrix viewToVideo = root->GetTransform().AfineInverse().PostMultiply(aCamera.GetTransform());
    glUniformMatrix4fv(mFisheyeScreenInvProjectionLoc, 1, GL_FALSE, invProjection.Data());
    glUniformMatrix4fv(mFisheyeScreenViewToVideoLoc, 1, GL_FALSE, viewToVideo.Data());
#if !GW_FISHEYE_SOURCE_DEBUG
    vrb::TexturePtr videoTex = std::dynamic_pointer_cast<vrb::Texture>(window->GetSurfaceTexture());
    if (videoTex && videoTex->GetHandle()) {
      glActiveTexture(GL_TEXTURE0);
      glBindTexture(GL_TEXTURE_EXTERNAL_OES, videoTex->GetHandle());
      glUniform1i(mFisheyeScreenVideoLoc, 0);
      glUniform1f(mFisheyeScreenEyeXLoc, aEye == device::Eye::Left ? 0.0f : 0.5f);
    }
#endif
    glBindBuffer(GL_ARRAY_BUFFER, mFisheyeScreenVBO);
    glEnableVertexAttribArray(mFisheyeScreenPosLoc);
    glVertexAttribPointer(mFisheyeScreenPosLoc, 2, GL_FLOAT, GL_FALSE, 0, nullptr);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(mFisheyeScreenPosLoc);

#if !GW_FISHEYE_SOURCE_DEBUG
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
#endif
    glActiveTexture(savedActiveTexture);
    glBindBuffer(GL_ARRAY_BUFFER, savedVBO);
    glUseProgram(savedProgram);
    if (depthOn) glEnable(GL_DEPTH_TEST);
    if (blendOn) glEnable(GL_BLEND);
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
VRVideo::DrawFisheyeScreenSpace(const vrb::Camera& aCamera, device::Eye aEye) {
  m.DrawFisheyeScreenSpace_Internal(aCamera, aEye);
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
