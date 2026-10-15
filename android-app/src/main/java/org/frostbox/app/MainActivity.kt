package org.frostbox.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import org.frostbox.app.input.ControllerWindowCallback
import org.frostbox.app.ui.FrostApp

class MainActivity: ComponentActivity() {
    private val model: FrostModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.callback = ControllerWindowCallback(window.callback, model.controllers, {model.launcher.activeId.value!=null && !model.controllers.gameInputBlocked}, {onBackPressedDispatcher.onBackPressed()})
        setContent { FrostApp(model) }
    }
    override fun onStart() { super.onStart(); model.startMonitoring(); model.gpu.foreground(true) }
    override fun onStop() { model.gpu.foreground(false); model.stopMonitoring(); super.onStop() }
}
