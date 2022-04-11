package com.tinydavid.djiwaypointmission.ui.connection

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.PermissionChecker
import androidx.lifecycle.Observer
import com.tinydavid.djiwaypointmission.R
import com.tinydavid.djiwaypointmission.databinding.ActivityConnectionBinding
import com.tinydavid.djiwaypointmission.ui.main.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import dji.sdk.sdkmanager.DJISDKManager

@AndroidEntryPoint
class ConnectionActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityConnectionBinding

    private val mViewModel: ConnectionViewModel by viewModels()

    private var permissionsToRequest = arrayListOf<String>()
    private val permissionsRejected = arrayListOf<String>()
    private var permissions = arrayListOf<String>()

    //Class Variables
    private lateinit var mTextConnectionStatus: TextView
    private lateinit var mTextProduct: TextView
    private lateinit var mTextModelAvailable: TextView
    private lateinit var mBtnOpen: Button
    private lateinit var mVersionTv: TextView


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mBinding = ActivityConnectionBinding.inflate(layoutInflater)
        val view = mBinding.root
        setContentView(view)

        permissions.addAll(
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.VIBRATE,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS,
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.SYSTEM_ALERT_WINDOW,
                Manifest.permission.READ_PHONE_STATE
            )
        )
        permissionsToRequest = findUnAskedPermissions(permissions)
        //get the permissions we have asked for before but are not granted..
        //we will store this in a global list to access later.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            if (permissionsToRequest.size > 0)
                requestPermissions(
                    permissionsToRequest.toArray(arrayOf(permissionsToRequest.size.toString())),
                    ALL_PERMISSIONS_RESULT
                )
        }


        //Initialize the UI, register the app with DJI's mobile SDK, and set up the observers
        initUI()
        mViewModel.registerApp()
        observers()

    }

    private fun findUnAskedPermissions(wanted: ArrayList<String>): ArrayList<String> {
        val result = ArrayList<String>()
        for (perm in wanted) {
            if (!hasPermission(perm)) {
                result.add(perm)
            }
        }
        return result
    }

    private fun hasPermission(permission: String): Boolean {
        if (canMakeSmores()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return PermissionChecker.checkSelfPermission(
                    this,
                    permission
                ) == PermissionChecker.PERMISSION_GRANTED
            }
        }
        return true
    }


    private fun canMakeSmores(): Boolean {
        return Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1
    }

    //Function to initialize the activity's UI
    private fun initUI() {

        //referencing the layout views using their resource ids
        mTextConnectionStatus = mBinding.textConnectionStatus
        mTextModelAvailable = mBinding.textModelAvailable
        mTextProduct = mBinding.textProductInfo
        mBtnOpen = mBinding.btnOpen
        mVersionTv = mBinding.textView2

        //Getting the DJI SDK version and displaying it on mVersionTv TextView
        mVersionTv.text =
            resources.getString(R.string.sdk_version, DJISDKManager.getInstance().sdkVersion)

        mBtnOpen.isEnabled = false //mBtnOpen Button is initially disabled

        //If mBtnOpen Button is clicked on, start MainActivity (only works when button is enabled)
        mBtnOpen.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    //Function to setup observers
    private fun observers() {
        //observer listens to changes to the connectionStatus variable stored in the viewModel
        mViewModel.connectionStatus.observe(this, Observer<Boolean> { isConnected ->
            //If boolean is True, enable mBtnOpen button. If false, disable the button.
            if (isConnected) {
                mTextConnectionStatus.text = "Status: Connected"
                mBtnOpen.isEnabled = true
            } else {
                mTextConnectionStatus.text = "Status: Disconnected"
                mBtnOpen.isEnabled = true
            }
        })

        /*
        Observer listens to changes to the product variable stored in the viewModel.
        product is a BaseProduct object and represents the DJI product connected to the mobile device
        */
        mViewModel.product.observe(this, Observer { baseProduct ->
            //if baseProduct is connected to the mobile device, display its firmware version and model name.
            if (baseProduct != null && baseProduct.isConnected) {
                mTextModelAvailable.text = baseProduct.firmwarePackageVersion

                //name of the aircraft attached to the remote controller
                mTextProduct.text = baseProduct.model.displayName
            }
        })
    }

    companion object {
        const val TAG = "ConnectionActivity"
        const val ALL_PERMISSIONS_RESULT = 107
    }
}