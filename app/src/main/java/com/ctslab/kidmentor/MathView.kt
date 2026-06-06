package com.ctslab.kidmentor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.webkit.WebView
import androidx.core.content.ContextCompat

/**
 * Kid Mentor math worksheet — renders a LaTeX formula with KaTeX inside a WebView
 * (from the Claude Design handoff: MathBlock). KaTeX is bundled locally under
 * assets/katex/ so formulas render fully offline; falls back to raw text on error.
 *
 * Set the formula with [setLatex] or the `app:km_latex` / `app:km_label` XML attrs.
 */
@SuppressLint("SetJavaScriptEnabled")
class MathView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : WebView(context, attrs, defStyleAttr) {

    private val textColorHex: String =
        String.format("#%06X", 0xFFFFFF and ContextCompat.getColor(context, R.color.km_math_text))

    init {
        settings.javaScriptEnabled = true
        settings.allowFileAccess = true
        setBackgroundColor(Color.TRANSPARENT)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false

        var latex = "2 + 3 = 5"
        var label: String? = null
        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.MathView)
            a.getString(R.styleable.MathView_km_latex)?.let { v -> latex = v }
            label = a.getString(R.styleable.MathView_km_label)
            a.recycle()
        }
        setLatex(latex, label)
    }

    /** Render a LaTeX [latex] formula, with an optional small uppercase [label]. */
    fun setLatex(latex: String, label: String? = null) {
        val safe = latex.replace("\\", "\\\\").replace("\"", "\\\"")
        val labelHtml = if (label.isNullOrBlank()) "" else
            "<div class=\"lbl\">${label.uppercase()}</div>"
        val html = """
            <!DOCTYPE html><html><head>
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <link rel="stylesheet" href="katex.min.css">
            <style>
              html,body{margin:0;padding:0;background:transparent;}
              .wrap{display:flex;flex-direction:column;align-items:center;
                    justify-content:center;height:100vh;color:$textColorHex;}
              .lbl{font-family:sans-serif;font-weight:700;font-size:11px;
                   letter-spacing:.08em;text-transform:uppercase;opacity:.55;
                   margin-bottom:8px;}
              #m{font-size:30px;}
            </style></head>
            <body>
              <div class="wrap">$labelHtml<div id="m"></div></div>
              <script src="katex.min.js"></script>
              <script>
                try{katex.render("$safe",document.getElementById('m'),
                  {displayMode:true,throwOnError:false});}
                catch(e){document.getElementById('m').textContent="$safe";}
              </script>
            </body></html>
        """.trimIndent()
        loadDataWithBaseURL(
            "file:///android_asset/katex/", html, "text/html", "utf-8", null
        )
    }
}
