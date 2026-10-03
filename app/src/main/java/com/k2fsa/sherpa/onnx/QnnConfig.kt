// Copied from https://github.com/k2-fsa/sherpa-onnx (v1.13.8, sherpa-onnx/kotlin-api), Apache-2.0.
// Do not rename classes or fields: the JNI library looks them up by name.
package com.k2fsa.sherpa.onnx

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
)
