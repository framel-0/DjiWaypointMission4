package com.tinydavid.djiwaypointmission.ui.media_manager.adapter

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.tinydavid.djiwaypointmission.databinding.ListItemMediaFileBinding
import dji.sdk.media.MediaFile

class FileViewHolder(
    private val view: ListItemMediaFileBinding,
    onFileClick: (MediaFile) -> Unit,
    onThumbnailClick: (MediaFile) -> Unit
) :
    RecyclerView.ViewHolder(view.root) {

    private var mediaFilePosition: Int = 0
    private lateinit var _mediaFile: MediaFile

    fun bind(mediaFile: MediaFile, position: Int) {

        mediaFilePosition = position
        _mediaFile = mediaFile

        //If the media file is not a movie or mp4 file, then hide the itemView's fileTime TextView
        if (mediaFile.mediaType != MediaFile.MediaType.MOV && mediaFile.mediaType != MediaFile.MediaType.MP4) {
            view.fileTime.visibility = View.GONE

            /*
            If the media file is a movie or mp4 file, show the video's duration time in seconds
            on the itemView's fileTime TextView.
            */
        } else {
            view.fileTime.visibility = View.VISIBLE
            view.fileTime.text = mediaFile.durationInSeconds.toString() + " s"
        }
        //display the media file's name, type, size, and thumbnail in the itemView
        view.fileName.text = mediaFile.fileName
        view.fileType.text = mediaFile.mediaType.name
        view.fileSize.text = mediaFile.fileSize.toString() + " Bytes"
        view.fileThumbnail.setImageBitmap(mediaFile.thumbnail)


        //setting the MediaFile object as the thumbnail_img ImageView's tag
        view.fileThumbnail.tag = mediaFile

        //setting the current mediaFileList index as the itemView's tag
        itemView.tag = mediaFile

    }

    init {

        //making the thumbnail_img ImageView clickable
        view.fileThumbnail.setOnClickListener { onThumbnailClick(_mediaFile) }

        //if the itemView is clicked on...
        itemView.setOnClickListener {
            onFileClick(_mediaFile)
        }
    }

}