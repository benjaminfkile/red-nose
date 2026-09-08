package com.wmsfo.rednose.ipc;

oneway interface IBeaconListener {
    void onState(String stateJson);
    void onLog(String line);
    void onLogUploadResult(String json);
}
