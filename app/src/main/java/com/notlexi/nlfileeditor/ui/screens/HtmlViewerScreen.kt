package com.notlexi.nlfileeditor.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notlexi.nlfileeditor.model.UiEvent
import com.notlexi.nlfileeditor.ui.GradientButton
import com.notlexi.nlfileeditor.ui.GradientPill
import com.notlexi.nlfileeditor.ui.InfoButton
import com.notlexi.nlfileeditor.ui.InfoSheet
import com.notlexi.nlfileeditor.ui.SlidingTabs
import com.notlexi.nlfileeditor.ui.bounceClick
import com.notlexi.nlfileeditor.ui.theme.NLColors
import com.notlexi.nlfileeditor.util.DocTreeResolver
import com.notlexi.nlfileeditor.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * HTML Viewer: upload a local .html/.htm file, edit its source in the "Code" tab (Save writes a
 * copy through the system "Create document" dialog, like the main editor) and see it rendered in
 * the "View" tab. "Hide" collapses the tabs and the content into a single lock icon; tapping the
 * lock brings everything back exactly as it was.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlViewerScreen(vm: MainViewModel) {
    val doc by vm.htmlDoc.collectAsStateWithLifecycle()
    val dirty by vm.htmlDirty.collectAsStateWithLifecycle()
    val saving by vm.htmlSaving.collectAsStateWithLifecycle()
    val tree by vm.htmlTree.collectAsStateWithLifecycle()
    // The View tab (and its WebView) is created the first time it is opened, then kept alive.
    var viewVisited by rememberSaveable { mutableStateOf(false) }
    // The View tab renders a snapshot taken when it is opened, so typing in the Code tab never reloads the page.
    var viewHtml by remember(doc?.loadId) { mutableStateOf(vm.htmlDraft) }
    val snackbar = remember { SnackbarHostState() }
    val scheme = MaterialTheme.colorScheme

    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showDiscard by remember { mutableStateOf(false) }
    var hidden by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(0) } // 0 = Code, 1 = View

    // These live ABOVE the hide switch, so hiding and showing again keeps cursor, scroll and page position.
    val codeScroll = rememberScrollState()
    val viewScrollY = remember { IntArray(1) }
    var field by remember(doc?.loadId) { mutableStateOf(TextFieldValue(vm.htmlDraft)) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onHtmlPicked(uri)
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.setHtmlTree(uri)
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        vm.onHtmlSaveLocation(uri)
    }
    LaunchedEffect(Unit) {
        launch { vm.events.collect { if (it is UiEvent.Message) snackbar.showSnackbar(it.text) } }
        vm.htmlSaveRequests.collect { saveLauncher.launch(it) }
    }

    fun tryClose() {
        if (dirty) showDiscard = true else vm.back()
    }
    BackHandler { tryClose() }

    Scaffold(
        containerColor = scheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("HTML Viewer", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        // The file name is hidden together with the content.
                        if (doc != null && !hidden) {
                            Text(
                                doc?.name.orEmpty() + if (dirty) " · unsaved changes" else "",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = scheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { tryClose() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Same About panel as everywhere else: only the trigger is repeated here.
                    InfoButton(onClick = { showInfo = true }, modifier = Modifier.padding(end = 8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            if (doc == null) {
                EmptyHtmlState(onUpload = { picker.launch(arrayOf("*/*")) })
            } else {
                // ---- tab bar + Hide, or just the lock when hidden ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hidden) {
                        LockButton(onClick = { hidden = false })
                    } else {
                        SlidingTabs(
                            options = listOf("Code", "View"),
                            selectedIndex = tab,
                            onSelect = { tab = it; if (it == 1) { viewHtml = vm.htmlDraft; viewVisited = true } },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(10.dp))
                        FolderButton(granted = tree != null, onClick = { treePicker.launch(null) })
                        Spacer(Modifier.width(8.dp))
                        HideButton(onClick = { hidden = true })
                    }
                }

                // Local images / CSS / fonts need the page's folder: say so instead of silently showing a broken page.
                val needsFolder = remember(doc?.loadId) { DocTreeResolver.usesRelativeResources(vm.htmlDraft) }
                if (!hidden && needsFolder && tree == null) {
                    Text(
                        "This page uses local files (images, CSS, fonts). Tap the folder button and choose the folder this HTML file is in.",
                        color = NLColors.Blue, fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                    )
                }

                AnimatedVisibility(
                    visible = !hidden,
                    enter = expandVertically(tween(240)) + fadeIn(tween(240)),
                    exit = shrinkVertically(tween(200)) + fadeOut(tween(160)),
                    modifier = Modifier.weight(1f)
                ) {
                    // Both layers stay composed: switching tabs never rebuilds (and reloads) the WebView.
                    Box(Modifier.fillMaxSize()) {
                        if (viewVisited) ViewTab(html = viewHtml, scrollY = viewScrollY, tree = tree)
                        AnimatedVisibility(
                            visible = tab == 0,
                            enter = fadeIn(tween(160)),
                            exit = fadeOut(tween(160))
                        ) {
                            Box(Modifier.fillMaxSize().background(scheme.background)) {
                                CodeTab(
                                    field = field,
                                    scroll = codeScroll,
                                    dirty = dirty,
                                    saving = saving,
                                    onChange = { value ->
                                        val textChanged = value.text != field.text
                                        field = value
                                        if (textChanged) vm.onHtmlEdited(value.text)
                                    },
                                    onSave = vm::requestHtmlSave
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showInfo) InfoSheet(onDismiss = { showInfo = false })

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes to this HTML file. If you leave now they will be lost.") },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; vm.back() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("Keep editing") }
            },
            shape = MaterialTheme.shapes.large
        )
    }
}

@Composable
private fun EmptyHtmlState(onUpload: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(96.dp).clip(CircleShape).background(NLColors.Blue.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Code, contentDescription = null, tint = NLColors.Blue, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text("Open an HTML file", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Edit its source code and see how it looks, all offline on your phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                fontSize = 15.sp
            )
            Spacer(Modifier.height(28.dp))
            GradientButton(text = "Upload HTML", icon = Icons.Rounded.CloudUpload, onClick = onUpload, glow = true)
        }
    }
}

/** Small "Hide" control next to the tabs. */
@Composable
private fun HideButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .height(40.dp)
            .bounceClick(onClick = onClick)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, NLColors.gradient, shape)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.VisibilityOff, contentDescription = null, tint = NLColors.Blue, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Hide", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Grants the folder the HTML file lives in, so its relative images / CSS / fonts load. */
@Composable
private fun FolderButton(granted: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .bounceClick(onClick = onClick)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, if (granted) NLColors.Success else NLColors.Blue, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Rounded.FolderOpen,
            contentDescription = if (granted) "Resource folder allowed (tap to change)" else "Allow the page's folder",
            tint = if (granted) NLColors.Success else NLColors.Blue,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The only thing left on screen while hidden. */
@Composable
private fun LockButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .bounceClick(onClick = onClick)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
            .border(1.5.dp, NLColors.gradient, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = "Show", tint = NLColors.Blue, modifier = Modifier.size(22.dp))
    }
}

// ====================================================================== Code tab

@Composable
private fun CodeTab(
    field: TextFieldValue,
    scroll: androidx.compose.foundation.ScrollState,
    dirty: Boolean,
    saving: Boolean,
    onChange: (TextFieldValue) -> Unit,
    onSave: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (dirty) "Unsaved changes" else "Source code",
                fontSize = 13.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (saving) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            } else {
                GradientPill(text = "Save", icon = Icons.Rounded.Save, enabled = dirty, onClick = onSave)
            }
        }
        val shape = RoundedCornerShape(16.dp)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp)
                .clip(shape)
                .background(scheme.surface)
                .border(1.dp, scheme.outline.copy(alpha = 0.6f), shape)
        ) {
            BasicTextField(
                value = field,
                onValueChange = onChange,
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = scheme.onSurface
                ),
                cursorBrush = SolidColor(NLColors.Blue),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(12.dp)
            )
        }
    }
}

