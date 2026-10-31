package org.webgpt.app.webview

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import org.webgpt.app.R

/**
 * RecyclerView adapter for the "Downloader" tab of [WebViewManagerDialog].
 *
 * One row per [WebViewSource]: the source name, a short URL, and a "Download"
 * button that runs the source's `onDownload` callback (which opens the
 * releases page in the browser).
 */
class WebViewDownloaderAdapter(
    private val context: Context,
    private val sources: Array<WebViewSource>
) : RecyclerView.Adapter<WebViewDownloaderAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_webview_download, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val src = sources[position]
        holder.txtName.text = src.name
        holder.txtSource.text = src.source
        holder.btnDownload.setOnClickListener(src.onDownload)
        holder.btnDownload.isEnabled = src.supported
        holder.btnDownload.text = context.getString(if (src.supported) R.string.download else R.string.unsupported)
    }

    override fun getItemCount(): Int = sources.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txt_webview_name)
        val txtSource: TextView = view.findViewById(R.id.txt_webview_source)
        val btnDownload: MaterialButton = view.findViewById(R.id.btn_download)
    }
}
