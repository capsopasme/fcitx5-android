/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.ActivityNotFoundException
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.OpenDocument
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.utils.setup
import org.fcitx.fcitx5.android.utils.toast
import org.fcitx.fcitx5.android.voice.AudioCapture
import org.fcitx.fcitx5.android.voice.SpeechModel
import org.fcitx.fcitx5.android.voice.VoiceClient
import org.fcitx.fcitx5.android.voice.VoiceModelManager
import org.fcitx.fcitx5.android.voice.VoiceModelManager.State
import org.fcitx.fcitx5.android.voice.VoicePermissionActivity
import java.io.File

class VoiceSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().voice) {

    private val prefs = AppPrefs.getInstance().voice

    private lateinit var importLauncher: ActivityResultLauncher<Array<String>>

    private val modelPrefs = mutableMapOf<SpeechModel, Preference>()
    private var importPref: Preference? = null
    private var permissionPref: Preference? = null

    private val onModelChange = ManagedPreference.OnChangeListener<SpeechModel> { _, _ ->
        updateModelSummaries(VoiceModelManager.states.value)
    }

    private var testDialog: AlertDialog? = null
    private var testClient: VoiceClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        importLauncher = registerForActivityResult(OpenDocument()) { uri ->
            if (uri == null) return@registerForActivityResult
            val ctx = requireContext().applicationContext
            VoiceModelManager.importArchive(ctx, uri) { result ->
                lifecycleScope.launch(Dispatchers.Main) {
                    result
                        .onSuccess {
                            ctx.toast(getString(R.string.voice_import_done, getString(it.stringRes)))
                        }
                        .onFailure {
                            ctx.toast(getString(R.string.voice_import_failed, it.localizedMessage ?: ""))
                        }
                }
            }
        }
    }

    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val ctx = screen.context

        permissionPref = Preference(ctx).apply {
            setup(getString(R.string.voice_permission_title)) {
                VoicePermissionActivity.launch(ctx)
            }
        }
        screen.addPreference(permissionPref!!)

        val models = PreferenceCategory(ctx).apply {
            setTitle(R.string.voice_models)
            isIconSpaceReserved = false
        }
        screen.addPreference(models)
        SpeechModel.entries.forEach { model ->
            val p = Preference(ctx).apply {
                setup(getString(model.stringRes)) { onModelClicked(model) }
            }
            models.addPreference(p)
            modelPrefs[model] = p
        }
        importPref = Preference(ctx).apply {
            setup(getString(R.string.voice_import_archive), getString(R.string.voice_import_archive_summary)) {
                try {
                    importLauncher.launch(arrayOf("*/*"))
                } catch (_: ActivityNotFoundException) {
                    ctx.toast(getString(R.string.voice_import_no_picker))
                }
            }
        }
        models.addPreference(importPref!!)

        models.addPreference(EditTextPreference(ctx).apply {
            key = prefs.downloadPrefix.key
            isSingleLineTitle = false
            isIconSpaceReserved = false
            setTitle(R.string.voice_download_prefix)
            setDialogTitle(R.string.voice_download_prefix)
            setDialogMessage(R.string.voice_download_prefix_hint)
            setDefaultValue("")
            summaryProvider = Preference.SummaryProvider<EditTextPreference> {
                it.text?.takeIf { t -> t.isNotBlank() } ?: getString(R.string.voice_download_prefix_none)
            }
        })

        models.addPreference(Preference(ctx).apply {
            setup(getString(R.string.voice_device_info), deviceInfo())
            isSelectable = false
        })
    }

    private fun deviceInfo(): String {
        val ctx = requireContext()
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        val libDir = ctx.applicationInfo.nativeLibraryDir
        val sherpa = File(libDir, "libsherpa-onnx-jni.so").exists()
        val qnn = File(libDir, "libQnnHtpV75Skel.so").exists()
        return getString(
            R.string.voice_device_info_summary,
            soc,
            getString(if (sherpa) R.string.voice_yes else R.string.voice_no),
            getString(if (qnn) R.string.voice_yes else R.string.voice_no)
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        VoiceModelManager.refresh(requireContext())
        prefs.model.registerOnChangeListener(onModelChange)
        viewLifecycleOwner.lifecycleScope.launch {
            VoiceModelManager.states.collect { updateModelSummaries(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            VoiceModelManager.importState.collect { s ->
                importPref?.summary = when (s) {
                    is State.Working -> progressText(R.string.voice_importing, s)
                    else -> getString(R.string.voice_import_archive_summary)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        permissionPref?.summary = getString(
            if (AudioCapture.hasPermission(requireContext())) R.string.voice_permission_ok
            else R.string.voice_permission_missing
        )
        VoiceModelManager.refresh(requireContext())
    }

    private fun progressText(label: Int, s: State.Working): String {
        val ctx = requireContext()
        val done = Formatter.formatShortFileSize(ctx, s.done)
        return if (s.total > 0) {
            val pct = (s.done * 100 / s.total).coerceIn(0, 100)
            getString(label) + " $pct% ($done / ${Formatter.formatShortFileSize(ctx, s.total)})"
        } else {
            getString(label) + " $done"
        }
    }

    private fun updateModelSummaries(states: Map<SpeechModel, State>) {
        val ctx = context ?: return
        val selected = prefs.model.getValue()
        modelPrefs.forEach { (model, pref) ->
            val state = states[model] ?: State.NotInstalled
            val text = when (state) {
                State.NotInstalled -> getString(R.string.voice_model_not_installed, model.downloadSizeMb)
                is State.Installed -> getString(
                    R.string.voice_model_installed, Formatter.formatShortFileSize(ctx, state.bytes)
                )
                is State.Working -> progressText(R.string.voice_downloading, state)
                is State.Failed -> getString(R.string.voice_model_failed, state.message)
            }
            pref.summary = if (model == selected) "✔ " + getString(R.string.voice_model_selected) + " · " + text else text
        }
    }

    private fun onModelClicked(model: SpeechModel) {
        val ctx = requireContext()
        val installed = VoiceModelManager.isInstalled(ctx, model)
        val busy = VoiceModelManager.isBusy(model)
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        when {
            busy -> actions += getString(R.string.voice_cancel_download) to { VoiceModelManager.cancel(model) }
            installed -> {
                actions += getString(R.string.voice_use_model) to { selectModel(model) }
                actions += getString(R.string.voice_self_test) to { runSelfTest(model) }
                actions += getString(R.string.voice_delete_model) to { confirmDelete(model) }
            }
            else -> actions += getString(R.string.voice_download, model.downloadSizeMb) to {
                VoiceModelManager.download(ctx, model, prefs.downloadPrefix.getValue())
                // the first downloaded model becomes the active one
                if (!VoiceModelManager.isInstalled(ctx, prefs.model.getValue())) {
                    selectModel(model)
                }
            }
        }
        AlertDialog.Builder(ctx)
            .setTitle(model.stringRes)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun selectModel(model: SpeechModel) {
        val list = findPreference<ListPreference>(prefs.model.key)
        if (list != null) {
            // updates both the UI and the stored value
            list.value = model.name
        } else {
            prefs.model.setValue(model)
        }
        updateModelSummaries(VoiceModelManager.states.value)
    }

    private fun confirmDelete(model: SpeechModel) {
        AlertDialog.Builder(requireContext())
            .setTitle(model.stringRes)
            .setMessage(R.string.voice_delete_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                VoiceModelManager.delete(requireContext(), model)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun runSelfTest(model: SpeechModel) {
        val ctx = requireContext()
        testClient?.unbind()
        val client = VoiceClient(ctx, object : VoiceClient.Listener {
            override fun onTestResult(
                text: String, backend: String, loadMillis: Long, decodeMillis: Long, audioMillis: Long
            ) {
                testDialog?.dismiss()
                val rtf = if (audioMillis > 0) decodeMillis.toFloat() / audioMillis else 0f
                showResult(
                    getString(
                        R.string.voice_self_test_result,
                        backend, loadMillis, audioMillis / 1000f, decodeMillis, rtf, text
                    )
                )
                finishTest()
            }

            override fun onError(message: String) {
                testDialog?.dismiss()
                showResult(getString(R.string.voice_status_error, message))
                finishTest()
            }

            override fun onServiceCrashed() {
                testDialog?.dismiss()
                showResult(getString(R.string.voice_status_crashed))
                finishTest()
            }
        })
        testClient = client
        testDialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_self_test)
            .setMessage(R.string.voice_self_test_running)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> finishTest() }
            .show()
        client.selfTest(model, prefs.language.getValue(), prefs.itn.getValue())
    }

    private fun finishTest() {
        testClient?.unbind()
        testClient = null
    }

    private fun showResult(message: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.voice_self_test)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onDestroyView() {
        prefs.model.unregisterOnChangeListener(onModelChange)
        super.onDestroyView()
    }

    override fun onDestroy() {
        testDialog?.dismiss()
        finishTest()
        super.onDestroy()
    }
}