// ====================================================================== View tab

/**
 * Renders the HTML like a normal browser.
 *
 * What v5 did wrong: it blocked all network loads, had no INTERNET permission, loaded the page with a
 * null base URL (so every relative path was unresolvable) and switched JavaScript off unless the page
 * contained a literal <script>. Logos, icons, images, web fonts and CSS backgrounds therefore never appeared.
 *
 * Now:
 *  - HTML5 / CSS3 / JavaScript / DOM storage are on; images load; mixed content is allowed in
 *    compatibility mode (so http:// images inside https pages still show); forced dark mode is off.
 *  - The page is loaded from the virtual origin [DocTreeResolver.BASE_URL]; relative URLs are answered
 *    from the folder the user granted (see [DocTreeResolver]). Inline SVG, Base64 data: URLs and
 *    internet resources work natively.
 *  - Still safe: no file:// access, no content:// access, no JavaScript bridge objects, no multiple
 *    windows, third-party cookies off, Safe Browsing on, and external links open in the user's browser
 *    instead of navigating inside the viewer.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ViewTab(html: String, scrollY: IntArray, tree: Uri?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val resolver = remember(tree) { tree?.let { DocTreeResolver(context.contentResolver, it) } }
    // The WebViewClient is created once per WebView, so it reads the resolver through this holder.
    val resolverRef = remember { arrayOf<DocTreeResolver?>(null) }
    resolverRef[0] = resolver

    Box(
        Modifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(AndroidColor.WHITE)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = true
                        blockNetworkImage = false
                        blockNetworkLoads = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        allowFileAccess = false
                        allowContentAccess = false
                        javaScriptCanOpenWindowsAutomatically = false
                        setSupportMultipleWindows(false)
                        setGeolocationEnabled(false)
                        mediaPlaybackRequiresUserGesture = true
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        if (Build.VERSION.SDK_INT >= 29) {
                            @Suppress("DEPRECATION")
                            forceDark = WebSettings.FORCE_DARK_OFF
                        }
                        if (Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = true
                    }
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                            val url = request.url
                            if (url.host != DocTreeResolver.HOST) return null // internet: normal loading
                            val r = resolverRef[0]
                            val path = url.path.orEmpty()
                            val hit = if (r != null && path.length > 1) r.open(path) else null
                            return hit ?: WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), "".byteInputStream())
                        }

                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url
                            val s = url.toString()
                            if (s.startsWith("about:blank") || s.startsWith("data:")) return false
                            // Same-page anchors and links to other local pages stay inside the viewer.
                            if (url.host == DocTreeResolver.HOST) return false
                            if (url.scheme == "http" || url.scheme == "https" || url.scheme == "mailto" || url.scheme == "tel") {
                                try {
                                    view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                } catch (_: Exception) {
                                }
                            }
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {}

                        override fun onPageFinished(view: WebView, url: String?) {
                            val y = scrollY[0]
                            if (y > 0) view.postDelayed({ view.scrollTo(0, y) }, 80)
                        }
                    }
                }
            },
            update = { web ->
                val key = html.hashCode().toString() + "|" + (tree?.toString() ?: "")
                if (web.tag != key) {
                    web.tag = key
                    web.loadDataWithBaseURL(DocTreeResolver.BASE_URL, html, "text/html", "UTF-8", null)
                }
            },
            onRelease = { web ->
                scrollY[0] = web.scrollY // remembered, so Hide/Show or switching tabs returns to the same spot
                web.stopLoading()
                web.destroy()
            }
        )
    }
}
