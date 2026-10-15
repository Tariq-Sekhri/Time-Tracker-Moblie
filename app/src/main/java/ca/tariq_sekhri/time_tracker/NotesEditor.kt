package ca.tariq_sekhri.time_tracker

import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/** Same rich payload as desktop; no network, scripts, or links from note content. */
class NotesEditor(context: Context) : WebView(context), SharedPreferences.OnSharedPreferenceChangeListener {
    private val prefs = context.getSharedPreferences(NotesStore.PREFS, Context.MODE_PRIVATE)
    private var displayed = prefs.getString(NotesStore.CONTENT, "") ?: ""
    private var ready = false
    init {
        setBackgroundColor(android.graphics.Color.rgb(14,16,21))
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.blockNetworkLoads = true
        addJavascriptInterface(object {
            @JavascriptInterface fun save(text: String, expected: String) { post {
                displayed = text
                val saved = NotesStore.save(context, text, expected)
                if(saved != text) { displayed = saved;render() }
            } }
        }, "NotesBridge")
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) { ready = true; render() }
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?) = true
        }
        loadDataWithBaseURL("https://notes.invalid/", HTML, "text/html", "UTF-8", null)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();prefs.registerOnSharedPreferenceChangeListener(this);refresh() }
    override fun onDetachedFromWindow() { prefs.unregisterOnSharedPreferenceChangeListener(this);super.onDetachedFromWindow() }
    override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) { if(key == NotesStore.CONTENT) post { refresh() } }
    private fun refresh() { val text = prefs.getString(NotesStore.CONTENT, "") ?: "";if(text != displayed) { displayed = text;render() } }
    private fun render() { if(ready) evaluateJavascript("loadNotes(${JSONObject.quote(displayed)})", null) }
    fun clearNotes() { displayed = NotesStore.save(context, "", displayed);render() }
    fun copyNotes() { evaluateJavascript("document.getElementById('editor').innerText") { value ->
        val text = org.json.JSONArray("[$value]").getString(0)
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Notes", text))
    } }
    companion object {
        private val HTML = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:">
            <style>body{margin:0;color:#fff;background:#0e1015;font:16px sans-serif}#toolbar{padding:8px;position:sticky;top:0;background:#0e1015}button{color:white;background:#292f3c;border:0;border-radius:6px;padding:10px;margin-right:4px}#editor{padding:16px;min-height:75vh;outline:none;overflow-wrap:anywhere}img{max-width:100%;height:auto}#editor:empty:before{content:'Write your notes here…';color:#6b7280}</style></head>
            <body><div id="toolbar"><button onmousedown="event.preventDefault()" onclick="format('bold')">Bold</button><button onmousedown="event.preventDefault()" onclick="format('italic')">Italic</button><button onmousedown="event.preventDefault()" onclick="format('insertUnorderedList')">Bullets</button></div><div id="editor" contenteditable="true" role="textbox" aria-label="Notes editor" aria-multiline="true"></div>
            <script>
            const prefix='<!--time-tracker-rich-notes-->',editor=document.getElementById('editor');let base='';
            const tags=new Set(['P','DIV','BR','STRONG','B','EM','I','U','S','UL','OL','LI','BLOCKQUOTE','H1','H2','H3','A','IMG']);
            function clean(html){const doc=new DOMParser().parseFromString(html,'text/html');function walk(n){if(n.nodeType===3)return [document.createTextNode(n.textContent)];if(n.nodeType!==1||!tags.has(n.tagName))return Array.from(n.childNodes).flatMap(walk);const el=document.createElement(n.tagName);if(n.tagName==='IMG'){const src=n.getAttribute('src')||'';if(!/^data:image\/(png|jpeg|gif|webp);base64,[a-z0-9+/]+=*$/i.test(src)||src.length>5600000)return [];el.src=src;el.alt=n.getAttribute('alt')||'Pasted image';}if(n.tagName==='A'){const href=n.getAttribute('href')||'';if(/^(https?:|mailto:)/i.test(href))el.setAttribute('href',href);}el.append(...Array.from(n.childNodes).flatMap(walk));return [el];}const root=document.createElement('div');root.append(...Array.from(doc.body.childNodes).flatMap(walk));return root.innerHTML;}
            function loadNotes(text){base=text;if(text.startsWith(prefix))editor.innerHTML=clean(text.slice(prefix.length));else{editor.innerText=text;}}
            function save(){const text=prefix+clean(editor.innerHTML),expected=base;base=text;NotesBridge.save(text,expected);}
            function format(command){editor.focus();document.execCommand(command,false,null);save();}
            editor.addEventListener('input',save);
            editor.addEventListener('paste',e=>{e.preventDefault();const html=e.clipboardData.getData('text/html'),text=e.clipboardData.getData('text/plain');if(html)document.execCommand('insertHTML',false,clean(html));else document.execCommand('insertText',false,text);save();});
            </script></body></html>
        """.trimIndent()
    }
}
