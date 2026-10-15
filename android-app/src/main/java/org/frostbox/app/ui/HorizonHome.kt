package org.frostbox.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.frostbox.app.runtime.LibraryLauncher
import org.frostbox.core.GameProfile
import org.frostbox.core.GraphicsBackend
import java.text.DateFormat
import java.util.Date

val HorizonColors=darkColorScheme(primary=Color(0xFFB69BFF),onPrimary=Color(0xFF241442),
    primaryContainer=Color(0xFF3C2C59),onPrimaryContainer=Color(0xFFE8DCFF),
    secondary=Color(0xFF96D5C7),background=Color(0xFF0D0C12),onBackground=Color(0xFFF4F0F8),
    surface=Color(0xFF1A1821),onSurface=Color(0xFFF4F0F8),surfaceVariant=Color(0xFF282430),
    onSurfaceVariant=Color(0xFFBBB4C9),outline=Color(0xFF514A5D))
val HorizonShapes=Shapes(small=RoundedCornerShape(12.dp),medium=RoundedCornerShape(20.dp),large=RoundedCornerShape(28.dp))

@Composable fun HorizonMark() {
    Box(Modifier.size(42.dp).background(Brush.linearGradient(listOf(Color(0xFFB69BFF),Color(0xFF5F478F))),RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center){Text("F",color=Color.White,style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Black)}
}
@Composable fun HorizonNavIcon(kind: String) {
    val c=MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.size(22.dp)) {
        val w=size.width;val h=size.height;val s=Stroke(w*.085f)
        when(kind){
            "Home"->{val p=Path().apply{moveTo(w*.12f,h*.45f);lineTo(w*.5f,h*.1f);lineTo(w*.88f,h*.45f)};drawPath(p,c,style=s);drawRect(c,Offset(w*.24f,h*.46f),Size(w*.52f,h*.43f),style=s)}
            "Library"->{for(i in 0..2)drawRoundRect(c,Offset(w*(.12f+i*.29f),h*(.18f+i*.04f)),Size(w*.18f,h*.7f),style=s)}
            "Controllers"->{drawRoundRect(c,Offset(w*.03f,h*.26f),Size(w*.94f,h*.53f),style=s);drawLine(c,Offset(w*.18f,h*.53f),Offset(w*.42f,h*.53f),w*.08f);drawLine(c,Offset(w*.3f,h*.41f),Offset(w*.3f,h*.65f),w*.08f);drawCircle(c,w*.065f,Offset(w*.68f,h*.48f));drawCircle(c,w*.065f,Offset(w*.81f,h*.61f))}
            else->{drawCircle(c,w*.34f,Offset(w*.5f,h*.5f),style=s);drawCircle(c,w*.1f,Offset(w*.5f,h*.5f),style=s)}
        }
    }
}
fun runtimeCaption(p: GameProfile)="${if(p.architecture=="x86-64")"Windows x64" else "Windows x86"} · ${if(p.graphics==GraphicsBackend.DXVK)"DXVK ${if(p.graphicsApi=="auto")if(p.architecture=="x86")"D3D9" else "D3D11" else p.graphicsApi.uppercase()}" else "Wine GDI"} · ${p.width}×${p.height}"
fun lastPlayed(p: GameProfile)=p.lastLaunch?.let {DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it))} ?: "Ещё не запускалось"

@Composable fun HorizonHero(p: GameProfile,details: ()->Unit,launch: @Composable ()->Unit) {
    Card(Modifier.fillMaxWidth().testTag("home-hero"),shape=RoundedCornerShape(28.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min=250.dp)) {
            GameCover(p,Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent,Color(0xE60D0C12)))))
            Column(Modifier.fillMaxWidth().padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Surface(color=Color(0xDD282030),shape=RoundedCornerShape(8.dp)){Text(if(p.lastLaunch!=null)"ПОСЛЕДНИЙ ЗАПУСК" else "ИЗ БИБЛИОТЕКИ",Modifier.padding(horizontal=10.dp,vertical=6.dp),style=MaterialTheme.typography.labelSmall,color=Color(0xFFE8DCFF))}
                Spacer(Modifier.height(42.dp))
                Text(p.name,style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,maxLines=2,overflow=TextOverflow.Ellipsis,color=Color.White)
                Text(runtimeCaption(p),style=MaterialTheme.typography.bodySmall,color=Color(0xFFDBD3E8))
                Text(lastPlayed(p),style=MaterialTheme.typography.bodySmall,color=Color(0xFFBBB4C9))
                launch()
                TextButton(onClick=details,modifier=Modifier.testTag("hero-details")){Text("Об игре и профиле")}
            }
        }
    }
}
@Composable fun HorizonHome(profiles: List<GameProfile>,sessions: Map<String,LibraryLauncher.Session>,busy: Boolean,
    library: ()->Unit,add: ()->Unit,details: (String)->Unit,launch: @Composable (GameProfile,LibraryLauncher.Session)->Unit) {
    Text("Ваши игры. Ваше пространство.",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
    Text("Локальная Windows-библиотека на Android",color=MaterialTheme.colorScheme.onSurfaceVariant)
    val recent=profiles.filter {it.lastLaunch!=null}.sortedByDescending{it.lastLaunch}.take(8)
    val hero=recent.firstOrNull() ?: profiles.lastOrNull()
    if(hero!=null)HorizonHero(hero,{details(hero.id)}) {launch(hero,sessions[hero.id] ?: LibraryLauncher.Session())}
    else Card(Modifier.fillMaxWidth().testTag("home-empty")){Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        Text("Библиотека начинается с вашей игры",style=MaterialTheme.typography.titleLarge)
        Text("Добавьте EXE, затем выберите папку с DLL и ресурсами. FrostBox ничего не запускает автоматически.")
        Button(onClick=add,enabled=!busy){Text("Добавить игру")}
    }}
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){Text("Недавно запускались",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold);TextButton(onClick=library){Text("Все ${profiles.size}")}}
    if(recent.isEmpty())Text("История запусков пока пуста",color=MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        recent.forEach {p->Card(Modifier.width(168.dp).clickable {details(p.id)}){Column {
            GameCover(p,Modifier.fillMaxWidth().height(122.dp))
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){Text(p.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall);Text(if(p.graphics==GraphicsBackend.DXVK)"DXVK · ${p.architecture}" else "Wine · ${p.architecture}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }}}
    }
    Card(Modifier.fillMaxWidth()){Row(Modifier.padding(18.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Моя библиотека",style=MaterialTheme.typography.titleMedium);Text("${profiles.size} программ · только ваши файлы",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};Button(onClick=add,enabled=!busy,modifier=Modifier.testTag("home-add")){Text("+ Добавить")}}}
    val added=profiles.filter{it.addedAt!=null}.sortedByDescending{it.addedAt}.take(4)
    if(added.isNotEmpty()){Text("Недавно добавлены",style=MaterialTheme.typography.titleLarge);added.forEach {p->TextButton(onClick={details(p.id)}){Text(p.name,maxLines=1,overflow=TextOverflow.Ellipsis)}}}
    Text("Совместимость экспериментальная. Успешный запуск не означает стабильный игровой процесс.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
}
