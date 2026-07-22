package com.mesh.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.mesh.app.ui.navigation.MeshNavGraph
import com.mesh.app.ui.theme.MeshTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MeshTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MeshNavGraph()
                }
            }
        }
    }
}
