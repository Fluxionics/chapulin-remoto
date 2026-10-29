package com.chapulin.remoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chapulin.remoto.net.HostTarget
import com.chapulin.remoto.ui.ChapulinTheme
import com.chapulin.remoto.ui.ConnectScreen
import com.chapulin.remoto.ui.RemoteScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            ChapulinTheme {
                var target by remember { mutableStateOf<HostTarget?>(null) }
                val current = target

                if (current == null) {
                    ConnectScreen(onConnect = { target = it })
                } else {
                    RemoteScreen(target = current, onExit = { target = null })
                }
            }
        }
    }
}
