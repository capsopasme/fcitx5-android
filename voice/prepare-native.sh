#!/usr/bin/env bash
#
# Build sherpa-onnx (with Qualcomm QNN support) for arm64-v8a and put the native libraries
# needed by the built-in voice input into app/src/main/jniLibs/arm64-v8a/.
#
# Requirements: git, curl, cmake (>= 3.22), make, an Android NDK (r27+).
#   export ANDROID_NDK=/path/to/ndk
#   ./voice/prepare-native.sh
#
# Environment:
#   SHERPA_ONNX_VERSION  sherpa-onnx git tag (default v1.13.8, must match the vendored Kotlin API)
#   QNN_HTP_ARCHS        Hexagon versions to bundle (default "75" = Snapdragon 8 Gen 3).
#                        e.g. "73 75 79 81" for 8 Gen 2 / 8 Gen 3 / 8 Elite / 8 Elite Gen 5
#   QNN_WITH_PREPARE     set to 1 to also bundle libQnnHtpPrepare.so (~74 MB). Only needed for
#                        on-device graph compilation; the precompiled context binaries don't use it.
#   WORK_DIR             build directory (default build/voice-native)
#
set -euo pipefail

SHERPA_ONNX_VERSION=${SHERPA_ONNX_VERSION:-v1.13.8}
QNN_VERSION=2.40.0.251030
QNN_HTP_ARCHS=${QNN_HTP_ARCHS:-75}

ROOT=$(cd "$(dirname "$0")/.." && pwd)
WORK_DIR=${WORK_DIR:-$ROOT/build/voice-native}
OUT=$ROOT/app/src/main/jniLibs/arm64-v8a

if [ -z "${ANDROID_NDK:-}" ]; then
  if [ -n "${ANDROID_NDK_HOME:-}" ]; then
    ANDROID_NDK=$ANDROID_NDK_HOME
  elif [ -n "${ANDROID_NDK_LATEST_HOME:-}" ]; then
    ANDROID_NDK=$ANDROID_NDK_LATEST_HOME
  else
    echo "Please set ANDROID_NDK" >&2
    exit 1
  fi
fi
export ANDROID_NDK
echo "Using NDK: $ANDROID_NDK"

RELEASE=https://github.com/k2-fsa/sherpa-onnx/releases/download

download() {
  # download <url> <output>, with retries
  for i in 1 2 3 4 5; do
    if curl -fSL --retry 3 -o "$2" "$1"; then return 0; fi
    echo "download failed ($i), retrying..." >&2
    sleep 5
  done
  return 1
}

mkdir -p "$WORK_DIR" "$OUT"
cd "$WORK_DIR"

# ---------------------------------------------------------------------------------------------
# 1. sherpa-onnx + onnxruntime (JNI)
if [ ! -f "sherpa-libs-$SHERPA_ONNX_VERSION/libsherpa-onnx-jni.so" ]; then
  if [ ! -d "sherpa-onnx-$SHERPA_ONNX_VERSION" ]; then
    git clone --depth 1 -b "$SHERPA_ONNX_VERSION" https://github.com/k2-fsa/sherpa-onnx.git \
      "sherpa-onnx-$SHERPA_ONNX_VERSION"
  fi

  if [ ! -f "qnn-include-$QNN_VERSION/include/QNN/QnnInterface.h" ]; then
    download "$RELEASE/asr-models-qnn/qnn-include-$QNN_VERSION.tar.bz2" qnn-include.tar.bz2
    tar xf qnn-include.tar.bz2
    rm qnn-include.tar.bz2
  fi
  export QNN_SDK_ROOT=$WORK_DIR/qnn-include-$QNN_VERSION

  pushd "sherpa-onnx-$SHERPA_ONNX_VERSION"
  export SHERPA_ONNX_ENABLE_TTS=OFF
  export SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF
  export SHERPA_ONNX_ENABLE_BINARY=OFF
  export SHERPA_ONNX_ENABLE_C_API=OFF
  export SHERPA_ONNX_ENABLE_JNI=ON
  export SHERPA_ONNX_ENABLE_QNN=ON
  export BUILD_SHARED_LIBS=ON
  ./build-android-arm64-v8a.sh
  popd

  mkdir -p "sherpa-libs-$SHERPA_ONNX_VERSION"
  cp -v "sherpa-onnx-$SHERPA_ONNX_VERSION"/build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so \
    "sherpa-onnx-$SHERPA_ONNX_VERSION"/build-android-arm64-v8a/install/lib/libonnxruntime.so \
    "sherpa-libs-$SHERPA_ONNX_VERSION/"
fi
cp -v "sherpa-libs-$SHERPA_ONNX_VERSION"/*.so "$OUT/"

# ---------------------------------------------------------------------------------------------
# 2. QNN runtime (HTP backend). Must match the QNN version used to build the model context binary.
if [ ! -f "qnn-libs-$QNN_VERSION/libQnnHtp.so" ]; then
  download "$RELEASE/asr-models-qnn/qnn-libs-$QNN_VERSION.tar.bz2" qnn-libs.tar.bz2
  tar xf qnn-libs.tar.bz2
  rm qnn-libs.tar.bz2
fi
cp -v "qnn-libs-$QNN_VERSION/libQnnHtp.so" "qnn-libs-$QNN_VERSION/libQnnSystem.so" "$OUT/"
if [ "${QNN_WITH_PREPARE:-0}" = 1 ]; then
  cp -v "qnn-libs-$QNN_VERSION/libQnnHtpPrepare.so" "$OUT/"
fi
for v in $QNN_HTP_ARCHS; do
  cp -v "qnn-libs-$QNN_VERSION/libQnnHtpV${v}Stub.so" "qnn-libs-$QNN_VERSION/libQnnHtpV${v}Skel.so" "$OUT/"
done

echo
echo "Native libraries for voice input are ready:"
ls -lh "$OUT"
