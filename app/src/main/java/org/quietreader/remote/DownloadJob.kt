package org.quietreader.remote

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.quietreader.ReaderApp

class DownloadJob:JobService(){
    private val executor=Executors.newSingleThreadExecutor()
    private var stopped=AtomicBoolean(false)
    override fun onStartJob(params:JobParameters):Boolean{val signal=AtomicBoolean(false);stopped=signal;executor.execute{val queue=(application as ReaderApp).downloads;try{queue.process{signal.get()}}finally{jobFinished(params,queue.hasQueued())}};return true}
    override fun onStopJob(params:JobParameters):Boolean{stopped.set(true);return true}
    override fun onDestroy(){stopped.set(true);executor.shutdown();super.onDestroy()}
}
