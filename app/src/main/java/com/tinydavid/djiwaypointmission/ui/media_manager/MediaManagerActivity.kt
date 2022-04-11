package com.tinydavid.djiwaypointmission.ui.media_manager

import android.app.ProgressDialog
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tinydavid.djiwaypointmission.DJIApplication
import com.tinydavid.djiwaypointmission.R
import com.tinydavid.djiwaypointmission.ui.media_manager.adapter.FileListAdapter
import dji.common.camera.SettingsDefinitions
import dji.common.error.DJICameraError
import dji.common.error.DJIError
import dji.common.util.CommonCallbacks
import dji.log.DJILog
import dji.sdk.camera.Camera
import dji.sdk.media.*
import dji.sdk.media.MediaManager.*
import java.io.File

class MediaManagerActivity : AppCompatActivity(), View.OnClickListener {

    private lateinit var mBackBtn: Button
    private lateinit var mDeleteBtn: Button
    private lateinit var mReloadBtn: Button
    private lateinit var mDownloadBtn: Button
    private lateinit var mStatusBtn: Button
    private lateinit var mPlayBtn: Button
    private lateinit var mResumeBtn: Button
    private lateinit var mPauseBtn: Button
    private lateinit var mStopBtn: Button
    private lateinit var mMoveToBtn: Button
    private lateinit var listView: RecyclerView
    private var mLoadingDialog: ProgressDialog? = null
    private var mDownloadDialog: ProgressDialog? = null
    private var mPushDrawerSd: SlidingDrawer? = null
    private lateinit var mDisplayImageView: ImageView
    private lateinit var mPushTv: TextView

    /*
    Notes:
    *   If the DJI product already has videos or pictures saved to its SD card, these media files can
        be accessed and interacted with using the MediaManager class.

    *   The MediaManager has a enum class called FileListState which stores the state of its file list.

    *   The file list is a list of files the MediaManager obtains from the DJI product's SD card.

    *   In this app, we use a recycler view to display previews of each media file in the MediaManager's file list.
        To do this, we have created the mediaFileList variable to locally store the data from the MediaManager's
        file list. The recycler view uses the mediaFileList as its adapter's data set.
    */

    private lateinit var mListAdapter: FileListAdapter //recycler view adapter
    private var mediaFileList: MutableList<MediaFile> =
        mutableListOf() //empty list of MediaFile objects
    private var mMediaManager: MediaManager? = null //uninitialized media manager

    //variable for the current state of the MediaManager's file list
    private var currentFileListState = FileListState.UNKNOWN

    /*
    The scheduler object can be used to queue and download small content types of media
    (previews, thumbnails, and custom data) sequentially from a series of files. The scheduler can
    also re-prioritize files during the download process.
    */
    private var scheduler: FetchMediaTaskScheduler? = null

    private var currentProgress = -1 //integer variable for the current download progress
    private var lastClickViewIndex = 0
    private var lastClickView: View? = null

