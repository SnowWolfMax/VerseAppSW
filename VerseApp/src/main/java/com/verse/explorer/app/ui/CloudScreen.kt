package com.verse.explorer.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.verse.explorer.app.data.cloud.CloudAccount
import com.verse.explorer.app.data.cloud.CloudApi
import com.verse.explorer.app.data.cloud.CloudArchive
import com.verse.explorer.app.data.cloud.CloudException
import com.verse.explorer.app.data.cloud.CloudFileItem
import com.verse.explorer.app.data.cloud.CloudOverview
import com.verse.explorer.app.data.cloud.CloudSession
import com.verse.explorer.app.ui.components.PcButton
import com.verse.explorer.app.ui.components.PcButtonVariant
import com.verse.explorer.app.ui.theme.VerseColors
import com.verse.explorer.app.ui.theme.cardColor
import com.verse.explorer.app.ui.theme.onCardColor
import com.verse.explorer.core.config.GameConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * Verse 云盘（手机端）。
 *
 * 与桌面端同一套服务：登录用爱发电 UID + 皮肤站密码，文件是以版本为单位打包的存档 zip。
 * 上传 = 把所选版本的 saves/ 打包上传；下载 = 下载 zip 并还原到所选版本的 saves/。
 * 与桌面端唯一的差异：同名世界不覆盖，自动改名 `名字 (2)`。
 */

private const val BUY_URL = "https://afdian.com/item/143beddcc5fa11f189925254001e7c00"
private const val SKIN_URL = "https://skin.verselauncher.cn"

private data class ProgressState(val label: String, val fraction: Float?)

