package com.tinydavid.djiwaypointmission.ui.media_manager.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.tinydavid.djiwaypointmission.databinding.ListItemMediaFileBinding
import dji.sdk.media.MediaFile

class FileListAdapter(
    private val onThumbnailClick: (MediaFile) -> Unit,
    private val onFileClick: (MediaFile) -> Unit,
) :
    RecyclerView.Adapter<FileViewHolder>() {

    private var _mediaFiles = listOf<MediaFile>()

    //returns the number of items in the adapter's data set list
    override fun getItemCount(): Int = _mediaFiles.size

    //inflates an item view and creates a ViewHolder to wrap it
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = ListItemMediaFileBinding
            //item view layout defined in media_info_item.xml
            .inflate(LayoutInflater.from(parent.context), parent, false)
        return FileViewHolder(view, onFileClick, onThumbnailClick)
    }

    /*
    Binds a ViewHolder in the recyclerView to a MediaFile object in the mediaFileList.
    The UI of the ViewHolder is changed to display the MediaFile's data.
    */
    override fun onBindViewHolder(mFileViewHolder: FileViewHolder, index: Int) {
        val mediaFile: MediaFile = _mediaFiles[index]

        mFileViewHolder.bind(mediaFile, index)
    }


    fun setMediaFiles(mediaFiles: List<MediaFile>) {
        _mediaFiles = mediaFiles
        notifyDataSetChanged()
    }
}