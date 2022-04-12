package com.tinydavid.djiwaypointmission.ui.waypoint

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dji.common.mission.waypoint.Waypoint
import javax.inject.Inject

@HiltViewModel
class WaypointOneViewModel @Inject constructor() : ViewModel() {


    private val waypointList = mutableListOf<Waypoint>()

    fun addWaypoint(waypoint: Waypoint){
        waypointList.add(waypoint)
    }

}