/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.voice

import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceEnum

/**
 * Offline speech recognition models (sherpa-onnx release assets).
 */
enum class SpeechModel(
    override val stringRes: Int,
    /** Release tag + archive name under https://github.com/k2-fsa/sherpa-onnx/releases/download/ */
    val releasePath: String,
    /** Top-level directory inside the archive */
    val dirName: String,
    /** Files (relative to [dirName]) that must exist for the model to be usable */
    val requiredFiles: List<String>,
    /** Rough download size, for UI only */
    val downloadSizeMb: Int,
    /** Run on Qualcomm HTP (NPU) via QNN */
    val isQnn: Boolean,
    /**
     * The longest audio segment the model can take.
     * QNN models have a fixed input length; longer speech is split by VAD.
     */
    val maxSegmentSeconds: Float,
    /** Whether it's cheap enough to re-run on the growing buffer to show partial results */
    val fastEnoughForPartial: Boolean,
    /** Test wav files (relative to [dirName]) kept after extraction, used by the self test */
    val testWav: String,
) : ManagedPreferenceEnum {

    /**
     * SenseVoice-Small on Snapdragon 8 Gen 3 (SM8650, Hexagon v75) NPU.
     * Precompiled QNN context binary, QNN SDK 2.40, fixed 20s input.
     */
    SenseVoiceQnnSM8650(
        R.string.voice_model_sense_voice_qnn,
        "asr-models-qnn-binary/sherpa-onnx-qnn-SM8650-binary-20-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8.tar.bz2",
        "sherpa-onnx-qnn-SM8650-binary-20-seconds-sense-voice-zh-en-ja-ko-yue-2024-07-17-int8",
        listOf("model.bin", "tokens.txt"),
        downloadSizeMb = 157,
        isQnn = true,
        maxSegmentSeconds = 19f,
        fastEnoughForPartial = true,
        testWav = "test_wavs/zh.wav",
    ),

    /** SenseVoice-Small int8 on CPU, works on any arm64 device */
    SenseVoiceCpu(
        R.string.voice_model_sense_voice_cpu,
        "asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2",
        "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17",
        listOf("model.int8.onnx", "tokens.txt"),
        downloadSizeMb = 156,
        isQnn = false,
        maxSegmentSeconds = 25f,
        fastEnoughForPartial = true,
        testWav = "test_wavs/zh.wav",
    ),

    /** Qwen3-ASR 0.6B int8 on CPU: most accurate, but slow-ish and large */
    Qwen3Asr(
        R.string.voice_model_qwen3_asr,
        "asr-models/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25.tar.bz2",
        "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25",
        listOf(
            "conv_frontend.onnx",
            "encoder.int8.onnx",
            "decoder.int8.onnx",
            "tokenizer/vocab.json",
            "tokenizer/merges.txt",
            "tokenizer/tokenizer_config.json"
        ),
        downloadSizeMb = 838,
        isQnn = false,
        maxSegmentSeconds = 25f,
        fastEnoughForPartial = false,
        testWav = "test_wavs/codeswitch.wav",
    );

    val defaultUrl: String
        get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/$releasePath"

    /** keep model files and a single test wav, skip the rest (other test wavs, scripts, docs) */
    fun shouldExtract(pathInArchive: String): Boolean {
        val rel = pathInArchive.removePrefix("$dirName/")
        if (rel == pathInArchive) return false // not inside the model directory
        if (rel.startsWith("test_wavs/")) return rel == testWav
        return rel.endsWith(".onnx") || rel.endsWith(".bin") || rel.endsWith(".txt") ||
                rel.endsWith(".json") || rel == "LICENSE" || rel == "README.md"
    }

    companion object {
        fun fromDirName(name: String) = entries.firstOrNull { it.dirName == name }
    }
}

enum class SpeechLanguage(override val stringRes: Int, val senseVoiceCode: String) :
    ManagedPreferenceEnum {
    Auto(R.string.voice_lang_auto, "auto"),
    Chinese(R.string.voice_lang_zh, "zh"),
    English(R.string.voice_lang_en, "en"),
    Cantonese(R.string.voice_lang_yue, "yue"),
    Japanese(R.string.voice_lang_ja, "ja"),
    Korean(R.string.voice_lang_ko, "ko");
}
