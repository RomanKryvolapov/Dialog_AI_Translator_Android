package com.romankryvolapov.dialogaitranslator.capture;

import android.os.ParcelFileDescriptor;

/** Runs as the shell user, which may hear calls and the phone's own sound. */
interface ICaptureService {
    /** Frames of 16 kHz 16-bit mono PCM; closing the descriptor ends the capture. */
    ParcelFileDescriptor start(boolean call, boolean withMicrophone) = 1;
    void stop() = 2;
    /** Shizuku's own transaction code for ending the process. */
    void destroy() = 16777114;
}
