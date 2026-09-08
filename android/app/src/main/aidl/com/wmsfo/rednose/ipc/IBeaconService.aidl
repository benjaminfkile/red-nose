// The only channel between the UI process and the :beacon process (red-nose.md 4.1).
package com.wmsfo.rednose.ipc;

import com.wmsfo.rednose.ipc.IBeaconListener;

interface IBeaconService {
    String getStateJson();
    void   saveEnrollment(String enrollmentJson);
    void   clearEnrollment();
    void   setGpsOnlyFallback(boolean enabled);
    void   startReplay(String source, int ratePerSecond);
    void   stopReplay();
    void   uploadLog();
    String getRecentLog(int maxLines);
    void   registerListener(IBeaconListener listener);
    void   unregisterListener(IBeaconListener listener);
}
