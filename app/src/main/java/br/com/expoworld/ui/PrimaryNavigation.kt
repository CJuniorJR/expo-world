package br.com.expoworld.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import br.com.expoworld.R

@Composable
fun PrimaryNavigation(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(tonalElevation = 6.dp, windowInsets = WindowInsets(0,0,0,0)) {
        listOf("Mapa" to R.drawable.ic_map, "Bairros" to R.drawable.ic_district,
            "Roteiros" to R.drawable.ic_explore, "Histórico" to R.drawable.ic_history).forEachIndexed { index, (label, icon) ->
            NavigationBarItem(selected = selected == index, onClick = { onSelect(index) },
                icon = { Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(23.dp)) }, label = { Text(label) })
        }
    }
}