@Composable
fun CloudScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf(CloudSession.load(context)) }
    var overview by remember { mutableStateOf<CloudOverview?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ProgressState?>(null) }

    // 登录表单
    var uid by remember { mutableStateOf(account?.uid.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by remember { mutableStateOf("") }

    // 已安装版本（有 <id>.json 才算装好）
    var versions by remember { mutableStateOf<List<String>>(emptyList()) }

    // 弹窗状态
    var showUploadPicker by remember { mutableStateOf(false) }
    var downloadFile by remember { mutableStateOf<CloudFileItem?>(null) }
    var confirmDownload by remember { mutableStateOf<Pair<CloudFileItem, String>?>(null) }
    var deleteFile by remember { mutableStateOf<CloudFileItem?>(null) }
    var showRedeem by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        versions = withContext(Dispatchers.IO) {
            val dir = GameConfig.getVersionsDir(GameConfig.getGameDir(context))
            dir.listFiles()
                ?.filter { it.isDirectory && File(it, "${it.name}.json").exists() }
                ?.map { it.name }
                ?.sorted()
                ?: emptyList()
        }
    }

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast(context, "无法打开链接") }
    }

    fun doLogin() {
        val id = uid.trim()
        if (id.isBlank() || password.isBlank()) {
            loginError = "请输入 UID 和密码"
            return
        }
        scope.launch {
            loginBusy = true
            loginError = ""
            try {
                val acc = CloudApi.login(id, password)
                CloudSession.save(context, acc)
                account = acc
                password = ""
                toast(context, "登录成功，欢迎 ${acc.name.ifBlank { acc.uid }}")
                runCatching { overview = CloudApi.overview(acc.token) }
            } catch (e: Exception) {
                loginError = e.message ?: "登录失败"
            } finally {
                loginBusy = false
            }
        }
    }

    fun doLogout() {
        CloudSession.clear(context)
        account = null
        overview = null
        progress = null
    }

    fun refresh() {
        val acc = account ?: return
        scope.launch {
            loading = true
            try {
                overview = CloudApi.overview(acc.token)
            } catch (e: CloudException) {
                if (e.status == 401) {
                    doLogout()
                    toast(context, "登录已过期，请重新登录")
                } else {
                    toast(context, e.message ?: "刷新失败")
                }
            } catch (e: Exception) {
                toast(context, "云盘服务无法连接：${e.message ?: "未知错误"}")
            } finally {
                loading = false
            }
        }
    }

    // 首次进入已登录状态时拉一次概览
    LaunchedEffect(account?.token) {
        if (account != null && overview == null) refresh()
    }

    fun doUpload(versionId: String) {
        val acc = account ?: return
        if (busy) return
        scope.launch {
            busy = true
            var tempZip: File? = null
            try {
                progress = ProgressState("正在打包存档...", null)
                val packed = withContext(Dispatchers.IO) { CloudArchive.pack(context, versionId) }
                tempZip = packed.file
                overview?.freeBytes?.let { free ->
                    if (packed.size > free) {
                        throw CloudException(
                            "存档压缩后 ${fmtSize(packed.size)}，超过云盘剩余空间 ${fmtSize(free)}，" +
                                "请先删除云盘文件或扩容"
                        )
                    }
                }
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                val name = "$versionId-存档-$stamp.zip"
                progress = ProgressState("准备上传...", 0f)
                CloudApi.upload(acc.token, packed.file, name) { sent, total ->
                    val fraction = if (total > 0) sent.toFloat() / total.toFloat() else null
                    progress = ProgressState("上传中 ${fmtSize(sent)} / ${fmtSize(total)}", fraction)
                }
                progress = ProgressState("上传完成", 1f)
                toast(context, "上传成功：$name")
                runCatching { overview = CloudApi.overview(acc.token) }
            } catch (e: Exception) {
                progress = null
                toast(context, "上传失败：${e.message ?: "未知错误"}")
            } finally {
                withContext(Dispatchers.IO) { tempZip?.delete() }
                busy = false
                delay(700)
                progress = null
            }
        }
    }

    fun doDownload(file: CloudFileItem, versionId: String) {
        val acc = account ?: return
        if (busy) return
        scope.launch {
            busy = true
            var tempZip: File? = null
            try {
                progress = ProgressState("准备下载...", 0f)
                val target = withContext(Dispatchers.IO) {
                    File(context.cacheDir, "verse-cloud-dl-${System.currentTimeMillis()}.zip")
                }
                tempZip = target
                CloudApi.download(acc.token, file.id, target) { got, total ->
                    val fraction = if (total > 0) got.toFloat() / total.toFloat() else null
                    val label =
                        if (total > 0) "下载中 ${fmtSize(got)} / ${fmtSize(total)}" else "下载中 ${fmtSize(got)}"
                    progress = ProgressState(label, fraction)
                }
                progress = ProgressState("正在解压到版本存档...", null)
                val worlds = withContext(Dispatchers.IO) {
                    CloudArchive.restore(context, versionId, target)
                }
                progress = ProgressState("还原完成", 1f)
                toast(
                    context,
                    "已还原到 $versionId" + if (worlds.isNotEmpty()) "：${worlds.joinToString("、")}" else ""
                )
                runCatching { overview = CloudApi.overview(acc.token) }
            } catch (e: Exception) {
                progress = null
                toast(context, "下载失败：${e.message ?: "未知错误"}")
            } finally {
                withContext(Dispatchers.IO) { tempZip?.delete() }
                busy = false
                delay(700)
                progress = null
            }
        }
    }

    fun doDelete(file: CloudFileItem) {
        val acc = account ?: return
        scope.launch {
            try {
                val msg = CloudApi.delete(acc.token, file.id)
                toast(context, msg)
                overview = CloudApi.overview(acc.token)
            } catch (e: Exception) {
                toast(context, "删除失败：${e.message ?: "未知错误"}")
            }
        }
    }

    fun doRedeem(orderNo: String) {
        val acc = account ?: return
        scope.launch {
            try {
                val msg = CloudApi.redeem(acc.token, orderNo)
                toast(context, msg)
                overview = CloudApi.overview(acc.token)
            } catch (e: Exception) {
                toast(context, "兑换失败：${e.message ?: "未知错误"}")
            }
        }
    }

    val files = overview?.files.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Verse云盘",
                    style = MaterialTheme.typography.titleLarge,
                    color = VerseColors.PcTextPrimary
                )
                Text(
                    text = "把版本存档备份到云端，换机、重装都能直接取回",
                    fontSize = 12.sp,
                    color = VerseColors.PcTextMuted,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (account == null) {
            item {
                LoginCard(
                    uid = uid,
                    password = password,
                    busy = loginBusy,
                    error = loginError,
                    onUidChange = { uid = it; loginError = "" },
                    onPasswordChange = { password = it; loginError = "" },
                    onLogin = { doLogin() },
                    onOpenSkin = { openUrl(SKIN_URL) }
                )
            }
        } else {
            item {
                CapacityCard(
                    overview = overview,
                    loading = loading,
                    account = account!!,
                    onBuy = { openUrl(BUY_URL) }
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcButton(
                            text = "上传存档",
                            onClick = { showUploadPicker = true },
                            variant = PcButtonVariant.PRIMARY,
                            enabled = !busy
                        )
                        PcButton(
                            text = "刷新",
                            onClick = { refresh() },
                            variant = PcButtonVariant.OUTLINE,
                            enabled = !loading && !busy
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PcButton(
                            text = "兑换",
                            onClick = { showRedeem = true },
                            variant = PcButtonVariant.OUTLINE,
                            enabled = !busy
                        )
                        PcButton(
                            text = "购买套餐",
                            onClick = { openUrl(BUY_URL) },
                            variant = PcButtonVariant.SECONDARY
                        )
                        PcButton(
                            text = "退出登录",
                            onClick = { doLogout() },
                            variant = PcButtonVariant.GHOST,
                            enabled = !busy
                        )
                    }
                }
            }

            progress?.let { p ->
                item { ProgressCard(p) }
            }

            if (files.isNotEmpty()) {
                item {
                    Text(
                        text = "文件（${files.size} 个）",
                        fontSize = 12.sp,
                        color = VerseColors.PcTextMuted
                    )
                }
            }

            if (files.isEmpty() && !loading) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = VerseColors.PcBgCard,
                        border = BorderStroke(1.dp, VerseColors.PcBorder)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("云盘还是空的", fontSize = 13.sp, color = VerseColors.PcTextSecondary)
                            Text(
                                "点上方「上传存档」，把某个版本的存档备份上来",
                                fontSize = 11.sp,
                                color = VerseColors.PcTextMuted,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            items(files, key = { it.id }) { file ->
                CloudFileRow(
                    file = file,
                    enabled = !busy,
                    onDownload = { downloadFile = file },
                    onDelete = { deleteFile = file }
                )
            }
        }
    }

    // ---------- 上传：选版本 ----------
    if (showUploadPicker) {
        val uploadable = remember(versions) {
            versions.filter { CloudArchive.savesDir(context, it).isDirectory }
        }
        VersionPickerDialog(
            title = "选择要上传存档的版本",
            versions = uploadable,
            emptyHint = "没有找到带存档的已安装版本",
            onSelect = {
                showUploadPicker = false
                doUpload(it)
            },
            onDismiss = { showUploadPicker = false }
        )
    }

    // ---------- 下载：选目标版本 ----------
    downloadFile?.let { file ->
        VersionPickerDialog(
            title = "把「${file.name}」还原到哪个版本？",
            versions = versions,
            emptyHint = "还没有已安装的版本",
            onSelect = {
                downloadFile = null
                confirmDownload = file to it
            },
            onDismiss = { downloadFile = null }
        )
    }

    // ---------- 下载：二次确认 ----------
    confirmDownload?.let { (file, versionId) ->
        AlertDialog(
            onDismissRequest = { confirmDownload = null },
            containerColor = cardColor(),
            titleContentColor = onCardColor(),
            shape = MaterialTheme.shapes.large,
            title = { Text("确认还原存档", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "把「${file.name}」解压到版本「$versionId」的 saves 目录。\n" +
                        "同名世界不会覆盖，会自动改名为「名字 (2)」。",
                    fontSize = 13.sp,
                    color = VerseColors.PcTextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = null
                    doDownload(file, versionId)
                }) { Text("开始还原") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = null }) { Text("取消") }
            }
        )
    }

    // ---------- 删除确认 ----------
    deleteFile?.let { file ->
        AlertDialog(
            onDismissRequest = { deleteFile = null },
            containerColor = cardColor(),
            titleContentColor = onCardColor(),
            shape = MaterialTheme.shapes.large,
            title = { Text("删除云盘文件", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "确定删除「${file.name}」？删除后不可恢复。",
                    fontSize = 13.sp,
                    color = VerseColors.PcTextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleteFile = null
                    doDelete(file)
                }) { Text("删除", color = VerseColors.PcRed) }
            },
            dismissButton = {
                TextButton(onClick = { deleteFile = null }) { Text("取消") }
            }
        )
    }

    // ---------- 兑换 ----------
    if (showRedeem) {
        var orderNo by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showRedeem = false },
            containerColor = cardColor(),
            titleContentColor = onCardColor(),
            shape = MaterialTheme.shapes.large,
            title = { Text("兑换套餐", style = MaterialTheme.typography.titleLarge) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = orderNo,
                        onValueChange = { orderNo = it },
                        singleLine = true,
                        label = { Text("爱发电订单号") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "用下单的爱发电账号登录，粘贴订单号即可到账（¥3=1G，¥13.88=5G，30 天有效）",
                        fontSize = 11.sp,
                        color = VerseColors.PcTextMuted
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = orderNo.isNotBlank(),
                    onClick = {
                        showRedeem = false
                        doRedeem(orderNo.trim())
                    }
                ) { Text("确认兑换") }
            },
            dismissButton = {
                TextButton(onClick = { showRedeem = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun LoginCard(
    uid: String,
    password: String,
    busy: Boolean,
    error: String,
    onUidChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLogin: () -> Unit,
    onOpenSkin: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = VerseColors.PcBgCard,
        border = BorderStroke(1.dp, VerseColors.PcBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                "登录云盘",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = VerseColors.PcTextPrimary
            )
            Text(
                "账号 = 爱发电数字 UID　密码 = 皮肤站密码",
                fontSize = 11.sp,
                color = VerseColors.PcTextMuted
            )
            Text(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onOpenSkin),
                text = "没有账号？先去皮肤站登录一次会自动注册 →",
                fontSize = 11.sp,
                color = VerseColors.PcAccent
            )
            OutlinedTextField(
                value = uid,
                onValueChange = onUidChange,
                singleLine = true,
                label = { Text("爱发电数字 UID") },
                placeholder = { Text("例如 546792942489") },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                singleLine = true,
                label = { Text("统一账号密码") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            if (error.isNotBlank()) {
                Text(error, fontSize = 11.sp, color = VerseColors.PcRed)
            }
            PcButton(
                text = if (busy) "登录中..." else "登录",
                onClick = onLogin,
                variant = PcButtonVariant.PRIMARY,
                enabled = !busy && uid.trim().isNotEmpty() && password.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun CapacityCard(
    overview: CloudOverview?,
    loading: Boolean,
    account: CloudAccount,
    onBuy: () -> Unit
) {
    val used = overview?.usedBytes ?: 0L
    val cap = overview?.capacityBytes ?: 0L
    val pct = if (cap > 0) (used.toFloat() / cap.toFloat()).coerceIn(0f, 1f) else 0f
    val barColor = when {
        pct >= 0.95f -> VerseColors.PcRed
        pct >= 0.80f -> VerseColors.PcOrange
        else -> VerseColors.PcAccent
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = VerseColors.PcBgCard,
        border = BorderStroke(1.dp, VerseColors.PcBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = account.name.ifBlank { account.uid },
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = VerseColors.PcTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (loading) {
                    Text("刷新中...", fontSize = 11.sp, color = VerseColors.PcTextMuted)
                }
            }

            Row(verticalAlignment = Alignment.Bottom) {
                Text(fmtSize(used), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = VerseColors.PcTextPrimary)
                Text(
                    " / ${if (cap > 0) fmtSize(cap) else "--"}",
                    fontSize = 13.sp,
                    color = VerseColors.PcTextMuted,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (cap > 0) "已用 " + String.format(Locale.US, "%.1f", pct * 100) + "%" else "",
                    fontSize = 11.sp,
                    color = if (pct >= 0.80f) barColor else VerseColors.PcTextMuted
                )
            }

            LinearProgressIndicator(
                progress = { pct },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = barColor,
                trackColor = VerseColors.PcBgTertiary
            )

            val orders = overview?.orders.orEmpty()
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (orders.isEmpty()) {
                    OrderChip("免费版 100MB", null)
                } else {
                    orders.forEach { order ->
                        val msLeft = order.expiresAt - System.currentTimeMillis()
                        if (msLeft <= 0) {
                            OrderChip("+${order.gb}G · 已过期", VerseColors.PcRed)
                        } else {
                            OrderChip("+${order.gb}G · 剩 ${ceil(msLeft / 86400000.0).toInt()} 天", null)
                        }
                    }
                }
            }

            val pool = overview?.poolRemainingBytes ?: 0L
            if (pool > 0) {
                Text(
                    "全网剩余可兑换空间 ${fmtSize(pool)}（总池 ${fmtSize(overview?.poolBytes ?: 0L)}）",
                    fontSize = 10.sp,
                    color = VerseColors.PcTextMuted
                )
            }

            Text(
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onBuy),
                text = "容量不够？点这里扩容 →",
                fontSize = 11.sp,
                color = VerseColors.PcAccent
            )
        }
    }
}

@Composable
private fun OrderChip(text: String, color: androidx.compose.ui.graphics.Color?) {
    Surface(
        shape = RoundedCornerShape(50),
        color = VerseColors.PcBgTertiary,
        border = BorderStroke(1.dp, VerseColors.PcBorder)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            fontSize = 10.sp,
            color = color ?: VerseColors.PcTextSecondary
        )
    }
}

@Composable
private fun ProgressCard(state: ProgressState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = VerseColors.PcBgCard,
        border = BorderStroke(1.dp, VerseColors.PcBorder)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = state.label,
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                    color = VerseColors.PcTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                state.fraction?.let {
                    Text("${(it * 100).toInt()}%", fontSize = 11.sp, color = VerseColors.PcTextMuted)
                }
            }
            val fraction = state.fraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = VerseColors.PcAccent,
                    trackColor = VerseColors.PcBgTertiary
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                    color = VerseColors.PcAccent,
                    trackColor = VerseColors.PcBgTertiary
                )
            }
        }
    }
}

