package com.ft.sdk.garble.utils;

import android.os.Process;

import java.io.FileNotFoundException;
import java.io.RandomAccessFile;

final class ProcFiles {
    private ProcFiles() {
    }

    static RandomAccessFile openSystemStat() throws FileNotFoundException {
        return new RandomAccessFile("/proc/stat", "r");
    }

    static RandomAccessFile openProcessFile(String name) throws FileNotFoundException {
        return new RandomAccessFile("/proc/" + Process.myPid() + "/" + name, "r");
    }
}
