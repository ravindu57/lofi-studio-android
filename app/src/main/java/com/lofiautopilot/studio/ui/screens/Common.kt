package com.lofiautopilot.studio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.lofiautopilot.studio.data.JobEntity
import com.lofiautopilot.studio.data.JobStatus
import com.lofiautopilot.studio.data.Stage
import com.lofiautopilot.studio.ui.components.GlassCard
import com.lofiautopilot.studio.ui.components.ProgressRing
import com.lofiautopilot.studio.ui.components.StatusDot
import com.lofiautopilot.studio.ui.theme.Lofi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun statusLabel(j: JobEntity) = when (j.status) {
    JobStatus.QUEUED -> "Queued"
    JobStatus.RUNNING -> Stage.label(j.stage)
    JobStatus.WAITING -> "Scheduled"
    JobStatus.PAUSED -> "Paused"
    JobStatus.FAILED -> "Needs attention"
    JobStatus.DONE -> if (j.ytUrl.isNotBlank() || j.fbUrl.isNotBlank()) "Published" else "Ready"
    JobStatus.CANCELLED -> "Cancelled"
    else -> j.status
}

@Composable
fun statusColor(j: JobEntity): Color {
    val c = Lofi.c
    return when (j.status) {
        JobStatus.RUNNING -> c.sky
        JobStatus.DONE -> c.ok
        JobStatus.FAILED -> c.crit
        JobStatus.PAUSED, JobStatus.WAITING -> c.warn
        else -> c.faint
    }
}

fun fmtDuration(s: Double): String {
    val t = s.toInt()
    val h = t / 3600; val m = t % 3600 / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun fmtWhen(ms: Long): String = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
fun Thumb(job: JobEntity, modifier: Modifier = Modifier) {
    val c = Lofi.c
    val file = job.thumbPath.takeIf { it.isNotBlank() && File(it).exists() }
    Box(modifier.clip(RoundedCornerShape(14.dp)).background(c.surfaceHi), contentAlignment = Alignment.Center) {
        val model: Any? = file?.let { File(it) } ?: job.imageUri.takeIf { it.isNotBlank() }
        if (model != null) AsyncImage(model, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        else Text("♪", color = c.faint, fontSize = 20.sp)
    }
}

@Composable
fun JobCard(job: JobEntity, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Lofi.c
    GlassCard(modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (job.status == JobStatus.RUNNING) {
                ProgressRing(job.progress, Modifier.size(64.dp), stroke = 5.dp) {
                    Text("${(job.progress * 100).toInt()}%", color = c.ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            } else {
                Thumb(job, Modifier.size(width = 96.dp, height = 60.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(job.title.ifBlank { "Video ${"%04d".format(job.id)}" }, color = c.ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(statusColor(job), pulsing = job.status == JobStatus.RUNNING, size = 7.dp)
                    Text(statusLabel(job), color = statusColor(job), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (job.durationS > 0) Text("  ·  ${fmtDuration(job.durationS)}", color = c.faint, fontSize = 12.sp)
                }
                val sub = when {
                    job.status == JobStatus.FAILED -> job.error
                    job.status == JobStatus.RUNNING || job.status == JobStatus.WAITING -> job.phase
                    job.status == JobStatus.DONE -> job.phase
                    else -> ""
                }
                if (sub.isNotBlank()) Text(sub, color = if (job.status == JobStatus.FAILED) c.crit else c.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun StatTile(label: String, value: Int, modifier: Modifier = Modifier, accent: Color) {
    val c = Lofi.c
    GlassCard(modifier, corner = 18.dp) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            com.lofiautopilot.studio.ui.components.AnimatedCount(value, androidx.compose.ui.text.TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Bold), accent)
            Text(label, color = c.muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun Gap(h: Int = 12) = androidx.compose.foundation.layout.Spacer(Modifier.height(h.dp))

@Composable
fun GapW(w: Int = 12) = androidx.compose.foundation.layout.Spacer(Modifier.width(w.dp))