    /*
    Creating a photo and video file directory on the user's mobile phone which will store the media
    files that get downloaded from the DJI product's SD card.
    */
    private lateinit var photoStorageDir: File
    private lateinit var videoStorageDir: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_media_manager)
        initUI() //initializing the UI

        /*
        getExternalFilesDir() refers to a private directory on the user's mobile device which can only be
        accessed by this app.Here we have created a pictures and videos folder within this private directory.
        */
        photoStorageDir = File(this.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!.path)
        videoStorageDir = File(this.getExternalFilesDir(Environment.DIRECTORY_MOVIES)!!.path)

    }

    //When the activity is resumed, initialize the MediaManager
    override fun onResume() {
        super.onResume()
        initMediaManager()
    }

    override fun onDestroy() {
        lastClickView = null
        mMediaManager?.let { mediaManager ->
            mediaManager.stop(null)
            mediaManager.removeFileListStateCallback(updateFileListStateListener)
            mediaManager.removeMediaUpdatedVideoPlaybackStateListener(
                updatedVideoPlaybackStateListener
            )
            mediaManager.exitMediaDownloading()
            if (scheduler != null) {
                scheduler!!.removeAllTasks()
            }
        }
        DJIApplication.getCameraInstance()?.let { camera ->
            camera.setMode(SettingsDefinitions.CameraMode.SHOOT_PHOTO) { mError ->
                if (mError != null) {
                    Log.i(TAG, "Set Shoot Photo Mode Failed" + mError.description)
                }
            }
            mediaFileList.clear()
            super.onDestroy()
        }
        mLoadingDialog?.dismiss()
        mDownloadDialog?.dismiss()
    }

    //Function used to initialize the activity's Layout views
    private fun initUI() {

        //referencing the recycler view by its resource id and giving it a LinearLayoutManager
        listView = findViewById(R.id.filelistView)
        val layoutManager =
            LinearLayoutManager(this@MediaManagerActivity, RecyclerView.VERTICAL, false)
        listView.layoutManager = layoutManager

        //Instantiating a FileListAdapter and setting it as the recycler view's adapter
        mListAdapter = FileListAdapter(::onThumbnailClick, ::onFileClick)
        listView.adapter = mListAdapter

        //Creating a ProgressDialog and configuring its behavioural settings as a loading screen
        mLoadingDialog = ProgressDialog(this@MediaManagerActivity)
        mLoadingDialog?.let { progressDialog ->
            progressDialog.setMessage("Please wait")
            progressDialog.setCanceledOnTouchOutside(false)
            progressDialog.setCancelable(false)
        }

        //Creating a ProgressDialog and configuring its behavioural settings as a download progress screen
        mDownloadDialog = ProgressDialog(this@MediaManagerActivity)
        mDownloadDialog?.let { progressDialog ->
            progressDialog.setTitle("Downloading file")
            progressDialog.setIcon(android.R.drawable.ic_dialog_info)
            progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            progressDialog.setCanceledOnTouchOutside(false)
            progressDialog.setCancelable(true)

            //If the ProgressDialog is cancelled, the MediaManager will stop the downloading process
            progressDialog.setOnCancelListener {
                mMediaManager?.exitMediaDownloading()
            }
        }

        //referencing other layout views by their resource ids
        mPushDrawerSd = findViewById(R.id.pointing_drawer_sd)
        mPushTv = findViewById(R.id.pointing_push_tv)
        mBackBtn = findViewById(R.id.back_btn)
        mDeleteBtn = findViewById(R.id.delete_btn)
        mDownloadBtn = findViewById(R.id.download_btn)
        mReloadBtn = findViewById(R.id.reload_btn)
        mStatusBtn = findViewById(R.id.status_btn)
        mPlayBtn = findViewById(R.id.play_btn)
        mResumeBtn = findViewById(R.id.resume_btn)
        mPauseBtn = findViewById(R.id.pause_btn)
        mStopBtn = findViewById(R.id.stop_btn)
        mMoveToBtn = findViewById(R.id.moveTo_btn)
        mDisplayImageView = findViewById(R.id.imageView)
        mDisplayImageView.visibility = View.VISIBLE
        mBackBtn.setOnClickListener(this)
        mDeleteBtn.setOnClickListener(this)
        mDownloadBtn.setOnClickListener(this)
        mReloadBtn.setOnClickListener(this)
        mDownloadBtn.setOnClickListener(this)
        mStatusBtn.setOnClickListener(this)
        mPlayBtn.setOnClickListener(this)
        mResumeBtn.setOnClickListener(this)
        mPauseBtn.setOnClickListener(this)
        mStopBtn.setOnClickListener(this)
        mMoveToBtn.setOnClickListener(this)
    }

    //Function used to display the loading ProgressDialog
    private fun showProgressDialog() {
        runOnUiThread { mLoadingDialog?.show() }
    }

    //Function used to dismiss the loading ProgressDialog
    private fun hideProgressDialog() {
        runOnUiThread {
            mLoadingDialog?.let { progressDialog ->
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    //Function used to display the download ProgressDialog
    private fun showDownloadProgressDialog() {
        runOnUiThread {
            mDownloadDialog?.let { progressDialog ->
                //progressDialog.incrementProgressBy(progressDialog.progress)
                progressDialog.show()
            }
        }
    }

    //Function used to dismiss the download ProgressDialog
    private fun hideDownloadProgressDialog() {
        runOnUiThread {
            mDownloadDialog?.let { progressDialog ->
                if (progressDialog.isShowing) {
                    progressDialog.dismiss()
                }
            }
        }
    }

    //Function that turns strings into toast messages displayed to the user
    private fun setResultToToast(result: String) {
        runOnUiThread {
            Toast.makeText(this@MediaManagerActivity, result, Toast.LENGTH_SHORT).show()
        }
    }

    //Function used to display a string on the mPushTv TextView
    private fun setResultToText(string: String) {
        runOnUiThread {
            mPushTv.text = string
        }
    }

    //Function used to initialize the MediaManager
    private fun initMediaManager() {
        //If there is no DJI product connected to the mobile device...
        if (DJIApplication.getProductInstance() == null) {
            //clear the mediaFileList and notify the recycler view's adapter is that its dataset has changed
            mediaFileList.clear()
            mListAdapter.notifyDataSetChanged()

            DJILog.e(TAG, "Product disconnected")
            return

            //If there is a DJI product connected to the mobile device...
        } else {
            //get an instance of the DJI product's camera
            DJIApplication.getCameraInstance()?.let { camera ->
                //If the camera supports downloading media from it...
                if (camera.isMediaDownloadModeSupported) {
                    mMediaManager = camera.mediaManager //get the camera's MediaManager
                    mMediaManager?.let { mediaManager ->

                        /*
                         NOTE:
                         To know when a change in the MediaManager's file list state occurs, the MediaManager needs a
                         FileListStateListener. We have created a FileListStateListener (further down in the code)
                         named updateFileListStateListener, and gave this listener to the MediaManager.

                         Similarly, the MediaManager also needs a VideoPlaybackStateListener to monitor changes to
                         its video playback state. We have created updatedVideoPlaybackStateListener
                         (further down in the code) for this reason, and gave it to the MediaManager.
                        */
                        mediaManager.addUpdateFileListStateListener(updateFileListStateListener)
                        mediaManager.addMediaUpdatedVideoPlaybackStateListener(
                            updatedVideoPlaybackStateListener
                        )
                        //Setting the camera mode to media download mode and then receiving an error callback
                        camera.setMode(
                            SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD
                        ) { error ->
                            //If the error is null, the operation was successful
                            if (error == null) {
                                DJILog.e(TAG, "Set cameraMode success")
                                showProgressDialog() //show the loading screen ProgressDialog
                                getFileList() //update the mediaFileList using the DJI product' SD card
                                //If the error is not null, alert user
                            } else {
                                setResultToToast("Set cameraMode failed")
                            }
                        }
                        //If the MediaManager doesn't support video playback, let the user know
                        if (mediaManager.isVideoPlaybackSupported) {
                            DJILog.e(TAG, "Camera support video playback!")
                        } else {
                            setResultToToast("Camera does not support video playback!")
                        }
                        //Setting the scheduler to be the MediaManager's scheduler
                        scheduler = mediaManager.scheduler
                    }
                } else {
                    //If the camera doesn't support downloading media from it, alert the user
                    setResultToToast("Media Download Mode not Supported")
                }
            }
        }
        return
    }

    /*
    NOTE:
    * refreshFileListOfStorageLocation() is used to update the MediaManager's file list using the
      DJI product's SD card.

    * If the file list state is RESET, the MediaManager will fetch the complete list
      list of files from the SD card. If the file list state is INCOMPLETE, the MediaManager will only fetch
      the missing list of files from the SD card.

    * This function requires the storage location of the media files to be specified. In this case, the SD card.

    * Once the MediaManager's file list is refreshed, use it to update the recycler view's mediaFileList.
    */

    private fun getFileList() {
        DJIApplication.getCameraInstance()
            ?.let { camera -> //Get an instance of the connected DJI product's camera
                mMediaManager = camera.mediaManager //Get the camera's MediaManager
                mMediaManager?.let { mediaManager ->
                    //If the MediaManager's file list state is syncing or deleting, the MediaManager is busy
                    if (currentFileListState == MediaManager.FileListState.SYNCING || currentFileListState == MediaManager.FileListState.DELETING) {
                        DJILog.e(TAG, "Media Manager is busy.")
                    } else {
                        setResultToToast(currentFileListState.toString()) //for debugging

                        //refreshing the MediaManager's file list using the connected DJI product's SD card
                        mediaManager.refreshFileListOfStorageLocation(
                            SettingsDefinitions.StorageLocation.SDCARD //file storage location
                        ) { djiError -> //checking the callback error

                            //If the error is null, dismiss the loading screen ProgressDialog
                            if (null == djiError) {
                                hideProgressDialog()

                                //Reset data if the file list state is not incomplete
                                if (currentFileListState != FileListState.INCOMPLETE) {
                                    mediaFileList.clear()
                                    lastClickViewIndex = -1
                                    lastClickView = null
                                }
                                //updating the recycler view's mediaFileList using the now refreshed MediaManager's file list
                                mediaManager.sdCardFileListSnapshot?.let { listOfMedia ->
                                    mediaFileList = listOfMedia
                                    mListAdapter.setMediaFiles(mediaFileList)
                                }

                                /*
                                Sort the files in the mediaFileList by descending order based on the time each media file was created.
                                Older files are now at the top of the mediaFileList, and newer ones are at the bottom. This results in
                                recent files showing up first in the recycler view.
                                */
                                mediaFileList.sortByDescending { it.timeCreated }

                                //Resume the scheduler. This will allow it to start executing any tasks in its download queue.
                                scheduler?.let { schedulerSafe ->
                                    schedulerSafe.resume { error ->
                                        //if the callback error is null, the operation was successful.
                                        if (error == null) {
                                            getThumbnails() //
                                        }
                                    }
                                }
                                /*
                                If there was an error with refreshing the MediaManager's file list, dismiss the loading progressDialog
                                and alert the user.
                                */
                            } else {
                                hideProgressDialog()
                                setResultToToast("Get Media File List Failed:" + djiError.description)
                            }
                        }
                    }
                }

            }
    }

    /*
    NOTE:
    Because full resolution photos/videos take too long to load, we want the recycler view to only display
    thumbnails of each media file in the mediaFileList.
    */

    //Function used to get the thumbnail images of all the media files in the mediaFileList
    private fun getThumbnails() {
        //if the mediaFileList is empty, alert the user
        if (mediaFileList.size <= 0) {
            setResultToToast("No File info for downloading thumbnails")
            return
        }
        //if the mediaFileList is not empty, call getThumbnailByIndex() on each media file
        for (i in mediaFileList.indices) {
            getThumbnailByIndex(i)
        }
    }

    //creating a Callback which is called whenever media content is downloaded using FetchMediaTask()
    private val taskCallback =
        FetchMediaTask.Callback { _, option, error ->
            //if the callback error is null, the download operation was successful
            if (null == error) {
                //if a preview image or thumbnail was downloaded, notify the recycler view that its data set has changed.
                if (option == FetchMediaTaskContent.PREVIEW) {
                    runOnUiThread { mListAdapter.notifyDataSetChanged() }
                }
                if (option == FetchMediaTaskContent.THUMBNAIL) {
                    runOnUiThread { mListAdapter.notifyDataSetChanged() }
                }
            } else {
                DJILog.e(TAG, "Fetch Media Task Failed" + error.description)
            }
        }

    private fun getThumbnailByIndex(index: Int) {
        /*
        Creating a task to fetch the thumbnail of a media file in the mediaFileList.
        This function also calls taskCallback to check for and respond to errors.
        */
        val task =
            FetchMediaTask(mediaFileList[index], FetchMediaTaskContent.THUMBNAIL, taskCallback)

        /*
        Using the scheduler to move each task to the back of its download queue.
        The task will be executed after all other tasks are completed.
        */
        scheduler?.let {
            it.moveTaskToEnd(task)
        }
    }

    //if the thumbnail_img ImageView is clicked on...
    private fun onThumbnailClick(mediaFile: MediaFile) {
//        val selectedMedia = v.tag as MediaFile
//        //if the MediaManager is not null, call addMediaTask() on the ImageView's MediaFile
//        if (mMediaManager != null) {
//            addMediaTask(selectedMedia)
//        }

    }

    //if the thumbnail_img ImageView is clicked on...
    fun onFileClick(mediaFile: MediaFile) {
//        val selectedMedia = v.tag as MediaFilee
//        //if the MediaManager is not null, call addMediaTask() on the ImageView's MediaFile
//        if (mMediaManager != null) {
//            addMediaTask(selectedMedia)
//        }
    }

    /*
    Function used to download the preview image of a provided MediaFile, and
    then display the preview on the mDisplayImageView ImageView.
    */
    private fun addMediaTask(mediaFile: MediaFile) {
        mMediaManager?.let {
            //creating a task to fetch the preview of the provided MediaFile
            val task = FetchMediaTask(
                mediaFile,
                FetchMediaTaskContent.PREVIEW
            ) { mediaFile, _, error ->
                //if the callback error is null, the download was successful
                if (null == error) {
                    //if the downloaded preview image is not null, make the mDisplayImageView
                    //... visible and use it to display the preview.
                    if (mediaFile.preview != null) {
                        runOnUiThread {
                            val previewBitmap = mediaFile.preview
                            mDisplayImageView.visibility = View.VISIBLE
                            mDisplayImageView.setImageBitmap(previewBitmap)
                        }
                    } else {
                        setResultToToast("null bitmap!")
                    }
                } else {
                    setResultToToast("fetch preview image failed: " + error.description)
                }
            }
            //resume the scheduler
            it.scheduler.resume { error ->
                /*
                If the callback error is null, push the task to the front of the scheduler's download queue.
                The task will be executed after any currently executing task is complete.
                */
                if (error == null) {
                    it.scheduler.moveTaskToNext(task)
                } else {
                    setResultToToast("resume scheduler failed: " + error.description)
                }
            }
        }
    }

    //Listeners
    private val updateFileListStateListener =
        //when the MediaManager's FileListState changes, save the state to currentFileListState
        MediaManager.FileListStateListener { state -> currentFileListState = state }

    private val updatedVideoPlaybackStateListener =
        //when the MediaManager's videoPlaybackState changes, pass the state into updateStatusTextView()
        MediaManager.VideoPlaybackStateListener { videoPlaybackState ->
            updateStatusTextView(
                videoPlaybackState
            )
        }

    //Function used to update the status text view (mPushTv)
    private fun updateStatusTextView(videoPlaybackState: MediaManager.VideoPlaybackState?) {
        val pushInfo = StringBuffer()
        addLineToSB(pushInfo, "Video Playback State", null)

        //if the video playback state is not null...
        if (videoPlaybackState != null) {
            if (videoPlaybackState.playingMediaFile != null) { //if there is a video media file playing...
                addLineToSB( //add the media file's index to the StringBuffer
                    pushInfo,
                    "media index",
                    videoPlaybackState.playingMediaFile.index
                )
                addLineToSB( //add the media file's size to the StringBuffer
                    pushInfo,
                    "media size",
                    videoPlaybackState.playingMediaFile.fileSize
                )
                addLineToSB( //add the media file's duration to the StringBuffer
                    pushInfo,
                    "media duration",
                    videoPlaybackState.playingMediaFile.durationInSeconds
                )
                addLineToSB( //add the media file's creation date to the StringBuffer
                    pushInfo,
                    "media created date",
                    videoPlaybackState.playingMediaFile.dateCreated
                )
                addLineToSB( //add the media file's orientation to the StringBuffer
                    pushInfo,
                    "media orientation",
                    videoPlaybackState.playingMediaFile.videoOrientation
                )
            } else { //if there is no video media file playing...
                addLineToSB(pushInfo, "media index", "None")
            }
            /*
            Add the media file's playingPosition, playbackStatus, cachedPercentage, cachedPosition,
            as different lines to the StringBuffer.
            */
            addLineToSB(pushInfo, "media current position", videoPlaybackState.playingPosition)
            addLineToSB(pushInfo, "media current status", videoPlaybackState.playbackStatus)
            addLineToSB(
                pushInfo,
                "media cached percentage",
                videoPlaybackState.cachedPercentage
            )
            addLineToSB(pushInfo, "media cached position", videoPlaybackState.cachedPosition)
            pushInfo.append("\n") //new line

            //display the StringBuffer's string on the mPushTv TextView
            setResultToText(pushInfo.toString())
        }
    }

    //Function used to add a new line to a StringBuffer
    private fun addLineToSB(
        sb: StringBuffer?,
        name: String?,
        value: Any?
    ) {
        if (sb == null) return
        sb.append(if (name == null || "" == name) "" else "$name: ")
            .append(if (value == null) "" else value.toString() + "").append("\n")
    }

    private val downloadFileListener = object : DownloadListener<String> {
        //if the download fails, dismiss the download progressDialog, alert the user,
        //...and reset currentProgress.
        override fun onFailure(error: DJIError) {
            hideDownloadProgressDialog()
            setResultToToast("Download File Failed" + error.description)
            currentProgress = -1
        }

        override fun onProgress(total: Long, current: Long) {}

        //called every 1 second to show the download rate
        override fun onRateUpdate(
            total: Long, //the total size
            current: Long, //the current download size
            persize: Long //the download size between two calls
        ) {
            //getting the current download progress as an integer between 1-100
            val tmpProgress = (1.0 * current / total * 100).toInt()

            if (tmpProgress != currentProgress) {
                mDownloadDialog?.let {
                    it.progress =
                        tmpProgress //set tmpProgress as the progress of the download progressDialog
                    currentProgress = tmpProgress //save tmpProgress to currentProgress
                }
            }
        }

        //When the download starts, reset currentProgress and show the download ProgressDialog
        override fun onStart() {
            currentProgress = -1
            showDownloadProgressDialog()
        }

        //When the download successfully finishes, dismiss the download ProgressDialog, alert the user,
        //...and reset currentProgress.
        override fun onSuccess(filePath: String) {
            hideDownloadProgressDialog()
            setResultToToast("Download File Success:$filePath")
            currentProgress = -1
        }

        override fun onRealtimeDataUpdate(p0: ByteArray?, p1: Long, p2: Boolean) {
        }
    }

    //Function used to download full resolution photos/videos from the DJI product's SD card
    private fun downloadFileByIndex(index: Int) {
        val camera: Camera = DJIApplication.getCameraInstance() ?: return

        camera.setMode(SettingsDefinitions.CameraMode.MEDIA_DOWNLOAD) { error ->
            if (error == null) {
                Log.d(TAG, "Set Camera to Download Mode Succeeded")
            } else {
                Log.d(TAG, "Set Camera to Download Mode Succeeded Failed: ${error.description}")
            }
        }
        //If the media file's type is panorama or shallow_focus, don't download it
        if (mediaFileList[index].mediaType == MediaFile.MediaType.PANORAMA
            || mediaFileList[index].mediaType == MediaFile.MediaType.SHALLOW_FOCUS
        ) {
            return
        }
        //If the media file's type is MOV or MP4, download it to videoStorageDir
        if (mediaFileList[index].mediaType == MediaFile.MediaType.MOV
            || mediaFileList[index].mediaType == MediaFile.MediaType.MP4
        ) {
            mediaFileList[index].fetchFileData(videoStorageDir, null, downloadFileListener)
        }
        //If the media file's type is JPEG or JSON, download it to photoStorageDir
        if (mediaFileList[index].mediaType == MediaFile.MediaType.JPEG
            || mediaFileList[index].mediaType == MediaFile.MediaType.JSON
        ) {
            mediaFileList[index].fetchFileData(photoStorageDir, null, downloadFileListener)
        }
    }

    //Function used to delete a media file from the DJI product's SD card
    private fun deleteFileByIndex(index: Int) {
        val fileToDelete = ArrayList<MediaFile>()
        //if the size of mediaFileList is larger than the provided index...
        if (mediaFileList.size > index) {
            //delete the media file from the SD card
            fileToDelete.add(mediaFileList[index])
            mMediaManager?.let { mediaManager ->
                mediaManager.deleteFiles(
                    fileToDelete,
                    object :
                        CommonCallbacks.CompletionCallbackWithTwoParam<List<MediaFile?>?, DJICameraError?> {
                        //if the deletion from the SD card is successful...
                        override fun onSuccess(
                            x: List<MediaFile?>?,
                            y: DJICameraError?
                        ) {
                            DJILog.e(TAG, "Delete file success")
                            //remove the deleted file from the mediaFileList
                            runOnUiThread {
                                mediaFileList.removeAt(index)

                                //Reset select view
                                lastClickViewIndex = -1
                                lastClickView = null

                                //Update recyclerView
                                mListAdapter.notifyDataSetChanged()
                            }
                        }

                        //if the deletion from the SD card failed, alert the user
                        override fun onFailure(error: DJIError) {
                            setResultToToast("Delete file failed")
                        }
                    })
            }
        }
    }

    //Function used to play the last-clicked media file in the recyclerView if it is a type of video
    private fun playVideo() {
        mDisplayImageView.visibility = View.INVISIBLE
        if (lastClickViewIndex == -1) return

        val selectedMediaFile = mediaFileList[lastClickViewIndex]

        //if the selected media file is a video, play it using the MediaManager
        if (selectedMediaFile.mediaType == MediaFile.MediaType.MOV || selectedMediaFile.mediaType == MediaFile.MediaType.MP4) {
            mMediaManager?.let { mediaManager ->
                mediaManager.playVideoMediaFile(
                    selectedMediaFile
                ) { error ->
                    //if the callback error is null, the video played successfully
                    if (null != error) {
                        setResultToToast("Play Video Failed " + error.description)
                    } else { //alert the user of the error
                        DJILog.e(TAG, "Play Video Success")
                    }
                }
            }
        }
    }

    private fun moveToPosition() {
        val li = LayoutInflater.from(this)
        val promptsView = li.inflate(R.layout.prompt_input_position, null)
        val alertDialogBuilder =
            AlertDialog.Builder(this)
        alertDialogBuilder.setView(promptsView)
        val userInput = promptsView.findViewById<EditText>(R.id.editTextDialogUserInput)
        alertDialogBuilder.setCancelable(false)
            .setPositiveButton("OK") { dialog, id ->
                val ms = userInput.text.toString()
                mMediaManager?.let { mediaManager ->
                    if (ms != "") {
                        mediaManager.moveToPosition(ms.toInt().toFloat()) { error ->
                            if (error != null) {
                                setResultToToast("Move to video position failed" + error.description)
                            } else {
                                DJILog.e(TAG, "Move to video position successfully.")
                            }
                        }
                    }

                }
            }
            .setNegativeButton(
                "Cancel"
            ) { dialog, id -> dialog.cancel() }
        val alertDialog = alertDialogBuilder.create()
        alertDialog.show()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.back_btn -> {
                finish()
            }
            R.id.delete_btn -> {
                deleteFileByIndex(lastClickViewIndex)
            }
            R.id.reload_btn -> {
                getFileList()
            }
            R.id.download_btn -> {
                Log.d(TAG, "$lastClickViewIndex")
                downloadFileByIndex(lastClickViewIndex)
            }
            R.id.status_btn -> {
                if (mPushDrawerSd!!.isOpened) {
                    mPushDrawerSd!!.animateClose()
                } else {
                    mPushDrawerSd!!.animateOpen()
                }
            }
            R.id.play_btn -> {
                playVideo()
            }
            R.id.resume_btn -> {
                mMediaManager?.let { mediaManager ->
                    mediaManager.resume { error ->
                        if (null != error) {
                            setResultToToast("Resume Video Failed" + error.description)
                        } else {
                            DJILog.e(TAG, "Resume Video Success")
                        }
                    }
                }
            }
            R.id.pause_btn -> {
                mMediaManager?.let { mediaManager ->
                    mediaManager.pause { error ->
                        if (null != error) {
                            setResultToToast("Pause Video Failed" + error.description)
                        } else {
                            DJILog.e(TAG, "Pause Video Success")
                        }
                    }
                }
            }
            R.id.stop_btn -> {
                mMediaManager?.let { mediaManager ->
                    mediaManager.stop { error ->
                        if (null != error) {
                            setResultToToast("Stop Video Failed" + error.description)
                        } else {
                            DJILog.e(TAG, "Stop Video Success")
                        }
                    }
                }
            }
            R.id.moveTo_btn -> {
                moveToPosition()
            }
            else -> {
            }
        }
    }


    companion object {
        const val TAG = "WaypointOneActivity"

    }

}