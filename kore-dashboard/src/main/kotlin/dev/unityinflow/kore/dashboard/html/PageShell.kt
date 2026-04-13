package dev.unityinflow.kore.dashboard.html

import kotlinx.html.FlowContent
import kotlinx.html.HTML
import kotlinx.html.body
import kotlinx.html.div
import kotlinx.html.head
import kotlinx.html.lang
import kotlinx.html.meta
import kotlinx.html.script
import kotlinx.html.style
import kotlinx.html.title
import kotlinx.html.unsafe

/**
 * Embedded `<style>` block defining all dashboard CSS classes from
 * UI-SPEC.md "CSS Delivery Strategy". Kept as a single block so we ship one
 * HTML document with no external assets — D-32 (no build step, no JS framework).
 */
private val koreEmbeddedCss =
    """
    body {
      font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
      font-size: 14px;
      color: #111827;
      background: #FFFFFF;
      margin: 0;
    }
    .kore-nav {
      background: #F5F5F5;
      padding: 16px;
      display: flex;
      align-items: center;
      gap: 16px;
      border-bottom: 1px solid #E5E7EB;
    }
    .kore-nav h1 {
      font-size: 24px;
      font-weight: 600;
      line-height: 1.2;
      margin: 0;
      color: #111827;
    }
    .kore-nav .version { color: #6B7280; font-size: 14px; }
    .kore-page {
      max-width: 1200px;
      margin: 0 auto;
      padding: 48px 16px;
      display: flex;
      flex-direction: column;
      gap: 32px;
    }
    .kore-fragment {
      background: #FFFFFF;
      border: 1px solid #E5E7EB;
      border-radius: 6px;
      padding: 24px;
    }
    .kore-section-heading {
      font-size: 18px;
      font-weight: 600;
      line-height: 1.2;
      margin: 0 0 16px 0;
      color: #111827;
    }
    .kore-table { width: 100%; border-collapse: collapse; }
    .kore-table caption {
      text-align: left;
      font-size: 12px;
      font-weight: 600;
      color: #6B7280;
      padding: 0 0 4px 0;
    }
    .kore-table th {
      font-size: 12px;
      font-weight: 600;
      background: #F5F5F5;
      padding: 4px 8px;
      text-align: left;
      border-bottom: 1px solid #E5E7EB;
    }
    .kore-table td {
      padding: 8px 8px;
      border-bottom: 1px solid #E5E7EB;
      min-height: 40px;
    }
    .kore-table tr:nth-child(even) td { background: #F5F5F5; }
    .kore-table tr:hover td { background: #F9FAFB; }
    .kore-badge {
      border-radius: 4px;
      padding: 2px 8px;
      font-size: 12px;
      font-weight: 600;
      border: 1px solid;
      display: inline-block;
    }
    .kore-badge--running { color: #2563EB; border-color: #2563EB; }
    .kore-badge--success { color: #16A34A; border-color: #16A34A; }
    .kore-badge--warning { color: #D97706; border-color: #D97706; }
    .kore-badge--error { color: #DC2626; border-color: #DC2626; }
    .kore-badge--neutral { color: #6B7280; border-color: #E5E7EB; }
    .kore-empty { text-align: center; padding: 48px 0; }
    .kore-empty h3 {
      font-size: 18px;
      font-weight: 600;
      color: #111827;
      margin: 0 0 8px 0;
    }
    .kore-empty p { font-size: 14px; color: #6B7280; margin: 0; }
    .kore-degraded {
      padding: 16px;
      color: #6B7280;
      font-style: italic;
      font-size: 14px;
    }
    .kore-mono {
      font-family: ui-monospace, "SF Mono", "Fira Code", Consolas, monospace;
      font-size: 13px;
    }
    @keyframes kore-pulse {
      0%, 100% { opacity: 1; }
      50% { opacity: 0.4; }
    }
    .kore-pulse {
      animation: kore-pulse 1.5s ease-in-out infinite;
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      background: #2563EB;
      margin-right: 6px;
      vertical-align: middle;
    }
    .kore-footer {
      text-align: center;
      padding: 24px 0;
      color: #6B7280;
      font-size: 12px;
    }
    """.trimIndent()

/**
 * Top-level dashboard page shell — full HTML document with HTMX script,
 * embedded CSS, nav bar, content slot, and footer attribution.
 *
 * @param version kore version string injected server-side (UI-SPEC.md
 *                Copywriting Contract: "v{kore.version}").
 * @param content lambda that emits the three fragment containers.
 */
fun HTML.koreDashboardPage(
    version: String = "0.0.1",
    content: FlowContent.() -> Unit,
) {
    lang = "en"
    head {
        meta(charset = "utf-8")
        meta(name = "viewport", content = "width=device-width, initial-scale=1")
        title("kore dashboard")
        // HTMX CDN — UI-SPEC.md HTMX Interaction Contract.
        script(src = "https://unpkg.com/htmx.org@1.9.12/dist/htmx.min.js") {}
        style {
            unsafe { +koreEmbeddedCss }
        }
    }
    body {
        navBar(version)
        div("kore-page") {
            content()
        }
        div("kore-footer") { +"kore · UnityInFlow" }
    }
}