@Composable
private fun CloudFileRow(
    file: CloudFileItem,
    enabled: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = VerseColors.PcBgCard,
        border = BorderStroke(1.dp, VerseColors.PcBorder)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    fontSize = 13.sp,
                    color = VerseColors.PcTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${fmtSize(file.size)} · ${fmtTime(file.uploadedAt)}",
                    fontSize = 10.sp,
                    color = VerseColors.PcTextMuted
                )
            }
            RowAction("下载", enabled, onDownload)
            RowAction("删除", enabled, onDelete, color = VerseColors.PcOrange)
        }
    }
}

@Composable
private fun RowAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    color: androidx.compose.ui.graphics.Color = VerseColors.PcAccent
) {
    Text(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        text = text,
        fontSize = 12.sp,
        color = if (enabled) color else VerseColors.PcTextMuted
    )
}

@Composable
private fun VersionPickerDialog(
    title: String,
    versions: List<String>,
    emptyHint: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = cardColor(),
        titleContentColor = onCardColor(),
        shape = MaterialTheme.shapes.large,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            if (versions.isEmpty()) {
                Text(emptyHint, fontSize = 13.sp, color = VerseColors.PcTextMuted)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(versions, key = { it }) { version ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable {
                                onSelect(version)
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = VerseColors.PcBgTertiary
                        ) {
                            Text(
                                text = version,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                fontSize = 13.sp,
                                color = VerseColors.PcTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ---------------- 格式化 ----------------

private fun fmtSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

private fun fmtTime(ts: Long): String =
    if (ts <= 0) "--" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
