package org.quietreader.remote

import android.app.job.JobParameters
import android.app.job.JobService
import org.quietreader.ReaderApp
import java.util.concurrent.Executors

class ProgressJob:JobService() {
    private val worker=Executors.newSingleThreadExecutor()
    override fun onStartJob(params:JobParameters):Boolean {
        val prefs=getSharedPreferences("progress-device",MODE_PRIVATE)
        if(!prefs.getBoolean("enabled",false)||prefs.getBoolean("paused",false))return false
        worker.execute{var retry=false;try{org.quietreader.reader.ReaderPositions.awaitPendingWrites();(application as ReaderApp).progressSync.sync();prefs.edit().remove("error").apply()}catch(e:Exception){prefs.edit().putString("error",e.message?:"同步失败").apply();retry=e is java.io.IOException};jobFinished(params,retry)}
        return true
    }
    override fun onStopJob(params:JobParameters)=true
    override fun onDestroy(){worker.shutdown();super.onDestroy()}
}
