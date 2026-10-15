package org.frostbox.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.frostbox.app.R
import org.frostbox.app.runtime.LibraryLauncher
import org.frostbox.core.GameProfile
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable fun GameCover(p: GameProfile,modifier: Modifier=Modifier) {
    val context=LocalContext.current
    val bitmap by produceState<android.graphics.Bitmap?>(null,p.id,p.coverFile) {
        value=withContext(Dispatchers.IO) {p.coverFile?.let {runCatching {BitmapFactory.decodeFile(File(context.filesDir,"games/${p.id}/$it").path)}.getOrNull()}}
    }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF322748),Color(0xFF17262F),Color(0xFF19151F)))),contentAlignment=Alignment.Center) {
        if(bitmap!=null)Image(bitmap!!.asImageBitmap(),contentDescription=null,contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize())
        else Text(p.name.take(2).uppercase(),style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.onPrimaryContainer)
    }
}
@Composable fun ColumnScope.LibraryCatalog(profiles: List<GameProfile>,sessions: Map<String,LibraryLauncher.Session>,busy: Boolean,
    remove: (String)->Unit,details: (String)->Unit,settings: (String)->Unit = details,cover: (String)->Unit = details,launch: @Composable (GameProfile,LibraryLauncher.Session)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var recent by rememberSaveable {mutableStateOf(true)}
    var grid by rememberSaveable {mutableStateOf(true)}
    var filter by rememberSaveable {mutableStateOf("Все")}
    val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focus=androidx.compose.ui.platform.LocalFocusManager.current
    OutlinedTextField(query,{query=it},label={Text(stringResource(R.string.catalog_search))},singleLine=true,keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(imeAction=androidx.compose.ui.text.input.ImeAction.Search),keyboardActions=androidx.compose.foundation.text.KeyboardActions(onSearch={keyboard?.hide();focus.clearFocus()}),modifier=Modifier.fillMaxWidth().testTag("library-search"))
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        FilterChip(selected=recent,onClick={recent=!recent},label={Text(stringResource(if(recent)R.string.catalog_recent else R.string.catalog_name))},modifier=Modifier.testTag("library-sort"))
        FilterChip(selected=grid,onClick={grid=!grid},label={Text(stringResource(if(grid)R.string.catalog_grid else R.string.catalog_list))},modifier=Modifier.testTag("library-layout"))
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        for(f in listOf("Все","x64","x86","DXVK"))FilterChip(filter==f,{filter=f},label={Text(f)},modifier=Modifier.testTag("filter-$f"))
    }
    val matching=profiles.filter {it.name.contains(query,true) && when(filter){"x64"->it.architecture=="x86-64";"x86"->it.architecture=="x86";"DXVK"->it.graphics==org.frostbox.core.GraphicsBackend.DXVK;else->true}}
    val shown=if(recent)matching.sortedByDescending {it.lastLaunch ?: 0} else matching.sortedBy {it.name.lowercase()}
    if(shown.isEmpty() && profiles.isNotEmpty())Text(stringResource(R.string.catalog_no_matches))
    LazyVerticalGrid(columns=GridCells.Fixed(if(grid)2 else 1),modifier=Modifier.weight(1f).testTag("catalog-scroll"),
        verticalArrangement=Arrangement.spacedBy(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=20.dp)) {
        items(shown,key={it.id}) {p ->
            val state=sessions[p.id] ?: LibraryLauncher.Session()
            Card(Modifier.fillMaxWidth().testTag("game-card-${p.id}")) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                GameCover(p,Modifier.fillMaxWidth().height(if(grid)150.dp else 180.dp))
                Text(p.name,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(if(p.architecture=="x86-64")"Экспериментально · x64" else "Экспериментально · x86 WoW64",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.secondary)
                Text(stringResource(phaseLabel(state.phase)),modifier=Modifier.testTag("status-${p.id}"))
                Text(lastPlayed(p),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                launch(p,state)
                OutlinedButton(onClick={details(p.id)},modifier=Modifier.testTag("details-${p.id}")){Text(stringResource(R.string.catalog_details))}
                GameRecordMenu(p.id,busy,{remove(p.id)},details={details(p.id)},settings={settings(p.id)},cover={cover(p.id)})
            }}
        }
    }
}
fun phaseLabel(phase: LibraryLauncher.Phase)=when(phase) {
    LibraryLauncher.Phase.Ready->R.string.phase_ready
    LibraryLauncher.Phase.Preparing->R.string.phase_preparing
    LibraryLauncher.Phase.Starting->R.string.phase_starting
    LibraryLauncher.Phase.Running->R.string.phase_running
    LibraryLauncher.Phase.Stopping->R.string.phase_stopping
    LibraryLauncher.Phase.Finished->R.string.phase_finished
    LibraryLauncher.Phase.Failed->R.string.phase_failed
}
