package com.example.spectrumanalyzer

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class FileAdapter(
    private var files: List<File>,
    private val onPlayClick: (File) -> Unit,
    private val onDeleteClick: (File) -> Unit
) : RecyclerView.Adapter<FileAdapter.FileViewHolder>() {
    var playingFile: File? = null
    inner class FileViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvFileName)
        val info: TextView = view.findViewById(R.id.tvFileInfo)
        val deleteBtn: View = view.findViewById(R.id.btnDelete)
        val playIcon: ImageView = view.findViewById(R.id.ivPlayIcon)
        fun bind(file: File) {
            name.text = file.name
            val sizeMb = String.format("%.2f MB", file.length() / (1024.0 * 1024.0))
            info.text = sizeMb

            if (playingFile == file) {
                playIcon.setImageResource(android.R.drawable.ic_media_pause)
            } else {
                playIcon.setImageResource(android.R.drawable.ic_media_play)
            }

            itemView.setOnClickListener { onPlayClick(file) }
            deleteBtn.setOnClickListener { onDeleteClick(file) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount() = files.size

    fun updateData(newFiles: List<File>) {
        this.files = newFiles
        notifyDataSetChanged()
    }
}