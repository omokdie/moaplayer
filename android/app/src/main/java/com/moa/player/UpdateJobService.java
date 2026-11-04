package com.moa.player;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * Installs a downloaded update without asking once the app has been left alone (see
 * {@link UpdateInstaller#onAppBackground}). Scheduled as a job because Android freezes cached
 * background processes, so a plain timer would often never fire.
 */
public class UpdateJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        return UpdateInstaller.runSilentJob(this, () -> jobFinished(params, false));
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // the next time the app is left schedules it again
    }
}
